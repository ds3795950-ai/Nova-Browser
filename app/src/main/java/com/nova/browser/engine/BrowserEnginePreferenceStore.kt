package com.nova.browser.engine

import android.content.SharedPreferences

/** Persistent user choices; Private/Guest browsing state is never written here. */
class BrowserEnginePreferenceStore(private val preferences: SharedPreferences) {
    private val affinity = SiteEngineAffinityStore(preferences)

    fun normalMode(): BrowserEngineMode = preferences.getString(KEY_NORMAL_MODE, null)
        ?.let { runCatching { BrowserEngineMode.valueOf(it) }.getOrNull() }
        ?: BrowserEngineMode.CEFRIUM

    fun setNormalMode(mode: BrowserEngineMode) {
        preferences.edit().putString(KEY_NORMAL_MODE, mode.name).apply()
    }

    fun cefriumProfileName(): String {
        val generation = preferences.getInt(KEY_PROFILE_GENERATION, 1).coerceIn(1, 999_999)
        return if (generation == 1) BrowserEngineProfilePolicy.NORMAL_CEFRIUM_PROFILE else "nova-normal-v$generation"
    }

    fun rotateCefriumProfile(): String? {
        val next = (preferences.getInt(KEY_PROFILE_GENERATION, 1).coerceIn(1, 999_998) + 1)
        if (!preferences.edit().putInt(KEY_PROFILE_GENERATION, next).commit()) return null
        return cefriumProfileName()
    }

    fun retiredCefriumProfiles(): Set<String> =
        preferences.getStringSet(KEY_RETIRED_PROFILES, emptySet())?.toSet() ?: emptySet()

    fun recordRetiredCefriumProfile(profile: String): Boolean {
        if (profile.isBlank()) return false
        return preferences.edit().putStringSet(KEY_RETIRED_PROFILES, retiredCefriumProfiles() + profile).commit()
    }

    fun forgetRetiredCefriumProfile(profile: String): Boolean =
        preferences.edit().putStringSet(KEY_RETIRED_PROFILES, retiredCefriumProfiles() - profile).commit()

    fun siteAffinity(url: String): BrowserEngineType? = affinity.get(url)

    fun setSiteAffinity(url: String, engine: BrowserEngineType): Boolean = affinity.set(url, engine)

    /** Saves the same preference for only the exact sign-in hosts explicitly entered by the user. */
    fun setRelatedSiteAffinities(hosts: Collection<String>, engine: BrowserEngineType): Int =
        RelatedDomainAffinityPolicy.normalizeOrigins(hosts).count { affinity.set(it, engine) }

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
                siteAffinity = affinityForNormal,
                cefriumAvailable = cefriumAvailable,
                cefriumCompatible = cefriumCompatible
            )
        )
    }

    private companion object {
        const val KEY_NORMAL_MODE = "browser_engine_mode"
        const val KEY_PROFILE_GENERATION = "cefrium_profile_generation"
        const val KEY_RETIRED_PROFILES = "cefrium_retired_profiles"
    }
}
