package com.nova.browser.engine

import android.content.SharedPreferences

/** Persistent user choices; Private/Guest browsing state is never written here. */
class BrowserEnginePreferenceStore(private val preferences: SharedPreferences) {
    private val affinity = SiteEngineAffinityStore(preferences)

    fun normalMode(): BrowserEngineMode = preferences.getString(KEY_NORMAL_MODE, null)
        ?.let { runCatching { BrowserEngineMode.valueOf(it) }.getOrNull() }
        ?: BrowserEngineMode.AUTOMATIC

    fun setNormalMode(mode: BrowserEngineMode) {
        preferences.edit().putString(KEY_NORMAL_MODE, mode.name).apply()
    }

    /** Null means the agreed Guest default (GeckoView). */
    fun guestEngineOverride(): BrowserEngineType? = preferences.getString(KEY_GUEST_ENGINE, null)
        ?.let { runCatching { BrowserEngineType.valueOf(it) }.getOrNull() }

    fun setGuestEngineOverride(engine: BrowserEngineType?) {
        preferences.edit().apply {
            if (engine == null) remove(KEY_GUEST_ENGINE) else putString(KEY_GUEST_ENGINE, engine.name)
        }.apply()
    }

    fun siteAffinity(url: String): BrowserEngineType? = affinity.get(url)

    fun setSiteAffinity(url: String, engine: BrowserEngineType): Boolean = affinity.set(url, engine)

    fun clearSiteAffinity(url: String): Boolean = affinity.remove(url)

    fun resolve(
        url: String,
        profile: BrowserProfileKind,
        cefriumAvailable: Boolean,
        cefriumCompatible: Boolean = true
    ): EngineSelection {
        val mode = normalMode()
        val affinityForNormal = if (profile == BrowserProfileKind.NORMAL && mode == BrowserEngineMode.AUTOMATIC) {
            affinity.get(url, profile)
        } else null
        return EngineSelectionPolicy.resolve(
            EngineSelectionRequest(
                mode = mode,
                profile = profile,
                guestEngineOverride = if (profile == BrowserProfileKind.GUEST) guestEngineOverride() else null,
                siteAffinity = affinityForNormal,
                cefriumAvailable = cefriumAvailable,
                cefriumCompatible = cefriumCompatible
            )
        )
    }

    private companion object {
        const val KEY_NORMAL_MODE = "browser_engine_mode"
        const val KEY_GUEST_ENGINE = "guest_engine_override"
    }
}
