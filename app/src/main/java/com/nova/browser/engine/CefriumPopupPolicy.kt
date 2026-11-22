package com.nova.browser.engine

/** Conservative policy for Chromium-created secondary windows (OAuth and account popups). */
object CefriumPopupPolicy {
    fun isSafeUserInitiatedRequest(url: String, userGesture: Boolean): Boolean {
        if (!userGesture) return false
        return url.isBlank() || url == "about:blank" ||
            url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("http://", ignoreCase = true)
    }
}
