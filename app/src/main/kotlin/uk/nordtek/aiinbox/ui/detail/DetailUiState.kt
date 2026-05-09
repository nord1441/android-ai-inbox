package uk.nordtek.aiinbox.ui.detail

import uk.nordtek.aiinbox.data.db.Attachment
import uk.nordtek.aiinbox.data.db.InboxItem

data class DetailUiState(
    val item: InboxItem? = null,
    val attachments: List<Attachment> = emptyList(),
    val loading: Boolean = true,
    val deleted: Boolean = false,
    val errorMessage: String? = null,
)
