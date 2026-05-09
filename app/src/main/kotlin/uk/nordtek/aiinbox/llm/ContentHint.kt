package uk.nordtek.aiinbox.llm

enum class ContentHint {
    WEB_ARTICLE,
    CHAT_OR_EMAIL,
    MEMO,
    /** スクリーンショット（チャット画面 / アプリ UI / 記事スクショなど）。 */
    SCREENSHOT,
    /** ユーザーが共有した画像（写真・図・領収書など）。テキストは OCR 経由。 */
    IMAGE_OCR,
    UNKNOWN,
}
