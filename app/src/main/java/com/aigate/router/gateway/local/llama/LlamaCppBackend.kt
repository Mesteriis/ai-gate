package com.aigate.router.gateway.local.llama

import com.aigate.llamacpp.LlamaBridge
import com.aigate.router.gateway.local.ChatMsg
import com.aigate.router.gateway.local.EngineKind
import com.aigate.router.gateway.local.EngineLoader
import com.aigate.router.gateway.local.EngineSpec
import com.aigate.router.gateway.local.LocalBackend
import com.aigate.router.gateway.local.LocalBackendRegistry
import com.aigate.router.gateway.local.LocalChatRequest
import com.aigate.router.gateway.local.LocalDelta
import com.aigate.router.gateway.local.LocalEngineManager
import com.aigate.router.gateway.local.LocalModelStore
import com.aigate.router.gateway.local.Readiness
import com.aigate.router.gateway.local.nano.NanoPromptBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.File

/**
 * Движок llama.cpp: считает скачанные модели формата GGUF.
 *
 * Загрузка и выгрузка отданы [LocalEngineManager] — слот на весь процесс один,
 * и делить его с LiteRT нужно по общим правилам.
 */
class LlamaCppBackend(
    private val store: LocalModelStore,
    private val scope: CoroutineScope,
) : LocalBackend {

    override val providerType: String = LocalBackendRegistry.TYPE_LLAMA

    override suspend fun readiness(): Readiness {
        if (!LlamaBridge.isAvailable) return Readiness.NotReady("Библиотека llama.cpp не загрузилась")
        val ready = store.list(EngineKind.GGUF).any { File(it.filePath).isFile }
        return if (ready) Readiness.Ready else Readiness.NotReady("Нет скачанных моделей GGUF")
    }

    override fun generate(req: LocalChatRequest): Flow<LocalDelta> = flow {
        val record = store.byModelId(EngineKind.GGUF, req.modelId)
            ?: error("Модель «${req.modelId}» не найдена среди скачанных")
        if (!File(record.filePath).isFile) error("Файл модели удалён с устройства")

        val spec = EngineSpec(
            kind = EngineKind.GGUF,
            filePath = record.filePath,
            contextWindow = record.contextWindow,
        )

        var promptTokens = 0
        var completionTokens = 0
        var finishReason = "stop"

        LocalEngineManager.withEngine(spec, scope) { handle ->
            val session = handle as Long
            val prompt = buildPrompt(session, req.messages)
            promptTokens = LlamaBridge.start(session, prompt)
            if (promptTokens < 0) error("Модель не приняла запрос")

            val limit = req.maxTokens?.takeIf { it > 0 } ?: DEFAULT_MAX_TOKENS
            while (completionTokens < limit) {
                // Отмену проверяем перед каждым шагом и уносим её в нативный
                // цикл: без этого брошенный клиентом счёт догорал бы до конца,
                // грея телефон впустую.
                if (!currentCoroutineContext().isActive) {
                    LlamaBridge.cancel(session)
                    finishReason = "stop"
                    break
                }
                val piece = LlamaBridge.next(session)
                if (piece.isEmpty()) break
                completionTokens++
                emit(LocalDelta.Token(piece))
            }
            if (completionTokens >= limit) finishReason = "length"
        }

        emit(
            LocalDelta.Done(
                finishReason = finishReason,
                promptTokens = promptTokens.coerceAtLeast(0),
                completionTokens = completionTokens,
            )
        )
    }.flowOn(Dispatchers.Default)

    /**
     * Запрос в формате самой модели.
     *
     * Шаблон берётся из файла GGUF — у каждой модели он свой, и чужой заставляет
     * её продолжать текст вместо ответа. Если шаблона в файле нет, остаётся
     * стенограмма: она хуже, но лучше пустого ответа.
     */
    private fun buildPrompt(session: Long, messages: List<ChatMsg>): String {
        val usable = messages.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) error("В запросе нет ни одного сообщения")
        val roles = usable.map { if (it.role == "developer") "system" else it.role }.toTypedArray()
        val texts = usable.map { it.text }.toTypedArray()
        val templated = LlamaBridge.formatChat(session, roles, texts)
        return templated.ifBlank { NanoPromptBuilder.build(usable) }
    }

    /** Как поднять и погасить движок. Регистрируется при старте приложения. */
    object Loader : EngineLoader {
        override val kind: EngineKind = EngineKind.GGUF

        override fun load(spec: EngineSpec): Any {
            // Половина ядер: занять все — значит отобрать процессор у самого
            // шлюза и у системы, и ответ станет не быстрее, а рывками.
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
            val handle = LlamaBridge.load(spec.filePath, spec.contextWindow, threads)
            if (handle == 0L) error("Модель не открылась: ${spec.filePath}")
            return handle
        }

        override fun close(handle: Any) {
            LlamaBridge.free(handle as Long)
        }
    }

    private companion object {
        /** Потолок ответа, когда клиент не назвал свой. */
        const val DEFAULT_MAX_TOKENS = 1024
    }
}
