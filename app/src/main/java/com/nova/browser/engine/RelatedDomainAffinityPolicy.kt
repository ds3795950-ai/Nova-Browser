package com.nova.browser.engine

import java.net.IDN
import java.net.URI
import java.util.Locale

/** Normalizes only user-supplied exact sign-in hosts; never guesses sibling domains. */
object RelatedDomainAffinityPolicy {
    fun normalizeOrigins(values: Collection<String>): Set<String> = values.mapNotNull(::normalizeOrigin).toSet()

    fun normalizeOrigin(value: String): String? {
        val input = value.trim()
        if (input.isEmpty() || input.any(Char::isWhitespace) || input.contains('*')) return null
        val candidate = if (input.contains("://")) input else "https://$input"
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
        val host = uri.host?.trimEnd('.')?.takeIf { it.isNotBlank() } ?: return null
        if (uri.port !in -1..65535) return null
        val asciiHost = runCatching { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) }.getOrNull() ?: return null
        if (asciiHost.isBlank() || asciiHost.startsWith('.') || asciiHost.endsWith('.') || ".." in asciiHost) return null
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "https://$asciiHost$port"
    }
}
