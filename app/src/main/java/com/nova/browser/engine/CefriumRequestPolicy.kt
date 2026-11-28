package com.nova.browser.engine

import android.content.SharedPreferences
import android.net.Uri
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumExperimental
import com.cefrium.CefriumResourceInterceptor
import com.cefrium.CefriumResourceType

/** Preference-only Cefrium request policy safe to retain for the lifetime of a shared profile. */
internal class CefriumRequestPolicy(private val preferences: SharedPreferences) {
    fun imagesBlockedFor(url: String): Boolean =
        preferences.getBoolean("nova_data_saver_enabled", false) || !siteSettingAllowed(url, "images", true)

    fun cookiesAllowedFor(url: String): Boolean = siteSettingAllowed(url, "cookies", true)

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
        if (request.getType() == CefriumResourceType.IMAGE && policy.imagesBlockedFor(pageUrl)) return true
        return request.getType() != CefriumResourceType.MAIN_FRAME &&
            policy.shouldBlockTracker(request.url, pageUrl)
    }

    override fun onCanSendCookie(url: String, name: String, value: String): Boolean =
        policy.cookiesAllowedFor(url)

    override fun onCanSaveCookie(url: String, name: String, value: String): Boolean =
        policy.cookiesAllowedFor(url)
}
