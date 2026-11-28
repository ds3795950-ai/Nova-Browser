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
        // Sibling subdomains under the same listed tracker site are same-site, not third-party.
        val requestTrackerSite = trackerDomains.firstOrNull { host == it || host.endsWith(".$it") }
        val topTrackerSite = trackerDomains.firstOrNull { topHost == it || topHost.endsWith(".$it") }
        if (requestTrackerSite != null && requestTrackerSite == topTrackerSite) return false
        // Also preserve ordinary parent/child first-party relationships.
        if (host == topHost || host.endsWith(".$topHost") || topHost.endsWith(".$host")) return false
        return requestTrackerSite != null
    }

    /**
     * Profile-owned requests (notably service-worker fetches) may have no top-level URL.
     * Without that context, block only explicitly listed tracker hosts while protection
     * is enabled; do not guess whether an arbitrary host is third-party.
     */
    fun shouldBlockRequestWithoutTopLevel(url: String, enabled: Boolean): Boolean =
        enabled && isKnownTrackerHost(webHost(url))

    private fun isKnownTrackerHost(host: String?): Boolean = host != null &&
        trackerDomains.any { host == it || host.endsWith(".$it") }

    fun shouldBlockImage(isImage: Boolean, dataSaverEnabled: Boolean, siteImagesAllowed: Boolean): Boolean =
        isImage && (dataSaverEnabled || !siteImagesAllowed)

    private fun webHost(value: String): String? = runCatching {
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase()
        uri.host?.lowercase()?.removeSuffix(".")?.takeIf { scheme == "http" || scheme == "https" }
    }.getOrNull()
}
