package com.nova.browser

/** Events a concrete engine adapter must translate into browser chrome state. */
internal interface BrowserEngineCallbacks {
    fun onUrlChanged(url: String)
    fun onTitleChanged(title: String)
    fun onLoadingChanged(loading: Boolean, progress: Int)
    fun onSecurityChanged(secure: Boolean, certificateError: Boolean)
    fun onContentProcessCrashed()
}

/** Engine-neutral controls used by browser chrome. */
internal interface BrowserEngine {
    fun loadUrl(url: String)
    fun goBack()
    fun goForward()
    fun reload()
    fun stop()
    fun setActive(active: Boolean)
    fun setFocused(focused: Boolean)
    fun close()
}
