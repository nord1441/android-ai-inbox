package com.example.aiinbox.sync

import com.example.aiinbox.data.db.AttachmentDao
import com.example.aiinbox.data.db.InboxDao
import com.example.aiinbox.data.repository.InboxRepository
import com.example.aiinbox.data.storage.EncryptedImageStore
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sync orchestration: a pure-function differ ([diff], in the companion
 * object) plus instance methods that drive the apply phases against the
 * Drive REST client and the local DB.
 *
 * "Last-Write-Wins" with tombstone awareness: each item's effective
 * timestamp is `max(updated_at, deleted_at)`, so a fresh delete on one
 * side beats an older edit on the other.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val api: DriveApiClient,
    private val repository: InboxRepository,
    private val imageStore: EncryptedImageStore,
    private val inboxDao: InboxDao,
    private val attachmentDao: AttachmentDao,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Subset of inbox_items used by the differ. */
    data class LocalRef(
        val id: String,
        val updatedAt: Long,
        val deletedAt: Long?,
        val lastSyncedAt: Long?,
    )

    data class Diff(
        val push: List<String>,   // ids whose local state must replace Drive state
        val pull: List<String>,   // ids whose Drive state must replace local state
        val skip: List<String>,   // ids in agreement
    )

    /**
     * Pull and apply each id in [idsToPull]. [fileIdLookup] maps the wire-format
     * filename (`items/{id}.json` and `attachments/{att_id}.bin`) to the Drive
     * file id, populated upstream by the SyncWorker via a single Drive list call.
     */
    suspend fun applyPull(idsToPull: List<String>, fileIdLookup: Map<String, String>) {
        for (id in idsToPull) {
            val itemFileId = fileIdLookup["items/$id.json"] ?: continue
            val body = api.downloadBytes(itemFileId) as? DriveApiClient.DownloadResult.Body
                ?: continue
            val env = json.decodeFromString(SyncEnvelope.serializer(), body.bytes.decodeToString())
            repository.upsertFromSync(env)
            for (att in env.attachments) {
                if (att.deletedAt != null) continue
                val attFileId = fileIdLookup["attachments/${att.id}.bin"] ?: continue
                val attBody = api.downloadBytes(attFileId) as? DriveApiClient.DownloadResult.Body
                    ?: continue
                imageStore.writeWithName(att.encryptedFilename, attBody.bytes)
            }
        }
    }

    /**
     * Build a fresh manifest reflecting current local state (including
     * tombstones). Called by [SyncWorker] after applyPull/applyPush so the
     * published manifest is the post-sync ground truth.
     */
    suspend fun buildManifest(generatedAt: Long): SyncManifest {
        val refs = inboxDao.allRefsIncludingDeleted()
        val items = refs.map { ref ->
            val atts = attachmentDao.listForItemIncludingDeleted(ref.id)
            SyncManifest.ManifestItem(
                id = ref.id,
                updatedAt = ref.updatedAt,
                deletedAt = ref.deletedAt,
                attachmentIds = atts.map { it.id },
                attachmentByteSizes = atts.map { it.byteSize },
            )
        }
        return SyncManifest(generatedAt = generatedAt, items = items)
    }

    /**
     * Push each id in [idsToPush]: upload the envelope JSON (creating or
     * updating per existing file presence) and any non-tombstoned attachment
     * binaries whose remote size differs from the local bytes.
     */
    suspend fun applyPush(idsToPush: List<String>) {
        for (id in idsToPush) {
            val full = repository.getEnvelope(id) ?: continue
            val itemBytes = json.encodeToString(SyncEnvelope.serializer(), full).encodeToByteArray()
            val itemName = "items/$id.json"
            val existing = api.findFileByName(itemName)
            if (existing == null) {
                api.createFile(itemName, itemBytes, "application/json")
            } else {
                api.updateFileBytes(existing.id, itemBytes, "application/json")
            }
            for (att in full.attachments.filter { it.deletedAt == null }) {
                val attName = "attachments/${att.id}.bin"
                val attBytes = imageStore.readBytes(att.encryptedFilename) ?: continue
                val existingAtt = api.findFileByName(attName)
                if (existingAtt == null) {
                    api.createFile(attName, attBytes, att.mimeType)
                } else if (existingAtt.size != attBytes.size.toLong()) {
                    api.updateFileBytes(existingAtt.id, attBytes, att.mimeType)
                }
            }
        }
    }

    companion object {

        private fun ts(updatedAt: Long, deletedAt: Long?): Long =
            maxOf(updatedAt, deletedAt ?: 0L)

        fun diff(local: List<LocalRef>, remote: List<SyncManifest.ManifestItem>): Diff {
            val push = mutableListOf<String>()
            val pull = mutableListOf<String>()
            val skip = mutableListOf<String>()
            val byIdLocal = local.associateBy { it.id }
            val byIdRemote = remote.associateBy { it.id }
            val allIds = (byIdLocal.keys + byIdRemote.keys).sorted()
            for (id in allIds) {
                val l = byIdLocal[id]
                val r = byIdRemote[id]
                when {
                    l != null && r == null -> push += id
                    l == null && r != null -> pull += id
                    l != null && r != null -> {
                        val lts = ts(l.updatedAt, l.deletedAt)
                        val rts = ts(r.updatedAt, r.deletedAt)
                        when {
                            rts > lts -> pull += id
                            lts > rts -> push += id
                            else -> skip += id      // tie → already in sync
                        }
                    }
                    else -> error("unreachable")
                }
            }
            return Diff(push, pull, skip)
        }
    }
}
