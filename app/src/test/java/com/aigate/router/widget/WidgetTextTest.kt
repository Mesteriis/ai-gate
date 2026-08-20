package com.aigate.router.widget

import com.aigate.router.quota.ResourcePoolKind
import com.aigate.router.quota.ResourcePressure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Формулировки виджета проверяются здесь, потому что RemoteViews в JVM-тестах
 * недоступны: правила «значение говорит про остаток», «нет данных — прочерк»
 * и «тип ресурса называется своим словом» должны держаться без запуска на устройстве.
 */
class WidgetTextTest {

    @Test
    fun `квота показывает остаток в процентах`() {
        val value = WidgetText.poolValue(
            kind = ResourcePoolKind.QUOTA,
            remaining = 3.0,
            limit = 100.0,
            used = 97.0,
            unit = "PERCENT",
        )
        assertEquals("осталось 3%", value)
    }

    @Test
    fun `бюджет показывает остаток из лимита`() {
        val value = WidgetText.poolValue(
            kind = ResourcePoolKind.BUDGET,
            remaining = 3.8,
            limit = 20.0,
            used = 16.2,
            unit = "USD",
        )
        assertEquals("осталось \$3,80 из \$20,00", value)
    }

    @Test
    fun `баланс показывает сумму на счету`() {
        val value = WidgetText.poolValue(
            kind = ResourcePoolKind.BALANCE,
            remaining = 4.12,
            limit = null,
            used = null,
            unit = "USD",
        )
        assertEquals("\$4,12", value)
    }

    @Test
    fun `баланс в кредитах называет единицу словом`() {
        val value = WidgetText.poolValue(
            kind = ResourcePoolKind.BALANCE,
            remaining = 1240.0,
            limit = null,
            used = null,
            unit = "CREDITS",
        )
        assertEquals("1240,00 кред.", value)
    }

    @Test
    fun `проценты и доллары несут свой знак сами`() {
        assertEquals("", WidgetText.unitSuffix("USD"))
        assertEquals("", WidgetText.unitSuffix("PERCENT"))
        assertEquals(" ток.", WidgetText.unitSuffix("tokens"))
        assertEquals(" запр.", WidgetText.unitSuffix("REQUESTS"))
        assertEquals(" мин", WidgetText.unitSuffix("COMPUTE_MINUTES"))
    }

    @Test
    fun `бесплатный пул не имеет лимита`() {
        val value = WidgetText.poolValue(ResourcePoolKind.FREE, null, null, null, "UNKNOWN")
        assertEquals("без лимита", value)
    }

    @Test
    fun `без снимка вместо числа прочерк`() {
        val value = WidgetText.poolValue(ResourcePoolKind.QUOTA, null, null, null, "TOKENS")
        assertEquals(WidgetText.DASH, value)
    }

    @Test
    fun `доля израсходованного считается от лимита`() {
        assertEquals(0.97, WidgetText.usedFraction(3.0, 100.0)!!, 1e-9)
        assertNull(WidgetText.usedFraction(3.0, null))
        assertNull(WidgetText.usedFraction(null, 100.0))
        assertNull(WidgetText.usedFraction(3.0, 0.0))
    }

    @Test
    fun `центр кольца это остаток, а без данных прочерк`() {
        assertEquals("38%", WidgetText.ringCenter(0.62))
        assertEquals(WidgetText.DASH, WidgetText.ringCenter(null))
    }

    @Test
    fun `сброс бывает только у квоты`() {
        val now = 1_000_000L
        assertEquals("без сброса", WidgetText.resetText(ResourcePoolKind.BALANCE, now + 3_600_000, now))
        assertEquals("сброс через 3 ч", WidgetText.resetText(ResourcePoolKind.QUOTA, now + 3 * 3_600_000, now))
        assertEquals("сброс наступил", WidgetText.resetText(ResourcePoolKind.QUOTA, now - 1, now))
        assertEquals("сброс неизвестен", WidgetText.resetText(ResourcePoolKind.QUOTA, null, now))
    }

