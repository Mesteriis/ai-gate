package com.aigate.router.gateway.local.nano

import android.os.Build
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.flow.Flow

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
                when (client()?.checkStatus()) {
                    FeatureStatus.AVAILABLE -> Availability.AVAILABLE
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

    /**
     * Запуск системной загрузки модели. Поток отдаётся вызывающему как есть:
     * показывать ход — дело экрана, а не этого объекта.
     *
     * Загрузка идёт силами системы и переживает закрытие приложения.
     */
    fun download(): Flow<Any>? = try {
        @Suppress("UNCHECKED_CAST")
        client()?.download() as Flow<Any>?
    } catch (t: Throwable) {
        Log.w(TAG, "Загрузка встроенной модели не начата: ${t.message}")
        null
    }

    /** Сброс кэша: после загрузки состояние меняется, ждать пять минут незачем. */
    fun invalidate() {
        cached = null
    }
}
