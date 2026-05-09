package uk.nordtek.aiinbox.sync

import androidx.documentfile.provider.DocumentFile
import uk.nordtek.aiinbox.data.db.Attachment
import uk.nordtek.aiinbox.data.db.AttachmentKind
import uk.nordtek.aiinbox.data.db.ExtractedEvent
import uk.nordtek.aiinbox.data.db.InboxItem
import uk.nordtek.aiinbox.data.db.InboxRefRow
import uk.nordtek.aiinbox.data.db.ItemStatus
import uk.nordtek.aiinbox.data.repository.InboxRepository
import uk.nordtek.aiinbox.data.storage.EncryptedImageStore
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FsSyncEngine @Inject constructor(
    private val repository: InboxRepository,
    private val imageStore: EncryptedImageStore,
    private val saf: SafFolderAccess,
    private val exporter: MarkdownExporter,
    private val importer: MarkdownImporter,
) {

    /** Pre-classified disk view: id → (filename → tombstone?). */
    data class RemoteRef(val id: String, val isTombstone: Boolean, val doc: DocumentFile)

    data class Diff(
        val export: List<String>,
        val import: List<String>,
        val softDeleteLocally: List<String>,
        val reExportTombstone: List<String>,
        val skip: List<String>,
    )

    /**
     * Orchestrate one sync run: list disk → diff → apply each bucket.
     */
    suspend fun runOnce(treeUri: String): SyncRunStats {
        val docs = saf.listMarkdownFiles(treeUri)
        val parsedRemote = mutableListOf<RemoteRef>()
        for (doc in docs) {
            val bytes = runCatching { saf.readBytes(doc) }.getOrNull() ?: continue
            val parsed = importer.parse(bytes)
            if (parsed is MarkdownImporter.ParseResult.Success) {
                parsedRemote += RemoteRef(parsed.envelope.id, parsed.envelope.status == "DELETED", doc)
            } else {
                android.util.Log.w(TAG, "skipping unparseable file ${doc.name}: ${(parsed as MarkdownImporter.ParseResult.Failure).reason}")
            }
        }
        val local = repository.allLocalRefs()
        val remoteRefs = parsedRemote.map { RemoteIdRef(it.id, it.isTombstone) }
        val diff = diff(local, remoteRefs)

        val byId = parsedRemote.associateBy { it.id }
        for (id in diff.export) exportOne(treeUri, id)
        for (id in diff.import) importOne(treeUri, byId.getValue(id))
        for (id in diff.softDeleteLocally) repository.softDelete(id)
        for (id in diff.reExportTombstone) exportOne(treeUri, id)

        return SyncRunStats(
            exported = diff.export.size,
            imported = diff.import.size,
            softDeletedLocally = diff.softDeleteLocally.size,
            reExportedTombstone = diff.reExportTombstone.size,
            skipped = diff.skip.size,
        )
    }

    private suspend fun exportOne(treeUri: String, id: String) {
        val full = repository.getWithAttachmentsIncludingDeleted(id) ?: return
        val item = full.item
        val bytes = exporter.encode(item, full.attachments)
        val name = exporter.filenameFor(item)

        // Use the root tree directly so existing-file replacement works.
        val root = saf.resolveTreeRoot(treeUri)
        saf.writeAtomically(root, name, "text/markdown", bytes)

        // Attachment binaries.
        if (item.deletedAt == null && full.attachments.isNotEmpty()) {
            val attDir = saf.attachmentsDir(treeUri)
            for (att in full.attachments) {
                val bytesAtt = imageStore.readBytes(att.encryptedFilename) ?: continue
                saf.writeAtomically(attDir, exporter.attachmentFilename(att), att.mimeType, bytesAtt)
            }
        } else if (item.deletedAt != null) {
            // Tombstone: also remove attachment files from disk so the bytes
            // don't linger.
            runCatching {
                val attDir = saf.attachmentsDir(treeUri)
                for (att in full.attachments) {
                    saf.deleteByName(attDir, exporter.attachmentFilename(att))
                }
            }
        }
    }

    private suspend fun importOne(treeUri: String, remote: RemoteRef) {
        val parsed = importer.parse(saf.readBytes(remote.doc)) as? MarkdownImporter.ParseResult.Success ?: return
        val env = parsed.envelope
        val item = envelopeToItem(env, parsed.summaryBody)

        // Pre-resolve every attachment binary BEFORE writing anything. If any
        // file is missing (e.g. Syncthing has mirrored the .md but not the
        // binaries yet), bail out so the row isn't inserted with a partial
        // attachment list — next periodic sync will retry.
        val resolved = mutableListOf<Pair<MarkdownEnvelope.EnvelopeAttachment, ByteArray>>()
        if (env.attachments.isNotEmpty()) {
            val attDir = runCatching { saf.attachmentsDir(treeUri) }.getOrNull()
            if (attDir == null) {
                android.util.Log.w(TAG, "import skipped for ${env.id}: cannot access attachments/")
                return
            }
            val byName = attDir.listFiles().associateBy { it.name }
            for (envAtt in env.attachments) {
                val ext = envAtt.file.substringAfterLast('.', "bin")
                val doc = byName["${envAtt.id}.$ext"]
                if (doc == null) {
                    android.util.Log.w(TAG, "import deferred for ${env.id}: missing attachment ${envAtt.id}.$ext")
                    return
                }
                val bytes = runCatching { saf.readBytes(doc) }.getOrNull()
                if (bytes == null) {
                    android.util.Log.w(TAG, "import deferred for ${env.id}: cannot read ${envAtt.id}.$ext")
                    return
                }
                resolved += envAtt to bytes
            }
        }

        // All binaries present — re-encrypt locally and persist.
        val attachments = resolved.mapIndexed { idx, (envAtt, attBytes) ->
            val encName = "${envAtt.id}.${mimeToExt(envAtt.mime)}.enc"
            imageStore.writeWithName(encName, attBytes)
            Attachment(
                id = envAtt.id,
                itemId = item.id,
                ordering = idx,
                kind = AttachmentKind.SHARED_IMAGE,
                encryptedFilename = encName,
                mimeType = envAtt.mime,
                widthPx = envAtt.widthPx,
                heightPx = envAtt.heightPx,
                byteSize = envAtt.byteSize,
                ocrText = envAtt.ocrText,
                ocrCompletedAt = null,
                createdAt = item.receivedAt,
            )
        }
        repository.insertFromFile(item, attachments)
    }

    private fun mimeToExt(mime: String): String = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "bin"
    }

    private fun envelopeToItem(env: MarkdownEnvelope, body: String): InboxItem {
        return InboxItem(
            id = env.id,
            originalText = null,
            originalSubject = null,
            sourceApp = env.sourceApp,
            receivedAt = OffsetDateTime.parse(env.receivedAt).toInstant().toEpochMilli(),
            status = ItemStatus.valueOf(env.status.takeIf { it != "DELETED" } ?: "COMPLETED"),
            title = env.title,
            summary = body.takeIf { it.isNotBlank() },
            category = env.category,
            tags = env.tags,
            people = env.people,
            places = env.places,
            urls = env.urls,
            event = env.event?.let {
                ExtractedEvent(
                    title = it.title,
                    startMillis = it.start?.let(::parseInstantOrNull),
                    endMillis = it.end?.let(::parseInstantOrNull),
                    location = it.location,
                    confidence = it.confidence,
                )
            },
            userEditedFields = emptySet(),
            updatedAt = OffsetDateTime.parse(env.updatedAt).toInstant().toEpochMilli(),
            deletedAt = env.deletedAt?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() },
        )
    }

    private fun parseInstantOrNull(s: String): Long? =
        runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()

    data class SyncRunStats(
        val exported: Int,
        val imported: Int,
        val softDeletedLocally: Int,
        val reExportedTombstone: Int,
        val skipped: Int,
    )

    companion object {
        private const val TAG = "FsSyncEngine"

        data class RemoteIdRef(val id: String, val isTombstone: Boolean)

        fun diff(local: List<InboxRefRow>, remote: List<RemoteIdRef>): Diff {
            val export = mutableListOf<String>()
            val import = mutableListOf<String>()
            val softDelete = mutableListOf<String>()
            val reExport = mutableListOf<String>()
            val skip = mutableListOf<String>()
            val byIdLocal = local.associateBy { it.id }
            val byIdRemote = remote.associateBy { it.id }
            val ids = (byIdLocal.keys + byIdRemote.keys).sorted()
            for (id in ids) {
                val l = byIdLocal[id]
                val r = byIdRemote[id]
                when {
                    l != null && r == null -> export += id
                    l == null && r != null -> if (!r.isTombstone) import += id
                    l != null && r != null -> {
                        val lAlive = l.deletedAt == null
                        when {
                            lAlive && !r.isTombstone -> skip += id
                            !lAlive && r.isTombstone -> skip += id
                            lAlive && r.isTombstone -> softDelete += id
                            !lAlive && !r.isTombstone -> reExport += id
                        }
                    }
                }
            }
            return Diff(export, import, softDelete, reExport, skip)
        }
    }
}
