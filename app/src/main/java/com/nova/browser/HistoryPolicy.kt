package com.nova.browser

import java.net.URI

/** Only ordinary web navigations belong in persistent browsing history. */
internal object HistoryPolicy {
    fun shouldPersist(url: String): Boolean {
        if (url.isBlank() || url == "about:blank") return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.rawAuthority.isNullOrBlank() && uri.rawUserInfo == null
    }
}
