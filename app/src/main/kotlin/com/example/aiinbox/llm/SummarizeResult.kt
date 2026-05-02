package com.example.aiinbox.llm

import com.example.aiinbox.data.db.ExtractedEvent

data class SummarizeResult(
    val title: String?,
    val summary: String?,
    val category: String?,
    val tags: List<String>,
    val people: List<String>,
    val places: List<String>,
    val urls: List<String>,
    val event: ExtractedEvent?,
)
