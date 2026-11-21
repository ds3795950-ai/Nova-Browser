package com.nova.browser

internal enum class BrowserMode { NORMAL, PRIVATE, GUEST }
internal enum class BrowserSecurityState { SECURE, NOT_SECURE, CERTIFICATE_WARNING }
internal data class BrowserStatusState(val text: String, val priority: Int)

internal object BrowserUiState {
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
