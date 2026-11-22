package com.nova.browser

/** Dispatches a selected settings action without coupling action routing to Android UI. */
internal object NovaSettingsController {
    fun dispatch(action: NovaSettingsCatalog.Action?, handlers: Map<NovaSettingsCatalog.Action, () -> Unit>) {
        action?.let(handlers::get)?.invoke()
    }
}
