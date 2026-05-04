package com.example.aiinbox.sync

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncManifestSerializationTest {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    @Test
    fun roundTrip_preservesSchemaVersionAndItems() {
        val original = SyncManifest(
            generatedAt = 1746345600000L,
            items = listOf(
                SyncManifest.ManifestItem(
                    id = "it-1",
                    updatedAt = 1746345500000L,
                    deletedAt = null,
                    attachmentIds = listOf("att-1"),
                    attachmentByteSizes = listOf(482311L),
                ),
                SyncManifest.ManifestItem(
                    id = "it-2",
                    updatedAt = 1746345400000L,
                    deletedAt = 1746345450000L,
                    attachmentIds = emptyList(),
                    attachmentByteSizes = emptyList(),
                ),
            ),
        )
        val text = json.encodeToString(SyncManifest.serializer(), original)
        val decoded = json.decodeFromString(SyncManifest.serializer(), text)
        assertEquals(original, decoded)
        assertEquals(1, decoded.schemaVersion)
    }

    @Test
    fun decode_acceptsUnknownFields() {
        val text = """
            {"schema_version":1,"generated_at":100,"items":[],"future_field":"ignored"}
        """.trimIndent()
        val decoded = json.decodeFromString(SyncManifest.serializer(), text)
        assertEquals(0, decoded.items.size)
    }
}
