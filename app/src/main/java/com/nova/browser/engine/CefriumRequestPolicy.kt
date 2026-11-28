package com.nova.browser.engine

import android.content.SharedPreferences
import android.net.Uri
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumExperimental
import com.cefrium.CefriumResourceInterceptor
import com.cefrium.CefriumResourceType

/** Preference-only Cefrium request policy safe to retain for the lifetime of a shared profile.
 * Cookie filtering is browser-request-only; Nova does not rely on per-origin cookie hooks
 * for profile-level service-worker requests, which may use the profile cookie store.
 */
class CefriumRequestPolicy(private val preferences: SharedPreferences) {
    fun imagesBlockedFor(url: String): Boolean =
        CefriumTrackingProtectionPolicy.shouldBlockImage(
            isImage = true,
            dataSaverEnabled = preferences.getBoolean("nova_data_saver_enabled", false),
            siteImagesAllowed = siteSettingAllowed(url, "images", true)
        )

    fun browserCookiesAllowedFor(url: String): Boolean = siteSettingAllowed(url, "cookies", true)

    fun dataSaverEnabled(): Boolean = preferences.getBoolean("nova_data_saver_enabled", false)

    fun shouldBlockTrackerWithoutTopLevel(url: String): Boolean =
        CefriumTrackingProtectionPolicy.shouldBlockRequestWithoutTopLevel(
            url, preferences.getBoolean("site_default_cefrium_ads", true)
        )

    fun shouldBlockTracker(url: String, topLevelUrl: String): Boolean =
        CefriumTrackingProtectionPolicy.shouldBlockRequest(
            url, topLevelUrl, siteSettingAllowed(topLevelUrl, "ads", true)
        )

    private fun siteSettingAllowed(url: String, setting: String, default: Boolean): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return default
        val scheme = uri.scheme?.lowercase() ?: return default
        val host = uri.host?.lowercase() ?: return default
        if (scheme != "http" && scheme != "https") return default
        val origin = "$scheme://$host${if (uri.port > 0) ":${uri.port}" else ""}"
        val key = "site_${setting}_cefrium_${Uri.encode(origin)}"
        val globalDefault = preferences.getBoolean("site_default_cefrium_$setting", default)
        return preferences.getBoolean(key, globalDefault)
    }
}

/** Profile-level hook for service-worker requests; holds no Activity or tab references. */
@OptIn(CefriumExperimental::class)
internal class CefriumProfileResourceInterceptor(
    private val policy: CefriumRequestPolicy
) : CefriumResourceInterceptor {
    override fun shouldBlockRequest(request: CefriumBrowser.ResourceRequest): Boolean {
        val pageUrl = request.topLevelUrl.ifBlank { request.documentUrl }
        if (request.getType() == CefriumResourceType.IMAGE) {
            // If Cefrium cannot provide the page origin for a worker request, apply only
            // global Data Saver. Never mistake the image host for the embedding site.
            val blockImage = if (pageUrl.isNotBlank()) policy.imagesBlockedFor(pageUrl)
                else policy.dataSaverEnabled()
            if (blockImage) return true
        }
        return if (pageUrl.isNotBlank()) policy.shouldBlockTracker(request.url, pageUrl)
            else policy.shouldBlockTrackerWithoutTopLevel(request.url)
    }
}
