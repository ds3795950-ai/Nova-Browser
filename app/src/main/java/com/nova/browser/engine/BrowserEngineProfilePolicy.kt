package com.nova.browser.engine

/** Fixed storage rules for browser profiles. Only Normal Mode may use Cefrium. */
object BrowserEngineProfilePolicy {
    const val NORMAL_CEFRIUM_PROFILE = "nova-normal-v1"

    fun kindFor(isPrivate: Boolean, isGuest: Boolean): BrowserProfileKind = when {
        isGuest -> BrowserProfileKind.GUEST
        isPrivate -> BrowserProfileKind.PRIVATE
        else -> BrowserProfileKind.NORMAL
    }

    fun cefriumPersistentProfile(profile: BrowserProfileKind): String? =
        if (profile == BrowserProfileKind.NORMAL) NORMAL_CEFRIUM_PROFILE else null

    /** Private and Guest profiles are GeckoView-only; neither may open a Cefrium profile. */
    fun cefriumUsesIncognitoProfile(profile: BrowserProfileKind): Boolean = when (profile) {
        BrowserProfileKind.NORMAL, BrowserProfileKind.PRIVATE, BrowserProfileKind.GUEST -> false
    }

    fun geckoContextId(profile: BrowserProfileKind, guestContextId: String): String? = when (profile) {
        BrowserProfileKind.NORMAL -> null
        BrowserProfileKind.PRIVATE -> null
        BrowserProfileKind.GUEST -> guestContextId.also {
            require(it.isNotBlank()) { "Guest sessions require an explicit ephemeral Gecko context ID" }
        }
    }

    fun isEphemeral(profile: BrowserProfileKind): Boolean = profile != BrowserProfileKind.NORMAL
}
