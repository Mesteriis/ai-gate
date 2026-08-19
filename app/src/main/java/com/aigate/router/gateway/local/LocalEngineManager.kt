package com.aigate.router.gateway.local

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Что за модель загружать и чем её считать.
 *
 * [backendPref] — пожелание, а не приказ: если ускоритель не поднялся, движок
 * откатывается на процессор, и это лучше отказа.
 */
data class EngineSpec(
    val kind: EngineKind,
    val filePath: String,
    val contextWindow: Int,
    val backendPref: String = BACKEND_CPU,
) {
    companion object {
        const val BACKEND_CPU = "cpu"
        const val BACKEND_GPU = "gpu"
    }
}

/**
 * Как поднять и погасить движок конкретного вида. Реализация живёт рядом с
 * самим движком, а распорядок — здесь.
 */
interface EngineLoader {
    val kind: EngineKind
    /** Блокирующая загрузка: вызывается на IO. */
    fun load(spec: EngineSpec): Any
    fun close(handle: Any)
}

/**
 * Единственный загруженный тяжёлый движок на весь процесс.
 *
 * Модель на несколько гигабайт живёт в памяти приложения, и две сразу — верный
 * способ получить смерть от нехватки памяти вместе со всем шлюзом. Поэтому
 * слот один: запрос к другой модели сначала выгружает текущую.
 *
 * Мьютекс общий для всех видов движков намеренно: они соперничают не за код, а
 * за одну и ту же память устройства. Ожидание ограничено — лучше уйти на
 * облачную модель, чем держать клиента в очереди неизвестно сколько.
 */
object LocalEngineManager {

    private const val TAG = "LocalEngineManager"

    /** Сколько ждём освобождения движка, прежде чем признать его занятым. */
    private const val BUSY_TIMEOUT_MS = 20_000L

    /** Через сколько простоя выгружаем модель, освобождая память. */
    private const val IDLE_UNLOAD_MS = 5 * 60_000L

    private val mutex = Mutex()
    private val loaders = mutableMapOf<EngineKind, EngineLoader>()

    private class Loaded(val spec: EngineSpec, val handle: Any, val loader: EngineLoader)

    @Volatile
    private var loaded: Loaded? = null

    @Volatile
    private var idleJob: Job? = null

    fun register(loader: EngineLoader) {
        loaders[loader.kind] = loader
    }

    fun isRegistered(kind: EngineKind): Boolean = loaders.containsKey(kind)

    /**
     * Выполнить работу на загруженной модели.
     *
     * Загрузка, вытеснение чужой модели и выгрузка по простою спрятаны здесь,
     * чтобы бэкенды не повторяли этот распорядок каждый по-своему.
     *
     * @throws IllegalStateException если движок не подключён или занят
     */
    suspend fun <T> withEngine(spec: EngineSpec, scope: CoroutineScope, block: suspend (Any) -> T): T {
        val loader = loaders[spec.kind]
            ?: error("Движок «${spec.kind.dbValue}» не подключён на этом устройстве")

        val acquired = withTimeoutOrNull(BUSY_TIMEOUT_MS) { mutex.lock(); true } ?: error(
            "Локальный движок занят другим запросом"
        )
        check(acquired)
        idleJob?.cancel()
        try {
            val handle = ensureLoaded(spec, loader)
            return block(handle)
        } finally {
            mutex.unlock()
            scheduleIdleUnload(scope)
        }
    }

    private suspend fun ensureLoaded(spec: EngineSpec, loader: EngineLoader): Any {
        loaded?.let { if (it.spec == spec) return it.handle }
        // Чужую модель гасим до загрузки новой, а не после: иначе в пике в
        // памяти окажутся обе, и устройство этого не переживёт.
        unloadLocked()
        return withContext(Dispatchers.IO) {
            Log.i(TAG, "Загрузка модели ${spec.filePath} (${spec.backendPref})")
            val handle = loader.load(spec)
            loaded = Loaded(spec, handle, loader)
            handle
        }
    }

    private fun scheduleIdleUnload(scope: CoroutineScope) {
        idleJob?.cancel()
        idleJob = scope.launch(Dispatchers.IO) {
            delay(IDLE_UNLOAD_MS)
            // Замок берём и здесь: выгрузить модель посреди чужого счёта
            // значило бы уронить ответ на середине.
            mutex.withLock { unloadLocked() }
        }
    }

    private fun unloadLocked() {
        val current = loaded ?: return
        loaded = null
        runCatching { current.loader.close(current.handle) }
            .onFailure { Log.w(TAG, "Модель не выгрузилась чисто: ${it.message}") }
        Log.i(TAG, "Модель выгружена: ${current.spec.filePath}")
    }

    /**
     * Система просит освободить память. Выгружаем только когда движок свободен:
     * прервать идущий ответ ради предупреждения о нехватке памяти — потерять
     * работу, которая почти закончена.
     */
    fun onTrimMemory(scope: CoroutineScope) {
        if (loaded == null) return
        scope.launch(Dispatchers.IO) {
            if (mutex.tryLock()) {
                try {
                    unloadLocked()
                } finally {
                    mutex.unlock()
                }
            }
        }
    }

    /** Полная выгрузка: шлюз остановлен, счёт больше не нужен. */
    suspend fun unloadAll() {
        idleJob?.cancel()
        mutex.withLock { unloadLocked() }
    }
}
