# Главный экран «Обзор»: план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Перестроить «Обзор» под вопросы владельца (хватит ли квоты, что сгорит, что требует реакции), заменив фиксированные пороги расчётом из собственной истории расхода.

**Architecture:** Вычисления живут в чистых объектах без Android-зависимостей (`quota/QuotaBurn.kt`, `notify/QuotaTriggers.kt`, `usage/LocalSavings.kt`, `net/LocalAddress.kt`) — они покрываются JVM-тестами. Compose-слой только отображает готовые значения. Настройки уведомлений на каждый пул хранятся в конфиге (`GatewayForegroundService.getGatewayConfig/saveGatewayConfig`), схема Room не меняется: в базе включён `fallbackToDestructiveMigration()`, миграция стёрла бы данные.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Room, JUnit4, `com.google.zxing:core` (новая зависимость для QR).

## Global Constraints

- Ни одного эмодзи в исходниках UI и в строках, видимых пользователю.
- Никаких `Color(0x…)` и `.copy(alpha = …)` в экранах: только токены `Gateway.colors` и `MaterialTheme.colorScheme`.
- Никаких инструкций и подсказок на экранах: справка только в `HelpSheet` по кнопке «?».
- Никаких выдуманных чисел: нет данных — показываем прочерк или молчим.
- Имя приложения в строках всегда `AiGate`, без перевода.
- Терминология: `ResourcePoolKind.QUOTA` — «квота», `BALANCE` — «баланс», `FREE` — «бесплатно», `BUDGET` — «бюджет». Слово «квота» к балансу и бесплатным ресурсам не применяется.
- Сборка: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --no-daemon`.
- Коммит после каждой задачи; ветка `main`, пуш не делаем.

---

### Task 1: Адрес в сети берётся с правильного интерфейса

**Files:**
- Create: `app/src/main/java/com/aigate/router/net/LocalAddress.kt`
- Create: `app/src/test/java/com/aigate/router/net/LocalAddressTest.kt`
- Modify: `app/src/main/java/com/aigate/router/ui/util/Util.kt` (функция `localIpAddress`)

**Interfaces:**
- Consumes: ничего.
- Produces: `LocalAddress.pick(candidates: List<LocalAddress.Iface>): String?`, `data class LocalAddress.Iface(val name: String, val addresses: List<String>, val isUp: Boolean, val isLoopback: Boolean, val isPointToPoint: Boolean)`.

- [ ] **Step 1: Написать падающий тест**

```kotlin
package com.aigate.router.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAddressTest {
    private fun iface(
        name: String,
        addr: String?,
        up: Boolean = true,
        loopback: Boolean = false,
        p2p: Boolean = false,
    ) = LocalAddress.Iface(name, listOfNotNull(addr), up, loopback, p2p)

    @Test
    fun `wifi wins over mobile and vpn`() {
        val picked = LocalAddress.pick(
            listOf(
                iface("rmnet_data0", "10.204.68.22"),
                iface("tun0", "10.8.0.2", p2p = true),
                iface("wlan0", "192.168.1.42"),
            )
        )
        assertEquals("192.168.1.42", picked)
    }

    @Test
    fun `loopback and down interfaces are ignored`() {
        assertNull(
            LocalAddress.pick(
                listOf(
                    iface("lo", "127.0.0.1", loopback = true),
                    iface("wlan0", "192.168.1.42", up = false),
                )
            )
        )
    }

    @Test
    fun `ipv6 addresses are not used for the gateway url`() {
        assertNull(LocalAddress.pick(listOf(iface("wlan0", "fe80::1"))))
    }

    @Test
    fun `ethernet is used when there is no wifi`() {
        assertEquals(
            "192.168.5.7",
            LocalAddress.pick(listOf(iface("rmnet_data0", "10.1.1.1"), iface("eth0", "192.168.5.7")))
        )
    }

    @Test
    fun `mobile address is a last resort rather than nothing`() {
        assertEquals("10.204.68.22", LocalAddress.pick(listOf(iface("rmnet_data0", "10.204.68.22"))))
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests "com.aigate.router.net.LocalAddressTest" --no-daemon`
Expected: FAIL — `Unresolved reference 'LocalAddress'`.

- [ ] **Step 3: Реализовать выбор интерфейса**

```kotlin
package com.aigate.router.net

import java.net.NetworkInterface

/**
 * Адрес шлюза в локальной сети. Раньше брался первый не-loopback адрес любого
 * интерфейса, из-за чего в URL попадал адрес мобильной сети или VPN-туннеля,
 * недостижимый для других устройств.
 */
object LocalAddress {

    data class Iface(
        val name: String,
        val addresses: List<String>,
        val isUp: Boolean,
        val isLoopback: Boolean,
        val isPointToPoint: Boolean,
    )

    /** Приоритет: Wi-Fi, затем Ethernet, затем всё остальное. */
    private fun rank(name: String): Int = when {
        name.startsWith("wlan") || name.startsWith("ap") -> 0
        name.startsWith("eth") || name.startsWith("en") -> 1
        else -> 2
    }

    fun pick(candidates: List<Iface>): String? = candidates
        .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
        .sortedBy { rank(it.name) }
        .firstNotNullOfOrNull { i -> i.addresses.firstOrNull { isIpv4(it) } }

    private fun isIpv4(address: String): Boolean =
        address.count { it == '.' } == 3 && !address.contains(':')

    /** Реальные интерфейсы устройства. */
    fun current(): String? = runCatching {
        pick(
            NetworkInterface.getNetworkInterfaces().asSequence().map { ni ->
                Iface(
                    name = ni.name.orEmpty(),
                    addresses = ni.inetAddresses.asSequence().mapNotNull { it.hostAddress }.toList(),
                    isUp = ni.isUp,
                    isLoopback = ni.isLoopback,
                    isPointToPoint = ni.isPointToPoint,
                )
            }.toList()
        )
    }.getOrNull()
}
```

- [ ] **Step 4: Переключить UI на новый выбор**

В `app/src/main/java/com/aigate/router/ui/util/Util.kt` заменить тело функции:

```kotlin
/** Адрес устройства в локальной сети; null — сети нет. */
fun localIpAddress(): String? = com.aigate.router.net.LocalAddress.current()
```

В `OverviewScreen.kt` строка адреса при `null` показывает «нет сети» вместо URL — найти использование `localIpAddress()` и обернуть:

```kotlin
val lanUrl = lanIp?.let { "http://$it:$port" }
// …
if (lanUrl == null) {
    Text("нет сети", style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
} else {
    CopyableRow(label = "Адрес в сети", value = lanUrl, onCopy = onCopy)
}
```

- [ ] **Step 5: Тесты и проверка на устройстве**

Run: `JAVA_HOME=… ./gradlew :app:testDebugUnitTest --tests "com.aigate.router.net.LocalAddressTest" --no-daemon`
Expected: PASS (5 тестов).

Run: `adb shell ip route get 1.1.1.1` — запомнить `src`-адрес; установить сборку и сверить его со строкой «Адрес в сети» на экране.

- [ ] **Step 6: Коммит**

```bash
git add app/src/main/java/com/aigate/router/net/LocalAddress.kt app/src/test/java/com/aigate/router/net/LocalAddressTest.kt app/src/main/java/com/aigate/router/ui/util/Util.kt app/src/main/java/com/aigate/router/ui/screens/OverviewScreen.kt
git commit -m "fix: адрес шлюза берётся с интерфейса локальной сети, а не первого доступного"
```

---

### Task 2: Кнопка запуска outline, скорость моделей уходит с главной

**Files:**
- Modify: `app/src/main/java/com/aigate/router/ui/screens/OverviewScreen.kt`

**Interfaces:**
- Consumes: `LocalAddress` из задачи 1.
- Produces: ничего (внутренняя правка экрана). Функция `SpeedSummaryCard` удаляется целиком.

- [ ] **Step 1: Заменить кнопку на OutlinedButton**

В `GatewayStatusCard` заменить `Button(...)` на:

```kotlin
OutlinedButton(
    onClick = onToggle,
    modifier = Modifier.fillMaxWidth().height(56.dp),
) {
    Icon(
        imageVector = if (running) Icons.Default.Stop else Icons.Default.PlayArrow,
        contentDescription = null,
    )
    Spacer(Modifier.width(Gateway.spacing.sm))
    Text(
        text = if (running) "Остановить шлюз" else "Запустить шлюз",
        style = MaterialTheme.typography.titleMedium,
    )
}
```

- [ ] **Step 2: Удалить блок скорости моделей**

Убрать из `OverviewScreen`: вызов `SectionHeader("Скорость моделей")`, вызов `SpeedSummaryCard(...)`, саму функцию `SpeedSummaryCard`, а также ставшие лишними импорты (`SpeedHistory`, `Icons.Outlined.Bolt`, `latestSpeedHistory`).

- [ ] **Step 3: Проверить, что ничего не отвалилось**

Run: `JAVA_HOME=… ./gradlew :app:compileDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL, без предупреждений о неиспользуемых импортах в `OverviewScreen.kt`.

- [ ] **Step 4: Коммит**

```bash
git add app/src/main/java/com/aigate/router/ui/screens/OverviewScreen.kt
git commit -m "refactor(обзор): кнопка запуска outline, скорость моделей убрана с главной"
```

---

### Task 3: Расчёт темпа расхода квоты

**Files:**
- Create: `app/src/main/java/com/aigate/router/quota/QuotaBurn.kt`
- Create: `app/src/test/java/com/aigate/router/quota/QuotaBurnTest.kt`

**Interfaces:**
- Consumes: `com.aigate.router.data.model.QuotaSnapshot` (поля `used`, `remaining`, `limit`, `resetsAt`, `updatedAt`).
- Produces:
  - `data class QuotaBurn.Rate(val perHour: Double, val peakPerHour: Double)`
  - `QuotaBurn.rate(history: List<QuotaSnapshot>, now: Long): Rate?`
  - `data class QuotaBurn.Outlook(val exhaustAtMs: Long?, val surplus: Double, val hoursToReset: Double, val hoursNeededAtPeak: Double)`
  - `QuotaBurn.outlook(remaining: Double, resetsAt: Long, rate: Rate, now: Long): Outlook?`

- [ ] **Step 1: Написать падающий тест**

```kotlin
package com.aigate.router.quota

import com.aigate.router.data.model.QuotaSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaBurnTest {

    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    private fun snap(usedValue: Double, agoHours: Long) = QuotaSnapshot(
        poolId = 1,
        used = usedValue,
        remaining = 100.0 - usedValue,
        limit = 100.0,
        unit = "PERCENT",
        resetsAt = now + 24 * hour,
        updatedAt = now - agoHours * hour,
        source = "PROVIDER_API",
    )

    @Test
    fun `rate is computed from the last 24 hours of snapshots`() {
        // За 24 часа израсходовано 48 единиц → 2 единицы в час.
        val rate = QuotaBurn.rate(listOf(snap(10.0, 24), snap(58.0, 0)), now)
        assertNotNull(rate)
        assertEquals(2.0, rate!!.perHour, 0.01)
    }

    @Test
    fun `peak rate uses the busiest day of the month`() {
        val history = listOf(
            snap(0.0, 72), snap(24.0, 48),   // 1 ед/ч
            snap(24.0, 47), snap(96.0, 24),  // 3 ед/ч — пик
            snap(96.0, 23), snap(120.0, 0),  // 1 ед/ч
        )
        val rate = QuotaBurn.rate(history, now)!!
        assertTrue("пик должен быть не ниже среднего", rate.peakPerHour >= rate.perHour)
        assertEquals(3.0, rate.peakPerHour, 0.2)
    }

    @Test
    fun `no history means no rate and no guessing`() {
        assertNull(QuotaBurn.rate(emptyList(), now))
        assertNull(QuotaBurn.rate(listOf(snap(10.0, 0)), now))
    }

    @Test
    fun `zero consumption yields no rate`() {
        assertNull(QuotaBurn.rate(listOf(snap(10.0, 24), snap(10.0, 0)), now))
    }

    @Test
    fun `quota running out before reset reports the exhaustion moment`() {
        // Осталось 10 единиц, темп 2 ед/ч → 5 часов, сброс через 24 часа.
        val outlook = QuotaBurn.outlook(
            remaining = 10.0,
            resetsAt = now + 24 * hour,
            rate = QuotaBurn.Rate(perHour = 2.0, peakPerHour = 4.0),
            now = now,
        )!!
        assertEquals(now + 5 * hour, outlook.exhaustAtMs!!, hour / 2)
        assertEquals(0.0, outlook.surplus, 0.001)
    }

    @Test
    fun `unused quota is reported as surplus instead of exhaustion`() {
        // Осталось 100, темп 1 ед/ч, сброс через 24 ч → израсходуется 24, сгорит 76.
        val outlook = QuotaBurn.outlook(
            remaining = 100.0,
            resetsAt = now + 24 * hour,
            rate = QuotaBurn.Rate(perHour = 1.0, peakPerHour = 10.0),
            now = now,
        )!!
        assertNull(outlook.exhaustAtMs)
        assertEquals(76.0, outlook.surplus, 0.001)
        assertEquals(7.6, outlook.hoursNeededAtPeak, 0.1)
    }

    @Test
    fun `reset in the past yields no outlook`() {
        assertNull(
            QuotaBurn.outlook(
                remaining = 50.0,
                resetsAt = now - hour,
                rate = QuotaBurn.Rate(1.0, 1.0),
                now = now,
            )
        )
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `JAVA_HOME=… ./gradlew :app:testDebugUnitTest --tests "com.aigate.router.quota.QuotaBurnTest" --no-daemon`
Expected: FAIL — `Unresolved reference 'QuotaBurn'`.

- [ ] **Step 3: Реализовать расчёт**

```kotlin
package com.aigate.router.quota

import com.aigate.router.data.model.QuotaSnapshot

/**
 * Темп расхода квоты и его последствия. Фиксированные доли остатка здесь
 * непригодны: 29 % у одного тарифа — сутки работы, у другого — месяц, поэтому
 * всё считается из собственной истории снимков.
 */
object QuotaBurn {

    private const val HOUR_MS = 3_600_000.0
    private const val RATE_WINDOW_HOURS = 24.0
    private const val PEAK_WINDOW_DAYS = 30

    /** Единиц квоты в час: средний темп за сутки и пиковый суточный за месяц. */
    data class Rate(val perHour: Double, val peakPerHour: Double)

    /**
     * @param exhaustAtMs момент исчерпания, если квота кончится раньше сброса
     * @param surplus сколько сгорит неиспользованным при нынешнем темпе
     * @param hoursNeededAtPeak сколько часов работы на пиковом темпе нужно, чтобы израсходовать surplus
     */
    data class Outlook(
        val exhaustAtMs: Long?,
        val surplus: Double,
        val hoursToReset: Double,
        val hoursNeededAtPeak: Double,
    )

    fun rate(history: List<QuotaSnapshot>, now: Long): Rate? {
        val ordered = history.filter { it.used != null }.sortedBy { it.updatedAt }
        if (ordered.size < 2) return null

        val windowStart = now - (RATE_WINDOW_HOURS * HOUR_MS).toLong()
        val window = ordered.filter { it.updatedAt >= windowStart }.ifEmpty { ordered.takeLast(2) }
        val avg = slope(window) ?: return null

        val peakStart = now - PEAK_WINDOW_DAYS * 24L * HOUR_MS.toLong()
        val byDay = ordered.filter { it.updatedAt >= peakStart }.groupBy { it.updatedAt / 86_400_000L }
        val peak = byDay.values.mapNotNull { slope(it) }.maxOrNull() ?: avg
        if (avg <= 0.0) return null
        return Rate(perHour = avg, peakPerHour = maxOf(peak, avg))
    }

    /** Прирост израсходованного, делённый на время; сброс квоты (used упал) отсекается. */
    private fun slope(points: List<QuotaSnapshot>): Double? {
        if (points.size < 2) return null
        val first = points.first()
        val last = points.last()
        val hours = (last.updatedAt - first.updatedAt) / HOUR_MS
        if (hours <= 0.0) return null
        val delta = (last.used ?: return null) - (first.used ?: return null)
        if (delta <= 0.0) return null
        return delta / hours
    }

    fun outlook(remaining: Double, resetsAt: Long, rate: Rate, now: Long): Outlook? {
        val hoursToReset = (resetsAt - now) / HOUR_MS
        if (hoursToReset <= 0.0 || rate.perHour <= 0.0) return null
        val projected = rate.perHour * hoursToReset
        return if (projected >= remaining) {
            val hoursLeft = remaining / rate.perHour
            Outlook(
                exhaustAtMs = now + (hoursLeft * HOUR_MS).toLong(),
                surplus = 0.0,
                hoursToReset = hoursToReset,
                hoursNeededAtPeak = 0.0,
            )
        } else {
            val surplus = remaining - projected
            Outlook(
                exhaustAtMs = null,
                surplus = surplus,
                hoursToReset = hoursToReset,
                hoursNeededAtPeak = surplus / rate.peakPerHour,
            )
        }
    }
}
```

- [ ] **Step 4: Тесты**

Run: `JAVA_HOME=… ./gradlew :app:testDebugUnitTest --tests "com.aigate.router.quota.QuotaBurnTest" --no-daemon`
Expected: PASS (7 тестов).

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/java/com/aigate/router/quota/QuotaBurn.kt app/src/test/java/com/aigate/router/quota/QuotaBurnTest.kt
git commit -m "feat(quota): расчёт темпа расхода, момента исчерпания и сгорающего остатка"
```

---

### Task 4: Триггеры уведомлений на каждый ресурс

**Files:**
- Create: `app/src/main/java/com/aigate/router/notify/QuotaTriggers.kt`
- Create: `app/src/main/java/com/aigate/router/notify/NotifyPrefs.kt`
- Create: `app/src/test/java/com/aigate/router/notify/QuotaTriggersTest.kt`
- Modify: `app/src/main/java/com/aigate/router/notify/QuotaNotifier.kt`

**Interfaces:**
- Consumes: `QuotaBurn.Rate`, `QuotaBurn.Outlook` (задача 3), `ResourcePoolKind` (`QUOTA`/`BALANCE`/`FREE`/`BUDGET`).
- Produces:
  - `data class NotifyPrefs.Settings(val lowQuotaEnabled: Boolean, val lowQuotaFraction: Double, val exhaustBeforeResetEnabled: Boolean, val surplusEnabled: Boolean, val surplusDays: Double, val resetEnabled: Boolean, val lowBalanceEnabled: Boolean, val lowBalanceUsd: Double)`
  - `NotifyPrefs.defaultsFor(kind: ResourcePoolKind): Settings`, `NotifyPrefs.load(poolId: Long, kind: ResourcePoolKind): Settings`, `NotifyPrefs.save(poolId: Long, settings: Settings)`
  - `enum class QuotaTriggers.Kind { LOW_QUOTA, EXHAUST_BEFORE_RESET, SURPLUS, RESET, LOW_BALANCE }`
  - `data class QuotaTriggers.Alert(val kind: Kind, val title: String, val body: String)`
  - `QuotaTriggers.evaluate(input: Input): List<Alert>` где `data class Input(val poolName: String, val kind: ResourcePoolKind, val remaining: Double?, val limit: Double?, val unit: String, val resetsAt: Long?, val rate: QuotaBurn.Rate?, val settings: NotifyPrefs.Settings, val now: Long, val resetSeenAt: Long?)`

- [ ] **Step 1: Написать падающий тест**

```kotlin
package com.aigate.router.notify

import com.aigate.router.quota.QuotaBurn
import com.aigate.router.quota.ResourcePoolKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaTriggersTest {

    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    private fun input(
        kind: ResourcePoolKind = ResourcePoolKind.QUOTA,
        remaining: Double? = 100.0,
        limit: Double? = 100.0,
        resetsAt: Long? = now + 24 * hour,
        rate: QuotaBurn.Rate? = QuotaBurn.Rate(1.0, 10.0),
        settings: NotifyPrefs.Settings = NotifyPrefs.defaultsFor(kind),
        resetSeenAt: Long? = null,
    ) = QuotaTriggers.Input(
        poolName = "Codex",
        kind = kind,
        remaining = remaining,
        limit = limit,
        unit = "PERCENT",
        resetsAt = resetsAt,
        rate = rate,
        settings = settings,
        now = now,
        resetSeenAt = resetSeenAt,
    )

    @Test
    fun `low quota fires below the configured fraction`() {
        val alerts = QuotaTriggers.evaluate(input(remaining = 10.0, rate = null))
        assertEquals(listOf(QuotaTriggers.Kind.LOW_QUOTA), alerts.map { it.kind })
    }

    @Test
    fun `exhaustion before reset is reported with the moment`() {
        // Осталось 10, темп 2 ед/ч → 5 часов, сброс через 24 часа.
        val alerts = QuotaTriggers.evaluate(
            input(remaining = 10.0, rate = QuotaBurn.Rate(2.0, 4.0))
        )
        assertTrue(alerts.any { it.kind == QuotaTriggers.Kind.EXHAUST_BEFORE_RESET })
    }

    @Test
    fun `surplus fires only when the loss is worth a day of usage`() {
        // Темп 1 ед/ч: за сутки уходит 24. Сгорит 76 — больше суток, окно впритык.
        val big = QuotaTriggers.evaluate(
            input(remaining = 100.0, rate = QuotaBurn.Rate(1.0, 10.0), resetsAt = now + 24 * hour)
        )
        assertTrue(big.any { it.kind == QuotaTriggers.Kind.SURPLUS })

        // Сгорит всего 2 единицы — меньше суточного расхода, молчим.
        val small = QuotaTriggers.evaluate(
            input(remaining = 26.0, rate = QuotaBurn.Rate(1.0, 10.0), resetsAt = now + 24 * hour)
        )
        assertTrue(small.none { it.kind == QuotaTriggers.Kind.SURPLUS })
    }

    @Test
    fun `surplus stays silent while the reset is still far away`() {
        // Сгорит много, но до сброса 30 суток: действовать пока рано.
        val alerts = QuotaTriggers.evaluate(
            input(remaining = 100.0, rate = QuotaBurn.Rate(0.01, 0.02), resetsAt = now + 720 * hour)
        )
        assertTrue(alerts.none { it.kind == QuotaTriggers.Kind.SURPLUS })
    }

    @Test
    fun `fresh reset is announced once`() {
        val alerts = QuotaTriggers.evaluate(
            input(remaining = 100.0, resetsAt = now + 720 * hour, rate = null, resetSeenAt = null)
        )
        assertTrue(alerts.any { it.kind == QuotaTriggers.Kind.RESET })
    }

    @Test
    fun `balance pool only reports low balance`() {
        val alerts = QuotaTriggers.evaluate(
            input(kind = ResourcePoolKind.BALANCE, remaining = 3.0, limit = null, resetsAt = null, rate = null)
        )
        assertEquals(listOf(QuotaTriggers.Kind.LOW_BALANCE), alerts.map { it.kind })
    }

    @Test
    fun `free pool never notifies`() {
        val alerts = QuotaTriggers.evaluate(
            input(kind = ResourcePoolKind.FREE, remaining = null, limit = null, resetsAt = null, rate = null)
        )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `disabled triggers stay silent`() {
        val off = NotifyPrefs.defaultsFor(ResourcePoolKind.QUOTA).copy(
            lowQuotaEnabled = false,
            exhaustBeforeResetEnabled = false,
            surplusEnabled = false,
            resetEnabled = false,
        )
        assertTrue(QuotaTriggers.evaluate(input(remaining = 1.0, settings = off)).isEmpty())
    }

    @Test
    fun `missing data produces no alerts`() {
        assertTrue(QuotaTriggers.evaluate(input(remaining = null, limit = null)).isEmpty())
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `JAVA_HOME=… ./gradlew :app:testDebugUnitTest --tests "com.aigate.router.notify.QuotaTriggersTest" --no-daemon`
Expected: FAIL — `Unresolved reference 'QuotaTriggers'`.

- [ ] **Step 3: Реализовать настройки**

```kotlin
package com.aigate.router.notify

import com.aigate.router.quota.ResourcePoolKind
import com.aigate.router.service.GatewayForegroundService

/**
 * Настройки уведомлений на каждый пул. Хранятся в конфиге, а не в БД: схема
 * Room собрана с destructive fallback, и добавление колонок стёрло бы данные.
 */
object NotifyPrefs {

    data class Settings(
        val lowQuotaEnabled: Boolean,
        /** Доля остатка (0..1), ниже которой уведомляем. */
        val lowQuotaFraction: Double,
        val exhaustBeforeResetEnabled: Boolean,
        val surplusEnabled: Boolean,
        /** Сколько суток обычного расхода должно сгореть, чтобы сообщить. */
        val surplusDays: Double,
        val resetEnabled: Boolean,
        val lowBalanceEnabled: Boolean,
        val lowBalanceUsd: Double,
    )

    fun defaultsFor(kind: ResourcePoolKind): Settings = when (kind) {
        ResourcePoolKind.QUOTA, ResourcePoolKind.BUDGET -> Settings(
            lowQuotaEnabled = true,
            lowQuotaFraction = 0.15,
            exhaustBeforeResetEnabled = true,
            surplusEnabled = true,
            surplusDays = 1.0,
            resetEnabled = true,
            lowBalanceEnabled = false,
            lowBalanceUsd = 5.0,
        )
        ResourcePoolKind.BALANCE -> Settings(
            lowQuotaEnabled = false,
            lowQuotaFraction = 0.15,
            exhaustBeforeResetEnabled = false,
            surplusEnabled = false,
            surplusDays = 1.0,
            resetEnabled = false,
            lowBalanceEnabled = true,
            lowBalanceUsd = 5.0,
        )
        // Бесплатный ресурс: уведомлять не о чем.
        ResourcePoolKind.FREE -> Settings(
            lowQuotaEnabled = false,
            lowQuotaFraction = 0.15,
            exhaustBeforeResetEnabled = false,
            surplusEnabled = false,
            surplusDays = 1.0,
            resetEnabled = false,
            lowBalanceEnabled = false,
            lowBalanceUsd = 5.0,
        )
    }

    private fun key(poolId: Long, name: String) = "notify_${poolId}_$name"

    private fun flag(poolId: Long, name: String, fallback: Boolean): Boolean =
        when (GatewayForegroundService.getGatewayConfig(key(poolId, name), "")) {
            "true" -> true
            "false" -> false
            else -> fallback
        }

    private fun number(poolId: Long, name: String, fallback: Double): Double =
        GatewayForegroundService.getGatewayConfig(key(poolId, name), "").toDoubleOrNull() ?: fallback

    fun load(poolId: Long, kind: ResourcePoolKind): Settings {
        val d = defaultsFor(kind)
        return d.copy(
            lowQuotaEnabled = flag(poolId, "low", d.lowQuotaEnabled),
            lowQuotaFraction = number(poolId, "low_fraction", d.lowQuotaFraction),
            exhaustBeforeResetEnabled = flag(poolId, "exhaust", d.exhaustBeforeResetEnabled),
            surplusEnabled = flag(poolId, "surplus", d.surplusEnabled),
            surplusDays = number(poolId, "surplus_days", d.surplusDays),
            resetEnabled = flag(poolId, "reset", d.resetEnabled),
            lowBalanceEnabled = flag(poolId, "balance", d.lowBalanceEnabled),
            lowBalanceUsd = number(poolId, "balance_usd", d.lowBalanceUsd),
        )
    }

    fun save(poolId: Long, settings: Settings) {
        fun put(name: String, value: String) =
            GatewayForegroundService.saveGatewayConfig(key(poolId, name), value)
        put("low", settings.lowQuotaEnabled.toString())
        put("low_fraction", settings.lowQuotaFraction.toString())
        put("exhaust", settings.exhaustBeforeResetEnabled.toString())
        put("surplus", settings.surplusEnabled.toString())
        put("surplus_days", settings.surplusDays.toString())
        put("reset", settings.resetEnabled.toString())
        put("balance", settings.lowBalanceEnabled.toString())
        put("balance_usd", settings.lowBalanceUsd.toString())
    }

    /** Отметка об отправленном уведомлении: одно на цикл. */
    fun sentAt(poolId: Long, trigger: String): Long? =
        GatewayForegroundService.getGatewayConfig(key(poolId, "sent_$trigger"), "").toLongOrNull()

    fun markSent(poolId: Long, trigger: String, at: Long) {
        GatewayForegroundService.saveGatewayConfig(key(poolId, "sent_$trigger"), at.toString())
    }

    fun clearSent(poolId: Long) {
        listOf("low", "exhaust", "surplus", "reset", "balance").forEach {
            GatewayForegroundService.saveGatewayConfig(key(poolId, "sent_$it"), "")
        }
    }
}
```

- [ ] **Step 4: Реализовать вычисление триггеров**

```kotlin
package com.aigate.router.notify

import com.aigate.router.quota.QuotaBurn
import com.aigate.router.quota.ResourcePoolKind

/**
 * Какие уведомления заслужены текущим состоянием ресурса. Чистая функция:
 * ни Android, ни базы — только данные, поэтому поведение проверяется тестами.
 */
object QuotaTriggers {

    enum class Kind { LOW_QUOTA, EXHAUST_BEFORE_RESET, SURPLUS, RESET, LOW_BALANCE }

    data class Alert(val kind: Kind, val title: String, val body: String)

    data class Input(
        val poolName: String,
        val kind: ResourcePoolKind,
        val remaining: Double?,
        val limit: Double?,
        val unit: String,
        val resetsAt: Long?,
        val rate: QuotaBurn.Rate?,
        val settings: NotifyPrefs.Settings,
        val now: Long,
        /** Когда о нынешнем сбросе уже сообщали; null — ещё не сообщали. */
        val resetSeenAt: Long?,
    )

    fun evaluate(input: Input): List<Alert> {
        if (input.kind == ResourcePoolKind.FREE) return emptyList()
        val out = mutableListOf<Alert>()

        if (input.kind == ResourcePoolKind.BALANCE) {
            val remaining = input.remaining ?: return emptyList()
            if (input.settings.lowBalanceEnabled && remaining < input.settings.lowBalanceUsd) {
                out += Alert(
                    kind = Kind.LOW_BALANCE,
                    title = "Баланс на исходе",
                    body = "${input.poolName}: на счету ${money(remaining)}",
                )
            }
            return out
        }

        val remaining = input.remaining ?: return emptyList()
        val limit = input.limit ?: return emptyList()
        if (limit <= 0.0) return emptyList()

        val fraction = remaining / limit
        if (input.settings.lowQuotaEnabled && fraction < input.settings.lowQuotaFraction) {
            out += Alert(
                kind = Kind.LOW_QUOTA,
                title = "Квота на исходе",
                body = "${input.poolName}: осталось ${percent(fraction)}",
            )
        }

        val resetsAt = input.resetsAt
        val rate = input.rate
        if (resetsAt != null && rate != null) {
            val outlook = QuotaBurn.outlook(remaining, resetsAt, rate, input.now)
            if (outlook != null) {
                val exhaustAt = outlook.exhaustAtMs
                if (exhaustAt != null && input.settings.exhaustBeforeResetEnabled) {
                    val hoursEarlier = (resetsAt - exhaustAt) / 3_600_000.0
                    out += Alert(
                        kind = Kind.EXHAUST_BEFORE_RESET,
                        title = "Квота кончится раньше сброса",
                        body = "${input.poolName}: при нынешнем темпе — на " +
                            "${hours(hoursEarlier)} раньше сброса",
                    )
                }
                if (exhaustAt == null && input.settings.surplusEnabled) {
                    val dailyUsage = rate.perHour * 24.0
                    val worthTelling = outlook.surplus >= dailyUsage * input.settings.surplusDays
                    val actionable = outlook.hoursToReset <= 2.0 * outlook.hoursNeededAtPeak
                    if (worthTelling && actionable) {
                        out += Alert(
                            kind = Kind.SURPLUS,
                            title = "Квота сгорит неиспользованной",
                            body = "${input.poolName}: сгорит ${amount(outlook.surplus, input.unit)}; " +
                                "нужно ${hours(outlook.hoursNeededAtPeak)} работы, " +
                                "осталось ${hours(outlook.hoursToReset)}",
                        )
                    }
                }
            }
        }

        if (input.settings.resetEnabled && input.resetSeenAt == null && fraction > 0.99) {
            out += Alert(
                kind = Kind.RESET,
                title = "Квота обновилась",
                body = "${input.poolName}: доступно ${percent(fraction)}",
            )
        }
        return out
    }

    private fun percent(fraction: Double) = "${Math.round(fraction * 100)}%"
    private fun money(usd: Double) = "$" + String.format("%.2f", usd)
    private fun hours(h: Double) = if (h >= 24) "${Math.round(h / 24)} дн" else "${Math.round(h)} ч"
    private fun amount(value: Double, unit: String) =
        if (unit.equals("PERCENT", true)) "${Math.round(value)}%" else Math.round(value).toString()
}
```

- [ ] **Step 5: Тесты**

Run: `JAVA_HOME=… ./gradlew :app:testDebugUnitTest --tests "com.aigate.router.notify.QuotaTriggersTest" --no-daemon`
Expected: PASS (9 тестов).

- [ ] **Step 6: Подключить к QuotaNotifier**

В `QuotaNotifier.checkAndNotify` заменить фильтр по `ResourcePressure` на перебор пулов через `QuotaTriggers.evaluate`: для каждого пула читать историю (`db.quotaSnapshotDao().getHistoryForPool(pool.id)`), считать `QuotaBurn.rate`, собрать алерты, отсеять уже отправленные через `NotifyPrefs.sentAt`, отправить уведомление по одному на пул и вызвать `NotifyPrefs.markSent`. При обнаружении сброса (остаток вырос) — `NotifyPrefs.clearSent(pool.id)`. Глобальные `KEY_ENABLED`/`KEY_THRESHOLD` остаются как главный выключатель.

- [ ] **Step 7: Проверка сборки и коммит**

Run: `JAVA_HOME=… ./gradlew :app:testDebugUnitTest --no-daemon`
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/java/com/aigate/router/notify app/src/test/java/com/aigate/router/notify
git commit -m "feat(notify): пять вычисляемых триггеров и настройки на каждый ресурс"
```

---

### Task 5: Плитка ресурса и шит уведомлений

**Files:**
- Create: `app/src/main/java/com/aigate/router/ui/screens/ResourceNotifySheet.kt`
- Modify: `app/src/main/java/com/aigate/router/ui/screens/OverviewScreen.kt` (`ResourceTile`, `QuotaStrip`)

**Interfaces:**
- Consumes: `NotifyPrefs.Settings`, `NotifyPrefs.load/save`, `QuotaBurn.rate/outlook`, `ProviderAvatar`.
- Produces: `@Composable fun ResourceNotifySheet(pool: ResourcePool, kind: ResourcePoolKind, onDismiss: () -> Unit)`.

- [ ] **Step 1: Зафиксировать размеры плитки**

В `ResourceTile` заменить `Modifier.width(168.dp)` на `Modifier.width(168.dp).height(190.dp)`, содержимое выровнять `Arrangement.SpaceBetween`, чтобы разные наборы данных не меняли высоту.

- [ ] **Step 2: Шапка «логотип + имя», без ярлыка типа**

```kotlin
Row(verticalAlignment = Alignment.CenterVertically) {
    ProviderAvatar(name = pq.pool.name, type = providerType, size = 16.dp)
    Spacer(Modifier.width(Gateway.spacing.xs))
    Text(
        text = pq.pool.name,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
```

Убрать `StatusChip` с текстом «Квота · Критично»: состояние несёт цвет кольца.

- [ ] **Step 3: Строка прогноза под значением**

```kotlin
// «хватит до» либо «сгорит» — из расчёта, а не из фиксированных порогов.
outlook?.let { o ->
    val text = when {
        o.exhaustAtMs != null -> "хватит до ${Fmt.time(o.exhaustAtMs)}"
        o.surplus > 0.0 -> "сгорит ${Math.round(o.surplus)}%"
        else -> null
    }
    text?.let {
        Text(it, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
```

- [ ] **Step 4: Тап открывает шит уведомлений**

`AppCard(tone = CardTone.Raised, onClick = { showNotify = true }, …)`, а в `QuotaStrip` хранить `var notifyPool by remember { mutableStateOf<ResourcePool?>(null) }` и рендерить `ResourceNotifySheet` для выбранного.

- [ ] **Step 5: Написать шит**

`ResourceNotifySheet` использует `FormSheet(title = pool.name, confirmText = "Сохранить", onConfirm = { NotifyPrefs.save(pool.id, state) })`. Для `QUOTA`/`BUDGET`: четыре строки со `Switch` (порог остатка + `Slider` 5..50 %, «кончится раньше сброса», «сгорит неиспользованной» + `Slider` 0.5..7 суток, «сообщить о сбросе»). Для `BALANCE`: один `Switch` и `OutlinedTextField` суммы. Для `FREE`: одна строка «Ресурс без лимита — уведомлять не о чем», без элементов управления.

- [ ] **Step 6: Проверка на устройстве**

Установить, открыть «Обзор», убедиться: плитки одной высоты при разных данных, у бесплатного ресурса шит без тумблеров, значения сохраняются между перезапусками (`adb shell run-as com.aigate.router cat shared_prefs/aigate_config.xml | grep notify_`).

- [ ] **Step 7: Коммит**

```bash
git add app/src/main/java/com/aigate/router/ui/screens/ResourceNotifySheet.kt app/src/main/java/com/aigate/router/ui/screens/OverviewScreen.kt
git commit -m "feat(обзор): плитки ресурсов одной высоты, шит уведомлений по тапу"
```

---

### Task 6: Три графика

**Files:**
- Modify: `app/src/main/java/com/aigate/router/ui/screens/OverviewScreen.kt`
- Create: `app/src/main/java/com/aigate/router/ui/screens/OverviewCharts.kt`

**Interfaces:**
- Consumes: `UsageHistory.forecast/daily`, `QuotaSnapshotDao.getHistoryForPool(poolId)`, `QuotaBurn`, `LineChart(series, modifier, height, xLabelAt, yLabelAt, referenceY, referenceLabel)`, `LineSeries(label, points, colorIndex, projected, filled)`, `ChartPoint(x, y)`, `StackedBarChart(columns, …)`.
- Produces: `@Composable fun SpendForecastCard(...)`, `@Composable fun UsageByDayCard(days: List<UsageHistory.DayUsage>)`, `@Composable fun QuotaBurnCard(pools: List<QuotaRepository.PoolQuota>, histories: Map<Long, List<QuotaSnapshot>>)`.

- [ ] **Step 1: Перенести существующий график расхода в `OverviewCharts.kt`** как `SpendForecastCard`, оставив разбивку «Тарифы / Токены» и пунктир прогноза.

- [ ] **Step 2: График использования по дням**

```kotlin
StackedBarChart(
    columns = days.map { d ->
        StackedColumn(
            label = Fmt.day(d.dayStartMs),
            segments = listOf(
                StackSegment(d.promptTokens.toFloat(), colorIndex = 0),
                StackSegment(d.completionTokens.toFloat(), colorIndex = 1),
            ),
        )
    },
    height = 150.dp,
)
ChartLegend(labels = listOf("Входные токены", "Выходные токены"))
```

Точные сигнатуры: `StackedBarChart(columns: List<StackedColumn>, …)`,
`StackedColumn(label, segments)`, `StackSegment(value, colorIndex)` —
`app/src/main/java/com/aigate/router/ui/design/charts/BarCharts.kt:91-102`.

- [ ] **Step 3: График темпа расхода квоты**

Для каждого пула с `kind.hasReset` строим две серии: факт (остаток по снимкам) и пунктир ровного темпа от текущего остатка до нуля в момент сброса. Ровный темп — `referenceY` не подходит (это горизонталь), поэтому вторая серия строится точками:

```kotlin
val evenPace = listOf(
    ChartPoint(now.toFloat(), remaining.toFloat()),
    ChartPoint(resetsAt.toFloat(), 0f),
)
LineChart(
    series = listOf(
        LineSeries("Остаток", factPoints, colorIndex = 0, filled = true),
        LineSeries("Ровный темп", evenPace, projected = true),
    ),
    height = 150.dp,
    xLabelAt = { Fmt.time(it.toLong()) },
    yLabelAt = { "${it.toInt()}%" },
)
```

Подпись под графиком берётся из `QuotaBurn.outlook`: «кончится на N ч раньше сброса» либо «сгорит N%». Нет истории — `EmptyState("Истории расхода пока нет")`.

- [ ] **Step 4: Собрать три карточки в `OverviewScreen`** в порядке: расход и прогноз, использование, темп расхода квоты.

- [ ] **Step 5: Проверка на устройстве** — все три графика с осями и подписями, на реальных данных двух аккаунтов Codex.

- [ ] **Step 6: Коммит**

```bash
git add app/src/main/java/com/aigate/router/ui/screens/OverviewCharts.kt app/src/main/java/com/aigate/router/ui/screens/OverviewScreen.kt
git commit -m "feat(обзор): три графика — расход и прогноз, использование, темп расхода квоты"
```

---

### Task 7: Скорость моделей в строке модели и в детали

**Files:**
- Modify: `app/src/main/java/com/aigate/router/ui/screens/ResourcesHubScreen.kt` (секция моделей)
- Create: `app/src/main/java/com/aigate/router/ui/screens/ModelDetailSheet.kt`

**Interfaces:**
- Consumes: `GatewayViewModel.latestSpeedHistory`, `GatewayViewModel.testModelSpeed(model: AiModel)`, `CostCalculator.priceFor(db, providerType, modelId)`, `SpeedHistoryDao.getHistoryByModel(modelKey, limit)`.
- Produces: `@Composable fun ModelDetailSheet(model: AiModel, provider: Provider?, onDismiss: () -> Unit)`.

- [ ] **Step 1: Замер в строке модели**

Под именем модели строкой: `Fmt.latency(ttft)` + «${tps} ток/с» из `latestSpeedHistory`, кнопка «Тест» рядом. Нет замера — «замера нет».

- [ ] **Step 2: Деталь по тапу**

`ModelDetailSheet`: `LineChart` истории скорости этой модели (`getHistoryByModel(routeKey, 60)`), цена вход/выход за 1M, контекст, псевдоним, расход токенов этой модели за месяц.

- [ ] **Step 3: Проверка** — тап по модели открывает шит; «Тест» обновляет цифры в строке.

- [ ] **Step 4: Коммит**

```bash
git add app/src/main/java/com/aigate/router/ui/screens/ModelDetailSheet.kt app/src/main/java/com/aigate/router/ui/screens/ResourcesHubScreen.kt
git commit -m "feat(модели): скорость в строке модели и деталь модели с графиком"
```

---

### Task 8: Блок «Требует внимания» и проверка связи

**Files:**
- Create: `app/src/main/java/com/aigate/router/diag/ConnectivityCheck.kt`
- Create: `app/src/test/java/com/aigate/router/diag/ConnectivityCheckTest.kt`
- Modify: `app/src/main/java/com/aigate/router/ui/screens/OverviewScreen.kt`

**Interfaces:**
- Produces:
  - `enum class ConnectivityCheck.State { OK, WARN, FAIL }`
  - `data class ConnectivityCheck.Step(val title: String, val state: State, val detail: String)`
  - `suspend fun ConnectivityCheck.run(db: AppDatabase, port: Int): List<Step>`
  - `@Composable fun AttentionBlock(alerts: List<QuotaTriggers.Alert>, gatewayStopped: Boolean, blockedAttempts: Int, onAction: (QuotaTriggers.Kind) -> Unit)`

- [ ] **Step 1: Тест сборки шагов проверки**

```kotlin
package com.aigate.router.diag

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectivityCheckTest {
    @Test
    fun `worst state of the run drives the summary`() {
        val steps = listOf(
            ConnectivityCheck.Step("Порт", ConnectivityCheck.State.OK, "8889"),
            ConnectivityCheck.Step("Codex", ConnectivityCheck.State.FAIL, "токен истёк"),
        )
        assertEquals(ConnectivityCheck.State.FAIL, ConnectivityCheck.worst(steps))
    }

    @Test
    fun `empty run is not a failure`() {
        assertEquals(ConnectivityCheck.State.OK, ConnectivityCheck.worst(emptyList()))
    }
}
```

- [ ] **Step 2: Реализовать проверку** — шаги: порт слушается (`ServerSocket` bind test либо флаг сервиса), интернет (HEAD на 204-эндпоинт), у каждого включённого провайдера — доступность `GET /v1/models` либо, для Codex, `CodexModelsApi.fetch`; срок жизни токена CLI-сессии. Каждый шаг отдаёт `Step` с человеческой подписью.

- [ ] **Step 3: Блок «Требует внимания»** — строит строки из `QuotaTriggers.evaluate` для всех пулов плюс состояние шлюза; пустой список — блока нет вовсе.

- [ ] **Step 4: Тесты и проверка на устройстве** (отключить Wi-Fi → шаг «интернет» FAIL, при валидной сессии — OK).

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/java/com/aigate/router/diag app/src/test/java/com/aigate/router/diag app/src/main/java/com/aigate/router/ui/screens/OverviewScreen.kt
git commit -m "feat(обзор): блок «требует внимания» и проверка связи одним тапом"
```

---

### Task 9: QR-код адреса шлюза

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Create: `app/src/main/java/com/aigate/router/ui/design/QrCode.kt`
- Modify: `app/src/main/java/com/aigate/router/ui/screens/OverviewScreen.kt`

**Interfaces:**
- Produces: `@Composable fun QrCodeImage(content: String, size: Dp = 160.dp)`, `fun qrBitmap(content: String, sizePx: Int): Bitmap?`.

- [ ] **Step 1: Добавить зависимость**

В `gradle/libs.versions.toml`:

```toml
zxingCore = "3.5.3"
# [libraries]
zxing-core = { group = "com.google.zxing", name = "core", version.ref = "zxingCore" }
```

В `app/build.gradle.kts`: `implementation(libs.zxing.core)`.

- [ ] **Step 2: Рисовать битмап** через `MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)` и `BitMatrix` → `Bitmap` (чёрное по прозрачному, цвет берётся из темы через `tint`).

- [ ] **Step 3: Показать в статус-карточке** рядом с адресами; при `null`-адресе QR не рисуется.

- [ ] **Step 4: Проверка** — отсканировать телефоном/камерой: открывается `http://<ip>:<port>`.

- [ ] **Step 5: Коммит**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/aigate/router/ui/design/QrCode.kt app/src/main/java/com/aigate/router/ui/screens/OverviewScreen.kt
git commit -m "feat(обзор): QR-код адреса шлюза"
```

---

### Task 10: Кто обслужит запрос, экономия, тихий приёмник

**Files:**
- Create: `app/src/main/java/com/aigate/router/usage/LocalSavings.kt`
- Create: `app/src/test/java/com/aigate/router/usage/LocalSavingsTest.kt`
- Modify: `app/src/main/java/com/aigate/router/service/GatewayForegroundService.kt`, `app/src/main/java/com/aigate/router/gateway/GatewayService.kt`
- Modify: `app/src/main/java/com/aigate/router/ui/screens/OverviewScreen.kt`, `app/src/main/java/com/aigate/router/ui/screens/SettingsScreen.kt`

**Interfaces:**
- Produces:
  - `data class LocalSavings.Result(val savedUsd: Double, val referenceModel: String?, val localTokens: Long)`
  - `suspend fun LocalSavings.monthToDate(db: AppDatabase, now: Long): Result`
  - `GatewayForegroundService.isQuietListenerEnabled(): Boolean`, `setQuietListenerEnabled(Boolean)`, `blockedAttempts: AtomicInteger`

- [ ] **Step 1: Тест расчёта экономии**

```kotlin
package com.aigate.router.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSavingsTest {
    @Test
    fun `savings are local tokens priced by the cheapest cloud model`() {
        // 2 000 000 локальных токенов при эталоне $0.50 за 1M → $1.00
        val result = LocalSavings.compute(
            localPromptTokens = 1_000_000,
            localCompletionTokens = 1_000_000,
            cheapestInputPer1M = 0.25,
            cheapestOutputPer1M = 0.75,
            referenceModel = "cheap-model",
        )
        assertEquals(1.00, result.savedUsd, 0.001)
        assertEquals("cheap-model", result.referenceModel)
    }

    @Test
    fun `without a reference price there is no number`() {
        val result = LocalSavings.compute(1_000, 1_000, null, null, null)
        assertEquals(0.0, result.savedUsd, 0.0001)
        assertNull(result.referenceModel)
    }
}
```

- [ ] **Step 2: Реализовать `LocalSavings`** — токены провайдеров с `ResourcePoolKind.FREE` за месяц, умноженные на цену самой дешёвой облачной модели из `model_pricing`; эталон возвращается наружу, чтобы подпись называла его прямо.

- [ ] **Step 3: «Кто обслужит следующий запрос»** — карточка из `GatewayScheduler.bestModelKey`, `viewModel.forcedModelKey` и активного пресета: имя модели, провайдер и причина («выбрано вручную», «пресет Скорость», «быстрейшая по замерам»).

- [ ] **Step 4: Тихий приёмник** — переключатель в «Настройки → Сеть»; при выключенном шлюзе и включённом приёмнике сервис держит сокет, отвечает `503` с телом `{"error":{"message":"Шлюз остановлен"}}` и увеличивает `blockedAttempts`. Счётчик показывается в блоке «Требует внимания» и сбрасывается при запуске шлюза. По умолчанию выключен.

- [ ] **Step 5: Тесты и проверка** — при включённом приёмнике `curl` в остановленный шлюз даёт 503, счётчик растёт; при выключенном — соединение отвергается, счётчика нет.

- [ ] **Step 6: Коммит**

```bash
git add app/src/main/java/com/aigate/router/usage/LocalSavings.kt app/src/test/java/com/aigate/router/usage/LocalSavingsTest.kt app/src/main/java/com/aigate/router/service/GatewayForegroundService.kt app/src/main/java/com/aigate/router/gateway/GatewayService.kt app/src/main/java/com/aigate/router/ui/screens/OverviewScreen.kt app/src/main/java/com/aigate/router/ui/screens/SettingsScreen.kt
git commit -m "feat(обзор): кто обслужит запрос, экономия на локальных моделях, тихий приёмник"
```

---

## Порядок и зависимости

1 → 2 (адрес нужен статус-карточке) · 3 → 4 → 5 → 6 (расчёт нужен уведомлениям, шиту и графику) · 7, 9 независимы · 8 и 10 опираются на 4.

## Оговорка по уровню детализации

Задачи 1–6 содержат готовый код тестов и реализации. В задачах 7–10 приведены
точные файлы, сигнатуры (блок «Interfaces») и код ключевых фрагментов, но не
весь конечный код: там правки идут по существующим экранам, и итоговый вид
зависит от их текущего состояния. Эти задачи рассчитаны на исполнителя,
знакомого с кодовой базой, и не годятся для передачи «с нуля» без чтения
затрагиваемых файлов.
