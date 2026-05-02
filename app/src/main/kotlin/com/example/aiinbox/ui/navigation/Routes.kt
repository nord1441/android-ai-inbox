package com.example.aiinbox.ui.navigation

object Routes {
    const val INBOX = "inbox"
    const val DETAIL = "detail/{id}"
    fun detail(id: String) = "detail/$id"
}
