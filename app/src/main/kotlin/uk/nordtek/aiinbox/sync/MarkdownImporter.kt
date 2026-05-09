package uk.nordtek.aiinbox.sync

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Markdown bytes → MarkdownEnvelope + body summary. Pure: no I/O. The
 * caller (FsSyncEngine) decides what to do with the parse result.
 *
 * `strictMode = false` so unknown YAML fields (a forward-compat
 * `schema_version`, an editor-added comment, etc.) skip rather than
 * fail the import.
 */
@Singleton
class MarkdownImporter @Inject constructor() {

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    sealed interface ParseResult {
        data class Success(val envelope: MarkdownEnvelope, val summaryBody: String) : ParseResult
        data class Failure(val reason: String) : ParseResult
    }

    fun parse(bytes: ByteArray): ParseResult {
        val text = bytes.toString(Charsets.UTF_8)
        val (front, body) = splitFrontmatter(text) ?: return ParseResult.Failure(
            "missing frontmatter delimiters"
        )
        val envelope = try {
            yaml.decodeFromString(MarkdownEnvelope.serializer(), front)
        } catch (e: YamlException) {
            return ParseResult.Failure("YAML parse error: ${e.message}")
        }
        if (envelope.id.isBlank()) return ParseResult.Failure("envelope id is blank")
        return ParseResult.Success(envelope, body.trim())
    }

    /**
     * Split a Markdown text into (frontmatter YAML, body) at the
     * `---` delimiters. Returns null if the structure is invalid.
     */
    private fun splitFrontmatter(text: String): Pair<String, String>? {
        if (!text.startsWith("---")) return null
        val firstNewline = text.indexOf('\n')
        if (firstNewline < 0) return null
        val rest = text.substring(firstNewline + 1)
        val endIdx = rest.indexOf("\n---")
        if (endIdx < 0) return null
        val front = rest.substring(0, endIdx)
        val afterDelim = rest.substring(endIdx + "\n---".length)
        val body = afterDelim.removePrefix("\n").let {
            if (it.startsWith("\n")) it.substring(1) else it
        }
        return front to body
    }
}
