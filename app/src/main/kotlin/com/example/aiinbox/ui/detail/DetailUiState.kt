package com.example.aiinbox.ui.detail

import com.example.aiinbox.data.db.InboxItem

data class DetailUiState(
    val item: InboxItem? = null,
    val loading: Boolean = true,
    val deleted: Boolean = false,
    val errorMessage: String? = null,
)
