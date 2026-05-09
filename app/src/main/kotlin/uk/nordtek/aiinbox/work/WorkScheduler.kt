package uk.nordtek.aiinbox.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun enqueueSummarize(itemId: String) {
        android.util.Log.i(TAG, "enqueueSummarize($itemId) — building WorkRequest")
        val request = OneTimeWorkRequestBuilder<SummarizeWorker>()
            .setInputData(Data.Builder().putString(SummarizeWorker.KEY_ITEM_ID, itemId).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            // No setExpedited(): expedited work has a tight per-app daily quota
            // (~10 minutes on most devices) which a multi-job LLM session can
            // exhaust quickly. Regular WorkRequest runs fine for LLM inference;
            // foreground promotion happens inside the worker via setForeground()
            // which is what grants the BFSL needed to start LlmInferenceService
            // on Android 14+.
            .build()
        try {
            WorkManager.getInstance(context)
                .enqueueUniqueWork("summarize:$itemId", ExistingWorkPolicy.REPLACE, request)
            android.util.Log.i(TAG, "enqueueUniqueWork OK for $itemId (workSpecId=${request.id})")
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "enqueueUniqueWork threw for $itemId", t)
            throw t
        }
    }

    companion object {
        private const val TAG = "WorkScheduler"
    }
}
