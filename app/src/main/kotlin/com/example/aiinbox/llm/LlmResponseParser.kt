package com.example.aiinbox.llm

import com.example.aiinbox.data.db.ExtractedEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.ZoneId

class LlmResponseParser(private val zone: ZoneId) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): SummarizeResult? {
        val jsonStr = extractJson(raw) ?: return null
        return try {
            val raw = json.decodeFromString<RawSummarizeResult>(jsonStr)
            val event = raw.event?.let { ev ->
                val start = TimeConverter.parseToMillis(ev.start_iso, zone)
                val end = TimeConverter.parseToMillis(ev.end_iso, zone)
                ExtractedEvent(
                    title = ev.title,
                    startMillis = start?.millis,
                    endMillis = end?.millis,
                    location = ev.location,
                    confidence = ev.confidence,
                )
            }
            SummarizeResult(
                title = raw.title,
                summary = raw.summary,
                category = raw.category,
                tags = raw.tags,
                people = raw.people,
                places = raw.places,
                urls = raw.urls,
                event = event,
            )
        } catch (e: Exception) {
            null
        }
    }

    /** raw 文字列の中からJSONらしき部分を抜き出す（コードフェンスや前置きを除去） */
    private fun extractJson(raw: String): String? {
        if (raw.isBlank()) return null
        // コードフェンス内（```json ... ``` または ``` ... ```）優先
        val fence = Regex("""```(?:json)?\s*(\{[\s\S]*?\})\s*```""").find(raw)
        if (fence != null) return fence.groupValues[1]
        // 最初の `{` から最後の `}` までを試す
        val first = raw.indexOf('{')
        val last = raw.lastIndexOf('}')
        if (first >= 0 && last > first) return raw.substring(first, last + 1)
        return null
    }

    @Serializable
    private data class RawSummarizeResult(
        val title: String? = null,
        val summary: String? = null,
        val category: String? = null,
        val tags: List<String> = emptyList(),
        val people: List<String> = emptyList(),
        val places: List<String> = emptyList(),
        val urls: List<String> = emptyList(),
        val event: RawEvent? = null,
    )

    @Serializable
    private data class RawEvent(
        val title: String,
        val start_iso: String? = null,
        val end_iso: String? = null,
        val location: String? = null,
        val confidence: Float = 0.5f,
    )
}
