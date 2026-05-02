package com.example.aiinbox.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.aiinbox.AiInboxApplication
import com.example.aiinbox.R
import com.example.aiinbox.data.repository.InboxRepository
import com.example.aiinbox.work.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    @Inject lateinit var repository: InboxRepository
    @Inject lateinit var workScheduler: WorkScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i(TAG, "onCreate. action=${intent.action}")
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val sourceApp = referrer?.host

        if (text.isNullOrBlank()) {
            android.util.Log.w(TAG, "EXTRA_TEXT empty — aborting")
            Toast.makeText(this, "テキストが見つかりません", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        android.util.Log.i(TAG, "Got text len=${text.length} subject=$subject sourceApp=$sourceApp")
        Toast.makeText(this, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()

        // Theme.NoDisplay requires synchronous finish() before onResume completes.
        // Persist + enqueue happen in the Application-scoped coroutine so they
        // outlive this Activity's lifecycle.
        val app = application as AiInboxApplication
        app.applicationScope.launch {
            try {
                android.util.Log.i(TAG, "applicationScope.launch entered")
                val id = repository.createPendingItem(text, subject, sourceApp)
                android.util.Log.i(TAG, "createPendingItem returned id=$id")
                workScheduler.enqueueSummarize(id)
                android.util.Log.i(TAG, "enqueueSummarize($id) returned")
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "applicationScope coroutine threw", t)
            }
        }
        android.util.Log.i(TAG, "onCreate done — calling finish()")
        finish()
    }

    companion object {
        private const val TAG = "ShareReceiverActivity"
    }
}