    @Test
    fun `вывод о ресурсах склоняется по числу пулов`() {
        val (main, sub) = WidgetText.resourcesReadout(6, 3, "Codex", ResourcePressure.CRITICAL)
        assertEquals("6 пулов", main)
        assertEquals("3 требуют внимания · критично у Codex", sub)

        val single = WidgetText.resourcesReadout(1, 1, "Codex", ResourcePressure.CONSERVE)
        assertEquals("1 пул", single.first)
        assertEquals("1 требует внимания · экономить у Codex", single.second)
    }

    @Test
    fun `пустой список пулов не выдумывает чисел`() {
        val (main, sub) = WidgetText.resourcesReadout(0, 0, null, null)
        assertEquals(WidgetText.DASH, main)
        assertEquals("снимков ещё не было", sub)
    }

    @Test
    fun `спокойный сценарий называет ближайший к пределу пул`() {
        val (_, sub) = WidgetText.resourcesReadout(4, 0, "Claude", ResourcePressure.FREE)
        assertEquals("все в норме · ближе всех к пределу Claude", sub)
    }

    @Test
    fun `вывод о токенах называет пик и среднее`() {
        val (main, sub) = WidgetText.tokensReadout(
            totalTokens = 160_400,
            days = 14,
            peakDay = null,
            peakTokens = 0,
            average = 11_457,
        )
        assertEquals("160,4K", main)
        assertEquals("за 14 дней · в среднем 11,5K в день", sub)
    }

    @Test
    fun `нулевой расход честно говорит об этом`() {
        val (main, sub) = WidgetText.tokensReadout(0, 7, null, 0, 0)
        assertEquals(WidgetText.DASH, main)
        assertEquals("за 7 дней расхода не было", sub)
    }

    @Test
    fun `вывод о расходе за месяц печатает прогноз и день`() {
        val (main, sub) = WidgetText.spendReadout(12.38, 19.19, 20, 31, isEstimate = true)
        assertEquals("\$12,38", main)
        assertEquals("прогноз \$19,19 · день 20 из 31", sub)
    }

    @Test
    fun `вывод о вызовах склоняется и называет последний`() {
        val at = 1_700_000_000_000L
        val (main, sub) = WidgetText.callsReadout(46, 8, at)
        assertEquals("46 вызовов", main)
        assertEquals(true, sub.endsWith("показаны 8"))
    }

    @Test
    fun `состояние шлюза называется словами приложения`() {
        assertEquals("Работает", WidgetText.gateState(true))
        assertEquals("Остановлен", WidgetText.gateState(false))
        assertEquals("порт 8889", WidgetText.gatePort(true, 8889))
        assertEquals("порт 8889 · не слушает", WidgetText.gatePort(false, 8889))
    }

    @Test
    fun `причина выбора модели одна из трёх формулировок приложения`() {
        assertEquals("выбрана вручную", WidgetText.nextReason(forced = true, isBest = false, hasMeasurements = true))
        assertEquals("быстрейшая по замерам", WidgetText.nextReason(forced = false, isBest = true, hasMeasurements = true))
        assertEquals(
            "первая доступная: замеров ещё нет",
            WidgetText.nextReason(forced = false, isBest = false, hasMeasurements = false),
        )
    }

    @Test
    fun `без снимка футер не показывает время`() {
        assertEquals("снимков ещё не было", WidgetText.updatedFooter(null, "PROVIDER_API", 0L))
    }

    @Test
    fun `потолок оси округляется красивыми шагами`() {
        assertEquals(500.0, WidgetData.niceCeil(473.0), 1e-9)
        assertEquals(60.0, WidgetData.niceCeil(58.0), 1e-9)
        assertEquals(10000.0, WidgetData.niceCeil(8560.0), 1e-9)
        assertEquals(1.0, WidgetData.niceCeil(0.0), 1e-9)
    }
}
