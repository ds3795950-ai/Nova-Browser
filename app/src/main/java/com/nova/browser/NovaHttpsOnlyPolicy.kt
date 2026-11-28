package com.nova.browser

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/** HTTPS-Only policy. Private/local HTTP is allowed only when the user keeps that exception enabled. */
internal object NovaHttpsOnlyPolicy {
    enum class Decision { ALLOW, UPGRADE_TO_HTTPS, BLOCK }

    fun decide(url: String, httpsOnlyEnabled: Boolean, allowLocalHttp: Boolean = true): Decision {
        if (!httpsOnlyEnabled) return Decision.ALLOW
        val trimmed = url.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return if (trimmed.startsWith("http:", ignoreCase = true)) Decision.BLOCK else Decision.ALLOW
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return Decision.ALLOW
        if (scheme != "http") return Decision.ALLOW
        val host = uri.host?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: return Decision.BLOCK
        if (allowLocalHttp && isLocalOrPrivateHost(host)) return Decision.ALLOW
        return Decision.UPGRADE_TO_HTTPS
    }

    /** Replace only the validated scheme so percent-escaped path/query/fragment delimiters survive. */
    fun upgradeToHttps(url: String): String? {
        val trimmed = url.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (uri.scheme?.equals("http", ignoreCase = true) != true || uri.host.isNullOrBlank()) return null
        val colon = trimmed.indexOf(':')
        if (colon <= 0) return null
        return "https" + trimmed.substring(colon)
    }

    private fun isLocalOrPrivateHost(rawHost: String): Boolean {
        val host = rawHost.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT).removeSuffix(".")
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".localhost")) return true
        val parts = host.split('.')
        if (parts.size == 4) {
            val nums = parts.map { it.toIntOrNull() ?: return false }
            if (nums.any { it !in 0..255 }) return false
            if (nums[0] == 10 || nums[0] == 127) return true
            if (nums[0] == 192 && nums[1] == 168) return true
            if (nums[0] == 172 && nums[1] in 16..31) return true
            if (nums[0] == 169 && nums[1] == 254) return true
            return false
        }
        // Only parse colon-containing IPv6 literals; never resolve arbitrary hostnames here.
        if (!host.contains(':')) return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet6Address ?: return false
        val bytes = address.address
        val first = bytes[0].toInt() and 0xff
        return address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            (first and 0xfe) == 0xfc // RFC 4193 unique-local fc00::/7
    }
}
