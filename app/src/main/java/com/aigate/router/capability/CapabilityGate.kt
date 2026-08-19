package com.aigate.router.capability

import com.aigate.router.gateway.local.EngineKind
import kotlin.math.max
import kotlin.math.roundToLong

/*
 * Гейт по мощности устройства.
 *
 * Локальная модель — единственное место в приложении, где ошибка планирования
 * стоит не сообщения об ошибке, а убийства процесса системой: Android не даёт
 * поймать нехватку памяти при выделении буферов движка. Поэтому решение
 * принимается заранее, и на три разных вопроса отвечают три разные функции:
 * показывать ли модель в каталоге, разрешать ли скачивание и влезет ли она в
 * память прямо сейчас.
 *
 * Файл свободен от Android-зависимостей: цифры устройства собирает вызывающий
 * код, сюда они приходят обычными числами, и вся арифметика проверяется
 * обычным JVM-тестом.
 */

/** Что известно про устройство. */
data class DeviceCaps(
    val totalRamBytes: Long,
    val availRamBytes: Long,
    val isArm64: Boolean,
    val freeDiskBytes: Long,
)

/**
 * Что просит модель.
 *
 * [paramsB] опционально: у файла, положенного пользователем в папку загрузок,
 * число параметров может быть неизвестно, и это не повод отказываться считать.
 */
data class ModelDemand(
    val fileSizeBytes: Long,
    val paramsB: Double?,
    val contextTokens: Int = 4096,
    val engine: EngineKind,
)

/**
 * Итог проверки. Причина отказа приходит готовой строкой на русском: подобрать
 * формулировку можно только там, где известны числа, а UI не должен превращать
 * коды ошибок в текст.
 */
sealed interface GateResult {

    data object Ok : GateResult

    data class NoRam(val reasonRu: String) : GateResult

    data class NoDisk(val reasonRu: String) : GateResult

    data class NoAbi(val reasonRu: String) : GateResult
}

object CapabilityGate {

    /**
     * Запас памяти на сам движок: контекст исполнения, промежуточные буферы
     * слоёв, аллокатор. От размера модели почти не зависит, поэтому задан
     * константой.
     */
    const val RUNTIME_OVERHEAD_BYTES: Long = 512L * 1024 * 1024

    /**
     * Свободное место, которое нельзя занимать под модель: системе нужен запас
     * на кеши и обновления, а заполненный до нуля раздел ломает всё устройство,
     * а не только приложение.
     */
    const val DISK_HEADROOM_BYTES: Long = 1024L * 1024 * 1024

    /**
     * Доля всей оперативной памяти, которую приложение вправе занять моделью.
     * Половина, а не больше: остальное нужно системе, лаунчеру и самому
     * приложению, иначе процесс убьют при первом переключении экрана.
     */
    const val RAM_BUDGET_FRACTION: Double = 0.5

    /** Доля свободной прямо сейчас памяти — остаток нужен на пики генерации. */
    const val AVAIL_FRACTION: Double = 0.8

    /** KV-кеш на миллиард параметров при окне 4096 токенов. */
    private const val KV_PER_BILLION_AT_BASE_CONTEXT: Long = 64L * 1024 * 1024

    private const val BASE_CONTEXT_TOKENS: Double = 4096.0

    /**
     * Запасная доля от размера файла, когда число параметров неизвестно.
     * Размер файла примерно пропорционален числу параметров, так что 15 % —
     * та же оценка, только выраженная через то, что известно точно.
     */
    private const val KV_FALLBACK_SHARE: Double = 0.15

    /** Нижняя граница KV-кеша LiteRT: меньше движок не выделяет никогда. */
    private const val LITERT_MIN_KV_BYTES: Long = 256L * 1024 * 1024

