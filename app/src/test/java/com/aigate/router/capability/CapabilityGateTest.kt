package com.aigate.router.capability

import com.aigate.router.catalog.ModelNameHeuristics
import com.aigate.router.gateway.local.EngineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Нехватку памяти под локальную модель поймать нечем: система убивает процесс
 * молча, уже после старта генерации. Поэтому решение принимается заранее, а
 * фикстуры взяты из реальных весов файлов GGUF, а не из круглых чисел.
 */
class CapabilityGateTest {

    private val phone12Gb = DeviceCaps(
        totalRamBytes = 12_000_000_000,
        availRamBytes = 9_000_000_000,
        isArm64 = true,
        freeDiskBytes = 64_000_000_000,
    )

    private val phone8Gb = phone12Gb.copy(
        totalRamBytes = 8_000_000_000,
        availRamBytes = 6_000_000_000,
    )

    private fun gguf(fileSizeBytes: Long, paramsB: Double?, contextTokens: Int = 4096) =
        ModelDemand(
            fileSizeBytes = fileSizeBytes,
            paramsB = paramsB,
            contextTokens = contextTokens,
            engine = EngineKind.GGUF,
        )

    // Размеры — фактические веса файлов с Hugging Face.
    private val gguf7bQ4 = gguf(4_370_000_000, 7.0)
    private val gguf8bQ4 = gguf(4_920_000_000, 8.0)
    private val gguf8bQ5 = gguf(5_730_000_000, 8.0)
    private val gguf14bQ4 = gguf(8_600_000_000, 14.0)
    private val gguf4bQ4 = gguf(2_670_000_000, 4.0)
    private val gguf4bF16 = gguf(ModelNameHeuristics.estimateFileSizeBytes(4.0, "F16"), 4.0)

    @Test
    fun `на двенадцати гигабайтах четырёхбитные модели до восьми миллиардов проходят`() {
        assertTrue(CapabilityGate.catalogFits(phone12Gb, gguf7bQ4))
        assertTrue(CapabilityGate.catalogFits(phone12Gb, gguf8bQ4))
    }

    @Test
    fun `в каталог не попадает то, что не влезает в половину памяти`() {
        assertFalse("пятибитная 8B уже за бюджетом", CapabilityGate.catalogFits(phone12Gb, gguf8bQ5))
        assertFalse(CapabilityGate.catalogFits(phone12Gb, gguf14bQ4))
        assertFalse("несжатые веса не влезают даже с четырёх миллиардов",
            CapabilityGate.catalogFits(phone12Gb, gguf4bF16))
    }

    @Test
    fun `на восьми гигабайтах остаются только маленькие модели`() {
        assertFalse(CapabilityGate.catalogFits(phone8Gb, gguf8bQ4))
        assertTrue(CapabilityGate.catalogFits(phone8Gb, gguf4bQ4))
    }

    @Test
    fun `состав каталога не зависит от занятости памяти прямо сейчас`() {
        // Иначе список дрожал бы при каждом обновлении: модель то появлялась
        // бы, то исчезала следом за фоновой активностью системы.
        val busy = phone12Gb.copy(availRamBytes = 200_000_000)
        assertTrue(CapabilityGate.catalogFits(busy, gguf7bQ4))
        assertFalse("запустить прямо сейчас всё равно нельзя",
            CapabilityGate.canLoadNow(busy, gguf7bQ4))
    }

    @Test
    fun `свободная память ограничивает запуск, а общая — потолок`() {
        assertTrue(CapabilityGate.canLoadNow(phone12Gb, gguf7bQ4))
        // 5 ГБ свободно → потолок 4 ГБ, модели нужно больше пяти.
        assertFalse(CapabilityGate.canLoadNow(phone12Gb.copy(availRamBytes = 5_000_000_000), gguf7bQ4))
        // Свободной памяти вдоволь, но общий бюджет всё равно не пускает.
        assertFalse(CapabilityGate.canLoadNow(phone12Gb.copy(availRamBytes = 11_000_000_000), gguf14bQ4))
    }

    @Test
    fun `без 64-разрядного ARM отказ независимо от размера модели`() {
        val emulator = phone12Gb.copy(isArm64 = false)
        val tiny = gguf(300_000_000, 0.5)
        assertFalse(CapabilityGate.catalogFits(emulator, tiny))
        val result = CapabilityGate.downloadCheck(emulator, tiny)
        assertTrue("ожидался отказ по процессору, получено $result", result is GateResult.NoAbi)
    }

    @Test
    fun `отказ по памяти называет обе цифры по-русски`() {
        val result = CapabilityGate.downloadCheck(phone12Gb, gguf8bQ5)
        assertTrue("ожидался отказ по памяти, получено $result", result is GateResult.NoRam)
        assertEquals(
            "Нужно 6,8 ГБ оперативной памяти, для моделей доступно 6,0 ГБ",
            (result as GateResult.NoRam).reasonRu,
        )
    }

    @Test
    fun `отказ по диску учитывает запас для системы`() {
        val full = phone12Gb.copy(freeDiskBytes = 3_000_000_000)
        val result = CapabilityGate.downloadCheck(full, gguf7bQ4)
        assertTrue("ожидался отказ по диску, получено $result", result is GateResult.NoDisk)
        // 4,37 ГБ файла плюс гигабайт запаса — раздел под ноль забивать нельзя.
        assertEquals(
            "Нужно 5,4 ГБ свободного места, на устройстве осталось 3,0 ГБ",
            (result as GateResult.NoDisk).reasonRu,
        )
    }

    @Test
    fun `скачивание разрешено, когда хватает и памяти, и диска`() {
        assertEquals(GateResult.Ok, CapabilityGate.downloadCheck(phone12Gb, gguf7bQ4))
    }

    @Test
    fun `процессор проверяется раньше памяти и диска`() {
        // Порядок важен: пользователю незачем чистить диск, если движок на
        // этом устройстве не запустится в принципе.
        val hopeless = DeviceCaps(
            totalRamBytes = 2_000_000_000,
            availRamBytes = 500_000_000,
            isArm64 = false,
            freeDiskBytes = 100_000_000,
        )
        assertTrue(CapabilityGate.downloadCheck(hopeless, gguf14bQ4) is GateResult.NoAbi)
    }

    @Test
    fun `KV-кеш растёт вместе с окном контекста`() {
        val base = CapabilityGate.kvCacheBytes(gguf7bQ4)
        val wide = CapabilityGate.kvCacheBytes(gguf(4_370_000_000, 7.0, contextTokens = 8192))
        assertEquals(469_762_048L, base)
        assertEquals(2 * base, wide)
    }

    @Test
    fun `без числа параметров KV-кеш оценивается по размеру файла`() {
        // У файла, положенного пользователем вручную, параметров в имени может
        // не быть — считать всё равно надо.
        assertEquals(600_000_000L, CapabilityGate.kvCacheBytes(gguf(4_000_000_000, null)))
    }

    @Test
    fun `LiteRT не опускает KV-кеш ниже своего минимума`() {
        fun litert(size: Long) = ModelDemand(size, paramsB = null, engine = EngineKind.LITERT)
        assertEquals(268_435_456L, CapabilityGate.kvCacheBytes(litert(800_000_000)))
        assertEquals(600_000_000L, CapabilityGate.kvCacheBytes(litert(4_000_000_000)))
    }

    @Test
    fun `требуемая память складывается из файла, кеша и запаса на движок`() {
        assertEquals(
            4_370_000_000L + 469_762_048L + CapabilityGate.RUNTIME_OVERHEAD_BYTES,
            CapabilityGate.requiredRamBytes(gguf7bQ4),
        )
    }
}
