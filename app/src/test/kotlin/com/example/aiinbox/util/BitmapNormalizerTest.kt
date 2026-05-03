package com.example.aiinbox.util

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BitmapNormalizerTest {

    @Test
    fun `4K bitmap is downscaled to long edge 2048`() {
        val src = Bitmap.createBitmap(3000, 4000, Bitmap.Config.ARGB_8888)
        val out = BitmapNormalizer.normalize(src, maxLongEdge = 2048)
        // 長辺 4000 → 2048、短辺 3000 → 2048 * 3000/4000 = 1536
        assertThat(out.height).isEqualTo(2048)
        assertThat(out.width).isEqualTo(1536)
    }

    @Test
    fun `small bitmap is returned unchanged size`() {
        val src = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val out = BitmapNormalizer.normalize(src, maxLongEdge = 2048)
        assertThat(out.width).isEqualTo(800)
        assertThat(out.height).isEqualTo(600)
    }

    @Test
    fun `square bitmap is downscaled both dimensions equally`() {
        val src = Bitmap.createBitmap(4000, 4000, Bitmap.Config.ARGB_8888)
        val out = BitmapNormalizer.normalize(src, maxLongEdge = 2048)
        assertThat(out.width).isEqualTo(2048)
        assertThat(out.height).isEqualTo(2048)
    }

    @Test
    fun `encodeJpeg returns non-empty byte array`() {
        val src = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val bytes = BitmapNormalizer.encodeJpeg(src, quality = 85)
        // JPEG マジック FF D8
        assertThat(bytes.size).isGreaterThan(2)
        assertThat(bytes[0]).isEqualTo(0xFF.toByte())
        assertThat(bytes[1]).isEqualTo(0xD8.toByte())
    }
}
