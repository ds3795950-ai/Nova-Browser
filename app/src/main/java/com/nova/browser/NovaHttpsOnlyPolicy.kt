package com.nova.browser

import java.net.URI
import java.util.Locale

/**
 * HTTPS-Only mode: upgrade or block cleartext navigations.
 * Local/private hosts may keep http when [allowLocalHttp] is true.
 */
internal object NovaHttpsOnlyPolicy {
    enum class Decision { ALLOW, UPGRADE_TO_HTTPS, BLOCK }

    fun decide(
        url: String,
        httpsOnlyEnabled: Boolean,
        allowLocalHttp: Boolean = true
    ): Decision {
        if (!httpsOnlyEnabled) return Decision.ALLOW
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return Decision.ALLOW
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return Decision.ALLOW
        if (scheme != "http") return Decision.ALLOW
        val host = uri.host?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: return Decision.BLOCK
        if (allowLocalHttp && isLocalOrPrivateHost(host)) return Decision.ALLOW
        return Decision.UPGRADE_TO_HTTPS
    }

    fun upgradeToHttps(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) != "http") return null
        val rebuilt = URI(
            "https",
            uri.userInfo,
            uri.host,
            uri.port,
            uri.path,
            uri.query,
            uri.fragment
        )
        return rebuilt.toASCIIString()
    }

    private fun isLocalOrPrivateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".localhost")) return true
        if (host == "127.0.0.1" || host == "::1" || host == "[::1]") return true
        val parts = host.split('.')
        if (parts.size == 4) {
            val nums = parts.map { it.toIntOrNull() ?: return false }
            if (nums.any { it !in 0..255 }) return false
            if (nums[0] == 10) return true
            if (nums[0] == 127) return true
            if (nums[0] == 192 && nums[1] == 168) return true
            if (nums[0] == 172 && nums[1] in 16..31) return true
            if (nums[0] == 169 && nums[1] == 254) return true
        }
        return false
    }
}
