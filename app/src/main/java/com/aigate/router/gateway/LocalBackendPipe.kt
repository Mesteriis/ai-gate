package com.aigate.router.gateway

import com.aigate.router.capability.LocalGuard
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.model.Provider
import com.aigate.router.data.model.TokenUsage
import com.aigate.router.gateway.local.LocalBackend
import com.aigate.router.gateway.local.LocalBackendRegistry
import com.aigate.router.gateway.local.LocalChatRequest
import com.aigate.router.gateway.local.LocalDelta
import com.aigate.router.gateway.local.LocalOpenAi
import com.aigate.router.gateway.local.LocalStreamGate
import com.aigate.router.gateway.local.LocalStreamPump
import com.aigate.router.gateway.local.LocalStreamSource
import com.aigate.router.gateway.local.Readiness
import com.aigate.router.service.GatewayForegroundService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/*
 * Обслуживание моделей, которые считаются прямо в этом процессе: системная
 * Gemini Nano, llama.cpp и LiteRT-LM.
 *
 * Такой бэкенд — не ещё один диалект HTTP, а другой транспорт: URL нет,
 * запроса наружу нет, токены приходят потоком из движка. Поэтому вместо ветки
 * внутри HTTP-конвейера здесь отдельный путь, а сам конвейер лишь уходит сюда
 * одной строкой в самом начале, до сборки okhttp-запроса.
 *
 * Переключение при сбое сохраняется за счёт ожидания первого токена — см.
 * [LocalStreamGate].
 */

/** Ожидание первого токена. В него укладывается и загрузка модели в память. */
private const val FIRST_TOKEN_TIMEOUT_MS = 90_000L

/** Потолок на весь нестримовый ответ: клиент не должен висеть вечно. */
private const val NORMAL_TOTAL_TIMEOUT_MS = 180_000L

/** Обслуживает ли этот провайдер локальный бэкенд. */
internal fun ownsLocalBackend(provider: Provider): Boolean =
    LocalBackendRegistry.ownsType(provider.type)

/**
 * Нестримовый локальный ответ: поток движка собирается целиком и отдаётся
 * одним chat.completion.
 */
internal suspend fun pipeLocalNormal(
    call: ApplicationCall,
    provider: Provider,
    rawBody: ByteArray,
    path: String,
    database: AppDatabase,
    modelId: String,
    providerId: Long,
    apiKeyLabel: String,
) {
    val startedAt = System.currentTimeMillis()
    // Локальный провайдер может оказаться выбранным и для запроса, который
    // чатом не является (например, эмбеддинги). Отвечаем честной ошибкой:
    // выдать её за отказ апстрима значит отправить клиента в переключение,
    // которое ничем не поможет.
    if (!path.contains("completions")) {
        call.respondText(
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.BadRequest,
            text = LocalOpenAi.errorJson("Локальная модель отвечает только на запросы чата"),
        )
        return
    }

    val backend = resolveBackend(provider)
    val request = parseRequest(rawBody, modelId)

    val text = StringBuilder()
    var finishReason = "stop"
    var promptTokens = 0
    var completionTokens = 0

    coroutineScope {
        val source = LocalStreamGate.open(this, backend.generate(request), FIRST_TOKEN_TIMEOUT_MS)
        try {
            withTimeout(NORMAL_TOTAL_TIMEOUT_MS) {
                var delta: LocalDelta? = source.first
                while (delta != null) {
                    when (val current: LocalDelta = delta) {
                        is LocalDelta.Token -> text.append(current.text)
                        is LocalDelta.Done -> {
                            finishReason = current.finishReason
                            promptTokens = current.promptTokens
                            completionTokens = current.completionTokens
                        }
                    }
                    delta = source.next()
                }
            }
        } finally {
            source.close()
        }
    }

    // Пустой ответ локального движка — такой же повод переключиться, как
    // пустой ответ апстрима: клиенту он бесполезен.
    if (text.isBlank()) throw Exception("Локальная модель вернула пустой ответ")

    call.respondText(
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.OK,
        text = LocalOpenAi.completionJson(
            id = LocalOpenAi.newId(),
            model = modelId,
            text = text.toString(),
            finishReason = finishReason,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
        ),
    )

    GatewayScheduler.markModelSuccess(modelId, providerId, System.currentTimeMillis() - startedAt)
    saveUsage(database, providerId, modelId, promptTokens, completionTokens, apiKeyLabel)
}

