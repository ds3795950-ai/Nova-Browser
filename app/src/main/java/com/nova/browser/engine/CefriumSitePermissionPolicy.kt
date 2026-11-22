package com.nova.browser.engine

import java.net.URI
import java.util.Locale

/** Fail-closed validation for website permission prompts in the Cefrium adapter. */
object CefriumSitePermissionPolicy {
    private val supportedPermissions = setOf(
        "CAMERA",
        "MICROPHONE",
        "GEOLOCATION",
        "NOTIFICATIONS",
        "CLIPBOARD_READ",
        "NFC"
    )

    /** Returns normalized, supported permission names, or null when the request must be denied. */
    fun normalize(origin: String, permissions: Collection<String>): List<String>? {
        if (origin.isBlank() || origin != origin.trim() || origin.any(Char::isWhitespace)) return null
        val uri = runCatching { URI(origin) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val secureOrigin = scheme == "https" ||
            (scheme == "http" && host in LOCALHOST_HOSTS)
        if (!secureOrigin) return null

        val normalized = permissions.map { it.substringAfterLast('.').uppercase(Locale.ROOT) }
        if (normalized.isEmpty() || normalized.any { it !in supportedPermissions }) return null
        return normalized.distinct()
    }

    private val LOCALHOST_HOSTS = setOf("localhost", "127.0.0.1", "::1")
}
