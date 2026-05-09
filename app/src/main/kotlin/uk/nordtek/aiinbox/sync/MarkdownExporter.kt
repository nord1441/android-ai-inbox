package uk.nordtek.aiinbox.sync

import com.charleskorn.kaml.SequenceStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import uk.nordtek.aiinbox.data.db.Attachment
import uk.nordtek.aiinbox.data.db.InboxItem
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DB row → Markdown bytes. Pure: no I/O, no Android dependencies, fully
 * unit-testable. The shape of the produced bytes (frontmatter + body) is
 * specified in docs/superpowers/specs/2026-05-04-fs-markdown-sync-design.md.
 */
@Singleton
class MarkdownExporter @Inject constructor(
    private val zone: ZoneId,
) {
    private val yaml = Yaml(
        configuration = YamlConfiguration(
            encodeDefaults = true,
            // Block sequences and nested mappings render as Obsidian / plain
            // editors expect.
            sequenceStyle = SequenceStyle.Block,
        )
    )

    fun encode(item: InboxItem, attachments: List<Attachment>): ByteArray {
        val envelope = if (item.deletedAt != null) {
            tombstoneEnvelope(item)
        } else {
            fullEnvelope(item, attachments)
        }
        val front = yaml.encodeToString(MarkdownEnvelope.serializer(), envelope)
        val body = if (item.deletedAt != null) "" else (item.summary.orEmpty())
        val text = buildString {
            append("---\n")
            append(front)
            if (!front.endsWith("\n")) append('\n')
            append("---\n\n")
            append(body)
            if (body.isNotEmpty() && !body.endsWith("\n")) append('\n')
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    /** Filename a writer should use for [item]: `YYYY-MM-DD-<id>.md`. */
    fun filenameFor(item: InboxItem): String {
        val date = Instant.ofEpochMilli(item.receivedAt).atZone(zone).toLocalDate().toString()
        return "$date-${item.id}.md"
    }

    /** Filename a writer should use for an attachment binary. */
    fun attachmentFilename(att: Attachment): String {
        val ext = mimeToExt(att.mimeType)
        return "${att.id}.$ext"
    }

    private fun tombstoneEnvelope(item: InboxItem) = MarkdownEnvelope(
        id = item.id,
        receivedAt = formatInstant(item.receivedAt),
        updatedAt = formatInstant(item.updatedAt),
        status = "DELETED",
        deletedAt = item.deletedAt?.let(::formatInstant),
    )

    private fun fullEnvelope(item: InboxItem, attachments: List<Attachment>) = MarkdownEnvelope(
        id = item.id,
        receivedAt = formatInstant(item.receivedAt),
        updatedAt = formatInstant(item.updatedAt),
        status = item.status.name,
        deletedAt = null,
        title = item.title,
        category = item.category,
        tags = item.tags,
        people = item.people,
        places = item.places,
        urls = item.urls,
        event = item.event?.let {
            MarkdownEnvelope.EnvelopeEvent(
                title = it.title,
                start = it.startMillis?.let(::formatInstant),
                end = it.endMillis?.let(::formatInstant),
                location = it.location,
                confidence = it.confidence,
            )
        },
        sourceApp = item.sourceApp,
        attachments = attachments.map {
            MarkdownEnvelope.EnvelopeAttachment(
                id = it.id,
                file = "attachments/${attachmentFilename(it)}",
                mime = it.mimeType,
                widthPx = it.widthPx,
                heightPx = it.heightPx,
                byteSize = it.byteSize,
                ocrText = it.ocrText,
            )
        },
    )

    private fun formatInstant(epochMs: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone).toString()

    private fun mimeToExt(mime: String): String = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "bin"
    }
}
