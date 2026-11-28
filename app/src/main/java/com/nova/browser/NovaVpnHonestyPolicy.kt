package com.nova.browser

/** Distinguishes browser-scoped proxy routing from device-wide VPN or Tor Browser protection. */
internal object NovaVpnHonestyPolicy {
    fun vpnStatusLabel(enabled: Boolean, routingReady: Boolean): String = when {
        !enabled -> "1VPN off · no browser proxy routing"
        !routingReady -> "1VPN connecting · browser navigation is paused; not a device-wide VPN"
        else -> "1VPN ready · browser traffic only, not an Android-wide VPN"
    }

    fun guestTorStatusLabel(active: Boolean, ready: Boolean): String = when {
        !active -> "Guest Tor off"
        !ready -> "Guest Tor connecting or resetting · browsing stays blocked until verified"
        else -> "Guest Tor connected · embedded Tor via GeckoView proxy (not Tor Browser)"
    }
}
