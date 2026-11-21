package com.nova.browser

import java.net.URI
import java.net.URLDecoder

/** Filters non-web URLs and sensitive query parameters before history reaches disk or sync. */
internal object HistoryPolicy {
    private val sensitiveQueryKeys = setOf(
        "token", "access_token", "refresh_token", "id_token", "auth_token",
        "oauth_token", "oauth_verifier", "code", "authorization_code",
        "client_secret", "secret", "password", "passwd", "key", "api_key",
        "apikey", "sig", "signature", "jwt", "session", "sessionid",
        "reset_token", "magic_token", "state"
    )

    fun sanitizeForStorage(url: String): String? {
        if (url.isBlank() || url == "about:blank") return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!(uri.scheme.equals("http", true) || uri.scheme.equals("https", true))) return null
        val authority = uri.rawAuthority?.takeIf { it.isNotBlank() && uri.rawUserInfo == null } ?: return null
        val query = uri.rawQuery
        if (!query.isNullOrBlank()) {
            val hasSecret = query.split('&').any { part ->
                val key = runCatching { URLDecoder.decode(part.substringBefore('='), "UTF-8") }
                    .getOrDefault(part.substringBefore('=')).lowercase()
                key in sensitiveQueryKeys
            }
            if (hasSecret) return null
        }
        return buildString {
            append(uri.scheme.lowercase()).append("://").append(authority)
            append(uri.rawPath.orEmpty())
            if (!query.isNullOrBlank()) append('?').append(query)
        }
    }

    fun shouldPersist(url: String): Boolean = sanitizeForStorage(url) != null
}
