package com.nova.browser

/** User-facing honesty for proxy vs system VPN / Tor claims. */
internal object NovaVpnHonestyPolicy {
    fun vpnStatusLabel(enabled: Boolean, routingReady: Boolean): String = when {
        !enabled -> "1VPN off (browser proxy, not a device VPN)"
        !routingReady -> "1VPN connecting… browser traffic only"
        else -> "1VPN on · browser proxy (not system-wide)"
    }

    fun guestTorStatusLabel(active: Boolean, ready: Boolean): String = when {
        !active -> "Guest Tor off"
        !ready -> "Guest Tor starting…"
        else -> "Guest Tor on · Gecko runtime proxy (not Tor Browser)"
    }
}
