package com.example.aiinbox.data.db

data class ExtractedEvent(
    val title: String,
    val startMillis: Long?,
    val endMillis: Long?,
    val location: String?,
    val confidence: Float,
)
