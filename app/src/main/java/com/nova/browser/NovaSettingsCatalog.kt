package com.nova.browser

/** Single source of truth for the owner-profile settings menu and its action ordering. */
internal object NovaSettingsCatalog {
    enum class Action {
        CHOOSE_SEARCH_ENGINE, TOGGLE_HOME_SHORTCUTS, CLEAR_HISTORY, CLOSE_PRIVATE_GUEST_TABS,
        EXIT_GUEST_MODE, EXIT_READER_MODE, SHIELD, AI_ASSISTANTS, EXTENSIONS, PASSWORD_MANAGER,
        SITE_PERMISSIONS, ACCESSIBILITY, EXPORT_HISTORY, ABOUT, CLOUD_ACCOUNT, PROTECTED_DNS, VPN_PROXY
    }

    fun actionFor(index: Int): Action? = Action.entries.getOrNull(index)

    fun ownerItems(shortcutState: String): Array<String> = arrayOf(
        "Choose search engine", "Toggle home shortcuts (currently $shortcutState)",
        "Clear browsing history", "Close private and Guest tabs", "Exit Guest Mode",
        "Exit Reader view", "Nova Shield", "AI assistants", "Extensions", "Password manager",
        "Site permissions", "Accessibility", "Export history", "About Nova",
        "Account & encrypted cloud sync", "Protected DNS · AdGuard DNS", "Browser proxy & Shield · 1VPN"
    )
    fun guestItems(): Array<String> = arrayOf("Tor routing · Orbot", "Exit Guest Mode", "Close")
}
