package com.nova.browser

import androidx.activity.ComponentActivity
import android.app.AlertDialog
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/** Owns the installed-extension list and its enable/disable/options/uninstall UI. */
internal class NovaExtensionManagerUi(
    private val activity: ComponentActivity,
    private val geckoController: WebExtensionController,
    private val extensionController: NovaExtensionController,
    private val configureDelegates: (WebExtension) -> Unit,
    private val installExtensionPrompt: () -> Unit,
    private val createExtensionTab: (String) -> Unit,
    private val openAddonsSite: () -> Unit,
    private val showMessage: (String) -> Unit
) {
    fun show() {
        geckoController.list().accept({ extensions ->
            activity.runOnUiThread {
                val installed = extensions.orEmpty().filterNot { it?.id == NovaBrowserVpnController.EXTENSION_ID }
                installed.filterNotNull().forEach(configureDelegates)
                val rows = installed.map { extension ->
                    val metadata = extension?.metaData
                    "${metadata?.name ?: extension?.id ?: "Extension"} · ${if (metadata?.enabled == true) "Enabled" else "Disabled"}"
                }.toTypedArray()
                AlertDialog.Builder(activity)
                    .setTitle("Nova Add-ons · ${installed.size}")
                    .setMessage("Signed Firefox-compatible extensions are supported. Select an add-on to open its popup, configure it, or manage it.")
                    .setItems(rows) { _, which ->
                        val extension = installed[which]
                        if (extension == null) {
                            showMessage("This add-on entry is unavailable")
                            return@setItems
                        }
                        showExtensionActions(extension)
                    }
                    .setPositiveButton("Install signed XPI URL") { _, _ -> installExtensionPrompt() }
                    .setNeutralButton("Browse add-ons") { _, _ -> openAddonsSite() }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }, { error ->
            activity.runOnUiThread {
                showMessage("Could not list extensions: ${error?.localizedMessage ?: "Unknown error"}")
            }
        })
    }

    private fun showExtensionActions(extension: WebExtension) {
        val metadata = extension.metaData
        val actions = extensionController.menuItems(
            extensionId = extension.id,
            enabled = metadata?.enabled == true,
            hasOptionsPage = !metadata?.optionsPageUrl.isNullOrBlank()
        )
        AlertDialog.Builder(activity)
            .setTitle(metadata?.name ?: extension.id)
            .setItems(actions.toTypedArray()) { _, index ->
                when (actions.getOrNull(index)) {
                    "Open popup" -> extensionController.actions[extension.id]?.click()
                    "Options" -> metadata?.optionsPageUrl?.let(createExtensionTab)
                    "Disable" -> geckoController.disable(extension, WebExtensionController.EnableSource.USER)
                        .accept({ showMessage("Extension disabled") }, {
                            showMessage("Could not disable: ${it?.localizedMessage ?: "Unknown error"}")
                        })
                    "Enable" -> geckoController.enable(extension, WebExtensionController.EnableSource.USER)
                        .accept({
                            configureDelegates(extension)
                            showMessage("Extension enabled")
                        }, {
                            showMessage("Could not enable: ${it?.localizedMessage ?: "Unknown error"}")
                        })
                    "Uninstall" -> AlertDialog.Builder(activity)
                        .setMessage("Remove this extension and its data?")
                        .setPositiveButton("Uninstall") { _, _ ->
                            geckoController.uninstall(extension).accept({
                                extensionController.remove(extension.id)
                                showMessage("Extension removed")
                            }, {
                                showMessage("Could not uninstall: ${it?.localizedMessage ?: "Unknown error"}")
                            })
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
