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
        val generation = preferences.getInt(KEY_PROFILE_GENERATION, 1).coerceAtLeast(1)
        return if (generation == 1) BrowserEngineProfilePolicy.NORMAL_CEFRIUM_PROFILE else "nova-normal-v$generation"
    }

    /** Persist a never-reused identity before callers delete the previous profile. */
    fun rotateCefriumProfile(): String {
        val next = "nova-normal-${java.util.UUID.randomUUID()}"
        check(preferences.edit().putString(KEY_PROFILE_NAME, next).commit()) {
            "Could not persist the new Cefrium profile identity"
        }
        return next
    }

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
    }
}
