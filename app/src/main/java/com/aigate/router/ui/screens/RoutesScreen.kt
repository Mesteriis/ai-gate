package com.aigate.router.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aigate.router.data.model.RoutingRule
import com.aigate.router.ui.theme.Online
import com.aigate.router.ui.viewmodel.GatewayViewModel
import com.aigate.router.utils.localizedText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 命名路由预设 —— 每个预设背后是一条 RoutingRule（name == 预设id）
 */
private data class RoutePreset(
    val id: String,
    val emoji: String,
    val nameRu: String,
    val nameEn: String,
    val descRu: String,
    val descEn: String
)

private val routePresets = listOf(
    RoutePreset(
        id = "route:fast",
        emoji = "⚡",
        nameRu = "Скорость",
        nameEn = "Fastest",
        descRu = "Быстрейшая доступная модель (по TTFT)",
        descEn = "Fastest available model (by TTFT)"
    ),
    RoutePreset(
        id = "route:quality",
        emoji = "💎",
        nameRu = "Качество",
        nameEn = "Quality",
        descRu = "Приоритет качества (порядок задаёт пользователь)",
        descEn = "Quality first (order set by the user)"
    ),
    RoutePreset(
        id = "route:cheap",
        emoji = "💰",
        nameRu = "Экономия",
        nameEn = "Cheapest",
        descRu = "Минимальная стоимость (по прайсингу, когда доступен)",
        descEn = "Lowest cost (by pricing, when available)"
    ),
    RoutePreset(
        id = "route:offline",
        emoji = "🏠",
        nameRu = "Локально",
        nameEn = "Offline",
        descRu = "Только локальные модели (Ollama и т.п.)",
        descEn = "Local models only (Ollama, etc.)"
    )
)

// ============================================================
// 命名路由预设页面 —— 冰蓝浅色卡片
// ============================================================
@Composable
fun RoutesScreen(viewModel: GatewayViewModel) {
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf<List<RoutingRule>>(emptyList()) }

    // 首次加载 + 每次切换后重新加载
    suspend fun reload() {
        rules = viewModel.getAllRoutingRules()
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Маршруты",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp)
        )
        Text(
            text = "Именованные пресеты маршрутизации поверх правил",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        )

        routePresets.forEachIndexed { index, preset ->
            val rule = rules.find { it.name == preset.id }
            val enabled = rule?.enabled == true
            PresetCard(
                preset = preset,
                enabled = enabled,
                onToggle = { checked ->
                    val existing = rules.find { it.name == preset.id }
                    if (checked) {
                        if (existing == null) {
                            val newRule = RoutingRule(
                                name = preset.id,
                                enabled = true,
                                action = "route",
                                modelPattern = "*",
                                priority = index
                            )
                            viewModel.saveRoutingRule(newRule)
                            // 乐观更新，随后 reload 拿到真实自增 id
                            rules = rules + newRule
                        } else {
                            viewModel.setRoutingRuleEnabled(existing.id, true)
                            rules = rules.map {
                                if (it.name == preset.id) it.copy(enabled = true) else it
                            }
                        }
                    } else {
                        if (existing != null) {
                            viewModel.setRoutingRuleEnabled(existing.id, false)
                            rules = rules.map {
                                if (it.name == preset.id) it.copy(enabled = false) else it
                            }
                        }
                    }
                    // 数据库写入是异步的，稍后重新加载以对齐真实状态
                    scope.launch {
                        delay(200)
                        reload()
                    }
                }
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Именованные маршруты — пресеты поверх правил маршрутизации. Тонкая настройка — в правилах маршрутизации (Настройки).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
    }
}

@Composable
private fun PresetCard(
    preset: RoutePreset,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(preset.emoji, style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = preset.nameRu,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (enabled) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "●",
                            color = Online,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = preset.descRu,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle
            )
        }
    }
}
