package com.example.aiinbox.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.aiinbox.work.FsSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FsSyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun requestImmediateSync() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME_ONESHOT,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<FsSyncWorker>().build(),
        )
    }

    fun setPeriodicInterval(intervalMinutes: Long?) {
        val wm = WorkManager.getInstance(context)
        if (intervalMinutes == null) {
            wm.cancelUniqueWork(UNIQUE_NAME_PERIODIC)
            return
        }
        // No NetworkType constraint — file system sync is local-only; the
        // user-chosen sync tool will move files when *it* chooses.
        val req = PeriodicWorkRequestBuilder<FsSyncWorker>(intervalMinutes, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork(UNIQUE_NAME_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    companion object {
        const val UNIQUE_NAME_ONESHOT = "fs_sync_oneshot"
        const val UNIQUE_NAME_PERIODIC = "fs_sync_periodic"
    }
}
