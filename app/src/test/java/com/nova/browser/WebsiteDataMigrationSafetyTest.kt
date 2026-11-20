package com.nova.browser

import com.nova.browser.engine.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebsiteDataMigrationSafetyTest {
    private class Port(
        private val snapshot: WebsiteDataSnapshot = WebsiteDataSnapshot(),
        private val throwOnExport: Boolean = false,
        private val importResult: Boolean = true,
        private val verifyResult: Boolean = true
    ) : WebsiteDataPort {
        var exports = 0
        var imports = 0
        override fun export(origin: String): WebsiteDataSnapshot {
            exports++
            check(!throwOnExport) { "export failed" }
            return snapshot
        }
        override fun import(origin: String, snapshot: WebsiteDataSnapshot): Boolean {
            imports++
            return importResult
        }
        override fun verify(origin: String, expected: WebsiteDataSnapshot): Boolean = verifyResult
    }

    private val coordinator = WebsiteDataMigrationCoordinator()

    @Test
    fun rejectsUrlWithPathQueryFragmentOrUserInfoBeforeExport() {
        val invalid = listOf(
            "https://example.com/account",
            "https://example.com?next=/account",
            "https://example.com#section",
            "https://user:secret@example.com",
            "file:///data/local",
            "https://example.com/evil path"
        )
        invalid.forEach { value ->
            val source = Port()
            val result = coordinator.migrate(value, BrowserEngineType.GECKOVIEW, BrowserEngineType.CEFRIUM, true, source, Port())
            assertEquals(value, WebsiteMigrationStatus.FAILED_SOURCE_RETAINED, result.status)
            assertEquals(value, 0, source.exports)
        }
    }

    @Test
    fun acceptsExactHttpAndHttpsOrigins() {
        listOf("http://example.com", "https://example.com", "https://example.com:8443", "https://example.com/").forEach { origin ->
            val result = coordinator.migrate(origin, BrowserEngineType.GECKOVIEW, BrowserEngineType.CEFRIUM, true, Port(), Port())
            assertEquals(origin, WebsiteMigrationStatus.IMPORTED_AND_VERIFIED, result.status)
        }
    }

    @Test
    fun exportExceptionLeavesMigrationFailedAndNeverImports() {
        val destination = Port()
        val result = coordinator.migrate("https://example.com", BrowserEngineType.GECKOVIEW, BrowserEngineType.CEFRIUM, true, Port(throwOnExport = true), destination)
        assertEquals(WebsiteMigrationStatus.FAILED_SOURCE_RETAINED, result.status)
        assertEquals(0, destination.imports)
    }

    @Test
    fun rejectedDestinationImportDoesNotReportSuccess() {
        val destination = Port(importResult = false)
        val result = coordinator.migrate("https://example.com", BrowserEngineType.GECKOVIEW, BrowserEngineType.CEFRIUM, true, Port(), destination)
        assertEquals(WebsiteMigrationStatus.FAILED_SOURCE_RETAINED, result.status)
        assertEquals(1, destination.imports)
    }

    @Test
    fun failedVerificationDoesNotReportSuccess() {
        val destination = Port(verifyResult = false)
        val result = coordinator.migrate("https://example.com", BrowserEngineType.GECKOVIEW, BrowserEngineType.CEFRIUM, true, Port(), destination)
        assertEquals(WebsiteMigrationStatus.FAILED_SOURCE_RETAINED, result.status)
    }
}
