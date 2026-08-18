package com.aigate.router.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aigate.router.data.model.ModelPricing
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelPricingDao {
    @Query("SELECT * FROM model_pricing ORDER BY provider_type ASC, model_id ASC")
    fun observeAll(): Flow<List<ModelPricing>>

    @Query("SELECT * FROM model_pricing")
    suspend fun getAll(): List<ModelPricing>

    @Query("SELECT * FROM model_pricing WHERE provider_type = :providerType AND model_id = :modelId LIMIT 1")
    suspend fun get(providerType: String, modelId: String): ModelPricing?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pricing: ModelPricing): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(pricing: List<ModelPricing>)

    @Query("DELETE FROM model_pricing WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM model_pricing WHERE source = :source")
    suspend fun deleteBySource(source: String)

    @Query("DELETE FROM model_pricing")
    suspend fun clearAll()
}
