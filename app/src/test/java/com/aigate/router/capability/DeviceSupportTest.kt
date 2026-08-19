package com.aigate.router.capability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Мягкое отключение локального ИИ. На устройстве без встроенной модели функция
 * обязана числиться недоступной с понятной причиной, а не падать при первом
 * обращении, поэтому проверяются и флаги, и тексты.
 */
class DeviceSupportTest {

    private val cyrillic = Regex("[а-яА-ЯёЁ]")

    /** Полностью пригодное устройство: от него отталкиваются остальные случаи. */
    private fun signals(
        sdkInt: Int = 34,
        aiCore: Boolean = true,
        mlKit: Boolean = true,
        arm64: Boolean = true,
        llama: Boolean = true,
        litert: Boolean = true,
    ) = SupportSignals(
        sdkInt = sdkInt,
        aiCoreInstalled = aiCore,
        mlKitClassesPresent = mlKit,
        arm64 = arm64,
        llamaLibraryLoadable = llama,
        liteRtClassesPresent = litert,
    )

    private fun reasonOf(feature: FeatureSupport): String {
        assertFalse("ожидалась недоступность", feature.supported)
        val reason = feature.reasonRu
        assertNotNull("у недоступной функции должна быть причина", reason)
        assertTrue("причина должна быть на русском: $reason", cyrillic.containsMatchIn(reason!!))
        return reason
    }

    @Test
    fun `fully capable device enables everything without reasons`() {
        val report = DeviceSupport.evaluate(signals())

        assertTrue(report.nano.supported)
        assertTrue(report.llama.supported)
        assertTrue(report.litert.supported)
        assertTrue(report.anyEngineSupported)
        assertTrue(report.anyLocalSupported)
        assertNull(report.nano.reasonRu)
        assertNull(report.llama.reasonRu)
        assertNull(report.litert.reasonRu)
    }

    @Test
    fun `old android disables the system model but keeps engines`() {
        val report = DeviceSupport.evaluate(signals(sdkInt = 24))

        assertTrue(reasonOf(report.nano).contains("Android"))
        // Движки собственные и от версии системы не зависят.
        assertTrue(report.llama.supported)
        assertTrue(report.litert.supported)
        assertTrue(report.anyEngineSupported)
    }

    @Test
    fun `device without aicore reports missing system service`() {
        val report = DeviceSupport.evaluate(signals(aiCore = false))

        assertTrue(reasonOf(report.nano).contains("AICore"))
    }

    @Test
    fun `aicore without ml kit classes is still unavailable`() {
        // Пакет есть, а классов нет: вызов упал бы ошибкой загрузки классов,
        // поэтому функция должна выключиться заранее.
        val report = DeviceSupport.evaluate(signals(mlKit = false))

        assertTrue(reasonOf(report.nano).contains("ML Kit"))
    }

    @Test
    fun `non arm64 device loses both engines but may keep the system model`() {
        val report = DeviceSupport.evaluate(signals(arm64 = false))

        assertTrue(reasonOf(report.llama).contains("arm64"))
        assertTrue(reasonOf(report.litert).contains("arm64"))
        assertFalse("каталог скачиваемых моделей показывать нечему", report.anyEngineSupported)
        // Системная модель считается отдельно: она не зависит от разрядности движков.
        assertTrue(report.nano.supported)
        assertTrue(report.anyLocalSupported)
    }

    @Test
    fun `missing native library disables only llama`() {
        val report = DeviceSupport.evaluate(signals(llama = false))

        assertTrue(reasonOf(report.llama).contains("llama.cpp"))
        assertTrue(report.litert.supported)
        assertTrue("один живой движок оставляет каталог осмысленным", report.anyEngineSupported)
    }

    @Test
    fun `missing litert classes disable only litert`() {
        val report = DeviceSupport.evaluate(signals(litert = false))

        assertTrue(reasonOf(report.litert).contains("LiteRT"))
        assertTrue(report.llama.supported)
        assertTrue(report.anyEngineSupported)
    }

    @Test
    fun `device with nothing supported reports every feature off`() {
        val report = DeviceSupport.evaluate(
            signals(sdkInt = 21, aiCore = false, mlKit = false, arm64 = false, llama = false, litert = false)
        )

        reasonOf(report.nano)
        reasonOf(report.llama)
        reasonOf(report.litert)
        assertFalse(report.anyEngineSupported)
        assertFalse("ничего локального включать нельзя", report.anyLocalSupported)
    }
}
