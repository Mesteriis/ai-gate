package com.aigate.router.capability

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Зонд доступности локального ИИ: снимает сырые признаки устройства и отдаёт
 * их [DeviceSupport] на толкование.
 *
 * Здесь нет ни одной ветки политики — только факты. Зато здесь собраны все
 * опасные проверки: обращение к отсутствующему пакету, загрузка классов
 * beta-SDK и подгрузка нативной библиотеки. Любая из них на неподходящем
 * устройстве бросает не обычное исключение, а ошибку загрузки
 * (NoClassDefFoundError, UnsatisfiedLinkError), поэтому ловится Throwable:
 * задача зонда — вернуть «нет», а не уронить процесс.
 *
 * Результат кэшируется: набор признаков в пределах запуска не меняется, а
 * загрузка классов стоит заметно дороже, чем чтение флага.
 */
object DeviceSupportProbe {

    private const val TAG = "DeviceSupportProbe"

    /** Системный сервис Google, в котором исполняется Gemini Nano. */
    private const val AICORE_PACKAGE = "com.google.android.aicore"

    /**
     * Точка входа ML Kit GenAI. Проверяется именно загружаемость класса:
     * зависимость может отсутствовать в сборке или не разрешиться на
     * устройстве без сервисов Google.
     */
    private const val ML_KIT_ENTRY_CLASS = "com.google.mlkit.genai.prompt.Generation"

    /** Точка входа LiteRT-LM. */
    private const val LITERT_ENTRY_CLASS = "com.google.ai.edge.litertlm.Engine"

    /** Имя нативной библиотеки модуля llama.cpp. */
    private const val LLAMA_LIBRARY = "llama-android"

    @Volatile
    private var cachedReport: SupportReport? = null

    /**
     * Готовый отчёт. Первый вызов делает реальные проверки, дальше отдаётся
     * кэш. Вызывать можно откуда угодно, включая обработку запроса: тяжёлая
     * работа случается ровно один раз.
     */
    fun report(context: Context): SupportReport =
        cachedReport ?: synchronized(this) {
            cachedReport ?: DeviceSupport.evaluate(probe(context)).also {
                cachedReport = it
                Log.i(TAG, "Локальный ИИ: nano=${it.nano.supported}, llama=${it.llama.supported}, litert=${it.litert.supported}")
            }
        }

    /**
     * Уже посчитанный отчёт без побочных эффектов — для мест, где контекста
     * под рукой нет, а ждать проверки нельзя (например, отрисовка списка).
     * До первого [report] возвращает null.
     */
    fun cached(): SupportReport? = cachedReport

    /** Сброс кэша: нужен тестам и на случай доустановки системных компонентов. */
    fun invalidate() {
        cachedReport = null
    }

    private fun probe(context: Context): SupportSignals = SupportSignals(
        sdkInt = Build.VERSION.SDK_INT,
        aiCoreInstalled = isPackageInstalled(context, AICORE_PACKAGE),
        mlKitClassesPresent = isClassPresent(ML_KIT_ENTRY_CLASS),
        arm64 = Build.SUPPORTED_64_BIT_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) },
        llamaLibraryLoadable = isLibraryLoadable(LLAMA_LIBRARY),
        liteRtClassesPresent = isClassPresent(LITERT_ENTRY_CLASS),
    )

    private fun isPackageInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (t: Throwable) {
        // Например, ограничения видимости пакетов на новых Android: считаем
        // это отсутствием, а не поводом для отказа всему приложению.
        Log.w(TAG, "Не удалось проверить пакет $packageName: ${t.message}")
        false
    }

    /**
     * initialize=false намеренно: нам нужно знать, что класс есть, а не
     * запускать его статические блоки — они могут потянуть за собой сервисы,
     * которых на устройстве нет.
     */
    private fun isClassPresent(className: String): Boolean = try {
        Class.forName(className, false, DeviceSupportProbe::class.java.classLoader)
        true
    } catch (t: Throwable) {
        false
    }

    private fun isLibraryLoadable(name: String): Boolean = try {
        System.loadLibrary(name)
        true
    } catch (t: Throwable) {
        // На этапах, где модуль llama.cpp ещё не собран, это обычное состояние:
        // движок просто числится недоступным.
        false
    }
}
