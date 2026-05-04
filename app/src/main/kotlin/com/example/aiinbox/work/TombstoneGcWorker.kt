package com.example.aiinbox.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.aiinbox.data.db.AttachmentDao
import com.example.aiinbox.data.db.InboxDao
import com.example.aiinbox.data.storage.EncryptedImageStore
import com.example.aiinbox.sync.DriveApiClient
import com.example.aiinbox.sync.DriveAuthRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Daily worker that physically removes tombstoned items older than 30 days.
 *
 * Two phases per row:
 *   1. If a Drive account is linked, best-effort delete the corresponding
 *      `items/{id}.json` and `attachments/{att_id}.bin` files from Drive.
 *      Failures here are logged but do not block local cleanup — the row
 *      drops out of the next manifest publish either way, so Drive will
 *      converge on the next sync.
 *   2. Physical DELETE on the local DB (CASCADE removes attachments rows)
 *      and removal of any lingering encrypted file bytes.
 */
@HiltWorker
class TombstoneGcWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val inboxDao: InboxDao,
    private val attachmentDao: AttachmentDao,
    private val imageStore: EncryptedImageStore,
    private val api: DriveApiClient,
    private val authRepository: DriveAuthRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val cutoff = System.currentTimeMillis() - GC_WINDOW_MS
        val rows = inboxDao.tombstonesOlderThan(cutoff)
        if (rows.isEmpty()) return Result.success()

        val linked = authRepository.currentEmail() != null
        for (row in rows) {
            val attachments = attachmentDao.listForItemIncludingDeleted(row.id)

            if (linked) {
                // Best-effort remote cleanup; never let Drive errors block
                // the local purge.
                runCatching {
                    api.findFileByName("items/${row.id}.json")?.let { api.deleteFile(it.id) }
                    attachments.forEach { att ->
                        api.findFileByName("attachments/${att.id}.bin")?.let { api.deleteFile(it.id) }
                    }
                }.onFailure { t ->
                    android.util.Log.w(TAG, "remote GC failed for item ${row.id}", t)
                }
            }

            // Local cleanup. Encrypted files were erased at tombstone time, but
            // attempt removal again in case any orphans linger from earlier
            // partial deletes.
            attachments.forEach { runCatching { imageStore.delete(it.encryptedFilename) } }
            attachmentDao.physicalDeleteForItem(row.id)
            inboxDao.physicalDeleteById(row.id)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "TombstoneGcWorker"
        private const val GC_WINDOW_MS = 30L * 24 * 60 * 60 * 1000  // 30 days
    }
}
