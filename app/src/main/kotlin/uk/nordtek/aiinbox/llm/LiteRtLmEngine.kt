package uk.nordtek.aiinbox.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
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
 * Production [LlmEngine] backed by Google AI Edge's LiteRT-LM Android API.
 *
 * Replaced the previous MediaPipe Tasks GenAI implementation because Gemma 4
 * is distributed as `.litertlm` containers (with `LITERTLM` magic), not as
 * MediaPipe `.task` zip bundles. LiteRT-LM is the official runtime for
 * `.litertlm` files on Android.
 *
 * The engine loads the model once (a multi-second operation), then exposes
 * `summarize(...)` which spins up a per-call conversation and collects the
 * streamed token output back into a single `SummarizeResult`.
 */
@Singleton
class LiteRtLmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
    private val promptBuilder: PromptBuilder,
    private val responseParser: LlmResponseParser,
) : LlmEngine {

    private val _isLoaded = MutableStateFlow(false)
    override val isLoaded: StateFlow<Boolean> = _isLoaded

    private var engine: Engine? = null
    private var loadedVariant: ModelVariant? = null
    private val mutex = Mutex()

    override suspend fun ensureLoaded(variant: ModelVariant): Unit = mutex.withLock {
        if (_isLoaded.value && loadedVariant == variant) return
        if (_isLoaded.value && loadedVariant != variant) {
            unloadInternal()
        }
        check(modelManager.isModelPresent(variant)) {
            "Model file not present for $variant at ${modelManager.modelFilePath(variant)}"
        }
        val modelPath = modelManager.modelFilePath(variant).absolutePath

        withContext(Dispatchers.IO) {
            val cfg = EngineConfig(modelPath = modelPath, backend = Backend.CPU())
            val newEngine = Engine(cfg)
            newEngine.initialize()
            engine = newEngine
        }
        loadedVariant = variant
        _isLoaded.value = true
    }

    override suspend fun unload(): Unit = mutex.withLock {
        unloadInternal()
    }

    private fun unloadInternal() {
        runCatching { engine?.close() }
        engine = null
        loadedVariant = null
        _isLoaded.value = false
    }

    override suspend fun summarize(text: String, hint: ContentHint): SummarizeResult {
        check(_isLoaded.value) { "ensureLoaded() must be called before summarize()" }
        val prompt = promptBuilder.build(text, hint)

        val raw = generate(prompt, label = "structured")
        responseParser.parse(raw)?.let { return it }

        // Parse failed → an extra fallback round-trip is about to run. Log the raw
        // output (truncated) so the next session can diagnose how the model
        // deviated from the JSON schema.
        android.util.Log.w(
            TAG,
            "structured parse failed; running fallback. rawLen=${raw.length} " +
                "rawHead=${raw.take(400).replace('\n', ' ')} " +
                "rawTail=${raw.takeLast(200).replace('\n', ' ')}",
        )

        // Fallback: simpler prompt for plain summary if structured parse fails.
        val fallbackPrompt =
            "次のテキストを200文字以内で要約してください。出力は要約文のみ：\n\n${text.take(4000)}"
        val fallback = generate(fallbackPrompt, label = "fallback")
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

    private suspend fun generate(prompt: String, label: String): String = withContext(Dispatchers.IO) {
        val current = engine
            ?: error("Engine not initialized — ensureLoaded() must complete first")
        val cfg = ConversationConfig(systemInstruction = Contents.of(SYSTEM_INSTRUCTION))
        val out = StringBuilder()
        val startNs = System.nanoTime()
        var firstTokenNs = -1L
        var tokenCount = 0
        var lastLogNs = startNs
        android.util.Log.i(TAG, "generate[$label] start. promptLen=${prompt.length}")
        current.createConversation(cfg).use { conversation ->
            conversation.sendMessageAsync(prompt).collect { token ->
                if (firstTokenNs < 0L) {
                    firstTokenNs = System.nanoTime()
                    val ttftMs = (firstTokenNs - startNs) / 1_000_000
                    android.util.Log.i(TAG, "generate[$label] TTFT=${ttftMs}ms (prefill done)")
                }
                out.append(token)
                tokenCount++
                val now = System.nanoTime()
                if (now - lastLogNs > PROGRESS_LOG_INTERVAL_NS) {
                    val sinceFirstMs = (now - firstTokenNs) / 1_000_000
                    val tps = if (sinceFirstMs > 0) tokenCount * 1000.0 / sinceFirstMs else 0.0
                    android.util.Log.i(
                        TAG,
                        "generate[$label] progress: tokens=$tokenCount " +
                            "sinceFirstMs=${sinceFirstMs} tps=${"%.2f".format(tps)} chars=${out.length}",
                    )
                    lastLogNs = now
                }
            }
        }
        val endNs = System.nanoTime()
        val totalMs = (endNs - startNs) / 1_000_000
        val ttftMs = if (firstTokenNs > 0L) (firstTokenNs - startNs) / 1_000_000 else -1L
        val genMs = if (firstTokenNs > 0L) (endNs - firstTokenNs) / 1_000_000 else 0L
        val tps = if (genMs > 0) tokenCount * 1000.0 / genMs else 0.0
        android.util.Log.i(
            TAG,
            "generate[$label] done. tokens=$tokenCount totalMs=$totalMs ttftMs=$ttftMs " +
                "genMs=$genMs tps=${"%.2f".format(tps)} chars=${out.length}",
        )
        out.toString()
    }

    /** OOM/timeout fallback: re-run summarization with halved input length. */
    suspend fun summarizeWithReducedContext(text: String, hint: ContentHint): SummarizeResult {
        return summarize(text.take(text.length / 2), hint)
    }

    companion object {
        private const val TAG = "LiteRtLmEngine"
        private const val PROGRESS_LOG_INTERVAL_NS = 5_000_000_000L  // 5 seconds
        private const val SYSTEM_INSTRUCTION =
            "You are a Japanese-language summarization and structured-extraction assistant. " +
                "Always respond in Japanese unless the user requests otherwise. " +
                "When the user asks for JSON output, return raw JSON only — no markdown fences, " +
                "no commentary."
    }
}
