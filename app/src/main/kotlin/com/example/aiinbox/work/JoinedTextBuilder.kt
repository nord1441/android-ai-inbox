package com.example.aiinbox.work

import com.example.aiinbox.data.db.Attachment
import com.example.aiinbox.data.db.AttachmentKind

/**
 * 添付テキスト（OCR）と本文を、LLM 投入用の単一文字列に整形する。
 *
 * 出力フォーマット:
 *   [添付1: スクリーンショット]
 *   <ocr1>
 *
 *   [添付2: 画像]
 *   <ocr2>
 *
 *   [本文]
 *   <text>
 */
object JoinedTextBuilder {

    fun build(text: String?, attachments: List<Attachment>): String {
        val sortedAtts = attachments.sortedBy { it.ordering }
        val sections = mutableListOf<String>()

        sortedAtts.forEachIndexed { idx, att ->
            val label = when (att.kind) {
                AttachmentKind.SCREENSHOT -> "スクリーンショット"
                AttachmentKind.SHARED_IMAGE -> "画像"
            }
            val body = when {
                att.ocrText == null -> "（テキスト抽出未完了）"
                att.ocrText.isEmpty() -> "（テキストなし）"
                else -> att.ocrText
            }
            sections += "[添付${idx + 1}: $label]\n$body"
        }

        if (!text.isNullOrBlank()) {
            if (sections.isEmpty()) {
                return text
            }
            sections += "[本文]\n$text"
        }

        return sections.joinToString("\n\n")
    }
}
