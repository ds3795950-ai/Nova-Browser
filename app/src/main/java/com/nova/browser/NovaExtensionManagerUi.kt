package com.nova.browser

import androidx.activity.ComponentActivity
import android.app.AlertDialog
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
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
                val rows = arrayOf("Recommended Firefox add-ons · Manage switches") + installed.map { extension ->
                    val metadata = extension?.metaData
                    "${metadata?.name ?: extension?.id ?: "Extension"} · ${if (metadata?.enabled == true) "Enabled" else "Disabled"}"
                }.toTypedArray()
                AlertDialog.Builder(activity)
                    .setTitle("Nova Add-ons · ${installed.size}")
                    .setMessage("Manage recommended add-ons or select an installed extension to open its popup, configure it, or change its state.")
                    .setItems(rows) { _, which ->
                        if (which == 0) {
                            showRecommendedAddons()
                            return@setItems
                        }
                        val extension = installed[which - 1]
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

    private data class RecommendedAddon(val name: String, val slug: String)
    private val recommendedAddons = listOf(
        RecommendedAddon("uBlock Origin", "ublock-origin"),
        RecommendedAddon("Dark Reader", "darkreader"),
        RecommendedAddon("AdGuard AdBlocker", "adguard-adblocker"),
        RecommendedAddon("Ghostery", "ghostery"),
        RecommendedAddon("ClearURLs", "clearurls"),
        RecommendedAddon("FoxyProxy Standard", "foxyproxy-standard"),
        RecommendedAddon("Decentraleyes", "decentraleyes"),
        RecommendedAddon("NoScript Security Suite", "noscript"),
        RecommendedAddon("Privacy Badger", "privacy-badger17"),
        RecommendedAddon("YouTube High Definition", "youtube-high-definition"),
        RecommendedAddon("Read Aloud", "read-aloud"),
        RecommendedAddon("Web Archives", "view-page-archive"),
        RecommendedAddon("Tomato Clock", "tomato-clock"),
        RecommendedAddon("Search by Image", "search_by_image"),
        RecommendedAddon("Firefox Relay", "private-relay"),
        RecommendedAddon("Google Search Fixer", "google-search-fixer"),
        RecommendedAddon("Video Background Play Fix", "video-background-play-fix")
    )
    private fun addonKey(name: String?): String = name.orEmpty().lowercase().filter { it.isLetterOrDigit() }

    private fun showRecommendedAddons() {
        geckoController.list().accept({ extensions ->
            activity.runOnUiThread {
                val installedByName = extensions.orEmpty().filterNotNull()
                    .associateBy { addonKey(it.metaData?.name) }.toMutableMap()
                val rows = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 8, 24, 8)
                }
                recommendedAddons.forEach { addon ->
                    val key = addonKey(addon.name)
                    var extension = installedByName[key]
                    var updating = false
                    val check = CheckBox(activity).apply {
                        text = when {
                            extension == null -> "${addon.name} · Not installed"
                            extension?.metaData?.enabled == true -> "${addon.name} · On"
                            else -> "${addon.name} · Off"
                        }
                        isChecked = extension?.metaData?.enabled == true
                        setOnCheckedChangeListener { _, enabled ->
                            if (updating) return@setOnCheckedChangeListener
                            val current = extension
                            if (current == null && enabled) {
                                val url = "https://addons.mozilla.org/firefox/downloads/latest/${addon.slug}/latest.xpi"
                                geckoController.install(url, WebExtensionController.INSTALLATION_METHOD_MANAGER)
                                    .accept({ installedExtension ->
                                        activity.runOnUiThread {
                                            if (installedExtension == null) {
                                                updating = true
                                                isChecked = false
                                                updating = false
                                                text = "${addon.name} · Not installed"
                                                showMessage("Could not install ${addon.name}")
                                            } else {
                                                extension = installedExtension
                                                installedByName[key] = installedExtension
                                                configureDelegates(installedExtension)
                                                text = "${addon.name} · On"
                                                showMessage("${addon.name} installed and enabled")
                                            }
                                        }
                                    }, { error ->
                                        activity.runOnUiThread {
                                            updating = true
                                            isChecked = false
                                            updating = false
                                            text = "${addon.name} · Not installed"
                                            showMessage("Could not install ${addon.name}: ${error?.localizedMessage ?: "Unknown error"}")
                                        }
                                    })
                            } else if (current != null && enabled && current.metaData?.enabled != true) {
                                geckoController.enable(current, WebExtensionController.EnableSource.USER)
                                    .accept({
                                        activity.runOnUiThread {
                                            configureDelegates(current)
                                            text = "${addon.name} · On"
                                            showMessage("${addon.name} enabled")
                                        }
                                    }, { error ->
                                        activity.runOnUiThread {
                                            updating = true
                                            isChecked = false
                                            updating = false
                                            showMessage("Could not enable ${addon.name}: ${error?.localizedMessage ?: "Unknown error"}")
                                        }
                                    })
                            } else if (current != null && !enabled && current.metaData?.enabled == true) {
                                geckoController.disable(current, WebExtensionController.EnableSource.USER)
                                    .accept({
                                        activity.runOnUiThread {
                                            extensionController.remove(current.id)
                                            text = "${addon.name} · Off"
                                            showMessage("${addon.name} disabled")
                                        }
                                    }, { error ->
                                        activity.runOnUiThread {
                                            updating = true
                                            isChecked = true
                                            updating = false
                                            showMessage("Could not disable ${addon.name}: ${error?.localizedMessage ?: "Unknown error"}")
                                        }
                                    })
                            }
                        }
                    }
                    rows.addView(check)
                }
                val scroll = ScrollView(activity).apply { addView(rows) }
                AlertDialog.Builder(activity)
                    .setTitle("Recommended Firefox add-ons")
                    .setMessage("Switch an add-on on to install it and review its permissions. Switch it off to disable it. Compatibility depends on GeckoView support.")
                    .setView(scroll)
                    .setPositiveButton("Done", null)
                    .show()
            }
        }, { error ->
            activity.runOnUiThread { showMessage("Could not load recommended add-ons: ${error?.localizedMessage ?: "Unknown error"}") }
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
