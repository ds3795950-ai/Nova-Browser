package com.nova.browser

import com.nova.browser.engine.*
import org.junit.Assert.*
import org.junit.Test

class WebsiteDataMigrationCoordinatorTest {
    private class FakePort(
        private val snapshot: WebsiteDataSnapshot = WebsiteDataSnapshot(),
        private val importResult: Boolean = true,
        private val verifyResult: Boolean = true
    ) : WebsiteDataPort {
        var exportCalls = 0
        var importCalls = 0
        override fun export(origin: String): WebsiteDataSnapshot { exportCalls++; return snapshot }
        override fun import(origin: String, snapshot: WebsiteDataSnapshot): Boolean { importCalls++; return importResult }
        override fun verify(origin: String, expected: WebsiteDataSnapshot): Boolean = verifyResult
    }

    @Test fun migrationRequiresExplicitConsent() {
        val source = FakePort()
        val destination = FakePort()
        val result = WebsiteDataMigrationCoordinator().migrate(
            "https://example.com", BrowserEngineType.GECKOVIEW, BrowserEngineType.CEFRIUM,
            false, source, destination
        )
        assertEquals(WebsiteMigrationStatus.SKIPPED_NO_CONSENT, result.status)
        assertEquals(0, source.exportCalls)
        assertEquals(0, destination.importCalls)
    }

    @Test fun successfulMigrationReportsOnlyPortableCounts() {
        val snapshot = WebsiteDataSnapshot(
            cookies = listOf(PortableCookie("sid", "opaque", "example.com")),
            localStorage = mapOf("theme" to "dark"),
            indexedDbPayloads = mapOf("db-v1" to byteArrayOf(1, 2)),
            unsupportedKinds = setOf("service-workers", "passkeys")
        )
        val result = WebsiteDataMigrationCoordinator().migrate(
            "https://example.com", BrowserEngineType.GECKOVIEW, BrowserEngineType.CEFRIUM,
            true, FakePort(snapshot), FakePort()
        )
        assertEquals(WebsiteMigrationStatus.IMPORTED_AND_VERIFIED, result.status)
        assertEquals(1, result.migratedCookieCount)
        assertEquals(1, result.migratedLocalStorageCount)
        assertEquals(1, result.migratedIndexedDbCount)
        assertTrue(result.unsupportedKinds.contains("passkeys"))
    }

    @Test fun failedDestinationVerificationKeepsSourceAndReportsFailure() {
        val source = FakePort(WebsiteDataSnapshot(localStorage = mapOf("k" to "v")))
        val destination = FakePort(verifyResult = false)
        val result = WebsiteDataMigrationCoordinator().migrate(
            "https://example.com", BrowserEngineType.GECKOVIEW, BrowserEngineType.CEFRIUM,
            true, source, destination
        )
        assertEquals(WebsiteMigrationStatus.FAILED_SOURCE_RETAINED, result.status)
        assertEquals(1, source.exportCalls)
        assertEquals(1, destination.importCalls)
    }

    @Test fun invalidOriginsAndSameEngineDoNotCopyData() {
        val source = FakePort()
        val destination = FakePort()
        val coordinator = WebsiteDataMigrationCoordinator()
        assertEquals(WebsiteMigrationStatus.SAME_ENGINE, coordinator.migrate(
            "https://example.com", BrowserEngineType.GECKOVIEW, BrowserEngineType.GECKOVIEW,
            true, source, destination
        ).status)
        assertEquals(WebsiteMigrationStatus.FAILED_SOURCE_RETAINED, coordinator.migrate(
            "file:///data/local", BrowserEngineType.GECKOVIEW, BrowserEngineType.CEFRIUM,
            true, source, destination
        ).status)
        assertEquals(0, source.exportCalls)
    }
}
