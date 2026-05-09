package uk.nordtek.aiinbox.ocr

import android.graphics.Bitmap

/** 画像 → テキスト抽出の抽象。 */
interface OcrEngine {
    /**
     * [bitmap] を OCR してテキストを返す。
     * テキストが検出されなければ空文字列。失敗時は例外。
     */
    suspend fun recognize(bitmap: Bitmap): String
}
