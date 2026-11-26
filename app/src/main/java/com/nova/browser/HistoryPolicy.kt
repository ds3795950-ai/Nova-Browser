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
        "reset_token", "resettoken", "password_reset_token", "magic_token", "email_token",
        "verification_token", "accesstoken", "refreshtoken", "idtoken", "authtoken",
        "clientsecret", "authorization", "bearer", "code_verifier", "code_challenge",
        "otp", "state"
    )

    fun sanitizeForStorage(url: String): String? {
        if (url.isBlank() || url == "about:blank") return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!(uri.scheme.equals("http", true) || uri.scheme.equals("https", true))) return null
        // URI.host/rawUserInfo may be null for registry-based authorities such as
        // "alice:secret@example.com"; reject the raw delimiter before persisting.
        val authority = uri.rawAuthority?.takeIf {
            it.isNotBlank() && uri.rawUserInfo == null && '@' !in it
        } ?: return null
        val query = uri.rawQuery
        if (!query.isNullOrBlank()) {
            val hasSecret = query.split('&').any { part ->
                val rawKey = part.substringBefore('=')
                val key = runCatching { URLDecoder.decode(rawKey, "UTF-8") }
                    .getOrDefault(rawKey).lowercase()
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

    fun shouldAppendVisit(
        lastUrl: String?,
        lastVisitedAt: Long,
        nextUrl: String,
        now: Long,
        dedupeWindowMs: Long = 2_000L
    ): Boolean {
        if (lastUrl != nextUrl || now < lastVisitedAt) return true
        return now - lastVisitedAt >= dedupeWindowMs.coerceAtLeast(0L)
    }

    fun shouldPersist(url: String): Boolean = sanitizeForStorage(url) != null
}
