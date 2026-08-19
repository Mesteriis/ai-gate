package com.aigate.router.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.aigate.router.catalog.ModelCatalogRepository.CatalogEntry
import com.aigate.router.catalog.ModelCatalogRepository.CatalogSource
import com.aigate.router.catalog.ModelCatalogRepository.CatalogVariant
import com.aigate.router.data.model.LocalModel
import com.aigate.router.ui.design.ConfirmDialog
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.FormSheet
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.StatusChip
import com.aigate.router.ui.design.StatusTone

/**
 * Выбор варианта модели.
 *
 * Отдельный шит, а не раскрытие карточки в списке: у одного семейства бывает
 * десяток файлов, отличающихся только квантованием, и разворачивать их прямо в
 * выдаче значило бы утопить остальные результаты поиска.
 *
 * Список приходит уже отфильтрованным по возможностям устройства — своей
 * проверки здесь нет и быть не должно.
 */
@Composable
fun CatalogVariantSheet(
    entry: CatalogEntry,
    onDownload: (CatalogVariant) -> Unit,
    onDismiss: () -> Unit,
) {
    FormSheet(title = entry.displayName, onDismiss = onDismiss) {
        entry.variants.forEach { variant ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        // Различает варианты именно ref: у Ollama это размер
                        // модели («1.5b», «7b»), у HuggingFace — имя файла.
                        // Квант в заголовок не годится: у всех тегов Ollama он
                        // один и тот же, и три строки выглядели бы одинаково.
                        // Моноширинный шрифт потому, что тег вводят и сверяют
                        // посимвольно, и «l» с «1» путать нельзя.
                        text = variant.ref,
                        style = MaterialTheme.typography.bodyMedium
                            .copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOfNotNull(
                            variant.quant.takeIf { it.isNotBlank() },
                            // У Ollama размер до скачивания только оценочный:
                            // реестр не отдаёт его в поиске, и точное значение
                            // приходит с манифестом уже при загрузке. Ставим
                            // знак приблизительности, чтобы не выдавать
                            // прикидку за факт.
                            Fmt.bytes(variant.sizeBytes).let {
                                if (entry.source == CatalogSource.Ollama) "≈$it" else it
                            },
                            engineLabel(variant.engine.dbValue),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(Gateway.spacing.md))
                if (variant.downloaded) {
                    StatusChip(text = "на устройстве", tone = StatusTone.Success)
                } else {
                    Button(onClick = {
                        onDownload(variant)
                        onDismiss()
                    }) { Text("Загрузить") }
                }
            }
        }
    }
}

/**
 * Подробности скачанной модели и единственный путь к её удалению.
 *
 * Удаление стоит здесь, а не иконкой в строке списка: файл на гигабайты
 * стирается безвозвратно, и случайный тап по списку не должен его уносить.
 */
@Composable
fun DownloadedModelSheet(
    model: LocalModel,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    FormSheet(title = model.displayName, onDismiss = onDismiss) {
        DetailRow("Источник", sourceLabel(model.source))
        DetailRow("Файл", model.ref, mono = true)
        DetailRow("Движок", engineLabel(model.engine))
        DetailRow("Квант", model.quant.ifBlank { "—" })
        DetailRow("Размер", Fmt.bytes(model.sizeBytes))
        DetailRow("Окно контекста", "${Fmt.compact(model.contextWindow.toLong())} токенов")
        DetailRow("Загружена", Fmt.dateTime(model.createdAt))

        Spacer(Modifier.size(Gateway.spacing.sm))
        Button(
            onClick = { confirmDelete = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Удалить") }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Удалить модель?",
            message = "Файл ${Fmt.bytes(model.sizeBytes)} будет удалён с устройства. " +
                "Модель исчезнет из списка моделей.",
            confirmText = "Удалить",
            onConfirm = {
                onDelete()
                onDismiss()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Имя реестра для показа: в базе источник лежит коротким кодом. */
private fun sourceLabel(source: String): String = when (source) {
    LocalModel.SOURCE_OLLAMA -> "Ollama"
    LocalModel.SOURCE_HF -> "HuggingFace"
    else -> source
}

/** Строка «подпись — значение» подробностей. */
@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Gateway.spacing.md))
        Text(
            text = value,
            style = if (mono) {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
