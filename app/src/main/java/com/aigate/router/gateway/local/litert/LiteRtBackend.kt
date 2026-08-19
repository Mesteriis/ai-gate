package com.aigate.router.gateway.local.litert

import android.content.Context
import android.util.Log
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
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.SessionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Движок LiteRT-LM: считает скачанные модели формата `.litertlm`.
 *
 * Загрузка модели и её выгрузка отданы [LocalEngineManager] — в памяти
 * устройства помещается только одна модель, и распорядок для всех движков
 * должен быть общим.
 */
class LiteRtBackend(
    private val context: Context,
    private val store: LocalModelStore,
    private val scope: CoroutineScope,
) : LocalBackend {

    override val providerType: String = LocalBackendRegistry.TYPE_LITERT

    override suspend fun readiness(): Readiness {
        if (!LocalEngineManager.isRegistered(EngineKind.LITERT)) {
            return Readiness.NotReady("Движок LiteRT недоступен на устройстве")
        }
        val ready = store.list(EngineKind.LITERT).any { File(it.filePath).isFile }
        return if (ready) Readiness.Ready else Readiness.NotReady("Нет скачанных моделей LiteRT")
    }

    override fun generate(req: LocalChatRequest): Flow<LocalDelta> = flow {
        val record = store.byModelId(EngineKind.LITERT, req.modelId)
            ?: error("Модель «${req.modelId}» не найдена среди скачанных")
        // Файл могли удалить между обновлением списка и запросом.
        if (!File(record.filePath).isFile) error("Файл модели удалён с устройства")

        val spec = EngineSpec(
            kind = EngineKind.LITERT,
            filePath = record.filePath,
            contextWindow = record.contextWindow,
        )

        // Роли отдаём движку как есть, а не склеиваем в стенограмму: у каждой
        // модели свой шаблон диалога, и LiteRT применяет именно её. Со
        // склейкой «Пользователь: … Ассистент:» модель просто продолжает текст
        // и отвечает мусором — проверено на SmolLM2.
        val system = req.messages
            .filter { it.role == "system" || it.role == "developer" }
            .map { it.text }
            .filter { it.isNotBlank() }
        val dialogue = req.messages
            .filter { it.role != "system" && it.role != "developer" && it.text.isNotBlank() }
        val last = dialogue.lastOrNull() ?: error("В запросе нет ни одного сообщения")
        val history = dialogue.dropLast(1).map { msg ->
            if (msg.role == "assistant") Message.model(msg.text) else Message.user(msg.text)
        }
        val opening = buildList {
            if (system.isNotEmpty()) add(Message.system(system.joinToString("\n\n")))
            addAll(history)
        }
        val question = if (last.role == "assistant") Message.model(last.text) else Message.user(last.text)

        var completionTokens = 0
        LocalEngineManager.withEngine(spec, scope) { handle ->
            val engine = handle as Engine
            engine.createConversation(ConversationConfig(initialMessages = opening)).use { conversation ->
                streamOf(conversation, question).collect { piece ->
                    completionTokens++
                    emit(LocalDelta.Token(piece))
                }
            }
        }

        emit(
            LocalDelta.Done(
                finishReason = "stop",
                promptTokens = NanoPromptBuilder.estimateTokens(
                    (system + dialogue.map { it.text }).joinToString("\n")
                ),
                completionTokens = completionTokens,
            )
        )
    }.flowOn(Dispatchers.Default)

    /**
     * Мост из колбэка движка в поток.
     *
     * Движок отдаёт целые сообщения с накопленным текстом, а клиенту нужен
     * прирост — иначе ответ в потоке задваивается на каждом кадре.
     */
    private fun streamOf(conversation: Conversation, question: Message): Flow<String> =
        callbackFlow {
            var sent = ""
            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    val full = message.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                    if (full.length > sent.length && full.startsWith(sent)) {
                        trySend(full.substring(sent.length))
                        sent = full
                    } else if (full.isNotEmpty() && full != sent) {
                        trySend(full)
                        sent += full
                    }
                }

                override fun onDone() {
                    channel.close()
                }

                override fun onError(error: Throwable) {
                    channel.close(error)
                }
            }
            conversation.sendMessageAsync(question, callback)
            awaitClose { }
        }
    /** Как поднять и погасить движок LiteRT. Регистрируется при старте приложения. */
    object Loader : EngineLoader {
        override val kind: EngineKind = EngineKind.LITERT

        override fun load(spec: EngineSpec): Any {
            val backend = when (spec.backendPref) {
                EngineSpec.BACKEND_GPU -> Backend.GPU()
                else -> Backend.CPU()
            }
            val engine = Engine(
                EngineConfig(
                    modelPath = spec.filePath,
                    backend = backend,
                    maxNumTokens = spec.contextWindow,
                )
            )
            // Инициализация занимает секунды и обязана пройти до первого
            // запроса: иначе первый же ответ упрётся в неготовый движок.
            engine.initialize()
            return engine
        }

        override fun close(handle: Any) {
            runCatching { (handle as Engine).close() }
                .onFailure { Log.w("LiteRtBackend", "движок не закрылся: ${it.message}") }
        }
    }
}
