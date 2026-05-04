package com.example.aiinbox.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Round-trip representation of an inbox row + its attachments as it appears
 * inside a `.md` file's YAML frontmatter. The Markdown body holds only
 * `summary`; everything else lives here.
 *
 * Tombstoned rows are encoded with `status = "DELETED"` and most fields
 * left null — the importer's first job is to look at status before
 * trying to parse the rest.
 */
@Serializable
data class MarkdownEnvelope(
    val id: String,
    @SerialName("received_at") val receivedAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val status: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    val title: String? = null,
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val people: List<String> = emptyList(),
    val places: List<String> = emptyList(),
    val urls: List<String> = emptyList(),
    val event: EnvelopeEvent? = null,
    @SerialName("source_app") val sourceApp: String? = null,
    val attachments: List<EnvelopeAttachment> = emptyList(),
) {
    @Serializable
    data class EnvelopeEvent(
        val title: String,
        val start: String? = null,
        val end: String? = null,
        val location: String? = null,
        val confidence: Float = 0f,
    )

    @Serializable
    data class EnvelopeAttachment(
        val id: String,
        val file: String,
        val mime: String,
        @SerialName("width_px") val widthPx: Int = 0,
        @SerialName("height_px") val heightPx: Int = 0,
        @SerialName("byte_size") val byteSize: Long = 0,
        @SerialName("ocr_text") val ocrText: String? = null,
    )
}
