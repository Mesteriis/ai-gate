package com.aigate.router.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Файл модели, скачанный на устройство.
 *
 * Хранится отдельно от [AiModel] намеренно: [AiModel] отвечает на вопрос «кто
 * обслужит запрос», а здесь живёт судьба файла на диске — сколько занял, чем
 * проверен, докачан ли. Готовая запись превращается в строку [AiModel] и
 * попадает в общий список моделей, а сломанная или недокачанная — нет.
 *
 * Состояния строками, а не enum: так же сделан [AiModel.syncStatus], и Room не
 * тянет конвертеры ради четырёх значений.
 */
@Entity(
    tableName = "local_models",
    indices = [Index(value = ["source", "repo", "ref"], unique = true)],
)
@Serializable
data class LocalModel(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Откуда взято: "ollama" или "hf". */
    val source: String,
    /** Имя в реестре: "qwen3" для Ollama, "unsloth/Qwen3-1.7B-GGUF" для HuggingFace. */
    val repo: String,
    /** Тег Ollama или имя файла HuggingFace — вместе с repo однозначно задаёт файл. */
    val ref: String,
    /** Движок, который умеет это читать: "gguf" или "litertlm". */
    val engine: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "downloaded_bytes")
    val downloadedBytes: Long = 0,
    /**
     * Ожидаемая контрольная сумма. Пустая строка означает, что источник её не
     * дал — тогда проверка пропускается, и это не ошибка.
     */
    val sha256: String = "",
    @ColumnInfo(name = "file_path")
    val filePath: String = "",
    /** queued / downloading / paused / verifying / ready / error. */
    val state: String = STATE_QUEUED,
    @ColumnInfo(name = "error_message")
    val errorMessage: String = "",
    @ColumnInfo(name = "context_window")
    val contextWindow: Int = 4096,
    val quant: String = "",
    @ColumnInfo(name = "params_b")
    val paramsB: Double? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /**
     * Идентификатор для списка моделей. Источник и движок входят в него, чтобы
     * одна и та же модель из разных мест не сливалась в одну строку.
     */
    val routableModelId: String get() = "local/$engine/$repo:$ref"

    companion object {
        const val SOURCE_OLLAMA = "ollama"
        const val SOURCE_HF = "hf"

        const val STATE_QUEUED = "queued"
        const val STATE_DOWNLOADING = "downloading"
        const val STATE_PAUSED = "paused"
        const val STATE_VERIFYING = "verifying"
        const val STATE_READY = "ready"
        const val STATE_ERROR = "error"
    }
}
