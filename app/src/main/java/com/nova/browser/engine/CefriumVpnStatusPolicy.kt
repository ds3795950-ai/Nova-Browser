package com.nova.browser.engine

/** Converts actual per-profile proxy results into conservative user-facing status. */
internal object CefriumVpnStatusPolicy {
    fun disabledStatus(allDisabledCleanly: Boolean): String = if (allDisabledCleanly) "VPN off"
        else "VPN off requested, but at least one Cefrium profile could not clear its proxy or restore WebRTC settings"

    fun enabledStatus(
        hasCredentials: Boolean,
        location: String,
        profileCount: Int,
        allProxyReady: Boolean,
        allTrafficBlocked: Boolean,
        allWebRtcProtected: Boolean
    ): String {
        if (profileCount == 0) return if (!hasCredentials)
            "Connecting to 1VPN… · new Cefrium profiles will receive a fail-closed proxy"
        else "Applying 1VPN proxy to Cefrium profiles…"
        if (!hasCredentials) return if (allTrafficBlocked && allWebRtcProtected)
            "1VPN credentials unavailable; Cefrium browsing is blocked by the fail-closed proxy"
        else if (allTrafficBlocked) "Cefrium browsing is blocked, but WebRTC protection could not be applied"
        else "Could not enforce the fail-closed proxy on every Cefrium profile; do not assume traffic is protected"
        return when {
            allProxyReady && allWebRtcProtected -> "1VPN proxy configured · ${location.uppercase()} · WebRTC protection active"
            allTrafficBlocked && !allProxyReady -> "1VPN endpoint could not be applied; Cefrium browsing is blocked by the fail-closed proxy"
            allProxyReady && !allWebRtcProtected -> "1VPN proxy configured, but WebRTC protection failed; leak protection is not verified"
            else -> "Could not apply 1VPN proxy to every Cefrium profile; direct routing may be possible"
        }
    }
}
