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
 * Creates one GeckoView adapter per tab. Delegates are installed exactly once before opening
 * the session, so no navigation or content callback can race an unconfigured session.
 */
class GeckoViewEngineFactory(private val runtime: GeckoRuntime) {
    fun create(
        settings: GeckoSessionSettings,
        initialView: GeckoView,
        delegateInstaller: GeckoSessionDelegateInstaller
    ): GeckoViewBrowserEngine {
        val session = GeckoSession(settings)
        val engine = GeckoViewBrowserEngine(session, initialView)
        engine.installDelegates(delegateInstaller)
        session.open(runtime)
        return engine
    }
}
