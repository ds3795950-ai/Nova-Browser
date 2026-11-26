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
        preferences.getString(KEY_PROFILE_NAME, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generation = preferences.getInt(KEY_PROFILE_GENERATION, 1).coerceIn(1, 999_999)
        return if (generation == 1) BrowserEngineProfilePolicy.NORMAL_CEFRIUM_PROFILE else "nova-normal-v$generation"
    }

    fun rotateCefriumProfile(): String? {
        val previous = cefriumProfileName()
        val next = "nova-normal-${java.util.UUID.randomUUID()}"
        if (!preferences.edit().putString(KEY_PROFILE_NAME, next).commit()) {
            // SharedPreferences updates memory before reporting a disk-write failure. Restore the
            // old active identity immediately so retrying Clear cannot orphan the real profile.
            if (!preferences.edit().putString(KEY_PROFILE_NAME, previous).commit()) {
                android.util.Log.e("NovaBrowser", "Could not durably restore the previous Cefrium profile identity")
            }
            return null
        }
        return next
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
        const val KEY_PROFILE_NAME = "cefrium_profile_name"
        const val KEY_RETIRED_PROFILES = "cefrium_retired_profiles"
    }
}
