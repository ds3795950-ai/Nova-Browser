package com.nova.browser

/** Maps structured proxy state to UI copy; does not infer state from provider message text. */
internal object BrowserProxyStatusPolicy {
    fun describe(enabled: Boolean, supported: Boolean, location: String, detail: String): String = when {
        !enabled -> "Browser proxy: Off"
        !supported -> "Browser proxy not ready: ${detail.take(160)}"
        else -> "Browser proxy: On · $location · browser requests configured. Verify the exit IP independently."
    }
}
