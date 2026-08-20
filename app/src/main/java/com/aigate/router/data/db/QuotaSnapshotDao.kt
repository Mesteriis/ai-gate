package com.aigate.router.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aigate.router.data.model.QuotaSnapshot
import kotlinx.coroutines.flow.Flow

@Dao
interface QuotaSnapshotDao {
    /** Последний снимок для каждого пула. */
    @Query(
        """
        SELECT s.* FROM quota_snapshots s
        INNER JOIN (
            SELECT pool_id, MAX(updated_at) AS max_updated
            FROM quota_snapshots GROUP BY pool_id
        ) latest ON s.pool_id = latest.pool_id AND s.updated_at = latest.max_updated
        """
    )
    fun observeLatest(): Flow<List<QuotaSnapshot>>

    @Query("SELECT * FROM quota_snapshots WHERE pool_id = :poolId ORDER BY updated_at DESC LIMIT 1")
    suspend fun getLatestForPool(poolId: Long): QuotaSnapshot?

    /**
     * Последний снимок пула от конкретного источника. Нужен, чтобы отличить
     * реальный ответ провайдера от локальной оценки: по нему решается, не рано ли
     * идти к провайдеру снова и можно ли писать локальный расчёт поверх.
     */
    @Query(
        "SELECT * FROM quota_snapshots WHERE pool_id = :poolId AND source = :source " +
            "ORDER BY updated_at DESC LIMIT 1"
    )
    suspend fun getLatestForPoolBySource(poolId: Long, source: String): QuotaSnapshot?

    @Query("SELECT * FROM quota_snapshots WHERE pool_id = :poolId ORDER BY updated_at ASC")
    suspend fun getHistoryForPool(poolId: Long): List<QuotaSnapshot>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: QuotaSnapshot): Long

    /**
     * Отметить, что показание всё ещё актуально. При обновлении раз в пять минут
     * вставка неизменившегося снимка раздувала бы историю на пустом месте.
     */
    @Query("UPDATE quota_snapshots SET updated_at = :updatedAt WHERE id = :id")
    suspend fun touchUpdatedAt(id: Long, updatedAt: Long)

    /** Чистка старых снимков, чтобы таблица не росла бесконечно. */
    @Query("DELETE FROM quota_snapshots WHERE updated_at < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM quota_snapshots WHERE pool_id = :poolId")
    suspend fun deleteForPool(poolId: Long)

    @Query("DELETE FROM quota_snapshots")
    suspend fun clearAll()
}
