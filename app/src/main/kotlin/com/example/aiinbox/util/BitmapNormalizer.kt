package com.example.aiinbox.util

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

object BitmapNormalizer {

    /** 長辺が [maxLongEdge] を超えていれば等比縮小して返す。元 Bitmap は [src] が出力と同じ場合のみ返す。 */
    fun normalize(src: Bitmap, maxLongEdge: Int = 2048): Bitmap {
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= maxLongEdge) return src
        val scale = maxLongEdge.toFloat() / longEdge.toFloat()
        val newW = (src.width * scale).toInt().coerceAtLeast(1)
        val newH = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, newW, newH, /* filter = */ true)
    }

    /** Bitmap を JPEG にエンコード。 */
    fun encodeJpeg(bitmap: Bitmap, quality: Int = 85): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
