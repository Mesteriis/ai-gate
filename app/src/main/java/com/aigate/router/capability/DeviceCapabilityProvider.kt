package com.aigate.router.capability

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.aigate.router.download.ModelStorage

/**
 * Снимок мощности устройства для [CapabilityGate].
 *
 * Здесь только съём цифр: ни одного порога и ни одной формулы — решение
 * принимает гейт, и проверяется оно JVM-тестом. Разделение нужно ровно затем,
 * чтобы правила подбора модели не требовали эмулятора.
 *
 * Кэшируется только то, что не меняется за время работы приложения: общий
 * объём памяти и разрядность процессора. Свободная память и свободное место
 * читаются на каждый вызов — они меняются постоянно, а на них принимается
 * решение о запуске модели прямо сейчас, и вчерашнее значение здесь опаснее
 * лишнего обращения к системе.
 */
object DeviceCapabilityProvider {

    /**
     * Общая память, когда ActivityManager не ответил. Взят типичный минимум
     * телефона, на котором вообще имеет смысл локальная модель: с ним гейт
     * пропустит только маленькие модели. Ноль запретил бы всё навсегда, а
     * щедрая цифра разрешила бы запуск, который убьёт процесс.
     */
    private const val FALLBACK_TOTAL_RAM_BYTES: Long = 4L * 1024 * 1024 * 1024

    /**
     * Доля общей памяти, которая считается свободной, когда сведений нет.
     * Половина — состояние обычного телефона с открытым приложением.
     */
    private const val FALLBACK_AVAIL_FRACTION: Double = 0.5

    private const val ARM64_ABI = "arm64-v8a"

    /** Неизменная часть снимка; считается один раз за запуск. */
    private data class FixedCaps(val totalRamBytes: Long, val isArm64: Boolean)

    @Volatile
    private var fixed: FixedCaps? = null

    /** Текущее состояние устройства в том виде, в каком его ждёт [CapabilityGate]. */
    fun current(context: Context): DeviceCaps {
        val stable = fixed ?: synchronized(this) {
            fixed ?: readFixed(context).also { fixed = it }
        }
        return DeviceCaps(
            totalRamBytes = stable.totalRamBytes,
            availRamBytes = readAvailRam(context, stable.totalRamBytes),
            isArm64 = stable.isArm64,
            // Свободное место считает хранилище моделей: важен раздел, куда
            // ляжет файл, а он может отличаться от внутренней памяти.
            freeDiskBytes = ModelStorage.freeBytes(context),
        )
    }

    /** Сброс кэша: нужен тестам, а также после смены раздела хранения. */
    fun invalidate() {
        fixed = null
    }

    private fun readFixed(context: Context): FixedCaps = FixedCaps(
        totalRamBytes = memoryInfo(context)?.totalMem?.takeIf { it > 0 } ?: FALLBACK_TOTAL_RAM_BYTES,
        isArm64 = isArm64(),
    )

    private fun readAvailRam(context: Context, totalRamBytes: Long): Long =
        memoryInfo(context)?.availMem?.takeIf { it > 0 }
            ?: (totalRamBytes * FALLBACK_AVAIL_FRACTION).toLong()

    /**
     * Сервис может оказаться недоступен на урезанной прошивке или в момент
     * выключения процесса. Ловится Throwable, а не Exception: на нестандартной
     * сборке обращение к системному сервису способно закончиться ошибкой
     * загрузки класса, а зонд обязан вернуть цифру, а не уронить приложение.
     */
    private fun memoryInfo(context: Context): ActivityManager.MemoryInfo? = try {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.let { service ->
            ActivityManager.MemoryInfo().also { info -> service.getMemoryInfo(info) }
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Разрядность процессора. Если список ABI прочитать не удалось, считаем
     * устройство неподходящим: движки собраны только под arm64, и «нет» здесь
     * означает понятный отказ вместо падения нативной библиотеки.
     */
    private fun isArm64(): Boolean = try {
        Build.SUPPORTED_64_BIT_ABIS.any { it.equals(ARM64_ABI, ignoreCase = true) }
    } catch (_: Throwable) {
        false
    }
}
