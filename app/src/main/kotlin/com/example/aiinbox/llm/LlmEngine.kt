package com.example.aiinbox.llm

import kotlinx.coroutines.flow.StateFlow

enum class ModelVariant { FAKE, GEMMA_4_E2B, GEMMA_4_E4B }

interface LlmEngine {
    val isLoaded: StateFlow<Boolean>
    suspend fun ensureLoaded(variant: ModelVariant)
    suspend fun unload()
    suspend fun summarize(text: String, hint: ContentHint): SummarizeResult
}
