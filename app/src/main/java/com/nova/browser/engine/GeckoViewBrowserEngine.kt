package com.nova.browser.engine

import android.content.Context
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/** GeckoView implementation of the shared per-tab engine contract. */
class GeckoViewBrowserEngine(
    context: Context,
    runtime: GeckoRuntime,
    settings: GeckoSessionSettings = GeckoSessionSettings.Builder().build(),
    private val listener: BrowserEngineListener? = null
) : BrowserEngine {
    override val type = BrowserEngineType.GECKOVIEW
    override val view = GeckoView(context)
    private val session = GeckoSession(settings)
    private var currentState = BrowserEngineState()
    private var active = false
    private var focused = false
    private var hostPaused = false
    private var closed = false

    override val state: BrowserEngineState
        get() = currentState

    init {
        session.open(runtime)
        view.setSession(session)
        session.setNavigationDelegate(object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                publish(currentState.copy(canGoBack = canGoBack))
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                publish(currentState.copy(canGoForward = canGoForward))
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                publish(currentState.copy(url = url))
            }
        })
        session.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                publish(currentState.copy(title = title))
            }
        })
        session.setProgressDelegate(object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                publish(currentState.copy(url = url, isLoading = true, progress = 0, error = null))
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                publish(currentState.copy(isLoading = false, progress = if (success) 100 else currentState.progress, error = if (success) null else "Page load failed"))
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                publish(currentState.copy(progress = progress.coerceIn(0, 100)))
            }
        })
    }

    override fun loadUrl(url: String) {
        checkOpen()
        session.loadUri(url)
    }

    override fun goBack() {
        checkOpen()
        if (currentState.canGoBack) session.goBack()
    }

    override fun goForward() {
        checkOpen()
        if (currentState.canGoForward) session.goForward()
    }

    override fun reload() {
        checkOpen()
        session.reload()
    }

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

    override fun close() {
        if (closed) return
        closed = true
        view.releaseSession()
        session.close()
    }

    private fun applyVisibility() {
        session.setActive(active && !hostPaused)
        session.setFocused(focused && active && !hostPaused)
    }

    private fun publish(next: BrowserEngineState) {
        currentState = next
        listener?.onStateChanged(next)
    }

    private fun checkOpen() {
        check(!closed) { "GeckoView browser engine is closed" }
    }
}
