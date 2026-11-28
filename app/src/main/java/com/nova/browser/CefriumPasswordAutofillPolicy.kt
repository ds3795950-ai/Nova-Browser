package com.nova.browser

import android.net.Uri

/** Origin-bound guard for explicit saved-login filling in Cefrium Normal tabs. */
object CefriumPasswordAutofillPolicy {
    fun isAllowed(currentUrl: String, savedOrigin: String, formActionOrigin: String?): Boolean {
        val current = normalizedOrigin(currentUrl) ?: return false
        val saved = normalizedOrigin(savedOrigin) ?: return false
        if (current != saved) return false
        if (formActionOrigin.isNullOrBlank()) return true
        return normalizedOrigin(formActionOrigin) == current
    }

    fun normalizedOrigin(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        if (scheme !in setOf("http", "https") || uri.encodedUserInfo != null) return null
        val port = uri.port
        val normalizedPort = when {
            port <= 0 -> ""
            scheme == "https" && port == 443 -> ""
            scheme == "http" && port == 80 -> ""
            else -> ":$port"
        }
        return "$scheme://$host$normalizedPort"
    }
}
