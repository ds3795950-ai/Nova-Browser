package com.nova.browser.engine

import com.nova.browser.EngineProfileStoragePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EngineProfileStoragePolicyTest {
    @Test fun persistentAppRecordsArePartitionedByRenderingEngine() {
        val keys = BrowserEngineType.values().map { EngineProfileStoragePolicy.key("history_v2", it) }
        assertEquals(BrowserEngineType.values().size, keys.toSet().size)
        assertNotEquals(
            EngineProfileStoragePolicy.key("open_normal_tabs", BrowserEngineType.GECKOVIEW),
            EngineProfileStoragePolicy.key("open_normal_tabs", BrowserEngineType.CEFRIUM)
        )
    }

    @Test fun engineSuffixIsStableAndDoesNotDependOnDefaultLocale() {
        BrowserEngineType.values().forEach { engine ->
            assertEquals("history_${engine.name.lowercase()}", EngineProfileStoragePolicy.key("history", engine))
        }
    }
}
