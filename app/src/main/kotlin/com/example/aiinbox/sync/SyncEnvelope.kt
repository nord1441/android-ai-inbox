package com.example.aiinbox.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format for `appData/items/{id}.json`. A flat mirror of the
 * inbox_items row plus the embedded ExtractedEvent. Fields use snake_case
 * to match Drive convention and keep the JSON readable in raw form.
 */
@Serializable
data class SyncEnvelope(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val id: String,
    @SerialName("original_text") val originalText: String? = null,
    @SerialName("original_subject") val originalSubject: String? = null,
    @SerialName("source_app") val sourceApp: String? = null,
    @SerialName("received_at") val receivedAt: Long,
    val status: String,
    @SerialName("processing_attempts") val processingAttempts: Int = 0,
    @SerialName("last_error") val lastError: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val people: List<String> = emptyList(),
    val places: List<String> = emptyList(),
    val urls: List<String> = emptyList(),
    val event: EnvelopeEvent? = null,
    @SerialName("user_edited_fields") val userEditedFields: List<String> = emptyList(),
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
    val attachments: List<EnvelopeAttachment> = emptyList(),
) {
    @Serializable
    data class EnvelopeEvent(
        val title: String,
        @SerialName("start_millis") val startMillis: Long? = null,
        @SerialName("end_millis") val endMillis: Long? = null,
        val location: String? = null,
        val confidence: Float = 0.0f,
    )

    @Serializable
    data class EnvelopeAttachment(
        val id: String,
        @SerialName("item_id") val itemId: String,
        @SerialName("encrypted_filename") val encryptedFilename: String,
        @SerialName("mime_type") val mimeType: String,
        @SerialName("width_px") val widthPx: Int = 0,
        @SerialName("height_px") val heightPx: Int = 0,
        @SerialName("byte_size") val byteSize: Long,
        val kind: String,
        val ordering: Int,
        @SerialName("ocr_text") val ocrText: String? = null,
        @SerialName("ocr_completed_at") val ocrCompletedAt: Long? = null,
        @SerialName("created_at") val createdAt: Long,
        @SerialName("deleted_at") val deletedAt: Long? = null,
    )
}
