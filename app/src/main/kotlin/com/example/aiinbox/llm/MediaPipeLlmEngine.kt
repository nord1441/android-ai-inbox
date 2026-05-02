package com.example.aiinbox.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [LlmEngine] backed by MediaPipe LLM Inference API (tasks-genai 0.10.20).
 *
 * API note: In tasks-genai 0.10.20 temperature/topK live on [LlmInferenceSessionOptions],
 * not on [LlmInferenceOptions]. We create a fresh [LlmInferenceSession] per
 * [summarize] call so each request gets the correct sampling parameters.
 */
@Singleton
class MediaPipeLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
    private val promptBuilder: PromptBuilder,
    private val responseParser: LlmResponseParser,
) : LlmEngine {

    private val _isLoaded = MutableStateFlow(false)
    override val isLoaded: StateFlow<Boolean> = _isLoaded

    private var inference: LlmInference? = null
    private var loadedVariant: ModelVariant? = null
    private val mutex = Mutex()

    override suspend fun ensureLoaded(variant: ModelVariant): Unit = mutex.withLock {
        if (_isLoaded.value && loadedVariant == variant) return
        if (_isLoaded.value && loadedVariant != variant) {
            // Different variant already loaded — unload first.
            unloadInternal()
        }
        check(modelManager.isModelPresent(variant)) {
            "Model file is not present for $variant"
        }
        val modelPath = modelManager.modelFilePath(variant).absolutePath
        withContext(Dispatchers.IO) {
            val opts = LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(MAX_TOKENS_DEFAULT)
                .setMaxTopK(TOP_K_DEFAULT)
                .build()
            inference = LlmInference.createFromOptions(context, opts)
        }
        loadedVariant = variant
        _isLoaded.value = true
    }

    override suspend fun unload(): Unit = mutex.withLock {
        unloadInternal()
    }

    private fun unloadInternal() {
        inference?.close()
        inference = null
        loadedVariant = null
        _isLoaded.value = false
    }

    override suspend fun summarize(text: String, hint: ContentHint): SummarizeResult {
        check(_isLoaded.value) { "ensureLoaded() must be called before summarize()" }
        val prompt = promptBuilder.build(text, hint)

        val rawResponse = withContext(Dispatchers.IO) {
            generateWithSession(prompt)
        }

        // On parse failure, retry once with a simpler fallback prompt.
        val parsed = responseParser.parse(rawResponse)
        if (parsed != null) return parsed

        val fallbackPrompt = "次のテキストを200文字以内で要約してください。出力は要約文のみ：\n\n${text.take(4000)}"
        val fallback = withContext(Dispatchers.IO) { generateWithSession(fallbackPrompt) }
        return SummarizeResult(
            title = null,
            summary = fallback.trim().take(200),
            category = null,
            tags = emptyList(),
            people = emptyList(),
            places = emptyList(),
            urls = emptyList(),
            event = null,
        )
    }

    /**
     * Creates a fresh [LlmInferenceSession] with the desired sampling params,
     * generates a response, then closes the session.
     *
     * Must be called from a background thread (Dispatchers.IO).
     */
    private fun generateWithSession(prompt: String): String {
        val sessionOpts = LlmInferenceSessionOptions.builder()
            .setTopK(TOP_K_DEFAULT)
            .setTemperature(TEMPERATURE_DEFAULT)
            .build()
        return LlmInferenceSession.createFromOptions(inference!!, sessionOpts).use { session ->
            session.addQueryChunk(prompt)
            session.generateResponse()
        }
    }

    /** OOM / timeout recovery: retry with context halved. */
    suspend fun summarizeWithReducedContext(text: String, hint: ContentHint): SummarizeResult {
        val truncated = text.take(text.length / 2)
        return summarize(truncated, hint)
    }

    companion object {
        private const val MAX_TOKENS_DEFAULT = 1024
        private const val TOP_K_DEFAULT = 40
        private const val TEMPERATURE_DEFAULT = 0.3f
    }
}
