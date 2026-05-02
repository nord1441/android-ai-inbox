package com.example.aiinbox.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
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
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val sourceApp = referrer?.host

        if (text.isNullOrBlank()) {
            Toast.makeText(this, "テキストが見つかりません", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Toast.makeText(this, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val id = repository.createPendingItem(text, subject, sourceApp)
            workScheduler.enqueueSummarize(id)
            finish()
        }
    }
}