    /**
     * Память под кеш ключей и значений — вторая по величине статья расхода
     * после самих весов и единственная, которая растёт с длиной диалога.
     */
    fun kvCacheBytes(demand: ModelDemand): Long = when (demand.engine) {
        EngineKind.GGUF -> {
            val params = demand.paramsB
            if (params != null && params > 0.0) {
                val contextScale = demand.contextTokens.coerceAtLeast(1) / BASE_CONTEXT_TOKENS
                (params * KV_PER_BILLION_AT_BASE_CONTEXT * contextScale).toLong()
            } else {
                (demand.fileSizeBytes * KV_FALLBACK_SHARE).toLong()
            }
        }
        // LiteRT не даёт задать окно контекста и держит собственные буферы,
        // поэтому считать по числу параметров нечего: берём долю от файла, но
        // не ниже фактического минимума движка.
        EngineKind.LITERT ->
            max(LITERT_MIN_KV_BYTES, (demand.fileSizeBytes * KV_FALLBACK_SHARE).toLong())
    }

    /** Сколько всего оперативной памяти нужно, чтобы модель работала. */
    fun requiredRamBytes(demand: ModelDemand): Long =
        demand.fileSizeBytes + kvCacheBytes(demand) + RUNTIME_OVERHEAD_BYTES

    /** Потолок памяти под модель на этом устройстве. */
    fun ramBudgetBytes(caps: DeviceCaps): Long =
        (caps.totalRamBytes * RAM_BUDGET_FRACTION).toLong()

    /**
     * Показывать ли модель в каталоге.
     *
     * Проверка сознательно смотрит только на общий объём памяти и не смотрит на
     * свободную: свободная меняется каждую секунду, и список моделей дрожал бы
     * при каждом обновлении — модель то появлялась бы, то исчезала. Достаточно
     * ли памяти именно сейчас, отвечает [canLoadNow] в момент запуска.
     */
    fun catalogFits(caps: DeviceCaps, demand: ModelDemand): Boolean =
        caps.isArm64 && requiredRamBytes(demand) <= ramBudgetBytes(caps)

    /**
     * Можно ли начинать скачивание. Порядок проверок — от непоправимого к
     * поправимому: процессор пользователь не сменит, память освободит вряд ли,
     * а место на диске расчистит.
     *
     * Память сверяется с общим объёмом, а не со свободным: скачанная модель
     * запускается позже, когда устройство может быть свободнее.
     */
    fun downloadCheck(caps: DeviceCaps, demand: ModelDemand): GateResult {
        if (!caps.isArm64) {
            return GateResult.NoAbi("Локальные движки работают только на 64-разрядных процессорах ARM")
        }
        val required = requiredRamBytes(demand)
        val budget = ramBudgetBytes(caps)
        if (required > budget) {
            return GateResult.NoRam(
                "Нужно ${formatGb(required)} оперативной памяти, для моделей доступно ${formatGb(budget)}"
            )
        }
        val diskNeeded = demand.fileSizeBytes + DISK_HEADROOM_BYTES
        if (diskNeeded > caps.freeDiskBytes) {
            return GateResult.NoDisk(
                "Нужно ${formatGb(diskNeeded)} свободного места, на устройстве осталось ${formatGb(caps.freeDiskBytes)}"
            )
        }
        return GateResult.Ok
    }

    /**
     * Влезет ли модель в память прямо сейчас. Помимо общего бюджета учитывает
     * свободную память: занять её всю нельзя, на пиках генерации нужен запас.
     */
    fun canLoadNow(caps: DeviceCaps, demand: ModelDemand): Boolean {
        val ceiling = minOf(ramBudgetBytes(caps), (caps.availRamBytes * AVAIL_FRACTION).toLong())
        return requiredRamBytes(demand) <= ceiling
    }

    /**
     * Гигабайты для человека: одна цифра после запятой, десятичный разделитель
     * — запятая, ГБ считается как миллиард байт (так же, как на коробке
     * устройства). Своя функция вместо платформенного форматирования:
     * `String.format` зависит от локали и на англоязычной системе напечатал бы
     * точку, а android-форматтер утащил бы файл из JVM-тестов.
     */
    private fun formatGb(bytes: Long): String {
        val tenths = (bytes.coerceAtLeast(0) / 1e8).roundToLong()
        return "${tenths / 10},${tenths % 10} ГБ"
    }
}
