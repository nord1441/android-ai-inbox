package uk.nordtek.aiinbox.data.db

enum class AttachmentKind {
    /** 他アプリからシェアシート経由で受信した画像。 */
    SHARED_IMAGE,
    /** 自前で MediaProjection 撮影したスクリーンショット。 */
    SCREENSHOT,
}
