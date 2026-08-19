package com.aigate.router.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aigate.router.data.model.LocalModel
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalModelDao {

    @Query("SELECT * FROM local_models ORDER BY created_at DESC")
    fun observeAll(): Flow<List<LocalModel>>

    @Query("SELECT * FROM local_models ORDER BY created_at DESC")
    suspend fun getAll(): List<LocalModel>

    @Query("SELECT * FROM local_models WHERE state = :state ORDER BY created_at ASC")
    suspend fun getByState(state: String): List<LocalModel>

    @Query("SELECT * FROM local_models WHERE engine = :engine AND state = 'ready' ORDER BY display_name ASC")
    suspend fun getReadyByEngine(engine: String): List<LocalModel>

    @Query("SELECT * FROM local_models WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LocalModel?

    @Query("SELECT * FROM local_models WHERE source = :source AND repo = :repo AND ref = :ref LIMIT 1")
    suspend fun getByKey(source: String, repo: String, ref: String): LocalModel?

    /**
     * IGNORE, а не REPLACE: уникальный ключ source+repo+ref защищает от второй
     * постановки той же модели в очередь, и затирать уже скачанный файл новой
     * пустой записью нельзя.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(model: LocalModel): Long

    @Update
    suspend fun update(model: LocalModel)

    @Query("UPDATE local_models SET state = :state, error_message = :errorMessage, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateState(id: Long, state: String, errorMessage: String = "", updatedAt: Long = System.currentTimeMillis())

    /**
     * Отдельный запрос для прогресса: он идёт часто, и переписывать всю строку
     * ради двух чисел значит будить наблюдателей списка на каждый мегабайт.
     */
    @Query("UPDATE local_models SET downloaded_bytes = :downloadedBytes, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateProgress(id: Long, downloadedBytes: Long, updatedAt: Long = System.currentTimeMillis())

    @Query(
        "UPDATE local_models SET state = 'ready', file_path = :filePath, size_bytes = :sizeBytes, " +
            "downloaded_bytes = :sizeBytes, error_message = '', updated_at = :updatedAt WHERE id = :id"
    )
    suspend fun setReady(id: Long, filePath: String, sizeBytes: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM local_models WHERE id = :id")
    suspend fun deleteById(id: Long)
}
