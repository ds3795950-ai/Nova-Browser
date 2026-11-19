package com.nova.browser.engine

/** Exportable website state. Engine adapters must omit non-portable/device-bound material. */
data class WebsiteDataSnapshot(
    val cookies: List<PortableCookie> = emptyList(),
    val localStorage: Map<String, String> = emptyMap(),
    /** IndexedDB exports are opaque, versioned payloads supplied by an engine adapter. */
    val indexedDbPayloads: Map<String, ByteArray> = emptyMap(),
    val unsupportedKinds: Set<String> = emptySet()
)

data class PortableCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val secure: Boolean = true,
    val httpOnly: Boolean = false,
    val expiresAtEpochSeconds: Long? = null
)

interface WebsiteDataPort {
    /** Return only data that this engine can export safely for this exact origin. */
    fun export(origin: String): WebsiteDataSnapshot
    fun import(origin: String, snapshot: WebsiteDataSnapshot): Boolean
    /** Verify destination state without deleting or mutating the source. */
    fun verify(origin: String, expected: WebsiteDataSnapshot): Boolean
}

enum class WebsiteMigrationStatus { IMPORTED_AND_VERIFIED, SKIPPED_NO_CONSENT, SAME_ENGINE, FAILED_SOURCE_RETAINED }

data class WebsiteMigrationResult(
    val status: WebsiteMigrationStatus,
    val migratedCookieCount: Int = 0,
    val migratedLocalStorageCount: Int = 0,
    val migratedIndexedDbCount: Int = 0,
    val unsupportedKinds: Set<String> = emptySet()
)

/**
 * Conservative, user-consented, best-effort migration. The coordinator never clears the
 * source profile. Service workers, Cache Storage, sessionStorage, passkeys and device-bound
 * credentials are not represented as portable data and must be recreated or reauthenticated.
 */
class WebsiteDataMigrationCoordinator {
    fun migrate(
        origin: String,
        sourceEngine: BrowserEngineType,
        destinationEngine: BrowserEngineType,
        userConsented: Boolean,
        source: WebsiteDataPort,
        destination: WebsiteDataPort
    ): WebsiteMigrationResult {
        if (!userConsented) return WebsiteMigrationResult(WebsiteMigrationStatus.SKIPPED_NO_CONSENT)
        if (sourceEngine == destinationEngine) return WebsiteMigrationResult(WebsiteMigrationStatus.SAME_ENGINE)
        if (!origin.startsWith("https://") && !origin.startsWith("http://")) {
            return WebsiteMigrationResult(WebsiteMigrationStatus.FAILED_SOURCE_RETAINED)
        }
        return runCatching {
            val snapshot = source.export(origin)
            check(destination.import(origin, snapshot))
            check(destination.verify(origin, snapshot))
            WebsiteMigrationResult(
                WebsiteMigrationStatus.IMPORTED_AND_VERIFIED,
                snapshot.cookies.size,
                snapshot.localStorage.size,
                snapshot.indexedDbPayloads.size,
                snapshot.unsupportedKinds
            )
        }.getOrElse {
            WebsiteMigrationResult(WebsiteMigrationStatus.FAILED_SOURCE_RETAINED)
        }
    }
}
