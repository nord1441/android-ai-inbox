package com.example.aiinbox.llm

import kotlinx.coroutines.flow.StateFlow

enum class ModelVariant {
    FAKE,
    /** Gemma 4 E2B IT in `.litertlm` format. ~2.4 GB. For 6–8 GB RAM devices. */
    GEMMA_4_E2B,
    /** Gemma 4 E4B IT in `.litertlm` format. ~3.4 GB. For 8 GB+ RAM devices. */
    GEMMA_4_E4B,
}

interface LlmEngine {
    val isLoaded: StateFlow<Boolean>
    suspend fun ensureLoaded(variant: ModelVariant)
    suspend fun unload()
    suspend fun summarize(text: String, hint: ContentHint): SummarizeResult
}
