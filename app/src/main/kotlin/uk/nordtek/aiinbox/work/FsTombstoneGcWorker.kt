package uk.nordtek.aiinbox.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import uk.nordtek.aiinbox.data.db.AttachmentDao
import uk.nordtek.aiinbox.data.db.InboxDao
import uk.nordtek.aiinbox.data.storage.EncryptedImageStore
import uk.nordtek.aiinbox.sync.FsSyncFolderStore
import uk.nordtek.aiinbox.sync.MarkdownExporter
import uk.nordtek.aiinbox.sync.SafFolderAccess
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Daily sweep that physically removes tombstoned items older than 30 days,
 * locally and (best-effort) on the SAF tree.
 */
@HiltWorker
class FsTombstoneGcWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val inboxDao: InboxDao,
    private val attachmentDao: AttachmentDao,
    private val imageStore: EncryptedImageStore,
    private val folderStore: FsSyncFolderStore,
    private val saf: SafFolderAccess,
    private val exporter: MarkdownExporter,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val cutoff = System.currentTimeMillis() - GC_WINDOW_MS
        val rows = inboxDao.tombstonesOlderThan(cutoff)
        if (rows.isEmpty()) return Result.success()

        val treeUri = folderStore.get()
        for (row in rows) {
            // Best-effort SAF cleanup (only if a folder is configured).
            if (treeUri != null) {
                runCatching {
                    val name = exporter.filenameFor(row)
                    saf.deleteByName(treeUri, name)
                }.onFailure { android.util.Log.w(TAG, "remote GC failed for ${row.id}", it) }
            }

            // Local: erase any orphan encrypted bytes (defensive — finalizeDelete
            // already did this normally) then drop the rows.
            val attachments = attachmentDao.getForItem(row.id)
            attachments.forEach { runCatching { imageStore.delete(it.encryptedFilename) } }
            attachmentDao.deleteForItem(row.id)
            inboxDao.physicalDeleteById(row.id)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "FsTombstoneGcWorker"
        private const val GC_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    }
}
