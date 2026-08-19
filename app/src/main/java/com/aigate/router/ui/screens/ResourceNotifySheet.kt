package com.aigate.router.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.aigate.router.data.model.ResourcePool
import com.aigate.router.notify.NotifyPrefs
import com.aigate.router.quota.ResourcePoolKind
import com.aigate.router.ui.design.FormSheet
import com.aigate.router.ui.design.Gateway
import kotlin.math.roundToInt

/**
 * Уведомления по одному ресурсу. Набор настроек зависит от типа: у квоты есть
 * сброс и темп расхода, у баланса — только сумма на счету, а бесплатный ресурс
 * не имеет ни остатка, ни лимита, и уведомлять по нему не о чем.
 */
@Composable
fun ResourceNotifySheet(
    pool: ResourcePool,
    kind: ResourcePoolKind,
    onDismiss: () -> Unit,
) {
    var settings by remember(pool.id) { mutableStateOf(NotifyPrefs.load(pool.id, kind)) }

    if (kind == ResourcePoolKind.FREE) {
        FormSheet(title = pool.name, onDismiss = onDismiss) {
            Text(
                text = "Ресурс без лимита — уведомлять не о чем",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    FormSheet(
        title = pool.name,
        onDismiss = onDismiss,
        confirmText = "Сохранить",
        onConfirm = {
            NotifyPrefs.save(pool.id, settings)
            onDismiss()
        },
    ) {
        if (kind == ResourcePoolKind.BALANCE) {
            SwitchRow(
                title = "Низкий баланс",
                checked = settings.lowBalanceEnabled,
                onCheckedChange = { settings = settings.copy(lowBalanceEnabled = it) },
            )
            if (settings.lowBalanceEnabled) {
                var text by remember { mutableStateOf(trimZero(settings.lowBalanceUsd)) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { v ->
                        text = v.filter { it.isDigit() || it == '.' }
                        text.toDoubleOrNull()?.let { settings = settings.copy(lowBalanceUsd = it) }
                    },
                    label = { Text("Сообщить, когда меньше, USD") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            return@FormSheet
        }

        SwitchRow(
            title = "Остаток ниже порога",
            checked = settings.lowQuotaEnabled,
            onCheckedChange = { settings = settings.copy(lowQuotaEnabled = it) },
        )
        if (settings.lowQuotaEnabled) {
            ValueSlider(
                value = (settings.lowQuotaFraction * 100).toFloat(),
                range = 5f..50f,
                label = "${(settings.lowQuotaFraction * 100).roundToInt()}% остатка",
                onChange = { settings = settings.copy(lowQuotaFraction = it / 100.0) },
            )
        }

        SwitchRow(
            title = "Кончится раньше сброса",
            checked = settings.exhaustBeforeResetEnabled,
            onCheckedChange = { settings = settings.copy(exhaustBeforeResetEnabled = it) },
        )

        SwitchRow(
            title = "Сгорит неиспользованной",
            checked = settings.surplusEnabled,
            onCheckedChange = { settings = settings.copy(surplusEnabled = it) },
        )
        if (settings.surplusEnabled) {
            // Значимость в сутках собственного расхода: доля остатка тут
            // бессмысленна, потому что цена суток у тарифов разная.
            ValueSlider(
                value = settings.surplusDays.toFloat(),
                range = 0.5f..7f,
                label = "если сгорит больше ${trimZero(settings.surplusDays)} сут. обычного расхода",
                onChange = { settings = settings.copy(surplusDays = it.toDouble()) },
            )
        }

        SwitchRow(
            title = "Сообщить о сбросе",
            checked = settings.resetEnabled,
            onCheckedChange = { settings = settings.copy(resetEnabled = it) },
        )
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ValueSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    onChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Gateway.spacing.xs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            valueRange = range,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun trimZero(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
