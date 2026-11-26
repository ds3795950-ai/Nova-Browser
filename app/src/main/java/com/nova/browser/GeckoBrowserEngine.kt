package com.nova.browser

import org.mozilla.geckoview.GeckoSession

/** Gecko implementation. Cefrium can implement BrowserEngine without changing browser chrome. */
internal class GeckoBrowserEngine(private val session: GeckoSession) : BrowserEngine {
    override fun loadUrl(url: String) = session.loadUri(url)
    override fun goBack() = session.goBack()
    override fun goForward() = session.goForward()
    override fun reload() = session.reload()
    override fun stop() = session.stop()
    override fun setActive(active: Boolean) = session.setActive(active)
    override fun setFocused(focused: Boolean) = session.setFocused(focused)
    override fun close() = session.close()
}
