package com.example.aiinbox.ui.navigation

object Routes {
    const val INBOX = "inbox"
    const val DETAIL = "detail/{id}"
    const val MODEL_DOWNLOAD = "model_download"
    const val SETTINGS = "settings"
    fun detail(id: String) = "detail/$id"
}
