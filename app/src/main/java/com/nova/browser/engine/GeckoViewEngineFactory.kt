package com.nova.browser.engine

import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/** Host callbacks for Gecko-only delegate wiring. The engine factory owns session creation/open. */
fun interface GeckoSessionDelegateInstaller {
    fun install(session: GeckoSession)
}

/**
 * Creates one GeckoView adapter per tab. Delegates are installed exactly once before opening.
 * The host is notified after installation but before open, so early callbacks always resolve
 * back to a tab that already owns the adapter. Partial initialization is closed on failure.
 */
class GeckoViewEngineFactory(private val runtime: GeckoRuntime) {
    fun create(
        settings: GeckoSessionSettings,
        initialView: GeckoView,
        delegateInstaller: GeckoSessionDelegateInstaller,
        onEngineCreated: (GeckoViewBrowserEngine) -> Unit
    ): GeckoViewBrowserEngine {
        val session = GeckoSession(settings)
        val engine = GeckoViewBrowserEngine(session, initialView)
        try {
            engine.installDelegates(delegateInstaller)
            onEngineCreated(engine)
            session.open(runtime)
            return engine
        } catch (error: Throwable) {
            runCatching { engine.close() }
            throw error
        }
    }
}
