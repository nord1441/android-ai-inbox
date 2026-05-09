package uk.nordtek.aiinbox.ocr

import android.graphics.Bitmap

class FakeOcrEngine(
    var fixedResult: String = "fake-ocr-text",
    var throwOnNext: Throwable? = null,
) : OcrEngine {
    var callCount: Int = 0
        private set

    override suspend fun recognize(bitmap: Bitmap): String {
        callCount += 1
        throwOnNext?.let { throw it }
        return fixedResult
    }
}
