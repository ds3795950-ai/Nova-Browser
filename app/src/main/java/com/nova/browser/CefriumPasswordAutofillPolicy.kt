package com.nova.browser

import java.net.URI

/** Origin-bound guard for explicit saved-login filling in Cefrium Normal tabs. */
object CefriumPasswordAutofillPolicy {
    fun isAllowed(currentUrl: String, savedOrigin: String, formActionOrigin: String?): Boolean {
        val current = normalizedOrigin(currentUrl) ?: return false
        val saved = normalizedOrigin(savedOrigin) ?: return false
        if (current != saved) return false
        if (formActionOrigin.isNullOrBlank()) return true
        return normalizedOrigin(formActionOrigin) == current
    }

    /** Pure-JVM parser so the origin boundary can be regression-tested without Android stubs. */
    fun normalizedOrigin(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        // Never place a saved password into a clear-text HTTP page or a credential-bearing URL.
        if (scheme != "https" || uri.rawUserInfo != null) return null
        val port = uri.port
        val normalizedPort = when { port == -1 || port == 443 -> ""; else -> ":$port" }
        return "$scheme://$host$normalizedPort"
    }
}
