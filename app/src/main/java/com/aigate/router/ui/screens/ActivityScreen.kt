package com.aigate.router.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.HelpSection
import com.aigate.router.ui.viewmodel.GatewayViewModel

/**
 * Справка раздела «Активность». Вся содержательная помощь живёт здесь и
 * открывается кнопкой «?» в шапке — на экране инструкций быть не должно.
 */
internal val activityHelp = listOf(
    HelpSection(
        "Откуда берутся цифры",
        "Расход токенов записывается автоматически при каждом запросе через шлюз. " +
            "Вызовы, пришедшие без API-ключа, попадают в группу «Без ключа».",
    ),
    HelpSection(
        "Трафик шлюза",
        "Общие счётчики отправленных и полученных байт сохраняются между запусками " +
            "приложения и обнуляются только вручную. В журнале показан трафик текущего " +
            "сеанса шлюза — он начинается с нуля при каждом запуске.",
    ),
    HelpSection(
        "Тренд скорости",
        "График строится по истории тестов скорости: TTFT — время до первого токена, " +
            "TPS — скорость генерации, «Всего» — полное время ответа. Неудачные замеры в " +
            "график не попадают, их количество показано рядом с заголовком. История " +
            "замеров хранится 7 дней.",
    ),
    HelpSection(
        "Журнал",
        "Журнал держит последние строки активности шлюза в памяти: новые сверху, при " +
            "перезапуске приложения он очищается. Фильтр по уровню появляется, когда в " +
            "журнале есть предупреждения или ошибки.",
    ),
    HelpSection(
        "Очистка данных",
        "«Расход» удаляет все записи о токенах, «Трафик» обнуляет счётчики байт. " +
            "Оба действия необратимы и не затрагивают провайдеров, модели и настройки.",
    ),
)

private enum class ActivityTab(val title: String) {
    Charts("Графики"),
    Journal("Журнал"),
}

/**
 * «Активность» — два сегмента над одними данными: агрегированные графики и
 * сырой журнал шлюза. Шапку и справку даёт навигация, экран строит только тело.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    viewModel: GatewayViewModel,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(ActivityTab.Charts) }
    val tabs = remember { ActivityTab.entries }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Gateway.spacing.lg, vertical = Gateway.spacing.sm),
        ) {
            tabs.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = tab == item,
                    onClick = { tab = item },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                    label = { Text(item.title) },
                )
            }
        }

        when (tab) {
            ActivityTab.Charts -> StatsSegment(
                viewModel = viewModel,
                modifier = Modifier.weight(1f),
            )
            ActivityTab.Journal -> JournalSegment(
                viewModel = viewModel,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
