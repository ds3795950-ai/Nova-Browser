package com.nova.browser

import com.nova.browser.engine.BrowserEngineType
import com.nova.browser.engine.BrowserEngineMode

/** User-facing names for Nova's internal rendering choices. */
internal object NovaEnginePresentation {
    fun label(engine: BrowserEngineType): String = when (engine) {
        BrowserEngineType.CEFRIUM -> "Nova Core"
        BrowserEngineType.GECKOVIEW -> "Nova Compatibility"
    }

    fun normalModeLabel(mode: BrowserEngineMode): String = when (mode) {
        BrowserEngineMode.AUTOMATIC -> "Automatic · Nova chooses the best fit"
        BrowserEngineMode.CEFRIUM -> "Nova Core · for compatible pages"
        BrowserEngineMode.GECKOVIEW -> "Nova Compatibility · broader site support"
    }

    fun userFacingReason(reason: String): String = reason
        .replace("Cefrium", "Nova Core")
        .replace("GeckoView", "Nova Compatibility")
        .replace("Chromium", "page engine")
}
