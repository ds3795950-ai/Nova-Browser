package com.nova.browser

import java.net.IDN
import java.net.URI
import java.net.URLEncoder

/** Pure URL/search routing so provider behavior can be unit tested on the JVM. */
object BrowserUrlResolver {
    private val schemePattern = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val supportedSchemes = setOf(
        "http", "https", "about", "mailto", "tel", "ftp", "data", "file", "blob",
        "intent", "market", "geo", "sms", "magnet", "ws", "wss", "content",
        "chrome", "resource", "view-source"
    )

    fun resolve(input: String, engine: String = "Google"): String? {
        val value = input.trim()
        if (value.isEmpty()) return null

        val scheme = schemePattern.find(value)?.groupValues?.get(1)?.lowercase()
        if (scheme in supportedSchemes) return value
        if (isBareHostname(value)) {
            val authority = value.substringBefore('/').substringBefore('?').substringBefore('#')
            val host = authority.substringBeforeLast(':').takeIf {
                authority.count { ch -> ch == ':' } == 1 && authority.substringAfterLast(':').all(Char::isDigit)
            } ?: authority
            val local = host.equals("localhost", true) ||
                host.endsWith(".local", true) ||
                isPrivateIpv4(host) ||
                (host.startsWith("[") && host.endsWith("]"))
            return (if (local) "http://" else "https://") + value
        }

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

        if (authority.startsWith("[")) {
            val closingBracket = authority.indexOf(']')
            if (closingBracket <= 1) return false
            val suffix = authority.substring(closingBracket + 1)
            if (suffix.isNotEmpty()) {
                if (!suffix.startsWith(':')) return false
                val port = suffix.drop(1).toIntOrNull() ?: return false
                if (port !in 1..65535) return false
            }
            return runCatching { URI("https://$authority").host != null }.getOrDefault(false)
        }

        val colon = authority.lastIndexOf(':')
        val host = if (colon >= 0) {
            val portText = authority.substring(colon + 1)
            if (portText.isEmpty() || !portText.all(Char::isDigit)) return false
            val port = portText.toIntOrNull() ?: return false
            if (port !in 1..65535) return false
            authority.substring(0, colon)
        } else authority
        if (host.isBlank() || ':' in host) return false

        val ascii = runCatching {
            IDN.toASCII(host.trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase()
        }.getOrNull() ?: return false
        if (ascii == "localhost") return true
        if (ascii.matches(Regex("[0-9.]+"))) {
            val octets = ascii.split('.')
            return octets.size == 4 && octets.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
        }
        if (ascii.endsWith(".local")) return ascii.split('.').all(::validDnsLabel)
        if (!ascii.contains('.')) return false
        val labels = ascii.split('.')
        return labels.all(::validDnsLabel) && labels.last().length >= 2
    }

    private fun validDnsLabel(label: String): Boolean =
        label.length in 1..63 &&
            label.firstOrNull()?.isLetterOrDigit() == true &&
            label.lastOrNull()?.isLetterOrDigit() == true &&
            label.all { it.isLetterOrDigit() || it == '-' }

    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 ||
            parts[0] == 127 ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 169 && parts[1] == 254)
    }
}
