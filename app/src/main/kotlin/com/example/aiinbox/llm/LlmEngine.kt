package com.example.aiinbox.llm

import kotlinx.coroutines.flow.StateFlow

enum class ModelVariant {
    FAKE,
    /**
     * Gemma 3 1B IT, q4 block128, effective context 4096.
     * MediaPipe Tasks GenAI compatible bundle (~689 MB).
     *
     * Note: distribution is HuggingFace-gated (Gemma license click-through).
     * Plan 2 originally targeted Gemma 4 but its only Android distribution
     * is .litertlm (LiteRT-LM API), not MediaPipe Tasks GenAI bundles.
     * Gemma 3 1B is the largest readily-available MediaPipe-compatible
     * Gemma model.
     */
    GEMMA_3_1B,
}

interface LlmEngine {
    val isLoaded: StateFlow<Boolean>
    suspend fun ensureLoaded(variant: ModelVariant)
    suspend fun unload()
    suspend fun summarize(text: String, hint: ContentHint): SummarizeResult
}
