package com.example.aiinbox.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FsSyncStateDao {
    @Query("SELECT * FROM fs_sync_state WHERE id = 1 LIMIT 1")
    suspend fun get(): FsSyncStateEntity?

    @Query("SELECT * FROM fs_sync_state WHERE id = 1 LIMIT 1")
    fun observe(): Flow<FsSyncStateEntity?>

    @Upsert
    suspend fun upsert(state: FsSyncStateEntity)
}
