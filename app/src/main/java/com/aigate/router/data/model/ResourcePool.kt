package com.aigate.router.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Пул ресурсов — единица, к которой привязываются квоты (подписка, баланс API-ключа
 * или локальный бюджет). Один провайдер может иметь несколько пулов (например,
 * подписочную квоту и денежный баланс). Секретов здесь нет — только метаданные.
 *
 * `providerId == 0` означает глобальный пул (например, общий локальный бюджет в USD).
 */
@Entity(
    tableName = "resource_pools",
    indices = [Index("provider_id")]
)
@Serializable
data class ResourcePool(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "provider_id")
    val providerId: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    /** ResourcePoolKind как строка: SUBSCRIPTION | API_BALANCE | LOCAL_BUDGET */
    @ColumnInfo(name = "kind")
    val kind: String = "LOCAL_BUDGET",
    /** QuotaUnit как строка. */
    @ColumnInfo(name = "unit")
    val unit: String = "USD",
    /** Заданный пользователем лимит (для LOCAL_BUDGET/USER_CONFIGURED). null = не задан. */
    @ColumnInfo(name = "configured_limit")
    val configuredLimit: Double? = null,
    /** День месяца для сброса бюджета (1..28), null = без периодического сброса. */
    @ColumnInfo(name = "reset_day_of_month")
    val resetDayOfMonth: Int? = null,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,
    @ColumnInfo(name = "order_index")
    val orderIndex: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
