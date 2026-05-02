package com.example.aiinbox.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
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

    @RawQuery
    suspend fun searchFtsRaw(query: SupportSQLiteQuery): List<InboxItem>

    suspend fun searchFts(query: String): List<InboxItem> =
        searchFtsRaw(
            SimpleSQLiteQuery(
                """
                SELECT i.* FROM inbox_items i
                JOIN inbox_fts f ON f.id = i.id
                WHERE inbox_fts MATCH ?
                ORDER BY i.received_at DESC
                """,
                arrayOf(query),
            )
        )

    @Query(
        """
        SELECT * FROM inbox_items
        WHERE (:hasEventOnly = 0 OR event_title IS NOT NULL)
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
        WHERE (
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
}
