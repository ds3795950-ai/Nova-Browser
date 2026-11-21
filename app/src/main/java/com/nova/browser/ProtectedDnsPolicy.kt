package com.nova.browser

internal enum class ProtectedDnsMode { OFF, PREFER_HTTPS }

internal object ProtectedDnsPolicy {
    fun mode(enabled: Boolean): ProtectedDnsMode =
        if (enabled) ProtectedDnsMode.PREFER_HTTPS else ProtectedDnsMode.OFF
}
