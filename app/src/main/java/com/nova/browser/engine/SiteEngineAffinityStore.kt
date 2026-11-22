package com.nova.browser.engine

import android.content.SharedPreferences
import java.net.IDN
import java.net.URI
import java.util.Locale

/**
 * Persists exact-host engine affinity for normal browsing only. It deliberately does not
 * guess registrable domains (for example, it will not assume co.uk boundaries) or share
 * affinity with Guest/Private sessions.
 */
class SiteEngineAffinityStore(private val preferences: SharedPreferences) {
    fun get(url: String, profile: BrowserProfileKind = BrowserProfileKind.NORMAL): BrowserEngineType? {
        if (profile != BrowserProfileKind.NORMAL) return null
        val key = preferenceKey(url) ?: return null
        return preferences.getString(key, null)?.let { raw ->
            runCatching { BrowserEngineType.valueOf(raw) }.getOrNull()
        }
    }

    fun set(url: String, engine: BrowserEngineType, profile: BrowserProfileKind = BrowserProfileKind.NORMAL): Boolean {
        if (profile != BrowserProfileKind.NORMAL) return false
        val key = preferenceKey(url) ?: return false
        preferences.edit().putString(key, engine.name).apply()
        return true
    }

    fun remove(url: String, profile: BrowserProfileKind = BrowserProfileKind.NORMAL): Boolean {
        if (profile != BrowserProfileKind.NORMAL) return false
        val key = preferenceKey(url) ?: return false
        preferences.edit().remove(key).apply()
        return true
    }

    private fun preferenceKey(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.trimEnd('.')?.takeIf { it.isNotBlank() } ?: return null
        val asciiHost = runCatching { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES) }.getOrNull() ?: return null
        val defaultPort = (scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443)
        val authority = asciiHost.lowercase(Locale.ROOT) + if (uri.port >= 0 && !defaultPort) ":${uri.port}" else ""
        return KEY_PREFIX + authority
    }

    private companion object { const val KEY_PREFIX = "engine_affinity:" }
}
