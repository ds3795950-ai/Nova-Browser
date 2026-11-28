package com.nova.browser.engine

/** Explicitly describes whether an engine has a verified phishing/malware reputation service. */
object ThreatProtectionPolicy {
    enum class Availability {
        NATIVE_SAFE_BROWSING_ACTIVE,
        UNAVAILABLE_ON_CEFRIUM
    }

    fun availability(engine: BrowserEngineType): Availability = when (engine) {
        BrowserEngineType.GECKOVIEW -> Availability.NATIVE_SAFE_BROWSING_ACTIVE
        BrowserEngineType.CEFRIUM -> Availability.UNAVAILABLE_ON_CEFRIUM
    }
}
