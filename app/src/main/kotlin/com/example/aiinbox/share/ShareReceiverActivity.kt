package com.example.aiinbox.share

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.aiinbox.AiInboxApplication
import com.example.aiinbox.R
import com.example.aiinbox.data.db.AttachmentKind
import com.example.aiinbox.data.repository.AttachmentDraft
import com.example.aiinbox.data.repository.InboxRepository
import com.example.aiinbox.data.storage.EncryptedImageStore
import com.example.aiinbox.util.BitmapNormalizer
import com.example.aiinbox.work.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    @Inject lateinit var repository: InboxRepository
    @Inject lateinit var workScheduler: WorkScheduler
    @Inject lateinit var imageStore: EncryptedImageStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i(TAG, "onCreate. action=${intent.action} type=${intent.type}")

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val sourceApp = referrer?.host

        val imageUris: List<Uri> = collectImageUris(intent)

        if (text.isNullOrBlank() && imageUris.isEmpty()) {
            android.util.Log.w(TAG, "No text or image — aborting")
            Toast.makeText(this, "コンテンツが見つかりません", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Toast.makeText(this, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()

        // Activity finish 後も Uri 権限を保つため、application context にも grant
        for (uri in imageUris) {
            try {
                grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // 一部の content provider では grant 不要 / 失敗する
            }
        }
        val appContext = applicationContext
        val scope: CoroutineScope = (application as? AiInboxApplication)?.applicationScope
            ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

        scope.launch {
            try {
                val drafts = imageUris.mapIndexedNotNull { idx, uri ->
                    runCatching {
                        appContext.contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "openInputStream null for $uri" }
                            val raw = BitmapFactory.decodeStream(input)
                                ?: error("decodeStream null for $uri")
                            val normalized = BitmapNormalizer.normalize(raw, maxLongEdge = 2048)
                            val bytes = BitmapNormalizer.encodeJpeg(normalized, quality = 85)
                            val name = imageStore.save(bytes)
                            val draft = AttachmentDraft(
                                kind = AttachmentKind.SHARED_IMAGE,
                                encryptedFilename = name,
                                mimeType = "image/jpeg",
                                widthPx = normalized.width,
                                heightPx = normalized.height,
                                byteSize = bytes.size.toLong(),
                            )
                            if (raw !== normalized) raw.recycle()
                            normalized.recycle()
                            draft
                        }
                    }.onFailure {
                        android.util.Log.w(TAG, "Skipping image idx=$idx uri=$uri", it)
                    }.getOrNull()
                }

                if (text.isNullOrBlank() && drafts.isEmpty()) {
                    android.util.Log.w(TAG, "All images failed to read; nothing to persist")
                    return@launch
                }

                val itemId = repository.createPendingItemWithAttachments(
                    text = text, subject = subject, sourceApp = sourceApp, drafts = drafts,
                )
                workScheduler.enqueueSummarize(itemId)
                android.util.Log.i(TAG, "createPendingItemWithAttachments id=$itemId attachments=${drafts.size}")
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "applicationScope coroutine threw", t)
            }
        }
        finish()
    }

    private fun collectImageUris(intent: Intent): List<Uri> {
        val type = intent.type ?: return emptyList()
        if (!type.startsWith("image/")) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                listOfNotNull(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty().toList()
            }
            else -> emptyList()
        }
    }

    companion object {
        private const val TAG = "ShareReceiverActivity"
    }
}
