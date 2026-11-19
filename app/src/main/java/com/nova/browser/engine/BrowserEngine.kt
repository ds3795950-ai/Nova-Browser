package com.nova.browser.engine

import android.view.View

/** Rendering engine implementations supported by Nova. */
enum class BrowserEngineType {
    GECKOVIEW,
    CEFRIUM
}

/** A small, engine-neutral snapshot for the browser chrome. */
data class BrowserEngineState(
    val url: String? = null,
    val title: String? = null,
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val error: String? = null
)

fun interface BrowserEngineListener {
    fun onStateChanged(state: BrowserEngineState)
}

/**
 * Engine boundary for one browser tab. Tab storage, history, bookmarks, Shield and policy stay
 * outside this interface in Nova's app-level services. Implementations own only engine resources.
 */
interface BrowserEngine : AutoCloseable {
    val type: BrowserEngineType
    val view: View
    val state: BrowserEngineState

    fun loadUrl(url: String)
    fun goBack()
    fun goForward()
    fun reload()
    fun setActive(active: Boolean, focused: Boolean)
    fun onHostPause()
    fun onHostResume()

    /** Releases the engine tab and its native resources. Safe to call more than once. */
    override fun close()
}
