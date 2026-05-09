package uk.nordtek.aiinbox.llm

import uk.nordtek.aiinbox.data.db.ExtractedEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeLlmEngine @Inject constructor() : LlmEngine {
    private val _isLoaded = MutableStateFlow(false)
    override val isLoaded: StateFlow<Boolean> = _isLoaded

    override suspend fun ensureLoaded(variant: ModelVariant) {
        _isLoaded.value = true
    }

    override suspend fun unload() {
        _isLoaded.value = false
    }

    override suspend fun summarize(text: String, hint: ContentHint): SummarizeResult {
        val title = "[Fake] ${text.take(20).replace("\n", " ")}"
        val summary = "[Fake要約] hint=$hint, length=${text.length}"
        val category = when (hint) {
            ContentHint.WEB_ARTICLE -> "ニュース"
            ContentHint.CHAT_OR_EMAIL -> "仕事"
            ContentHint.MEMO -> "個人"
            ContentHint.SCREENSHOT -> "その他"
            ContentHint.IMAGE_OCR -> "その他"
            ContentHint.UNKNOWN -> "その他"
        }
        val event = if (text.contains("__FAKE_EVENT__")) {
            ExtractedEvent(
                title = "打ち合わせ（Fake）",
                startMillis = System.currentTimeMillis() + 24 * 3600_000L,
                endMillis = System.currentTimeMillis() + 25 * 3600_000L,
                location = "Fake Office",
                confidence = 0.9f,
            )
        } else null
        return SummarizeResult(
            title = title,
            summary = summary,
            category = category,
            tags = listOf("fake", hint.name.lowercase()),
            people = emptyList(),
            places = emptyList(),
            urls = emptyList(),
            event = event,
        )
    }
}
