package com.example.aiinbox.ui.inbox

import com.example.aiinbox.data.db.InboxItem

data class InboxUiState(
    val items: List<InboxItem> = emptyList(),
    val loading: Boolean = true,
)
