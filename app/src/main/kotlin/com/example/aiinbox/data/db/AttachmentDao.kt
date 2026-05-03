package com.example.aiinbox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AttachmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attachments: List<Attachment>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: Attachment)

    @Query("SELECT * FROM attachments WHERE item_id = :itemId ORDER BY ordering ASC")
    suspend fun getForItem(itemId: String): List<Attachment>

    @Query("UPDATE attachments SET ocr_text = :ocrText, ocr_completed_at = :completedAt WHERE id = :id")
    suspend fun updateOcr(id: String, ocrText: String?, completedAt: Long)

    @Query("DELETE FROM attachments WHERE item_id = :itemId")
    suspend fun deleteForItem(itemId: String)
}
