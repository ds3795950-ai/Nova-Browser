package com.nova.browser.engine

import android.view.View
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * GeckoView adapter for one tab session. It owns the Gecko session lifecycle and exposes a
 * guarded delegate-installation boundary for Nova's host callbacks, while safely moving one
 * session between primary/split panes.
 */
class GeckoViewBrowserEngine(
    val session: GeckoSession,
    initialView: GeckoView,
    private val listener: BrowserEngineListener? = null
) : BrowserEngine {
    override val type = BrowserEngineType.GECKOVIEW
    // Gecko's SDK path is wired by Nova for downloads and site-permission prompts.
    override val capabilities = setOf(
        BrowserEngineCapability.DOWNLOADS,
        BrowserEngineCapability.SITE_PERMISSIONS,
        BrowserEngineCapability.READER_MODE,
        BrowserEngineCapability.DESKTOP_MODE,
        BrowserEngineCapability.FIND_IN_PAGE,
        BrowserEngineCapability.FULLSCREEN,
        BrowserEngineCapability.POPUP_TABS,
        BrowserEngineCapability.JS_DIALOGS,
        BrowserEngineCapability.FILE_CHOOSER,
        BrowserEngineCapability.CONTEXT_MENU,
        BrowserEngineCapability.HTTP_AUTH,
        BrowserEngineCapability.EXTERNAL_NAVIGATION,
        BrowserEngineCapability.SITE_DATA,
        BrowserEngineCapability.VPN_PROXY,
        BrowserEngineCapability.TRANSLATION,
        BrowserEngineCapability.PASSWORD_AUTOFILL,
        BrowserEngineCapability.NOVA_SHIELD,
        BrowserEngineCapability.EXTENSIONS
    )
    private var attachedView: GeckoView = initialView
    private var isAttached = false
    private var currentState = BrowserEngineState()
    var contentPermissions: List<GeckoSession.PermissionDelegate.ContentPermission> = emptyList()
        private set
    var trackingPermission: GeckoSession.PermissionDelegate.ContentPermission? = null
        private set
    private var active = false
    private var focused = false
    private var hostPaused = false
    private var closed = false
    private var delegatesInstalled = false

    override val view: View
        get() = attachedView

    override val state: BrowserEngineState
        get() = currentState

    /**
     * Installs the host-provided Gecko delegates exactly once before the session is used.
     * The adapter owns the registration boundary and guards against duplicate installation.
     */
    fun installDelegates(installer: (GeckoSession) -> Unit) {
        checkOpen()
        check(!delegatesInstalled) { "Gecko delegates have already been installed" }
        installer(session)
        delegatesInstalled = true
    }

    override fun attachTo(view: View) {
        checkOpen()
        val target = view as? GeckoView
            ?: throw IllegalArgumentException("GeckoView engine requires a GeckoView host")
        if (isAttached && attachedView !== target) attachedView.releaseSession()
        attachedView = target
        attachedView.setSession(session)
        isAttached = true
        applyVisibility()
    }

    override fun detach() {
        if (closed || !isAttached) return
        attachedView.releaseSession()
        isAttached = false
        session.setActive(false)
        session.setFocused(false)
    }

    override fun loadUrl(url: String) {
        checkOpen()
        publish(currentState.copy(url = url, isLoading = true, progress = 0, error = null))
        session.loadUri(url)
    }

    override fun goBack() { checkOpen(); session.goBack() }
    override fun goForward() { checkOpen(); session.goForward() }
    override fun reload() { checkOpen(); session.reload() }
    override fun stop() { if (!closed) session.stop() }
    override fun findInPage(query: String, forward: Boolean, findNext: Boolean) {
        if (closed) return
        session.getFinder().find(query, if (forward) GeckoSession.FINDER_FIND_FORWARD else GeckoSession.FINDER_FIND_BACKWARDS)
    }
    override fun stopFinding() { if (!closed) session.getFinder().clear() }
    override fun setDesktopMode(enabled: Boolean) = Unit // Gecko desktop mode is configured through GeckoSessionSettings.

    override fun setActive(active: Boolean, focused: Boolean) {
        checkOpen()
        this.active = active
        this.focused = focused
        applyVisibility()
    }

    override fun onHostPause() {
        if (closed) return
        hostPaused = true
        applyVisibility()
    }

    override fun onHostResume() {
        if (closed) return
        hostPaused = false
        applyVisibility()
    }

    /** Allows the existing Gecko delegates to keep the shared state snapshot current. */
    fun updateState(state: BrowserEngineState) {
        if (!closed) publish(state)
    }

    /** Gecko-specific site permissions stay with the Gecko adapter, not the neutral tab model. */
    fun updatePermissions(permissions: List<GeckoSession.PermissionDelegate.ContentPermission>) {
        if (closed) return
        contentPermissions = permissions.toList()
        trackingPermission = permissions.firstOrNull {
            it.permission == GeckoSession.PermissionDelegate.PERMISSION_TRACKING
        }
    }

    override fun close() {
        if (closed) return
        detach()
        closed = true
        session.close()
    }

    private fun applyVisibility() {
        if (closed) return
        session.setActive(active && !hostPaused && isAttached)
        session.setFocused(focused && active && !hostPaused && isAttached)
    }

    private fun publish(next: BrowserEngineState) {
        currentState = next
        listener?.onStateChanged(next)
    }

    private fun checkOpen() {
        check(!closed) { "GeckoView browser engine is closed" }
    }
}
