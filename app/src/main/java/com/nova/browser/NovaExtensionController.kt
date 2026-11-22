package com.nova.browser

import org.mozilla.geckoview.WebExtension

/** Owns the live action registry for installed GeckoView WebExtensions. */
internal class NovaExtensionController {
    val actions: MutableMap<String, WebExtension.Action> = mutableMapOf()
    fun register(extensionId: String, action: WebExtension.Action) {
        if (action.enabled == false) actions.remove(extensionId) else actions[extensionId] = action
    }
    fun remove(extensionId: String) { actions.remove(extensionId) }
    fun clear() { actions.clear() }
    fun canOpenPersistentManager(isGuest: Boolean): Boolean = !isGuest
}
