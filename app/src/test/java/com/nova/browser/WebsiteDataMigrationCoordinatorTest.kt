package com.nova.browser

import com.nova.browser.engine.BrowserEngineType
import com.nova.browser.engine.WebsiteDataMigrationCoordinator
import com.nova.browser.engine.WebsiteMigrationStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class WebsiteDataMigrationCoordinatorTest {
    @Test
    fun geckoViewToCefriumMigrationIsAlwaysBlocked() {
        assertEquals(
            WebsiteMigrationStatus.BLOCKED_BY_ENGINE_ISOLATION,
            WebsiteDataMigrationCoordinator.migrate(
                BrowserEngineType.GECKOVIEW, BrowserEngineType.CEFRIUM
            )
        )
    }

    @Test
    fun cefriumToGeckoViewMigrationIsAlwaysBlocked() {
        assertEquals(
            WebsiteMigrationStatus.BLOCKED_BY_ENGINE_ISOLATION,
            WebsiteDataMigrationCoordinator.migrate(
                BrowserEngineType.CEFRIUM, BrowserEngineType.GECKOVIEW
            )
        )
    }

    @Test
    fun sameEngineRequestsAreNoOps() {
        for (engine in BrowserEngineType.entries) {
            assertEquals(
                WebsiteMigrationStatus.SAME_ENGINE_NO_OP,
                WebsiteDataMigrationCoordinator.migrate(engine, engine)
            )
        }
    }
}
