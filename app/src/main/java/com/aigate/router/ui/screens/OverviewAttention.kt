package com.aigate.router.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.diag.ConnectivityCheck
import com.aigate.router.gateway.GatewayStart
import com.aigate.router.notify.QuotaTriggers
import com.aigate.router.ui.design.AppCard
import com.aigate.router.ui.design.CardTone
import com.aigate.router.ui.design.ChartCard
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.SectionHeader
import com.aigate.router.ui.design.StatusChip
import com.aigate.router.ui.design.StatusDot
import com.aigate.router.ui.design.StatusTone
import com.aigate.router.ui.design.accent
import com.aigate.router.ui.design.appear
import kotlinx.coroutines.launch

/**
 * Строка блока внимания. Тон — единственный источник и иконки, и цвета
 * чипа-вердикта: два разных решения о цвете в одной строке неизбежно
 * разъезжаются.
 */
private data class AttentionItem(
    val tone: StatusTone,
    val title: String,
    val detail: String,
    /** Короткий вердикт справа: что именно случилось, одним словом. */
    val verdict: String,
)

/**
 * Вес повода для сортировки. Красное читается первым: если запросы уже не
 * проходят, «квота обновилась» ниже — приятная, но второстепенная новость.
 */
private fun severityRank(tone: StatusTone): Int = when (tone) {
    StatusTone.Error -> 0
    StatusTone.Warning -> 1
    StatusTone.Info -> 2
    StatusTone.Success -> 3
    StatusTone.Neutral -> 4
}

/**
 * Тон и вердикт по типу триггера. Alert несёт только текст, поэтому короткое
 * слово для чипа выводится из типа, а не выкусывается из готовой фразы:
 * разбор строки сломался бы от любой правки формулировки.
 */
private fun QuotaTriggers.Alert.toAttentionItem(): AttentionItem = when (kind) {
    QuotaTriggers.Kind.LOW_QUOTA -> AttentionItem(StatusTone.Error, title, body, "мало")
    QuotaTriggers.Kind.LOW_BALANCE -> AttentionItem(StatusTone.Error, title, body, "мало")
    QuotaTriggers.Kind.EXHAUST_BEFORE_RESET ->
        AttentionItem(StatusTone.Error, title, body, "не успеет")
    QuotaTriggers.Kind.SURPLUS -> AttentionItem(StatusTone.Warning, title, body, "сгорит")
    QuotaTriggers.Kind.RESET -> AttentionItem(StatusTone.Success, title, body, "сброс")
}

/**
 * «Требует внимания»: то, на что стоит отреагировать сейчас. Блок появляется
 * только при наличии поводов — пустых заголовков на экране быть не должно.
 */
@Composable
fun AttentionBlock(
    alerts: List<QuotaTriggers.Alert>,
    gatewayStopped: Boolean,
    blockedAttempts: Int,
    modifier: Modifier = Modifier,
    startFailure: GatewayStart.Failure? = null,
) {
    val hasAttempts = gatewayStopped && blockedAttempts > 0
    if (alerts.isEmpty() && !hasAttempts && startFailure == null) return

    // sortedBy устойчива, поэтому внутри одного тона сохраняется исходный
    // порядок пулов — строки не перескакивают между обновлениями.
    val items = buildList {
        // Неудачный запуск идёт отдельной строкой от «шлюз остановлен»: остановил
        // шлюз пользователь, а тут шлюз встать не смог, и причина другая.
        if (startFailure != null) {
            add(
                AttentionItem(
                    tone = StatusTone.Error,
                    title = "Шлюз не запущен",
                    detail = startFailure.shortText,
                    verdict = startFailure.verdict,
                )
            )
        }
        if (hasAttempts) {
            add(
                AttentionItem(
                    tone = StatusTone.Warning,
                    title = "Шлюз остановлен",
                    detail = "запросы не принимаются",
                    verdict = "$blockedAttempts " +
                        Fmt.plural(blockedAttempts.toLong(), "попытка", "попытки", "попыток"),
                )
            )
        }
        alerts.forEach { add(it.toAttentionItem()) }
    }.sortedBy { severityRank(it.tone) }

    // Заголовок и карточка едут одной волной, поэтому лежат в общей обёртке.
    // Отступ между ними задан явно: раньше его давал внешний Column экрана.
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md)) {
        SectionHeader("Требует внимания")
        // Тон остаётся Raised: Accent в системе — информационный синий, и под
        // красными строками он читался бы как подсказка, а не как тревога.
        AppCard(tone = CardTone.Raised) {
            Column(verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md)) {
                items.forEachIndexed { index, item ->
                    AttentionRow(item = item, modifier = Modifier.appear(index = index))
                }
            }
        }
    }
}

@Composable
private fun AttentionRow(item: AttentionItem, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (item.tone) {
                StatusTone.Error -> Icons.Outlined.ErrorOutline
                StatusTone.Success -> Icons.Outlined.CheckCircle
                else -> Icons.Outlined.WarningAmber
            },
            contentDescription = null,
            tint = item.tone.accent(),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(Gateway.spacing.md))
        // weight забирает остаток ряда: без него длинный текст выталкивал чип
        // за край карточки.
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = item.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Gateway.spacing.sm))
        StatusChip(text = item.verdict, tone = item.tone)
    }
}

/**
 * Проверка связи одним тапом: вывод — крупной строкой в шапке, ниже шаги,
 * поэтому видно, на каком именно звене оборвалась цепочка.
 */
@Composable
fun ConnectivityCheckCard(db: AppDatabase, port: Int, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf<List<ConnectivityCheck.Step>>(emptyList()) }
    var checkedAt by remember { mutableStateOf<Long?>(null) }
    // Номер прогона: по нему пересоздаются строки шагов, поэтому каждая новая
    // проверка проигрывает появление заново, а не молча подменяет текст.
    var pass by remember { mutableIntStateOf(0) }

    ChartCard(
        eyebrow = "Проверка связи",
        // Пока не проверяли — так и написано: выдуманного вердикта тут быть не должно.
        readMain = if (steps.isEmpty()) "Не проверялась" else ConnectivityCheck.summary(steps),
        readSub = checkedAt?.let { "проверено в ${Fmt.time(it)}" },
        modifier = modifier,
        headerAction = {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = {
                    running = true
                    scope.launch {
                        steps = ConnectivityCheck.run(db, port)
                        checkedAt = System.currentTimeMillis()
                        pass += 1
                        running = false
                    }
                }) { Text(if (steps.isEmpty()) "Проверить" else "Проверить снова") }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Gateway.spacing.sm)) {
            steps.forEachIndexed { index, step ->
                key(pass, index) {
                    CheckStepRow(
                        tone = when (step.state) {
                            ConnectivityCheck.State.OK -> StatusTone.Success
                            ConnectivityCheck.State.WARN -> StatusTone.Warning
                            ConnectivityCheck.State.FAIL -> StatusTone.Error
                        },
                        title = step.title,
                        detail = step.detail,
                        modifier = Modifier.appear(index = index),
                    )
                }
            }
        }
    }
}

/**
 * Шаг проверки: точка тона вместо иконки. Шагов бывает много, и ряд одинаковых
 * точек читается как список звеньев цепочки, а ряд крупных иконок — как набор
 * отдельных тревог.
 */
@Composable
private fun CheckStepRow(
    tone: StatusTone,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(tone)
        Spacer(Modifier.width(Gateway.spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
