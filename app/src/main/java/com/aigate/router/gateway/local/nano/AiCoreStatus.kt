package com.aigate.router.gateway.local.nano

import android.os.Build
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Состояние встроенной модели: есть ли она на устройстве и готова ли отвечать.
 *
 * Спрашивать систему на каждый запрос нельзя — обращение к AICore недёшево, а
 * состояние меняется редко: модель либо уже скачана, либо её ещё качают. Отсюда
 * короткий кэш и явный сброс после загрузки.
 *
 * Все обращения к классам ML Kit закрыты проверкой версии Android: библиотеке
 * нужен API 26, а приложение работает с 24. На более старых устройствах
 * загрузка её классов уронила бы процесс, поэтому там сразу возвращается
 * «недоступно».
 */
object AiCoreStatus {

    private const val TAG = "AiCoreStatus"

    /** Библиотеке Gemini Nano нужен Android 8.0. */
    private const val MIN_SDK = 26

    /** Столько живёт ответ системы. Состояние меняется редко, но не никогда. */
    private const val CACHE_TTL_MS = 5 * 60_000L

    /** Что ответила система о встроенной модели. */
    enum class Availability {
        /** Модель на устройстве, можно спрашивать. */
        AVAILABLE,

        /** Устройство поддерживает модель, но её надо скачать. */
        DOWNLOADABLE,

        /** Система уже качает модель. */
        DOWNLOADING,

        /** Ни модели, ни возможности её получить. */
        UNAVAILABLE,
    }

    @Volatile
    private var cached: Availability? = null

    @Volatile
    private var cachedAt = 0L

    /**
     * Текущее состояние. Любая беда — отсутствие AICore, отказ сервиса, сбой
     * загрузки классов — это [Availability.UNAVAILABLE], а не исключение:
     * вызывающий должен уметь просто не показывать функцию.
     */
    suspend fun availability(): Availability {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < CACHE_TTL_MS) return it }

        val fresh = try {
            if (Build.VERSION.SDK_INT < MIN_SDK) {
                Availability.UNAVAILABLE
            } else {
                val model = client()
                when (model?.checkStatus()) {
                    // Ответу «готово» верить нельзя без проверки. На Galaxy Z
                    // Fold7 AICore отвечает именно так, а сразу за этим пишет
                    // в журнал «checkFeatureStatus failed: Feature 654 is not
                    // available» — то есть Prompt API на устройстве нет вовсе.
                    // Без проверки модель попала бы в список включённой, и
                    // каждый запрос к ней падал бы уже у клиента.
                    FeatureStatus.AVAILABLE ->
                        if (respondsToMetadata(model)) Availability.AVAILABLE else Availability.UNAVAILABLE

                    FeatureStatus.DOWNLOADABLE -> Availability.DOWNLOADABLE
                    FeatureStatus.DOWNLOADING -> Availability.DOWNLOADING
                    else -> Availability.UNAVAILABLE
                }
            }
        } catch (t: Throwable) {
            // NoClassDefFoundError и прочие ошибки загрузки классов ловятся
            // здесь же: на устройстве без сервисов Google их не поймать иначе.
            Log.w(TAG, "Состояние встроенной модели не получено: ${t.message}")
            Availability.UNAVAILABLE
        }

        cached = fresh
        cachedAt = now
        return fresh
    }

    /**
     * Отвечает ли модель на дешёвый запрос метаданных.
     *
     * Предел контекста модель знает и без счёта, поэтому вызов ничего не стоит
     * и не греет устройство. Зато если нужной возможности в AICore нет, здесь
     * прилетит исключение — в отличие от [GenerativeModel.checkStatus], который
     * такую беду проглатывает и всё равно рапортует о готовности.
     */
    private suspend fun respondsToMetadata(model: GenerativeModel): Boolean = try {
        model.getTokenLimit() > 0
    } catch (t: Throwable) {
        Log.w(TAG, "Встроенная модель числится готовой, но не отвечает: ${t.message}")
        false
    }

    /**
     * Клиент модели или null, если библиотека недоступна. Клиент не кэшируется:
     * он дешёвый, а держать его открытым между запросами значит удерживать
     * ресурсы AICore без нужды.
     */
    fun client(): GenerativeModel? = try {
        if (Build.VERSION.SDK_INT < MIN_SDK) null else Generation.getClient()
    } catch (t: Throwable) {
        Log.w(TAG, "Клиент встроенной модели не создан: ${t.message}")
        null
    }

    /** Ход системной загрузки модели — для показа на экране. */
    sealed interface DownloadState {
        data object Idle : DownloadState
        data object Started : DownloadState
        data class Progress(val doneBytes: Long, val totalBytes: Long) : DownloadState
        data object Completed : DownloadState
        data class Failed(val reasonRu: String) : DownloadState
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    @Volatile
    private var downloadJob: Job? = null

    /**
     * Запуск системной загрузки модели.
     *
     * Собирать поток обязательно: без подписчика загрузка не начинается. И
     * собирать его надо в области жизни приложения, а не экрана — иначе уход с
     * экрана обрывает многогигабайтную загрузку на середине, что однажды здесь
     * и произошло.
     *
     * Повторный вызов при живой загрузке ничего не делает: система и так качает.
     */
    fun startDownload(scope: CoroutineScope) {
        if (downloadJob?.isActive == true) return
        val model = client() ?: run {
            _downloadState.value = DownloadState.Failed("Встроенная модель недоступна на устройстве")
            return
        }
        downloadJob = scope.launch(Dispatchers.IO) {
            _downloadState.value = DownloadState.Started
            var total = 0L
            try {
                model.download().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> {
                            total = status.bytesToDownload
                            _downloadState.value = DownloadState.Progress(0L, total)
                            Log.i(TAG, "Загрузка встроенной модели начата: $total Б")
                        }

                        is DownloadStatus.DownloadProgress ->
                            _downloadState.value = DownloadState.Progress(status.totalBytesDownloaded, total)

                        is DownloadStatus.DownloadCompleted -> {
                            _downloadState.value = DownloadState.Completed
                            Log.i(TAG, "Встроенная модель скачана")
                        }

                        is DownloadStatus.DownloadFailed -> {
                            val reason = status.e.message ?: "система не смогла скачать модель"
                            _downloadState.value = DownloadState.Failed(reason)
                            Log.w(TAG, "Загрузка встроенной модели не удалась: $reason")
                        }

                        else -> Unit
                    }
                }
            } catch (t: Throwable) {
                _downloadState.value = DownloadState.Failed(t.message ?: "загрузка прервана")
                Log.w(TAG, "Загрузка встроенной модели прервана: ${t.message}")
            } finally {
                invalidate()
            }
        }
    }

    /** Сброс кэша: после загрузки состояние меняется, ждать пять минут незачем. */
    fun invalidate() {
        cached = null
    }
}
