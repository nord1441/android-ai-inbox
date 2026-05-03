package com.example.aiinbox.data.db

import androidx.room.Embedded
import androidx.room.Relation

data class InboxItemWithAttachments(
    @Embedded val item: InboxItem,
    @Relation(
        parentColumn = "id",
        entityColumn = "item_id",
    )
    val attachments: List<Attachment>,
)
