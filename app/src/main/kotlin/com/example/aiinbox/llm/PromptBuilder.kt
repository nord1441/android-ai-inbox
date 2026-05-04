package com.example.aiinbox.llm

import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class PromptBuilder(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val maxInputChars: Int = 8000,
) {
    fun build(text: String, hint: ContentHint): String {
        val truncated = if (text.length > maxInputChars) {
            text.substring(0, maxInputChars) + "\n\n[...以下省略...]"
        } else text

        val hintGuidance = when (hint) {
            ContentHint.CHAT_OR_EMAIL -> CHAT_GUIDANCE
            ContentHint.WEB_ARTICLE -> ARTICLE_GUIDANCE
            ContentHint.MEMO -> MEMO_GUIDANCE
            ContentHint.SCREENSHOT -> SCREENSHOT_GUIDANCE
            ContentHint.IMAGE_OCR -> IMAGE_OCR_GUIDANCE
            ContentHint.UNKNOWN -> ""
        }

        // Without "today", Gemma resolves "5/4(月)" against its training-data
        // calendar (≈ 2024) rather than the current device date, so events get
        // backdated by years.
        val today = LocalDate.now(clock)
        val todayLabel = today.format(TODAY_FORMATTER)

        return SYSTEM_PROMPT
            .replace("{{TODAY}}", todayLabel)
            .replace("{{HINT_GUIDANCE}}", hintGuidance)
            .replace("{{INPUT}}", truncated)
    }

    companion object {
        private val TODAY_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd (EEE)", Locale.JAPANESE)

        private const val SYSTEM_PROMPT = """あなたはテキストの要約と構造化情報抽出のアシスタントです。
入力テキストを読んで、以下のJSONスキーマに厳密に従ってJSONのみを出力してください。説明文や前置きは禁止です。

今日の日付: {{TODAY}}
入力中の "明日" / "来週月曜" / "5/4(月)" などの日付表現は、すべてこの「今日の日付」を基準に絶対日時に変換してください。

{
  "title": "30文字以内の短いタイトル",
  "summary": "200文字以内の要約",
  "category": "仕事|個人|ニュース|買い物|その他",
  "tags": ["string"],
  "people": ["人物名"],
  "places": ["場所名"],
  "urls": ["URL"],
  "event": {
    "title": "イベント名",
    "start_iso": "ISO8601 (時刻不明なら YYYY-MM-DD のみ)",
    "end_iso": "ISO8601 or null",
    "location": "場所 or null",
    "confidence": 0.0
  }
}

イベントが含まれない場合は "event" を null にしてください。
配列フィールド (tags / people / places / urls) に該当するものが無い場合は空配列 [] を使ってください。null や [null] は使わないでください。
タイムゾーン未指定の時刻は端末ローカル時刻として解釈してください。

{{HINT_GUIDANCE}}

# 入力テキスト
{{INPUT}}

# 出力（JSONのみ）
"""

        private const val CHAT_GUIDANCE = """この入力はチャットまたはメールです。
日付・時刻表現の解釈に注意してください："明日"、"来週月曜"、"今度の金曜"などの相対表現は、
入力時点の日付（端末の今日）を基準として絶対日時に変換してください。
発信者の名前は people に含めてください。"""

        private const val ARTICLE_GUIDANCE = """この入力はWeb記事です。
記事のメインテーマを title に、本文の論点を summary にまとめてください。
記事内に明示された日時イベント（カンファレンス開催日など）があれば event に抽出してください。"""

        private const val MEMO_GUIDANCE = """この入力は個人のメモまたは議事録です。
要点を summary に、固有名詞があれば people / places に抽出してください。"""

        private const val SCREENSHOT_GUIDANCE = """この入力はスクリーンショットから抽出したテキストです。
UI要素やチャット内容を解釈し、重要な情報を summary に抽出してください。"""

        private const val IMAGE_OCR_GUIDANCE = """この入力は画像から OCR で抽出したテキストです。
読み取れた情報を整理し、重要な内容を summary にまとめてください。"""
    }
}
