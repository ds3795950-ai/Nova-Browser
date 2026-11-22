package com.nova.browser

import org.junit.Assert.*
import org.junit.Test

class NovaTabControllerTest {
    private fun tab(host: String) = BrowserTab(null, false, false, lastUrl = "https://$host.example")
    @Test fun addAndActivateMaintainCurrentTab() {
        val c = NovaTabController(); val a = tab("a"); val b = tab("b")
        c.add(a); c.add(b); assertEquals(b, c.currentTab())
        assertEquals(a, c.activate(0)); assertEquals(a, c.currentTab())
    }
    @Test fun removeBeforeActiveAdjustsIndex() {
        val c = NovaTabController(); val a = tab("a"); val b = tab("b"); val d = tab("d")
        c.add(a); c.add(b); c.add(d); c.activate(1)
        assertEquals(a, c.removeAt(0)); assertEquals(b, c.currentTab()); assertEquals(0, c.activeIndex)
    }
    @Test fun removingActiveSelectsNearestRemaining() {
        val c = NovaTabController(); val a = tab("a"); val b = tab("b"); val d = tab("d")
        c.add(a); c.add(b); c.add(d); c.activate(1)
        assertEquals(b, c.removeAt(1)); assertEquals(d, c.currentTab())
    }
    @Test fun clearResetsState() {
        val c = NovaTabController(); c.add(tab("a")); c.clear()
        assertTrue(c.tabs.isEmpty()); assertNull(c.currentTab()); assertEquals(0, c.activeIndex)
    }
}
