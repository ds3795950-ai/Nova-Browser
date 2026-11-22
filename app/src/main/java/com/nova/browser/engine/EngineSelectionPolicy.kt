package com.nova.browser.engine

/** User-facing engine preference for Normal tabs. */
enum class BrowserEngineMode { AUTOMATIC, CEFRIUM, GECKOVIEW }

enum class BrowserProfileKind { NORMAL, PRIVATE, GUEST }

data class EngineSelectionRequest(
    val mode: BrowserEngineMode = BrowserEngineMode.CEFRIUM,
    val profile: BrowserProfileKind = BrowserProfileKind.NORMAL,
    /** Per-site affinity is considered only in Automatic mode and only for Normal tabs. */
    val siteAffinity: BrowserEngineType? = null,
    val cefriumAvailable: Boolean,
    val cefriumCompatible: Boolean = true
)

data class EngineSelection(
    val engine: BrowserEngineType,
    val reason: String,
    val usedFallback: Boolean = false
)

/** Pure policy so privacy-mode engine rules can be tested without starting either engine. */
object EngineSelectionPolicy {
    fun resolve(request: EngineSelectionRequest): EngineSelection {
        if (request.profile == BrowserProfileKind.GUEST) {
            return EngineSelection(BrowserEngineType.GECKOVIEW, "Guest Mode always uses GeckoView")
        }

        // Private and Guest are hard privacy boundaries, not preferences. Normal-mode
        // engine choices and site affinities must never select Cefrium for these profiles.
        val requested = when (request.profile) {
            BrowserProfileKind.PRIVATE -> BrowserEngineType.GECKOVIEW
            BrowserProfileKind.GUEST -> BrowserEngineType.GECKOVIEW
            BrowserProfileKind.NORMAL -> when (request.mode) {
                BrowserEngineMode.CEFRIUM -> BrowserEngineType.CEFRIUM
                BrowserEngineMode.GECKOVIEW -> BrowserEngineType.GECKOVIEW
                BrowserEngineMode.AUTOMATIC -> request.siteAffinity ?: BrowserEngineType.CEFRIUM
            }
        }

        if (requested == BrowserEngineType.GECKOVIEW) {
            return EngineSelection(
                BrowserEngineType.GECKOVIEW,
                if (request.profile == BrowserProfileKind.PRIVATE) "Private Mode always uses GeckoView" else "GeckoView selected"
            )
        }
        if (!request.cefriumAvailable) {
            return EngineSelection(BrowserEngineType.GECKOVIEW, "Cefrium is unavailable; using GeckoView", true)
        }
        if (!request.cefriumCompatible) {
            return EngineSelection(BrowserEngineType.GECKOVIEW, "A required feature is not yet available in Cefrium; using GeckoView", true)
        }
        return EngineSelection(
            BrowserEngineType.CEFRIUM,
            if (request.mode == BrowserEngineMode.AUTOMATIC && request.siteAffinity == null) "Automatic mode prefers Cefrium" else "Cefrium selected"
        )
    }
}
