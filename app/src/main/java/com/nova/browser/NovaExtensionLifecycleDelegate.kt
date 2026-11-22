package com.nova.browser

import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/** Bridges GeckoView extension lifecycle events into the browser's extension registry and UI. */
internal class NovaExtensionLifecycleDelegate(
    private val configureDelegates: (WebExtension) -> Unit,
    private val removeAction: (String) -> Unit,
    private val showMessage: (String) -> Unit
) {
    fun attach(controller: WebExtensionController) {
        controller.setAddonManagerDelegate(object : WebExtensionController.AddonManagerDelegate {
            override fun onInstalled(extension: WebExtension) {
                configureDelegates(extension)
                showMessage("Extension installed: ${extension.metaData?.name ?: extension.id}")
            }
            override fun onReady(extension: WebExtension) = configureDelegates(extension)
            override fun onEnabled(extension: WebExtension) = configureDelegates(extension)
            override fun onDisabled(extension: WebExtension) = removeAction(extension.id)
            override fun onUninstalled(extension: WebExtension) = removeAction(extension.id)
            override fun onInstallationFailed(extension: WebExtension?, installException: WebExtension.InstallException) {
                showMessage("Extension installation failed")
            }
        })
    }
}
