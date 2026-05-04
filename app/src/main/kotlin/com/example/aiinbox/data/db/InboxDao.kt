package com.example.aiinbox.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface InboxDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: InboxItem)

    @Update
    suspend fun update(item: InboxItem)

    @Upsert
    suspend fun upsert(item: InboxItem)

    @Delete
    suspend fun delete(item: InboxItem)

    @Query("DELETE FROM inbox_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM inbox_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): InboxItem?

    @Query("SELECT * FROM inbox_items WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<InboxItem?>

    @Query("SELECT * FROM inbox_items WHERE deleted_at IS NULL ORDER BY received_at DESC")
    fun observeAll(): Flow<List<InboxItem>>

    @Query("SELECT * FROM inbox_items WHERE status = :status AND deleted_at IS NULL ORDER BY received_at ASC")
    suspend fun getByStatus(status: ItemStatus): List<InboxItem>

    @RawQuery
    suspend fun searchFtsRaw(query: SupportSQLiteQuery): List<InboxItem>

    suspend fun searchFts(query: String): List<InboxItem> =
        searchFtsRaw(
            SimpleSQLiteQuery(
                """
                SELECT i.* FROM inbox_items i
                JOIN inbox_fts f ON f.id = i.id
                WHERE inbox_fts MATCH ?
                  AND i.deleted_at IS NULL
                ORDER BY i.received_at DESC
                """,
                arrayOf(query),
            )
        )

    @Query(
        """
        SELECT * FROM inbox_items
        WHERE deleted_at IS NULL
          AND (:hasEventOnly = 0 OR event_title IS NOT NULL)
        ORDER BY received_at DESC
        """
    )
    fun observeFiltered(hasEventOnly: Int): Flow<List<InboxItem>>

    @RawQuery(observedEntities = [InboxItem::class])
    fun observeSearchRaw(query: SupportSQLiteQuery): Flow<List<InboxItem>>

    /**
     * FTS5 (trigram) full-text search joined with inbox_items. Queries shorter than
     * 3 characters return no rows (trigram limitation) — callers should route those
     * to [observeSearchLike] instead. Caller is responsible for FTS5-safe quoting
     * of the input (e.g. wrapping in double-quotes after stripping `"`).
     */
    fun observeSearch(query: String, hasEventOnly: Int): Flow<List<InboxItem>> =
        observeSearchRaw(
            SimpleSQLiteQuery(
                """
                SELECT i.* FROM inbox_items i
                JOIN inbox_fts f ON f.id = i.id
                WHERE inbox_fts MATCH ?
                  AND i.deleted_at IS NULL
                  AND (? = 0 OR i.event_title IS NOT NULL)
                ORDER BY i.received_at DESC
                """,
                arrayOf<Any>(query, hasEventOnly),
            )
        )

    /**
     * LIKE-based fallback for short queries (1-2 chars) where the FTS5 trigram
     * tokenizer cannot tokenize. Caller is responsible for wrapping the input
     * in `%` wildcards. Searches the same columns as the FTS index.
     */
    @Query(
        """
        SELECT * FROM inbox_items
        WHERE deleted_at IS NULL
          AND (
            title LIKE :pattern OR
            summary LIKE :pattern OR
            original_text LIKE :pattern OR
            tags LIKE :pattern OR
            people LIKE :pattern OR
            places LIKE :pattern
          )
          AND (:hasEventOnly = 0 OR event_title IS NOT NULL)
        ORDER BY received_at DESC
        """
    )
    fun observeSearchLike(pattern: String, hasEventOnly: Int): Flow<List<InboxItem>>

    @Transaction
    @Query("SELECT * FROM inbox_items WHERE id = :id LIMIT 1")
    suspend fun getByIdWithAttachments(id: String): InboxItemWithAttachments?

    /**
     * Sync-only point lookup that returns even tombstoned rows. Drive sync
     * needs to read deleted_at-set rows so it can publish their tombstones
     * to the manifest.
     */
    @Transaction
    @Query("SELECT * FROM inbox_items WHERE id = :id LIMIT 1")
    suspend fun getWithAttachmentsIncludingDeleted(id: String): InboxItemWithAttachments?

    /**
     * Sync-only scan: every id with its (updated_at, deleted_at, last_synced_at)
     * triple, including tombstones. Used by SyncEngine.diff against the remote
     * manifest.
     */
    @Query("SELECT id, updated_at AS updatedAt, deleted_at AS deletedAt, last_synced_at AS lastSyncedAt FROM inbox_items")
    suspend fun allRefsIncludingDeleted(): List<InboxRefRow>

    /** Update only the last_synced_at column for items the sync engine just touched. */
    @Query("UPDATE inbox_items SET last_synced_at = :syncedAt WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>, syncedAt: Long)

    @Transaction
    @Query("SELECT * FROM inbox_items WHERE id = :id LIMIT 1")
    fun observeByIdWithAttachments(id: String): Flow<InboxItemWithAttachments?>

    @Transaction
    @Query("SELECT * FROM inbox_items WHERE deleted_at IS NULL ORDER BY received_at DESC")
    fun observeAllWithAttachments(): Flow<List<InboxItemWithAttachments>>

    @Transaction
    @Query(
        """
        SELECT * FROM inbox_items
        WHERE deleted_at IS NULL
          AND (:hasEventOnly = 0 OR event_title IS NOT NULL)
        ORDER BY received_at DESC
        """
    )
    fun observeFilteredWithAttachments(hasEventOnly: Int): Flow<List<InboxItemWithAttachments>>

    @Transaction
    @RawQuery(observedEntities = [InboxItem::class, Attachment::class])
    fun observeSearchWithAttachmentsRaw(
        query: SupportSQLiteQuery,
    ): Flow<List<InboxItemWithAttachments>>

    fun observeSearchWithAttachments(
        query: String,
        hasEventOnly: Int,
    ): Flow<List<InboxItemWithAttachments>> =
        observeSearchWithAttachmentsRaw(
            SimpleSQLiteQuery(
                """
                SELECT i.* FROM inbox_items i
                JOIN inbox_fts f ON f.id = i.id
                WHERE inbox_fts MATCH ?
                  AND i.deleted_at IS NULL
                  AND (? = 0 OR i.event_title IS NOT NULL)
                ORDER BY i.received_at DESC
                """,
                arrayOf<Any>(query, hasEventOnly),
            )
        )

    @Transaction
    @Query(
        """
        SELECT * FROM inbox_items
        WHERE deleted_at IS NULL
          AND (
            title LIKE :pattern OR
            summary LIKE :pattern OR
            original_text LIKE :pattern OR
            tags LIKE :pattern OR
            people LIKE :pattern OR
            places LIKE :pattern OR
            EXISTS (SELECT 1 FROM attachments a WHERE a.item_id = inbox_items.id AND a.ocr_text LIKE :pattern)
          )
          AND (:hasEventOnly = 0 OR event_title IS NOT NULL)
        ORDER BY received_at DESC
        """
    )
    fun observeSearchLikeWithAttachments(
        pattern: String,
        hasEventOnly: Int,
    ): Flow<List<InboxItemWithAttachments>>

    @Query("UPDATE inbox_items SET deleted_at = :deletedAt, updated_at = :deletedAt WHERE id = :id")
    suspend fun markDeleted(id: String, deletedAt: Long)

    /** GC: tombstone rows whose deleted_at is older than [cutoff] (epoch ms). */
    @Query("SELECT * FROM inbox_items WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
    suspend fun tombstonesOlderThan(cutoff: Long): List<InboxItem>

    /** GC: physically remove the row (alias for [deleteById] with sync-aware naming). */
    @Query("DELETE FROM inbox_items WHERE id = :id")
    suspend fun physicalDeleteById(id: String)
}

/**
 * Lightweight row carrier for [InboxDao.allRefsIncludingDeleted]. Lives at
 * file scope so the DAO can return it directly without nested-type
 * resolution headaches.
 */
data class InboxRefRow(
    val id: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val lastSyncedAt: Long?,
)
