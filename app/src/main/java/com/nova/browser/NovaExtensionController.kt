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

    fun menuItems(extensionId: String, enabled: Boolean, hasOptionsPage: Boolean): List<String> =
        buildList {
            if (actions.containsKey(extensionId)) add("Open popup")
            add(if (enabled) "Disable" else "Enable")
            if (hasOptionsPage) add("Options")
            add("Uninstall")
        }
    fun canOpenPersistentManager(isGuest: Boolean): Boolean = !isGuest
}
