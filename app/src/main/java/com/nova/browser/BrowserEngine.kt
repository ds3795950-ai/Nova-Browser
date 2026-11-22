package com.nova.browser

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
