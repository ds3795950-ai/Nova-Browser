package com.nova.browser

import java.net.URI
import java.util.Locale

/** Rejects malformed or credential-bearing URLs before they enter the saved-login vault. */
internal object PasswordVaultPolicy {
    fun isAllowedOrigin(value: String): Boolean {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        return scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
            (uri.port == -1 || uri.port in 1..65535)
    }
    fun isAllowedOptionalOrigin(value: String?): Boolean = value.isNullOrBlank() || isAllowedOrigin(value)
}
