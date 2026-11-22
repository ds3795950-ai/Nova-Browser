package com.nova.browser.engine

/** Fixed storage rules for browser profiles. Only Normal Mode may use Cefrium. */
object BrowserEngineProfilePolicy {
    const val NORMAL_CEFRIUM_PROFILE = "nova-normal-v1"

    fun cefriumPersistentProfile(profile: BrowserProfileKind): String? =
        if (profile == BrowserProfileKind.NORMAL) NORMAL_CEFRIUM_PROFILE else null

    /** Private and Guest profiles are GeckoView-only; neither may open a Cefrium profile. */
    fun cefriumUsesIncognitoProfile(profile: BrowserProfileKind): Boolean = when (profile) {
        BrowserProfileKind.NORMAL, BrowserProfileKind.PRIVATE, BrowserProfileKind.GUEST -> false
    }

    fun geckoContextId(profile: BrowserProfileKind, guestContextId: String): String? = when (profile) {
        BrowserProfileKind.NORMAL -> null
        BrowserProfileKind.PRIVATE -> null
        BrowserProfileKind.GUEST -> guestContextId.takeIf { it.isNotBlank() }
    }

    fun isEphemeral(profile: BrowserProfileKind): Boolean = profile != BrowserProfileKind.NORMAL
}


/** Cefrium 0.21.0 ships native libraries for these Android ABIs only. */
object CefriumRuntimeAvailability {
    private val supportedAbis = setOf("arm64-v8a", "x86_64")

    fun supportsAny(deviceAbis: Iterable<String>): Boolean =
        deviceAbis.any { it in supportedAbis }
}
