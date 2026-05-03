package com.example.aiinbox.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ML Kit Text Recognition v2 を使う本番 OCR エンジン。
 *
 * - Latin スクリプト / Japanese スクリプトの両方を試行し、
 *   出力テキストが長い方を採用する（簡易的な言語自動選択）。
 * - 言語スクリプトモジュールは Play Services 経由で初回利用時に自動 DL される。
 *   ネットワーク不在 + 未 DL の場合 throws。
 * - 推論器は Singleton スコープで1個キャッシュ。
 */
@Singleton
class MlKitOcrEngine @Inject constructor() : OcrEngine {

    private val latin: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val japanese: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    override suspend fun recognize(bitmap: Bitmap): String = coroutineScope {
        val img = InputImage.fromBitmap(bitmap, /* rotation = */ 0)
        val latinDeferred = async { latin.process(img).await().text }
        val japaneseDeferred = async { japanese.process(img).await().text }
        val latinText = latinDeferred.await()
        val japaneseText = japaneseDeferred.await()
        if (japaneseText.length >= latinText.length) japaneseText else latinText
    }
}