/**
 * Потоковый локальный ответ.
 *
 * Первый токен ждём до открытия ответа — это и есть точка, где ещё можно
 * переключиться на другую модель. Всё, что случится после, клиент увидит как
 * оборванный поток: ровно так же ведёт себя и сетевой путь.
 */
internal suspend fun pipeLocalStream(
    call: ApplicationCall,
    provider: Provider,
    rawBody: ByteArray,
    path: String,
    modelId: String,
    providerId: Long,
    database: AppDatabase,
    apiKeyLabel: String,
) {
    val startedAt = System.currentTimeMillis()
    val backend = resolveBackend(provider)
    val request = parseRequest(rawBody, modelId)
    val pump = LocalStreamPump(modelId = modelId, streamId = LocalOpenAi.newId())

    var promptTokens = 0
    var completionTokens = 0
    var sawText = false

    coroutineScope {
        val source = LocalStreamGate.open(this, backend.generate(request), FIRST_TOKEN_TIMEOUT_MS)
        try {
            call.respondBytesWriter(contentType = ContentType.Text.EventStream, status = HttpStatusCode.OK) {
                try {
                    var delta: LocalDelta? = source.first
                    while (delta != null) {
                        val current = delta
                        if (current is LocalDelta.Done) {
                            promptTokens = current.promptTokens
                            completionTokens = current.completionTokens
                        }
                        for (frame in pump.frameFor(current)) {
                            writeFully(frame)
                            flush()
                            countDownload(frame.size)
                            sawText = true
                        }
                        delta = source.next()
                    }

                    if (!sawText) {
                        writeFully(pump.emptyErrorFrame())
                        flush()
                    }
                    val done = pump.doneFrame()
                    writeFully(done)
                    flush()
                    countDownload(done.size)
                } catch (e: Exception) {
                    // Ответ уже начал уходить: переключиться нельзя, остаётся
                    // закрыть поток и записать причину в отладочный журнал.
                    if (GatewayForegroundService.getDebugMode()) {
                        GatewayForegroundService.addDebugLog("LOCAL STREAM ERR: ${e.message?.take(80)}")
                    }
                }
            }
        } finally {
            // Клиент мог отвалиться на середине — движок должен об этом узнать
            // и прекратить счёт, иначе телефон будет греться впустую.
            source.close()
        }
    }

    if (sawText) {
        GatewayScheduler.markModelSuccess(modelId, providerId, System.currentTimeMillis() - startedAt)
    }
    saveUsage(database, providerId, modelId, promptTokens, completionTokens, apiKeyLabel)
}

private fun countDownload(bytes: Int) {
    GatewayForegroundService.trafficDownloadBytes.addAndGet(bytes.toLong())
    GatewayForegroundService.totalDownloadBytes.addAndGet(bytes.toLong())
}

/**
 * Бэкенд для провайдера плюс обе проверки готовности: поддержка устройства с
 * порогами питания и собственная готовность движка.
 *
 * Все отказы — исключения, потому что вызывающий код обрабатывает их как
 * недоступность модели и идёт к следующему кандидату.
 */
private suspend fun resolveBackend(provider: Provider): LocalBackend {
    // Бэкенда нет в реестре — значит, устройство его не тянет и он не
    // подключался при старте. Объясняем это причиной от проверки поддержки:
    // строка провайдера могла остаться от другого телефона.
    val backend = LocalBackendRegistry.forType(provider.type)
        ?: throw Exception(LocalGuard.unsupportedReason(provider.type))

    LocalGuard.blockReason(provider.type)?.let { throw Exception(it) }

    when (val readiness = backend.readiness()) {
        is Readiness.Ready -> Unit
        is Readiness.NotReady -> throw Exception(readiness.reasonRu)
    }
    return backend
}

private fun parseRequest(rawBody: ByteArray, modelId: String): LocalChatRequest {
    val parsed = LocalOpenAi.parseChatRequest(rawBody.decodeToString())
    // Идентификатор берём от маршрутизатора: в теле может стоять `auto` или
    // имя другой модели, если запрос дошёл сюда переключением.
    return parsed.copy(modelId = modelId)
}

private suspend fun saveUsage(
    database: AppDatabase,
    providerId: Long,
    modelId: String,
    promptTokens: Int,
    completionTokens: Int,
    apiKeyLabel: String,
) {
    val total = promptTokens + completionTokens
    if (total <= 0) return
    withContext(Dispatchers.IO) {
        runCatching {
            database.tokenUsageDao().insert(
                TokenUsage(
                    providerId = providerId,
                    modelId = modelId,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    totalTokens = total,
                    apiKeyLabel = apiKeyLabel,
                )
            )
        }
    }
}
