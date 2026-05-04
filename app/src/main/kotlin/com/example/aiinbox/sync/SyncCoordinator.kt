package com.example.aiinbox.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.aiinbox.work.SyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point for triggering Drive sync. Uses WorkManager unique-work
 * naming (`drive_sync_oneshot`, `drive_sync_periodic`) so concurrent triggers
 * collapse to a single in-flight run instead of stampeding.
 */
@Singleton
class SyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Enqueue a one-shot sync run; if one is already in flight, KEEP it. */
    fun requestImmediateSync() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME_ONESHOT,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().build(),
        )
    }

    /**
     * (Re)schedule the periodic sync to fire every [intervalMinutes] minutes.
     * Pass null to cancel the periodic schedule entirely (manual + event-driven
     * triggers continue to work).
     */
    fun setPeriodicInterval(intervalMinutes: Long?) {
        val wm = WorkManager.getInstance(context)
        if (intervalMinutes == null) {
            wm.cancelUniqueWork(UNIQUE_NAME_PERIODIC)
            return
        }
        val req = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE_NAME_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    companion object {
        const val UNIQUE_NAME_ONESHOT = "drive_sync_oneshot"
        const val UNIQUE_NAME_PERIODIC = "drive_sync_periodic"
    }
}
