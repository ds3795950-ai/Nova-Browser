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

/**
 * Optional features available from a concrete engine adapter. These describe engine capability,
 * not permission or privacy policy; Nova must still enforce its own profile and user settings.
 */
enum class BrowserEngineCapability {
    DOWNLOADS,
    SITE_PERMISSIONS,
    READER_MODE,
    DESKTOP_MODE,
    VPN_PROXY,
    TRANSLATION,
    PASSWORD_AUTOFILL,
    NOVA_SHIELD,
    FIND_IN_PAGE,
    FULLSCREEN,
    POPUP_TABS,
    JS_DIALOGS,
    FILE_CHOOSER,
    CONTEXT_MENU,
    HTTP_AUTH,
    EXTERNAL_NAVIGATION,
    SITE_DATA,
    EXTENSIONS
}

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

    /** Features supported by this engine implementation. */
    val capabilities: Set<BrowserEngineCapability>
        get() = emptySet()

    fun loadUrl(url: String)
    fun goBack()
    fun goForward()
    fun reload()
    fun stop() {}
    fun findInPage(query: String, forward: Boolean, findNext: Boolean) {}
    fun stopFinding() {}
    fun clearCache() {}
    fun exitFullscreen() {}
    fun setDesktopMode(enabled: Boolean) {}
    /** Attach an engine-owned view to a host pane. Engines with fixed views may ignore it. */
    fun attachTo(view: View) {}

    /** Detach the engine view without closing the tab or its browsing session. */
    fun detach() {}

    fun setActive(active: Boolean, focused: Boolean)
    fun onHostPause()
    fun onHostResume()

    /** Releases the engine tab and its native resources. Safe to call more than once. */
    override fun close()
}
