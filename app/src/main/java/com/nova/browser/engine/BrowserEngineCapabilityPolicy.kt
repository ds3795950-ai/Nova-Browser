package com.nova.browser.engine

/**
 * Capability fallback is separate from profile selection. It may move a Normal tab from
 * Cefrium to GeckoView when the requested feature is unavailable, but never moves a
 * Private or Guest tab into a persistent engine.
 */
data class BrowserEngineCapabilityRoute(
    val engine: BrowserEngineType?,
    val requiresSwitch: Boolean,
    val reason: String
) {
    val available: Boolean get() = engine != null
}

object BrowserEngineCapabilitySets {
    val GECKOVIEW: Set<BrowserEngineCapability> = setOf(
        BrowserEngineCapability.DOWNLOADS,
        BrowserEngineCapability.SITE_PERMISSIONS,
        BrowserEngineCapability.READER_MODE,
        BrowserEngineCapability.DESKTOP_MODE,
        BrowserEngineCapability.TRANSLATION,
        BrowserEngineCapability.PASSWORD_AUTOFILL,
        BrowserEngineCapability.NOVA_SHIELD
    )

    val CEFRIUM: Set<BrowserEngineCapability> = setOf(
        BrowserEngineCapability.DOWNLOADS,
        BrowserEngineCapability.SITE_PERMISSIONS,
        BrowserEngineCapability.READER_MODE,
        BrowserEngineCapability.DESKTOP_MODE,
        BrowserEngineCapability.VPN_PROXY
    )
}

object BrowserEngineCapabilityPolicy {
    fun resolve(
        currentEngine: BrowserEngineType,
        profile: BrowserProfileKind,
        required: BrowserEngineCapability,
        currentCapabilities: Set<BrowserEngineCapability>,
        geckoCapabilities: Set<BrowserEngineCapability> = BrowserEngineCapabilitySets.GECKOVIEW
    ): BrowserEngineCapabilityRoute {
        if (profile != BrowserProfileKind.NORMAL) {
            return if (currentEngine == BrowserEngineType.GECKOVIEW && required in geckoCapabilities) {
                BrowserEngineCapabilityRoute(BrowserEngineType.GECKOVIEW, false, "Ephemeral profiles stay on GeckoView")
            } else if (required in geckoCapabilities) {
                BrowserEngineCapabilityRoute(BrowserEngineType.GECKOVIEW, true, "Private and Guest profiles require GeckoView")
            } else {
                BrowserEngineCapabilityRoute(null, false, "The feature is unavailable in this privacy profile")
            }
        }

        if (required in currentCapabilities) {
            return BrowserEngineCapabilityRoute(currentEngine, false, "Current engine supports the feature")
        }
        if (currentEngine == BrowserEngineType.CEFRIUM && required in geckoCapabilities) {
            return BrowserEngineCapabilityRoute(BrowserEngineType.GECKOVIEW, true, "The feature requires GeckoView")
        }
        return BrowserEngineCapabilityRoute(null, false, "No available engine supports the requested feature")
    }
}
