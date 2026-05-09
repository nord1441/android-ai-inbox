package uk.nordtek.aiinbox.ui.inbox

data class InboxFilter(
    val query: String = "",
    val categories: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val hasEventOnly: Boolean = false,
) {
    val isEmpty: Boolean
        get() = query.isBlank() && categories.isEmpty() && tags.isEmpty() && !hasEventOnly
}
