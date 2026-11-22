package com.nova.browser

import com.nova.browser.engine.BrowserEngineType
import java.util.Locale

/** Stable Nova-owned persistence namespace for each browser engine. */
internal object EngineProfileStoragePolicy {
    fun suffix(engine: BrowserEngineType): String = engine.name.lowercase(Locale.ROOT)
    fun key(base: String, engine: BrowserEngineType): String = "${base}_${suffix(engine)}"
}
