package com.nova.browser.engine

import android.app.Activity
import android.view.View
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumProfile

/** Cefrium implementation of the shared per-tab engine contract. */
class CefriumBrowserEngine(
    activity: Activity,
    profile: CefriumProfile,
    private val listener: BrowserEngineListener? = null
) : BrowserEngine {
    override val type = BrowserEngineType.CEFRIUM
    private val browser = CefriumBrowser.createWithSurface(activity, profile)
    override val view: View = browser.getSurfaceContainer()
    private var currentState = BrowserEngineState(
        url = browser.getUrl(),
        title = browser.getTitle(),
        progress = browser.getProgress()
    )
    private var closed = false

    override val state: BrowserEngineState
        get() = currentState

    init {
        // Cefrium is pre-1.0. Keep its callback surface isolated here so SDK API migrations do not
        // leak into Nova's tab manager. These callbacks are retained for compatibility with 0.21.0.
        browser.setOnUrlChangedListener { url ->
            publish(currentState.copy(url = url))
        }
        browser.setOnTitleChangedListener { title ->
            publish(currentState.copy(title = title))
        }
        browser.setOnLoadingStateChangedListener { loading, canGoBack, canGoForward ->
            publish(currentState.copy(
                isLoading = loading,
                progress = browser.getProgress().coerceIn(0, 100),
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                error = null
            ))
        }
    }

    override fun loadUrl(url: String) {
        checkOpen()
        browser.loadUrl(url)
    }

    override fun goBack() {
        checkOpen()
        if (currentState.canGoBack) browser.goBack()
    }

    override fun goForward() {
        checkOpen()
        if (currentState.canGoForward) browser.goForward()
    }

    override fun reload() {
        checkOpen()
        browser.reload()
    }

    override fun setActive(active: Boolean, focused: Boolean) {
        checkOpen()
        browser.setPageVisible(active)
        browser.setFocus(focused && active)
    }

    override fun onHostPause() {
        if (!closed) browser.onPause()
    }

    override fun onHostResume() {
        if (!closed) browser.onResume()
    }

    override fun close() {
        if (closed) return
        closed = true
        browser.close()
    }

    private fun publish(next: BrowserEngineState) {
        currentState = next
        listener?.onStateChanged(next)
    }

    private fun checkOpen() {
        check(!closed && !browser.isClosed()) { "Cefrium browser engine is closed" }
    }
}
