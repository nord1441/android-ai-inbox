package com.example.aiinbox.llm

class ContentHintDetector {
    private val urlRegex = Regex("""\bhttps?://\S+""")
    private val emailHeaderRegex = Regex("""^(From|To|Subject|Cc|Bcc):""", RegexOption.IGNORE_CASE)
    // 行頭の「<名前>: 」または「<名前>:」（チャット風）
    private val chatLineRegex = Regex("""^[\p{L}\p{N}_぀-ヿ一-鿿 .]{1,30}:\s""")

    fun detect(text: String): ContentHint {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ContentHint.UNKNOWN

        val firstLines = trimmed.lineSequence().take(5).toList()

        // メールヘッダ
        if (firstLines.any { emailHeaderRegex.containsMatchIn(it) }) {
            return ContentHint.CHAT_OR_EMAIL
        }

        // チャット風（複数行で発信者プレフィクスが出る）
        val chatLineCount = trimmed.lineSequence().take(20)
            .count { chatLineRegex.containsMatchIn(it) }
        if (chatLineCount >= 2) return ContentHint.CHAT_OR_EMAIL

        // URLが先頭にある or 全体に占めるwordsの比率が高め → 記事
        if (firstLines.firstOrNull()?.let { urlRegex.containsMatchIn(it) } == true) {
            return ContentHint.WEB_ARTICLE
        }
        if (urlRegex.findAll(trimmed).count() >= 1 && trimmed.length > 200) {
            return ContentHint.WEB_ARTICLE
        }

        return ContentHint.MEMO
    }
}
