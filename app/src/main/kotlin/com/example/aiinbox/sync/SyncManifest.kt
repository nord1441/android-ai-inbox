package com.example.aiinbox.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format for `appData/manifest.json` — the single source of truth for
 * "what items exist on Drive". Each sync run pulls the manifest first
 * (with an `If-None-Match: <last_etag>`) and computes the diff against the
 * local DB, then publishes a new manifest reflecting post-sync state.
 */
@Serializable
data class SyncManifest(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("generated_at") val generatedAt: Long,
    val items: List<ManifestItem>,
) {
    @Serializable
    data class ManifestItem(
        val id: String,
        @SerialName("updated_at") val updatedAt: Long,
        @SerialName("deleted_at") val deletedAt: Long? = null,
        @SerialName("attachment_ids") val attachmentIds: List<String> = emptyList(),
        @SerialName("attachment_byte_sizes") val attachmentByteSizes: List<Long> = emptyList(),
    )
}
