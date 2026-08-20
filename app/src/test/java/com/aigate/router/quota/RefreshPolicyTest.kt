package com.aigate.router.quota

import com.aigate.router.data.model.QuotaSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила частоты обновления квот. Раньше их не было вовсе: воркер ходил раз в
 * шесть часов, а открытие экрана не обновляло ничего. Теперь триггеров много
 * (тик сервиса, воркер, старт приложения, открытие экрана), и без общих правил
 * они либо дублировали бы запросы к провайдеру, либо снова молчали.
 */
class RefreshPolicyTest {

    private val minute = 60_000L
    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    private fun snap(
        used: Double? = 10.0,
        remaining: Double? = 90.0,
        limit: Double? = 100.0,
        unit: String = "USD",
        resetsAt: Long? = now + 24 * hour,
        source: String = "PROVIDER_API",
        updatedAt: Long = now,
        id: Long = 0,
    ) = QuotaSnapshot(
        id = id,
        poolId = 1,
        used = used,
        remaining = remaining,
        limit = limit,
        unit = unit,
        resetsAt = resetsAt,
        updatedAt = updatedAt,
        source = source,
    )

    // ---- isDue: двигатель пятиминутного тика ------------------------------

    @Test
    fun `первое обновление после запуска нужно сразу`() {
        assertTrue(RefreshPolicy.isDue(lastAttemptAt = null, now = now))
    }

    @Test
    fun `обновление раз в пять минут, не чаще`() {
        assertFalse(RefreshPolicy.isDue(now - 4 * minute, now))
        assertTrue(RefreshPolicy.isDue(now - 5 * minute, now))
        assertTrue(RefreshPolicy.isDue(now - 40 * minute, now))
    }

    @Test
    fun `сдвиг часов назад не блокирует обновление навсегда`() {
        // Метка времени из будущего (пользователь перевёл часы) не должна
        // запирать обновления до тех пор, пока реальное время её не догонит.
        assertTrue(RefreshPolicy.isDue(now + 3 * hour, now))
    }

    // ---- shouldFetchRemote: полы частоты по триггерам ---------------------

    @Test
    fun `запуск приложения всегда идёт к провайдеру`() {
        assertTrue(
            RefreshPolicy.shouldFetchRemote(
                trigger = RefreshTrigger.APP_START,
                lastProviderApiAt = now - 10_000,
                lastAttemptAt = now - 10_000,
                now = now,
            )
        )
    }

    @Test
    fun `пятиминутный тик проходит, а сработавший следом воркер уже нет`() {
        assertTrue(
            RefreshPolicy.shouldFetchRemote(
                RefreshTrigger.PERIODIC, null, now - 5 * minute, now
            )
        )
        assertFalse(
            RefreshPolicy.shouldFetchRemote(
                RefreshTrigger.PERIODIC, null, now - minute, now
            )
        )
    }

    @Test
    fun `повторные открытия экрана не долбят провайдера`() {
        assertFalse(
            RefreshPolicy.shouldFetchRemote(
                RefreshTrigger.SCREEN_OPEN, null, now - minute, now
            )
        )
        assertTrue(
            RefreshPolicy.shouldFetchRemote(
                RefreshTrigger.SCREEN_OPEN, null, now - 3 * minute, now
            )
        )
    }

    @Test
    fun `действие пользователя обновляет почти сразу`() {
        assertTrue(
            RefreshPolicy.shouldFetchRemote(
                RefreshTrigger.USER_ACTION, null, now - 31_000, now
            )
        )
        assertFalse(
            RefreshPolicy.shouldFetchRemote(
                RefreshTrigger.USER_ACTION, null, now - 5_000, now
            )
        )
    }

