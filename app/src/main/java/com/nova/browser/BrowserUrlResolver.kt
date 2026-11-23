package com.nova.browser

import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

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

        val scheme = schemePattern.find(value)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        if (scheme in supportedSchemes) return value

        if (isBareHostname(value)) {
            val authority = value.substringBefore('/').substringBefore('?').substringBefore('#')
            val host = if (authority.startsWith("[")) {
                authority.substringBefore("]") + "]"
            } else if (authority.count { it == ':' } == 1 &&
                authority.substringAfterLast(':').all(Char::isDigit)
            ) {
                authority.substringBeforeLast(':')
            } else {
                authority
            }
            val local = host.equals("localhost", true) ||
                host.endsWith(".local", true) ||
                isPrivateIpv4(host) ||
                isLocalIpv6(host)
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
        if (value.any(Char::isWhitespace) || '\\' in value) return false
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
            val address = authority.substring(1, closingBracket)
            return address.contains(':') &&
                runCatching { InetAddress.getByName(address) is Inet6Address }.getOrDefault(false)
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
            IDN.toASCII(host.trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        }.getOrElse {
            // Preserve the browser's existing support for underscore-containing host labels.
            if ('_' !in host || !host.all { it.isLetterOrDigit() || it in "._-" }) return false
            host.trimEnd('.').lowercase(Locale.ROOT)
        }
        if (ascii.isBlank()) return false
        if (ascii == "localhost") return true
        if (ascii.matches(Regex("[0-9.]+"))) {
            val octets = ascii.split('.')
            return octets.size == 4 && octets.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
        }

        val labels = ascii.split('.')
        if (labels.any { !validDnsLabel(it) }) return false
        if (ascii.endsWith(".local")) return true
        return labels.size >= 2 && labels.last().length >= 2
    }

    private fun validDnsLabel(label: String): Boolean =
        label.length in 1..63 &&
            label.firstOrNull()?.isLetterOrDigit() == true &&
            label.lastOrNull()?.isLetterOrDigit() == true &&
            label.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 0 ||
            parts[0] == 10 ||
            parts[0] == 127 ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 169 && parts[1] == 254)
    }

    private fun isLocalIpv6(host: String): Boolean {
        if (!host.startsWith("[") || !host.endsWith("]")) return false
        val address = runCatching {
            InetAddress.getByName(host.substring(1, host.length - 1)) as? Inet6Address
        }.getOrNull() ?: return false
        val firstByte = address.address.firstOrNull()?.toInt()?.and(0xff) ?: return false
        val uniqueLocal = firstByte and 0xfe == 0xfc
        return address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress || uniqueLocal
    }
}
