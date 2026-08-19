package com.aigate.router.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aigate.router.R
import com.aigate.router.capability.CapabilityGate
import com.aigate.router.capability.DeviceCapabilityProvider
import com.aigate.router.capability.GateResult
import com.aigate.router.capability.ModelDemand
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.db.LocalModelDao
import com.aigate.router.data.model.LocalModel
import com.aigate.router.gateway.local.EngineKind
import com.aigate.router.network.UpstreamClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/*
 * Загрузка файла модели на устройство.
 *
 * Задача выделена в WorkManager, а не в обычную корутину, по одной причине:
 * файл весит гигабайты, и загрузка переживает сворачивание приложения, обрыв
 * связи и убийство процесса. Корутина в ViewModel не переживает ничего из
 * этого, а начинать многогигабайтную закачку заново из-за входящего звонка
 * недопустимо.
 *
 * Отсюда же вытекает остальное устройство файла: докачка по Range, контрольная
 * сумма, считаемая на лету, и запись во временный файл с переименованием в
 * конце. Итоговый файл появляется на диске только целым и проверенным, поэтому
 * любой, кто увидел его, может грузить модель без дополнительных проверок.
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val modelId = inputData.getLong(KEY_MODEL_ID, INVALID_ID)
        if (modelId <= 0L) {
            // Работа без входного параметра — след старой версии очереди.
            // Ругаться некому и не на что: просто нечего делать.
            return Result.success()
        }

        val dao = AppDatabase.getInstance(applicationContext).localModelDao()
        val model = dao.getById(modelId) ?: return Result.success()
        // Запись могли удалить или уже докачать, пока задание ждало очереди.
        if (model.state == LocalModel.STATE_READY) return Result.success()

        when (val gate = gateFor(model)) {
            is GateResult.Ok -> Unit
            // Причины отказа приходят готовым русским текстом из CapabilityGate:
            // подобрать формулировку можно только там, где известны числа.
            is GateResult.NoRam -> return failWith(dao, modelId, gate.reasonRu)
            is GateResult.NoDisk -> return failWith(dao, modelId, gate.reasonRu)
            is GateResult.NoAbi -> return failWith(dao, modelId, gate.reasonRu)
        }

        val url = downloadUrl(model) ?: return failWith(dao, modelId, urlFailureReason(model))

        return try {
            download(dao, model, url)
        } catch (e: CancellationException) {
            // Отмена — это пауза, а не провал: недокачанный .part остаётся на
            // диске и будет продолжен при следующем запуске.
            markPaused(dao, modelId)
            throw e
        } catch (e: IOException) {
            // Обрыв связи при отмене приходит сюда же: сокет закрывают снаружи,
            // и OkHttp сообщает об этом обычной сетевой ошибкой.
            if (isStopped) {
                markPaused(dao, modelId)
                return Result.success()
            }
            if (runAttemptCount >= MAX_ATTEMPTS) {
                return failWith(dao, modelId, "Сеть недоступна: загрузка не удалась за $MAX_ATTEMPTS попыток")
            }
            Log.w(TAG, "Сетевой сбой загрузки модели $modelId: ${e.message}")
            Result.retry()
        } catch (e: Exception) {
            // Всё остальное — испорченный адрес, отказ файловой системы, сбой
            // разбора ответа. Повтор такое не лечит.
            failWith(dao, modelId, "Ошибка загрузки: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Полный цикл загрузки одного файла.
     *
     * Вынесен из [doWork] целиком, чтобы обработка ошибок жила в одном месте, а
     * не размазывалась по вложенным try внутри цикла чтения.
     */
    private suspend fun download(dao: LocalModelDao, model: LocalModel, url: String): Result =
        withContext(Dispatchers.IO) {
            val modelId = model.id
            val partial = ModelStorage.partialFileFor(applicationContext, modelId)
            partial.parentFile?.mkdirs()

            val digest = MessageDigest.getInstance("SHA-256")
            // Уже скачанный кусок обязан пройти через digest: сумма считается
            // потоком и другого способа учесть старые байты нет. Для файла в
            // несколько гигабайт это заметное чтение с диска, но оно случается
            // один раз на продолжение, а не на каждый мегабайт.
            var offset = if (partial.isFile && partial.length() > 0L) feedDigest(partial, digest) else 0L

            dao.updateState(modelId, LocalModel.STATE_DOWNLOADING)
            setForegroundSafely(model, offset, model.sizeBytes)

            val client = downloadClient()
            var response = execute(client, url, offset)
            when (DownloadResume.decide(offset, response.code)) {
                DownloadResume.Decision.Continue -> Unit

                DownloadResume.Decision.RestartWithSameResponse -> {
                    offset = 0L
                    digest.reset()
                }

                DownloadResume.Decision.RestartWithNewRequest -> {
                    response.closeQuietly()
                    response = execute(client, url, 0L)
                    offset = 0L
                    digest.reset()
                }
            }

            response.use { active ->
                if (!active.isSuccessful) {
                    return@withContext httpFailure(dao, modelId, active.code)
                }
                val body = active.body ?: throw IOException("Пустой ответ сервера")
                // Общий размер: заявленный в записи важнее заголовка, потому что
                // при докачке Content-Length описывает только остаток.
                val total = when {
                    model.sizeBytes > 0L -> model.sizeBytes
                    body.contentLength() > 0L -> offset + body.contentLength()
                    else -> 0L
                }

                var written = offset
                var lastDbWriteAt = 0L
                val buffer = ByteArray(BUFFER_BYTES)

                body.byteStream().use { input ->
                    // append=false после сброса докачки: файл нужно переписать
                    // с нуля, иначе к старому мусору допишется новый.
                    FileOutputStream(partial, offset > 0L).use { output ->
                        while (true) {
                            if (isStopped) {
                                // Мягкий выход: поток и файл закроются штатно,
                                // .part останется целым куском для продолжения.
                                output.flush()
                                markPaused(dao, modelId)
                                return@withContext Result.success()
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            written += read

                            val now = System.currentTimeMillis()
                            if (now - lastDbWriteAt >= DB_PROGRESS_INTERVAL_MS) {
                                lastDbWriteAt = now
                                // Обе записи прогресса идут по одному расписанию.
                                // В базу — потому что строку local_models
                                // наблюдает список моделей, и запись на каждый
                                // буфер будила бы перерисовку экрана сотни раз в
                                // секунду. В setProgress — потому что это тоже
                                // запись в базу, только в собственную базу
                                // WorkManager: на каждый буфер вышло бы около
                                // четырёх тысяч записей на гигабайт файла.
                                setProgress(
                                    workDataOf(
                                        KEY_PROGRESS_BYTES to written,
                                        KEY_TOTAL_BYTES to total,
                                    )
                                )
                                dao.updateProgress(modelId, written)
                                setForegroundSafely(model, written, total)
                            }
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }
                dao.updateProgress(modelId, written)
            }

            dao.updateState(modelId, LocalModel.STATE_VERIFYING)
            val expected = normalizeDigest(model.sha256)
            if (expected.isNotEmpty()) {
                val actual = toHex(digest.digest())
                if (actual != expected) {
                    // Битый файл хранить незачем: докачивать в нём нечего, а
                    // повтор должен начаться с чистого листа.
                    partial.delete()
                    return@withContext failWith(dao, modelId, "Файл повреждён при загрузке")
                }
            }
            // Пустая сумма — не ошибка: источник её просто не дал, и проверять
            // нечего. Отказываться от скачанного файла из-за этого нельзя.

            val target = ModelStorage.finalFileFor(applicationContext, model)
            target.parentFile?.mkdirs()
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                // Переименование не сработает, если временный и итоговый файл
                // оказались на разных томах (например, при переносе приложения
                // на карту памяти). Тогда остаётся копирование.
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }

            dao.setReady(modelId, target.absolutePath, target.length())
            syncCatalogQuietly()
            Log.i(TAG, "Модель ${model.displayName} загружена: ${target.absolutePath}")
            Result.success()
        }

    /**
     * Клиент загрузчика.
     *
     * Берётся прямое соединение без прокси: реестры моделей — публичные хосты,
     * и гнать через них гигабайты по чужому прокси незачем. От общего клиента
     * отличается только чтением: там readTimeout выключен ради длинных
     * потоковых ответов, а здесь замерший сокет обязан рано или поздно
     * оборваться, иначе задание повиснет навсегда вместо повтора. Пул
     * соединений остаётся общим — newBuilder его не копирует.
     */
    private fun downloadClient(): OkHttpClient =
        UpstreamClient.getDirectClient().newBuilder()
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    /** Один запрос с необязательным продолжением с байта [offset]. */
    private fun execute(client: OkHttpClient, url: String, offset: Long): Response {
        val builder = Request.Builder().url(url).get()
        DownloadResume.rangeHeader(offset)?.let { builder.header("Range", it) }
        return client.newCall(builder.build()).execute()
    }

    /**
     * Отказ сервера. 401/403/404 повторять бессмысленно — модель убрали или она
     * требует токена; всё остальное считаем временным и отдаём на повтор.
     */
    private suspend fun httpFailure(dao: LocalModelDao, modelId: Long, code: Int): Result =
        if (code in PERMANENT_HTTP_CODES) {
            // 401 у HuggingFace почти всегда значит закрытый лицензией
            // репозиторий, а не пропавший файл: список файлов там открыт, а
            // сама загрузка требует принятых условий и токена. Общая фраза
            // «файл недоступен» отправила бы пользователя искать не там.
            val reason = if (code == 401) {
                "Репозиторий закрыт лицензией: примите условия на huggingface.co"
            } else {
                "Файл недоступен на сервере (код $code)"
            }
            failWith(dao, modelId, reason)
        } else if (runAttemptCount >= MAX_ATTEMPTS) {
            failWith(dao, modelId, "Сервер отвечает ошибкой $code, загрузка не удалась за $MAX_ATTEMPTS попыток")
        } else {
            Result.retry()
        }

    /** Проверка, влезет ли модель на устройство и в память. */
    private fun gateFor(model: LocalModel): GateResult {
        val caps = DeviceCapabilityProvider.current(applicationContext)
        val demand = ModelDemand(
            fileSizeBytes = model.sizeBytes,
            paramsB = model.paramsB,
            contextTokens = model.contextWindow,
            // Неизвестный движок считаем GGUF: его оценка памяти строже
            // LiteRT-овской, и ошибка в эту сторону безопаснее.
            engine = EngineKind.fromDbValue(model.engine) ?: EngineKind.GGUF,
        )
        return CapabilityGate.downloadCheck(caps, demand)
    }

    /**
     * Адрес файла.
     *
     * Ollama раздаёт слои по контрольной сумме, поэтому без неё запись
     * бесполезна — адрес просто не из чего собрать. HuggingFace отдаёт файл по
     * имени из ветки main. Оба реестра поддерживают Range и отвечают
     * перенаправлением на CDN, которое OkHttp проходит сам.
     */
    private fun downloadUrl(model: LocalModel): String? = when (model.source) {
        LocalModel.SOURCE_OLLAMA -> {
            val digest = normalizeDigest(model.sha256)
            if (digest.isEmpty()) null
            else "https://registry.ollama.ai/v2/library/${model.repo}/blobs/sha256:$digest"
        }
        LocalModel.SOURCE_HF -> "https://huggingface.co/${model.repo}/resolve/main/${model.ref}"
        else -> null
    }

    /** Почему адрес не собрался: пользователю нужна причина, а не «не вышло». */
    private fun urlFailureReason(model: LocalModel): String = when (model.source) {
        LocalModel.SOURCE_OLLAMA ->
            "Неизвестна контрольная сумма слоя Ollama: адрес файла собрать не из чего"
        else -> "Неизвестно, откуда качать: источник «${model.source}» не поддержан"
    }

    /**
     * Прогон уже скачанного куска через digest. Возвращает его длину — именно
     * её, а не File.length(), потому что дальше писать надо ровно с того байта,
     * который учтён в сумме.
     */
    private fun feedDigest(file: File, digest: MessageDigest): Long {
        var total = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                total += read
            }
        }
        return total
    }

    /** Отказ с русской причиной в записи: её показывает экран моделей как есть. */
    private suspend fun failWith(dao: LocalModelDao, modelId: Long, reasonRu: String): Result {
        dao.updateState(modelId, LocalModel.STATE_ERROR, reasonRu)
        Log.w(TAG, "Загрузка модели $modelId остановлена: $reasonRu")
        return Result.failure()
    }

    /**
     * Пометка паузы при отмене. NonCancellable обязателен: корутина уже
     * отменена, и обычная запись в базу не дошла бы до диска — состояние
     * осталось бы «загружается» у задания, которого больше нет.
     */
    private suspend fun markPaused(dao: LocalModelDao, modelId: Long) {
        withContext(NonCancellable) {
            runCatching { dao.updateState(modelId, LocalModel.STATE_PAUSED) }
        }
    }

    /**
     * Обновление списка моделей после появления нового файла: скачанная модель
     * должна сразу появиться среди тех, кто обслуживает запросы.
     *
     * Провал синхронизации не отменяет уже скачанный и проверенный файл —
     * список поправится при следующем запуске, — поэтому ошибка только пишется
     * в журнал.
     */
    private suspend fun syncCatalogQuietly() {
        runCatching { LocalModelSync.sync(AppDatabase.getInstance(applicationContext)) }
            .onFailure { Log.w(TAG, "Каталог моделей не обновлён: ${it.message}") }
    }

    // ── Уведомление ──────────────────────────────────────────────────────

    /**
     * Показ прогресса в шторке. Ошибки здесь глушатся: система вправе отказать
     * в переводе задания на передний план (нет разрешения на уведомления,
     * ограничения фонового запуска), и загрузку это отменять не должно.
     */
    private suspend fun setForegroundSafely(model: LocalModel, done: Long, total: Long) {
        runCatching { setForeground(foregroundInfo(model, done, total)) }
    }

    private fun foregroundInfo(model: LocalModel, done: Long, total: Long): ForegroundInfo {
        ensureChannel()
        val percent = if (total > 0L) ((done * 100L) / total).coerceIn(0L, 100L).toInt() else 0
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_gate_fg)
            .setContentTitle("Загрузка модели")
            .setContentText(
                if (total > 0L) "${model.displayName}: $percent%" else model.displayName
            )
            .setProgress(100, percent, total <= 0L)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отменить", cancel)
            .build()

        val notificationId = NOTIFICATION_ID_BASE + (model.id % NOTIFICATION_ID_RANGE).toInt()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            // До Android 10 типов у передних служб нет, и трёхаргументный
            // конструктор системе сказать нечего.
            ForegroundInfo(notificationId, notification)
        }
    }

    /**
     * Канал создаётся здесь, а не в GatewayApplication: загрузки — сама себе
     * подсистема, и её канал не должен появляться на устройствах, где моделей
     * никто не качал.
     */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Загрузка моделей",
                // LOW, а не DEFAULT: прогресс идёт часами, и звук на каждое
                // обновление превратил бы загрузку в наказание.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Ход загрузки файлов локальных моделей"
                setShowBadge(false)
            }
        )
    }

    private fun Response.closeQuietly() {
        runCatching { close() }
    }

    companion object {

        private const val TAG = "ModelDownloadWorker"

        /** Идентификатор записи local_models, которую надо скачать. */
        const val KEY_MODEL_ID = "local_model_id"

        /** Ключи прогресса для наблюдателей WorkInfo. */
        const val KEY_PROGRESS_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"

        private const val INVALID_ID = -1L

        private const val CHANNEL_ID = "aigate_downloads"

        /**
         * Буфер 256 КБ: на гигабайтном файле мелкие чтения превращаются в сотни
         * тысяч системных вызовов, а больший буфер уже упирается в сеть, а не
         * в накладные расходы.
         */
        private const val BUFFER_BYTES = 256 * 1024

        /** Минимальный шаг записи прогресса в базу. */
        private const val DB_PROGRESS_INTERVAL_MS = 2_000L

        /**
         * Предел молчания сокета. Минута, а не секунды: мобильная сеть при
         * переключении вышки замирает надолго, и обрывать из-за этого докачку
         * дороже, чем подождать.
         */
        private const val READ_TIMEOUT_SECONDS = 60L

        /**
         * Потолок попыток. Без него мёртвая ссылка перезапускалась бы вечно,
         * расходуя батарею на растущих интервалах backoff.
         */
        private const val MAX_ATTEMPTS = 5

        private const val HTTP_PARTIAL = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        /** Коды, после которых повтор ничего не изменит. */
        private val PERMANENT_HTTP_CODES = setOf(401, 403, 404, 410)

        private const val NOTIFICATION_ID_BASE = 5100
        private const val NOTIFICATION_ID_RANGE = 100L

        /** Тег задания одной модели: по нему очередь ставит на паузу и отменяет. */
        fun tagFor(localModelId: Long): String = "aigate_model_dl_$localModelId"

        /**
         * Сумма к сравнению: реестры пишут её то с префиксом «sha256:», то без,
         * то заглавными буквами. Хранить разницу негде, поэтому приводим к
         * одному виду и здесь, и при сборке адреса.
         */
        private fun normalizeDigest(raw: String): String =
            raw.trim().lowercase().removePrefix("sha256:")

        /** Своя шестнадцатеричная запись: стандартная в Kotlin ещё experimental. */
        private fun toHex(bytes: ByteArray): String {
            val out = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return out.toString()
        }

        private const val HEX = "0123456789abcdef"
    }
}
