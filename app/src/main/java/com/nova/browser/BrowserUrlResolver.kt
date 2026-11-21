package com.nova.browser

import java.net.IDN
import java.net.URI
import java.net.URLEncoder

/** Pure URL/search routing so provider behavior can be unit tested on the JVM. */
object BrowserUrlResolver {
    private val schemePattern = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val knownSchemes = setOf(
        "http", "https", "about", "mailto", "tel", "ftp", "data", "javascript", "file", "blob",
        "intent", "market", "geo", "sms", "magnet", "ws", "wss", "content", "chrome", "resource",
        "view-source"
    )

    fun resolve(input: String, engine: String = "Google"): String? {
        val value = input.trim()
        if (value.isEmpty()) return null
        val scheme = schemePattern.find(value)?.groupValues?.get(1)?.lowercase()
        if (scheme in knownSchemes) return value
        if (isBareHostname(value)) return "https://$value"
        val query = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        return when (engine) {
            "Bing" -> "https://www.bing.com/search?q=$query"
            "DuckDuckGo" -> "https://duckduckgo.com/?q=$query"
            "Brave Search" -> "https://search.brave.com/search?q=$query"
            else -> "https://www.google.com/search?q=$query"
        }
    }

    private fun isBareHostname(value: String): Boolean {
        if (value.any(Char::isWhitespace)) return false
        val uri = runCatching { URI("https://$value") }.getOrNull() ?: return false
        val authority = uri.rawAuthority ?: return false
        if (authority.isBlank() || '@' in authority) return false
        val host: String
        if (authority.startsWith("[")) {
            val closingBracket = authority.indexOf(']')
            if (closingBracket <= 1) return false
            host = authority.substring(1, closingBracket)
            val suffix = authority.substring(closingBracket + 1)
            if (suffix.isNotEmpty()) {
                if (!suffix.startsWith(':')) return false
                val port = suffix.drop(1).toIntOrNull() ?: return false
                if (port !in 1..65535) return false
            }
            return runCatching { URI("https://[$host]").host != null }.getOrDefault(false)
        }
        val colon = authority.lastIndexOf(':')
        host = if (colon >= 0 && authority.substring(colon + 1).all(Char::isDigit)) {
            val port = authority.substring(colon + 1).toIntOrNull() ?: return false
            if (port !in 1..65535) return false
            authority.substring(0, colon)
        } else authority
        if (host.isBlank() || ':' in host) return false
        val ascii = runCatching { IDN.toASCII(host.trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase() }.getOrNull() ?: return false
        if (ascii == "localhost") return true
        if (ascii.matches(Regex("[0-9.]+"))) {
            val octets = ascii.split('.')
            return octets.size == 4 && octets.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
        }
        if (!ascii.contains('.')) return false
        val labels = ascii.split('.')
        return labels.all { label -> label.length in 1..63 && label.firstOrNull()?.isLetterOrDigit() == true &&
            label.lastOrNull()?.isLetterOrDigit() == true && label.all { it.isLetterOrDigit() || it == '-' } } && labels.last().length >= 2
    }
}