    @Test
    fun `сломанный провайдер повторяется по расписанию, а не на каждом открытии`() {
        // Успеха не было ни разу (lastProviderApiAt = null), поэтому гейт идёт
        // по попытке: иначе безнадёжный провайдер получал бы запрос при каждом
        // открытии экрана.
        assertFalse(
            RefreshPolicy.shouldFetchRemote(
                RefreshTrigger.SCREEN_OPEN, lastProviderApiAt = null,
                lastAttemptAt = now - 30_000, now = now,
            )
        )
        assertTrue(
            RefreshPolicy.shouldFetchRemote(
                RefreshTrigger.SCREEN_OPEN, lastProviderApiAt = null,
                lastAttemptAt = now - 3 * minute, now = now,
            )
        )
    }

    @Test
    fun `после пересоздания процесса помнит о свежих данных провайдера`() {
        // Память о попытках живёт в процессе, а метка удачного ответа — в базе.
        // Без учёта базы каждое пересоздание процесса начинало бы опрос заново.
        assertFalse(
            RefreshPolicy.shouldFetchRemote(
                RefreshTrigger.PERIODIC, lastProviderApiAt = now - minute,
                lastAttemptAt = null, now = now,
            )
        )
    }

    // ---- shouldWriteLocalFallback: честность источника --------------------

    @Test
    fun `без адаптера локальный подсчёт остаётся единственным честным источником`() {
        assertTrue(
            RefreshPolicy.shouldWriteLocalFallback(
                hasAdapter = false, lastProviderApiAt = null, now = now
            )
        )
    }

    @Test
    fun `неудачный запрос не затирает свежие данные провайдера локальной оценкой`() {
        // Раньше сюда писался свежий LOCAL_USAGE, и пользователь видел
        // «обновлено только что» там, где данных провайдера не было.
        assertFalse(
            RefreshPolicy.shouldWriteLocalFallback(
                hasAdapter = true, lastProviderApiAt = now - hour, now = now
            )
        )
    }

    @Test
    fun `давно умерший провайдер уступает место локальному подсчёту`() {
        assertTrue(
            RefreshPolicy.shouldWriteLocalFallback(
                hasAdapter = true, lastProviderApiAt = now - 25 * hour, now = now
            )
        )
        assertTrue(
            RefreshPolicy.shouldWriteLocalFallback(
                hasAdapter = true, lastProviderApiAt = null, now = now
            )
        )
    }

    // ---- sameReading: дедуп вставок ---------------------------------------

    @Test
    fun `неизменившееся показание не порождает новую строку`() {
        // При пятиминутном опросе слепые вставки дали бы примерно 288 строк на
        // пул в сутки вместо четырёх.
        assertTrue(
            RefreshPolicy.sameReading(
                snap(id = 7, updatedAt = now - 5 * minute),
                snap(id = 0, updatedAt = now),
            )
        )
    }

    @Test
    fun `любое изменение показания сохраняется как новая точка`() {
        val previous = snap()
        assertFalse(RefreshPolicy.sameReading(previous, snap(used = 11.0)))
        assertFalse(RefreshPolicy.sameReading(previous, snap(remaining = 89.0)))
        assertFalse(RefreshPolicy.sameReading(previous, snap(limit = 200.0)))
        assertFalse(RefreshPolicy.sameReading(previous, snap(unit = "PERCENT")))
        assertFalse(RefreshPolicy.sameReading(previous, snap(resetsAt = now + 48 * hour)))
        assertFalse(RefreshPolicy.sameReading(previous, snap(source = "LOCAL_USAGE")))
    }

    @Test
    fun `первое показание пула всегда вставляется`() {
        assertFalse(RefreshPolicy.sameReading(null, snap()))
    }

    @Test
    fun `неизвестные значения тоже сравниваются`() {
        val unknown = snap(used = null, remaining = null, limit = null)
        assertTrue(RefreshPolicy.sameReading(unknown, unknown.copy(updatedAt = now + minute)))
        assertFalse(RefreshPolicy.sameReading(unknown, snap(used = 0.0, remaining = null, limit = null)))
    }
}
