package com.example.aiinbox.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface InboxDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: InboxItem)

    @Update
    suspend fun update(item: InboxItem)

    @Delete
    suspend fun delete(item: InboxItem)

    @Query("DELETE FROM inbox_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM inbox_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): InboxItem?

    @Query("SELECT * FROM inbox_items WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<InboxItem?>

    @Query("SELECT * FROM inbox_items ORDER BY received_at DESC")
    fun observeAll(): Flow<List<InboxItem>>

    @Query("SELECT * FROM inbox_items WHERE status = :status ORDER BY received_at ASC")
    suspend fun getByStatus(status: ItemStatus): List<InboxItem>
}
