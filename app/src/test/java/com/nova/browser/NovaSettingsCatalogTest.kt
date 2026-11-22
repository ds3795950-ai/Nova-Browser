package com.nova.browser

import org.junit.Assert.*
import org.junit.Test

class NovaSettingsCatalogTest {
    @Test fun ownerMenuIncludesAccountAndExtensionControls() {
        val items = NovaSettingsCatalog.ownerItems("shown")
        assertTrue(items.contains("Account & encrypted cloud sync"))
        assertTrue(items.contains("Extensions")); assertTrue(items.contains("Password manager"))
    }
    @Test fun actionIndicesMatchTheVisibleOwnerMenuOrder() {
        val expected = NovaSettingsCatalog.Action.entries.toList()
        expected.indices.forEach { assertEquals(expected[it], NovaSettingsCatalog.actionFor(it)) }
        assertNull(NovaSettingsCatalog.actionFor(-1))
        assertNull(NovaSettingsCatalog.actionFor(expected.size))
    }
    @Test fun dispatchRunsOnlyTheSelectedAction() {
        var calls = 0
        NovaSettingsController.dispatch(NovaSettingsCatalog.Action.CLOUD_ACCOUNT, mapOf(
            NovaSettingsCatalog.Action.CLOUD_ACCOUNT to { calls++ },
            NovaSettingsCatalog.Action.EXTENSIONS to { calls += 10 }
        ))
        assertEquals(1, calls)
        NovaSettingsController.dispatch(null, mapOf(NovaSettingsCatalog.Action.CLOUD_ACCOUNT to { calls++ }))
        assertEquals(1, calls)
    }
    @Test fun guestMenuContainsNoOwnerDataControls() {
        val items = NovaSettingsCatalog.guestItems()
        assertTrue(items.contains("Tor routing · Orbot")); assertTrue(items.contains("Exit Guest Mode"))
        assertFalse(items.any { it.contains("cloud", true) || it.contains("password", true) || it.contains("history", true) || it.contains("extension", true) })
    }
}
