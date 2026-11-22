package com.nova.browser

/** Single source of truth for the owner-profile settings menu. */
internal object NovaSettingsCatalog {
    fun ownerItems(shortcutState: String): Array<String> = arrayOf(
        "Choose search engine", "Toggle home shortcuts (currently $shortcutState)",
        "Clear browsing history", "Close private and Guest tabs", "Exit Guest Mode",
        "Exit Reader view", "Nova Shield", "AI assistants", "Extensions", "Password manager",
        "Site permissions", "Accessibility", "Export history", "About Nova",
        "Account & encrypted cloud sync", "Protected DNS · AdGuard DNS", "Browser proxy & Shield · 1VPN"
    )
    fun guestItems(): Array<String> = arrayOf("Tor routing · Orbot", "Exit Guest Mode", "Close")
}
