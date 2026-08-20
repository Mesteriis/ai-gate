package com.aigate.router.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aigate.router.data.model.SpeedHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface SpeedHistoryDao {
    /**
     * Последние N замеров модели, наружу — по возрастанию времени (график
     * читается слева направо). Подзапрос обязателен: LIMIT с прямым ASC
     * отдавал бы N старейших замеров вместо свежих.
     */
    @Query("SELECT * FROM (SELECT * FROM speed_history WHERE model_key = :modelKey ORDER BY measured_at DESC LIMIT :limit) ORDER BY measured_at ASC")
    fun getHistoryByModel(modelKey: String, limit: Int = 50): Flow<List<SpeedHistory>>

    /** 获取所有模型的最新一条测速记录（用于排行榜快速对照） */
    @Query("SELECT * FROM speed_history WHERE id IN (SELECT MAX(id) FROM speed_history GROUP BY model_key)")
    fun getLatestEachModel(): Flow<List<SpeedHistory>>

    /** 获取指定模型的所有历史记录（一次性，非Flow） */
    @Query("SELECT * FROM speed_history WHERE model_key = :modelKey ORDER BY measured_at ASC")
    suspend fun getHistoryByModelOnce(modelKey: String): List<SpeedHistory>

    /** 获取所有测速历史（一次性，用于备份） */
    @Query("SELECT * FROM speed_history ORDER BY measured_at ASC")
    suspend fun getAllOnce(): List<SpeedHistory>

    /** 插入一条测速记录 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: SpeedHistory): Long

    /** 批量插入 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(histories: List<SpeedHistory>)

    /** 删除超过指定天数的旧记录 */
    @Query("DELETE FROM speed_history WHERE measured_at < :before")
    suspend fun deleteOlderThan(before: Long)

    /** 清空全部历史 */
    @Query("DELETE FROM speed_history")
    suspend fun clearAll()
}