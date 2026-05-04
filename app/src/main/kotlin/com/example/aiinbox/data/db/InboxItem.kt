package com.example.aiinbox.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "inbox_items",
    indices = [
        Index("received_at"),
        Index("status"),
        Index("category"),
    ],
)
data class InboxItem(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "original_text") val originalText: String?,
    @ColumnInfo(name = "original_subject") val originalSubject: String?,
    @ColumnInfo(name = "source_app") val sourceApp: String?,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "status") val status: ItemStatus,
    @ColumnInfo(name = "processing_attempts") val processingAttempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "title") val title: String? = null,
    @ColumnInfo(name = "summary") val summary: String? = null,
    @ColumnInfo(name = "category") val category: String? = null,
    @ColumnInfo(name = "tags") val tags: List<String> = emptyList(),
    @ColumnInfo(name = "people") val people: List<String> = emptyList(),
    @ColumnInfo(name = "places") val places: List<String> = emptyList(),
    @ColumnInfo(name = "urls") val urls: List<String> = emptyList(),
    @Embedded(prefix = "event_") val event: ExtractedEvent? = null,
    @ColumnInfo(name = "user_edited_fields") val userEditedFields: Set<String> = emptySet(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long? = null,
)
