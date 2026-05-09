package uk.nordtek.aiinbox.share

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import uk.nordtek.aiinbox.AiInboxApplication
import uk.nordtek.aiinbox.R
import uk.nordtek.aiinbox.data.db.AttachmentKind
import uk.nordtek.aiinbox.data.repository.AttachmentDraft
import uk.nordtek.aiinbox.data.repository.InboxRepository
import uk.nordtek.aiinbox.data.storage.EncryptedImageStore
import uk.nordtek.aiinbox.util.BitmapNormalizer
import uk.nordtek.aiinbox.work.WorkScheduler
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

        // Activity finish 後も Uri 権限を保つため、application context にも grant
        for (uri in imageUris) {
            try {
                grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // 一部の content provider では grant 不要 / 失敗する
            }
        }
        val appContext = applicationContext
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
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

                if (text.isNullOrBlank() && drafts.isEmpty() && imageUris.isNotEmpty()) {
                    android.util.Log.w(TAG, "All images failed to read; nothing to persist")
                    mainHandler.post {
                        Toast.makeText(appContext, "画像読込に失敗しました", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                if (text.isNullOrBlank() && drafts.isEmpty()) {
                    return@launch
                }

                val itemId = repository.createPendingItemWithAttachments(
                    text = text, subject = subject, sourceApp = sourceApp, drafts = drafts,
                )
                workScheduler.enqueueSummarize(itemId)
                mainHandler.post {
                    Toast.makeText(appContext, R.string.toast_saved, Toast.LENGTH_SHORT).show()
                }
                android.util.Log.i(TAG, "createPendingItemWithAttachments id=$itemId attachments=${drafts.size}")
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "applicationScope coroutine threw", t)
                mainHandler.post {
                    Toast.makeText(appContext, "保存に失敗しました", Toast.LENGTH_SHORT).show()
                }
            }
        }
        finish()
    }

    private fun collectImageUris(intent: Intent): List<Uri> {
        val type = intent.type
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                if (type != null && type.startsWith("image/")) {
                    @Suppress("DEPRECATION")
                    val uri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    listOfNotNull(uri)
                } else {
                    clipDataImageUris(intent)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (type != null && type.startsWith("image/")) {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty().toList()
                } else {
                    clipDataImageUris(intent)
                }
            }
            else -> emptyList()
        }
    }

    private fun clipDataImageUris(intent: Intent): List<Uri> {
        val clip = intent.clipData ?: return emptyList()
        val uris = mutableListOf<Uri>()
        for (i in 0 until clip.itemCount) {
            val itemUri = clip.getItemAt(i).uri ?: continue
            val mime = contentResolver.getType(itemUri)
            if (mime != null && mime.startsWith("image/")) {
                uris += itemUri
            }
        }
        return uris
    }

    companion object {
        private const val TAG = "ShareReceiverActivity"
    }
}
