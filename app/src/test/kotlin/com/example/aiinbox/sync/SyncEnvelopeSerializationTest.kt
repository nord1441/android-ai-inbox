package com.example.aiinbox.sync

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncEnvelopeSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun roundTrip_fullPayload() {
        val original = SyncEnvelope(
            id = "it-1",
            originalText = "hello",
            sourceApp = "test",
            receivedAt = 100L,
            status = "COMPLETED",
            title = "t",
            summary = "s",
            category = "cat",
            tags = listOf("a", "b"),
            people = listOf("alice"),
            event = SyncEnvelope.EnvelopeEvent(
                title = "meet",
                startMillis = 200L,
                location = "office",
                confidence = 0.9f,
            ),
            updatedAt = 100L,
            attachments = listOf(
                SyncEnvelope.EnvelopeAttachment(
                    id = "att-1", itemId = "it-1",
                    encryptedFilename = "enc-1", mimeType = "image/png",
                    byteSize = 100L, kind = "IMAGE", ordering = 0, createdAt = 100L,
                ),
            ),
        )
        val text = json.encodeToString(SyncEnvelope.serializer(), original)
        val decoded = json.decodeFromString(SyncEnvelope.serializer(), text)
        assertEquals(original, decoded)
    }

    @Test
    fun decode_ignoresAdditionalFields() {
        val text = """
            {"id":"x","status":"PENDING","received_at":1,"updated_at":1,"new_field":42}
        """.trimIndent()
        val decoded = json.decodeFromString(SyncEnvelope.serializer(), text)
        assertEquals("x", decoded.id)
    }
}
