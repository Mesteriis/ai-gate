package com.aigate.router.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aigate.router.data.model.Credential

@Dao
interface CredentialDao {
    @Query("SELECT * FROM credentials")
    suspend fun getAll(): List<Credential>

    @Query("SELECT * FROM credentials WHERE id = :id")
    suspend fun getById(id: Long): Credential?

    @Query("SELECT * FROM credentials WHERE provider_id = :providerId LIMIT 1")
    suspend fun getByProvider(providerId: Long): Credential?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(credential: Credential): Long

    @Update
    suspend fun update(credential: Credential)

    @Query("DELETE FROM credentials WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM credentials WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long)

    @Query("DELETE FROM credentials")
    suspend fun clearAll()
}
