package com.aigate.llamacpp

/**
 * Kotlin-обёртка над llama.cpp.
 *
 * Все методы блокирующие: счёт занимает процессор надолго, и делать вид, что он
 * мгновенный, было бы враньём. Вызывающий обязан уводить их с потока, который
 * обслуживает запросы.
 *
 * Библиотеки может не быть: чужая архитектура, урезанный APK, сборка без NDK.
 * Поэтому [isAvailable] проверяется до первого вызова, а не выясняется падением
 * процесса.
 */
object LlamaBridge {

    /** Удалось ли подгрузить нативную библиотеку. */
    val isAvailable: Boolean = try {
        System.loadLibrary("llama-android")
        nativeInit()
        true
    } catch (t: Throwable) {
        false
    }

    /**
     * Загрузка модели в память.
     *
     * @return указатель сессии или 0, если модель не открылась
     */
    fun load(path: String, contextWindow: Int, threads: Int): Long =
        if (!isAvailable) 0L else nativeLoad(path, contextWindow, threads)

    fun free(handle: Long) {
        if (isAvailable && handle != 0L) nativeFree(handle)
    }

    /**
     * Запрос, собранный по шаблону самой модели. Пустая строка означает, что
     * шаблона в файле нет и собирать придётся вызывающему.
     */
    fun formatChat(handle: Long, roles: Array<String>, texts: Array<String>): String =
        if (!isAvailable || handle == 0L) "" else nativeFormatChat(handle, roles, texts)

    /** Обработка запроса. Возвращает число токенов запроса или -1 при ошибке. */
    fun start(handle: Long, prompt: String): Int =
        if (!isAvailable || handle == 0L) -1 else nativeStart(handle, prompt)

    /** Следующий кусок ответа; пустая строка означает конец. */
    fun next(handle: Long): String =
        if (!isAvailable || handle == 0L) "" else nativeNext(handle)

    /** Остановить счёт. Зовётся из другого потока — на то флаг и атомарный. */
    fun cancel(handle: Long) {
        if (isAvailable && handle != 0L) nativeCancel(handle)
    }

    fun decodedTokens(handle: Long): Int =
        if (!isAvailable || handle == 0L) 0 else nativeDecodedTokens(handle)

    private external fun nativeInit()
    private external fun nativeLoad(path: String, contextWindow: Int, threads: Int): Long
    private external fun nativeFree(handle: Long)
    private external fun nativeFormatChat(handle: Long, roles: Array<String>, texts: Array<String>): String
    private external fun nativeStart(handle: Long, prompt: String): Int
    private external fun nativeNext(handle: Long): String
    private external fun nativeCancel(handle: Long)
    private external fun nativeDecodedTokens(handle: Long): Int
}
