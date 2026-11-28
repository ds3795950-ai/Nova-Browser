package com.nova.browser.engine

import java.net.URI

/** Conservative request-level protections available to Normal Cefrium tabs. */
object CefriumTrackingProtectionPolicy {
    private val trackerDomains = setOf(
        "doubleclick.net", "googlesyndication.com", "google-analytics.com",
        "googletagmanager.com", "adservice.google.com", "connect.facebook.net",
        "pixel.facebook.com", "analytics.twitter.com", "ads-twitter.com",
        "bat.bing.com", "clarity.ms", "hotjar.com", "segment.io",
        "mixpanel.com", "amplitude.com", "scorecardresearch.com",
        "quantserve.com", "criteo.com", "taboola.com", "outbrain.com",
        "adnxs.com", "rubiconproject.com", "pubmatic.com", "openx.net",
        "amazon-adsystem.com"
    )

    fun shouldBlockRequest(url: String, topLevelUrl: String, enabled: Boolean): Boolean {
        if (!enabled) return false
        val host = webHost(url) ?: return false
        val topHost = webHost(topLevelUrl) ?: return false
        // Never block a first-party request just because its host also appears in the list.
        if (host == topHost || host.endsWith(".$topHost") || topHost.endsWith(".$host")) return false
        return trackerDomains.any { host == it || host.endsWith(".$it") }
    }

    fun shouldBlockImage(isImage: Boolean, dataSaverEnabled: Boolean, siteImagesAllowed: Boolean): Boolean =
        isImage && (dataSaverEnabled || !siteImagesAllowed)

    private fun webHost(value: String): String? = runCatching {
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase()
        uri.host?.lowercase()?.takeIf { scheme == "http" || scheme == "https" }
    }.getOrNull()
}
