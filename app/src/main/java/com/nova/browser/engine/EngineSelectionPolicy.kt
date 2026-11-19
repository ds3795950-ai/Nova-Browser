package com.nova.browser.engine

/** User-facing engine preference. */
enum class BrowserEngineMode { AUTOMATIC, CEFRIUM, GECKOVIEW }

enum class BrowserProfileKind { NORMAL, PRIVATE, GUEST }

data class EngineSelectionRequest(
    val mode: BrowserEngineMode = BrowserEngineMode.CEFRIUM,
    val profile: BrowserProfileKind = BrowserProfileKind.NORMAL,
    /** Per-site affinity is considered only in Automatic mode. */
    val siteAffinity: BrowserEngineType? = null,
    val cefriumAvailable: Boolean,
    val cefriumCompatible: Boolean = true
)

data class EngineSelection(
    val engine: BrowserEngineType,
    val reason: String,
    val usedFallback: Boolean = false
)

/** Pure policy so the rules can be unit-tested without starting a native browser engine. */
object EngineSelectionPolicy {
    fun resolve(request: EngineSelectionRequest): EngineSelection {
        val requested = when (request.profile) {
            BrowserProfileKind.GUEST -> BrowserEngineType.GECKOVIEW
            BrowserProfileKind.PRIVATE -> BrowserEngineType.GECKOVIEW
            BrowserProfileKind.NORMAL -> when (request.mode) {
                BrowserEngineMode.CEFRIUM -> BrowserEngineType.CEFRIUM
                BrowserEngineMode.GECKOVIEW -> BrowserEngineType.GECKOVIEW
                BrowserEngineMode.AUTOMATIC -> request.siteAffinity ?: BrowserEngineType.CEFRIUM
            }
        }

        if (requested != BrowserEngineType.CEFRIUM) {
            return EngineSelection(requested, if (request.profile == BrowserProfileKind.GUEST) {
                "Guest Mode always uses GeckoView"
            } else if (request.mode == BrowserEngineMode.AUTOMATIC && request.siteAffinity != null) {
                "Using the saved engine preference for this site"
            } else "GeckoView selected")
        }
        if (!request.cefriumAvailable) {
            return EngineSelection(BrowserEngineType.GECKOVIEW, "Cefrium is unavailable; using GeckoView", true)
        }
        if (!request.cefriumCompatible) {
            return EngineSelection(BrowserEngineType.GECKOVIEW, "This site is marked incompatible with Cefrium; using GeckoView", true)
        }
        return EngineSelection(BrowserEngineType.CEFRIUM, if (request.mode == BrowserEngineMode.AUTOMATIC && request.siteAffinity == null && request.profile != BrowserProfileKind.GUEST) {
            "Automatic mode prefers Cefrium"
        } else "Cefrium selected")
    }
}
