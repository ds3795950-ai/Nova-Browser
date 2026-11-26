package com.nova.browser

internal enum class BrowserMode { NORMAL, PRIVATE, GUEST }
internal enum class BrowserSecurityState { SECURE, NOT_SECURE, CERTIFICATE_WARNING }
internal data class BrowserStatusState(val text: String, val priority: Int)
internal data class BrowserSuggestion(val label: String, val url: String) {
    override fun toString(): String = label
}

internal object BrowserUiState {
    fun filterSuggestions(query: String, candidates: List<BrowserSuggestion>, limit: Int = 6): List<BrowserSuggestion> {
        val needle = query.trim().lowercase()
        if (needle.isBlank() || limit <= 0) return emptyList()
        return candidates.asSequence()
            .filter { it.label.contains(needle, ignoreCase = true) || it.url.contains(needle, ignoreCase = true) }
            .distinctBy { it.url }
            .sortedWith(compareBy<BrowserSuggestion> {
                when {
                    it.label.startsWith(needle, ignoreCase = true) -> 0
                    it.url.startsWith(needle, ignoreCase = true) -> 1
                    else -> 2
                }
            }.thenBy { it.label.lowercase() })
            .take(limit)
            .toList()
    }

    fun modeFor(isPrivate: Boolean, isGuest: Boolean): BrowserMode = when {
        isGuest -> BrowserMode.GUEST
        isPrivate -> BrowserMode.PRIVATE
        else -> BrowserMode.NORMAL
    }

    fun modeOrdinal(modes: List<BrowserMode>, currentIndex: Int): Int {
        if (currentIndex !in modes.indices) return 0
        val current = modes[currentIndex]
        return modes.take(currentIndex + 1).count { it == current }
    }

    fun modeCount(modes: List<BrowserMode>, mode: BrowserMode): Int = modes.count { it == mode }

    fun updateStatus(current: BrowserStatusState, text: String, priority: Int): BrowserStatusState =
        if (priority >= current.priority) BrowserStatusState(text, priority) else current

    fun securityState(isSecure: Boolean, isException: Boolean): BrowserSecurityState = when {
        isException -> BrowserSecurityState.CERTIFICATE_WARNING
        isSecure -> BrowserSecurityState.SECURE
        else -> BrowserSecurityState.NOT_SECURE
    }
}


/** Gate remote suggestions behind explicit consent and Normal-mode-only execution. */
internal object RemoteSearchSuggestionPolicy {
    fun shouldFetch(enabled: Boolean, isPrivate: Boolean, isGuest: Boolean, query: String): Boolean =
        enabled && !isPrivate && !isGuest && query.trim().length >= 2
}
