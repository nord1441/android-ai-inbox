package com.example.aiinbox.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = InboxItem::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("item_id"),
        Index(value = ["item_id", "ordering"]),
    ],
)
data class Attachment(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "ordering") val ordering: Int,
    @ColumnInfo(name = "kind") val kind: AttachmentKind,
    @ColumnInfo(name = "encrypted_filename") val encryptedFilename: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "width_px") val widthPx: Int,
    @ColumnInfo(name = "height_px") val heightPx: Int,
    @ColumnInfo(name = "byte_size") val byteSize: Long,
    @ColumnInfo(name = "ocr_text") val ocrText: String? = null,
    @ColumnInfo(name = "ocr_completed_at") val ocrCompletedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
