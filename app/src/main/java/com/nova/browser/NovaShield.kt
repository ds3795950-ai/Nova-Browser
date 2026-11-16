package com.nova.browser

import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime

enum class ShieldMode { OFF, STANDARD, AGGRESSIVE }

/** Uses GeckoView's native tracking-protection, cookie-isolation and Safe Browsing engines. */
object NovaShield {
    fun apply(runtime: GeckoRuntime, mode: ShieldMode) {
        val blocking = runtime.settings.contentBlocking
        when (mode) {
            ShieldMode.OFF -> {
                blocking.setAntiTracking(ContentBlocking.AntiTracking.NONE)
                blocking.setCookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_ALL)
                blocking.setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.NONE)
                blocking.setQueryParameterStrippingEnabled(false)
            }
            ShieldMode.STANDARD -> {
                blocking.setAntiTracking(ContentBlocking.AntiTracking.DEFAULT)
                blocking.setCookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
                blocking.setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT)
                blocking.setEnhancedTrackingProtectionCategory(ContentBlocking.EtpCategory.STANDARD)
                blocking.setQueryParameterStrippingEnabled(true)
            }
            ShieldMode.AGGRESSIVE -> {
                blocking.setAntiTracking(ContentBlocking.AntiTracking.STRICT or ContentBlocking.AntiTracking.FINGERPRINTING or ContentBlocking.AntiTracking.CRYPTOMINING or ContentBlocking.AntiTracking.EMAIL)
                blocking.setCookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                blocking.setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                blocking.setEnhancedTrackingProtectionCategory(ContentBlocking.EtpCategory.STRICT)
                blocking.setQueryParameterStrippingEnabled(true)
            }
        }
        // Purge stale tracker cookies and protect private/Guest sessions as well as normal tabs.
        // GeckoView's lists block known trackers and some ad trackers, not every cosmetic ad.
        blocking.setCookiePurging(mode != ShieldMode.OFF)
        blocking.setStrictSocialTrackingProtection(mode == ShieldMode.AGGRESSIVE)
        blocking.setCookieBehaviorPrivateMode(if (mode == ShieldMode.OFF) ContentBlocking.CookieBehavior.ACCEPT_ALL else ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
        blocking.setQueryParameterStrippingPrivateBrowsingEnabled(mode != ShieldMode.OFF)
        blocking.setEmailTrackerBlockingPrivateBrowsing(mode != ShieldMode.OFF)
        // Keep Google's native Safe Browsing lists active in every Shield mode, including Off.
        blocking.setSafeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
        blocking.setContentBlockingDatabaseStatus(true)
        runtime.settings.setGlobalPrivacyControl(mode != ShieldMode.OFF)
        runtime.settings.setFingerprintingProtection(mode == ShieldMode.AGGRESSIVE)
        runtime.settings.setFingerprintingProtectionPrivateBrowsing(true)
    }

    fun fromStored(value: String?): ShieldMode = runCatching { ShieldMode.valueOf(value ?: "STANDARD") }.getOrDefault(ShieldMode.STANDARD)
}
