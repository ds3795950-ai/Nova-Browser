package com.nova.browser.engine

/**
 * Cross-engine website-data migration is intentionally unsupported.
 * GeckoView and Cefrium own independent profiles; no cookies, history, cache,
 * storage, credentials, permissions, or session state may cross this boundary.
 */
object WebsiteDataMigrationCoordinator {
    fun migrate(
        sourceEngine: BrowserEngineType,
        destinationEngine: BrowserEngineType
    ): WebsiteMigrationStatus = when {
        sourceEngine == destinationEngine -> WebsiteMigrationStatus.SAME_ENGINE_NO_OP
        else -> WebsiteMigrationStatus.BLOCKED_BY_ENGINE_ISOLATION
    }
}

enum class WebsiteMigrationStatus {
    SAME_ENGINE_NO_OP,
    BLOCKED_BY_ENGINE_ISOLATION
}
