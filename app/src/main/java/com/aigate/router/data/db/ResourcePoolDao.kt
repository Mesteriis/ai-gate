package com.aigate.router.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aigate.router.data.model.ResourcePool
import kotlinx.coroutines.flow.Flow

@Dao
interface ResourcePoolDao {
    @Query("SELECT * FROM resource_pools ORDER BY order_index ASC, id ASC")
    fun observeAll(): Flow<List<ResourcePool>>

    @Query("SELECT * FROM resource_pools ORDER BY order_index ASC, id ASC")
    suspend fun getAll(): List<ResourcePool>

    @Query("SELECT * FROM resource_pools WHERE id = :id")
    suspend fun getById(id: Long): ResourcePool?

    @Query("SELECT * FROM resource_pools WHERE provider_id = :providerId ORDER BY id ASC")
    suspend fun getByProvider(providerId: Long): List<ResourcePool>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pool: ResourcePool): Long

    @Update
    suspend fun update(pool: ResourcePool)

    @Delete
    suspend fun delete(pool: ResourcePool)

    @Query("DELETE FROM resource_pools WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM resource_pools WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long)

    @Query("DELETE FROM resource_pools")
    suspend fun clearAll()
}
