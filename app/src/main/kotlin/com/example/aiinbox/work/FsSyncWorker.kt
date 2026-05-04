package com.example.aiinbox.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.aiinbox.data.db.FsSyncStateDao
import com.example.aiinbox.data.db.FsSyncStateEntity
import com.example.aiinbox.sync.FsSyncEngine
import com.example.aiinbox.sync.FsSyncFolderStore
import com.example.aiinbox.sync.FsSyncStateRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Drives one filesystem sync run.
 */
@HiltWorker
class FsSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val folderStore: FsSyncFolderStore,
    private val engine: FsSyncEngine,
    private val syncStateDao: FsSyncStateDao,
    private val syncStateRepository: FsSyncStateRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val treeUri = folderStore.get() ?: return Result.success()  // not configured: no-op
        syncStateRepository.setRunning()
        try {
            val stats = engine.runOnce(treeUri)
            android.util.Log.i(
                TAG,
                "sync done. exported=${stats.exported} imported=${stats.imported} " +
                    "softDel=${stats.softDeletedLocally} reExp=${stats.reExportedTombstone} skipped=${stats.skipped}",
            )
            val now = System.currentTimeMillis()
            syncStateDao.upsert(
                FsSyncStateEntity(id = 1, folderUri = treeUri, lastFullSyncAt = now)
            )
            syncStateRepository.setIdle()
            return Result.success()
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "sync failed", t)
            syncStateRepository.setError(t.message)
            return Result.retry()
        }
    }

    companion object {
        private const val TAG = "FsSyncWorker"
    }
}
