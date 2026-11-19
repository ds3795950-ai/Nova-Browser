package com.nova.browser.engine

/**
 * Names and lifetimes for engine-native profiles. A null persistent profile name means
 * the engine must create a fresh, in-memory private profile for that tab/session.
 */
object BrowserEngineProfilePolicy {
    const val NORMAL_CEFRIUM_PROFILE = "nova-normal-v1"

    fun cefriumPersistentProfile(profile: BrowserProfileKind): String? =
        if (profile == BrowserProfileKind.NORMAL) NORMAL_CEFRIUM_PROFILE else null

    fun geckoContextId(profile: BrowserProfileKind, guestContextId: String): String? = when (profile) {
        BrowserProfileKind.NORMAL -> null
        BrowserProfileKind.PRIVATE -> null // Gecko private mode is per-session, not a shared context.
        BrowserProfileKind.GUEST -> guestContextId.takeIf { it.isNotBlank() }
    }

    fun isEphemeral(profile: BrowserProfileKind): Boolean = profile != BrowserProfileKind.NORMAL
}
