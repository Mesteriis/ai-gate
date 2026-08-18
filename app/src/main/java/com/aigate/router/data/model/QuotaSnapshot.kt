package com.aigate.router.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Снимок состояния квоты для пула ресурсов. Храним последний известный снимок на пул
 * (плюс историю для графиков). Все числовые поля nullable — если провайдер НЕ отдаёт
 * показатель, поле остаётся null, и UI показывает «Данные о квоте недоступны», а не 0.
 */
@Entity(
    tableName = "quota_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = ResourcePool::class,
            parentColumns = ["id"],
            childColumns = ["pool_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("pool_id"), Index("updated_at")]
)
@Serializable
data class QuotaSnapshot(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "pool_id")
    val poolId: Long,
    /** Израсходовано. null = неизвестно. */
    @ColumnInfo(name = "used")
    val used: Double? = null,
    /** Остаток. null = провайдер не отдаёт остаток (не выдумываем). */
    @ColumnInfo(name = "remaining")
    val remaining: Double? = null,
    /** Лимит/ёмкость. null = неизвестно. */
    @ColumnInfo(name = "limit_value")
    val limit: Double? = null,
    /** QuotaUnit как строка. */
    @ColumnInfo(name = "unit")
    val unit: String = "UNKNOWN",
    /** Момент сброса квоты (epoch ms). null = неизвестен/без сброса. */
    @ColumnInfo(name = "resets_at")
    val resetsAt: Long? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    /** QuotaSource как строка: PROVIDER_API | LOCAL_USAGE | USER_CONFIGURED | ESTIMATED. */
    @ColumnInfo(name = "source")
    val source: String = "LOCAL_USAGE"
)
