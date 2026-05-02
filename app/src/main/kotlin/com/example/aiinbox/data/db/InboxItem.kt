package com.example.aiinbox.data.db

data class InboxItem(
    val id: String,
    val originalText: String,
    val originalSubject: String?,
    val sourceApp: String?,
    val receivedAt: Long,
    val status: ItemStatus,
    val processingAttempts: Int = 0,
    val lastError: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val people: List<String> = emptyList(),
    val places: List<String> = emptyList(),
    val urls: List<String> = emptyList(),
    val event: ExtractedEvent? = null,
    val userEditedFields: Set<String> = emptySet(),
    val updatedAt: Long,
)
