package uk.nordtek.aiinbox.ui.inbox

import uk.nordtek.aiinbox.data.db.InboxItemWithAttachments

data class InboxUiState(
    val items: List<InboxItemWithAttachments> = emptyList(),
    val loading: Boolean = true,
    val filter: InboxFilter = InboxFilter(),
    val availableCategories: Set<String> = emptySet(),
    val availableTags: Set<String> = emptySet(),
)
