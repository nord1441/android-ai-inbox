package com.example.aiinbox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface AttachmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attachments: List<Attachment>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: Attachment)

    @Upsert
    suspend fun upsertAll(attachments: List<Attachment>)

    @Query("SELECT * FROM attachments WHERE item_id = :itemId AND deleted_at IS NULL ORDER BY ordering ASC")
    suspend fun getForItem(itemId: String): List<Attachment>

    /** Alias for [getForItem] used in tests. */
    suspend fun listForItem(itemId: String): List<Attachment> = getForItem(itemId)

    /**
     * Sync-only: returns attachments for an item including tombstones. Used
     * by the manifest builder so deletes can be reflected in the published
     * manifest.
     */
    @Query("SELECT * FROM attachments WHERE item_id = :itemId ORDER BY ordering ASC")
    suspend fun listForItemIncludingDeleted(itemId: String): List<Attachment>

    @Query("UPDATE attachments SET ocr_text = :ocrText, ocr_completed_at = :completedAt WHERE id = :id")
    suspend fun updateOcr(id: String, ocrText: String?, completedAt: Long)

    @Query("DELETE FROM attachments WHERE item_id = :itemId")
    suspend fun deleteForItem(itemId: String)

    @Query("UPDATE attachments SET deleted_at = :deletedAt WHERE item_id = :itemId")
    suspend fun markDeletedForItem(itemId: String, deletedAt: Long)

    /** GC: physically remove all attachment rows for an item. */
    @Query("DELETE FROM attachments WHERE item_id = :itemId")
    suspend fun physicalDeleteForItem(itemId: String)
}
