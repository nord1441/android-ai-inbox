package com.example.aiinbox.ui.inbox

import com.example.aiinbox.data.db.InboxItem

data class InboxUiState(
    val items: List<InboxItem> = emptyList(),
    val loading: Boolean = true,
    val filter: InboxFilter = InboxFilter(),
    val availableCategories: Set<String> = emptySet(),
    val availableTags: Set<String> = emptySet(),
)
