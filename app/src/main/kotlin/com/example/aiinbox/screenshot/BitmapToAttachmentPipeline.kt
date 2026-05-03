package com.example.aiinbox.screenshot

import android.graphics.Bitmap
import com.example.aiinbox.data.db.AttachmentKind
import com.example.aiinbox.data.repository.AttachmentDraft
import com.example.aiinbox.data.repository.InboxRepository
import com.example.aiinbox.data.storage.EncryptedImageStore
import com.example.aiinbox.util.BitmapNormalizer
import com.example.aiinbox.work.WorkScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bitmap を「正規化 → 暗号化保存 → DB 行作成 → Worker 投入」まで一気通貫で行うパイプライン。
 * スクリーンショット撮影と画像 Share の両方から使う。
 */
@Singleton
class BitmapToAttachmentPipeline @Inject constructor(
    private val repository: InboxRepository,
    private val imageStore: EncryptedImageStore,
    private val workScheduler: WorkScheduler,
) {

    /**
     * [bitmap] を保存し、新しい InboxItem を1件作成、SummarizeWorker を投入する。
     * [bitmap] は処理後に [Bitmap.recycle] される。
     *
     * @return 作成した itemId
     */
    suspend fun saveAsItem(
        bitmap: Bitmap,
        kind: AttachmentKind,
        sourceApp: String?,
    ): String {
        val normalized = BitmapNormalizer.normalize(bitmap, maxLongEdge = 2048)
        val bytes = BitmapNormalizer.encodeJpeg(normalized, quality = 85)
        val name = imageStore.save(bytes)
        val draft = AttachmentDraft(
            kind = kind,
            encryptedFilename = name,
            mimeType = "image/jpeg",
            widthPx = normalized.width,
            heightPx = normalized.height,
            byteSize = bytes.size.toLong(),
        )
        val itemId = repository.createPendingItemWithAttachments(
            text = null, subject = null, sourceApp = sourceApp, drafts = listOf(draft),
        )
        workScheduler.enqueueSummarize(itemId)
        if (normalized !== bitmap) normalized.recycle()
        bitmap.recycle()
        return itemId
    }

    /** RGBA Bitmap の平均輝度を 0..255 で返す（黒画面検出用、簡易サンプリング）。 */
    fun averageLuminance(bitmap: Bitmap, sampleStep: Int = 16): Int {
        var sum = 0L
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val px = bitmap.getPixel(x, y)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                sum += (r * 299 + g * 587 + b * 114) / 1000
                count += 1
                x += sampleStep
            }
            y += sampleStep
        }
        return if (count == 0) 0 else (sum / count).toInt()
    }
}
