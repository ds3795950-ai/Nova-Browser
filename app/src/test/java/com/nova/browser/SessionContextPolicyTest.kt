package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionContextPolicyTest {
    @Test fun eachPrivateTabGetsItsOwnContext() {
        val first = SessionContextPolicy.contextId("tab-one", true, false, "guest")
        val second = SessionContextPolicy.contextId("tab-two", true, false, "guest")
        assertEquals("nova-private-tab-one", first)
        assertEquals("nova-private-tab-two", second)
    }
    @Test fun guestTabsShareGuestContext() {
        assertEquals("guest-context", SessionContextPolicy.contextId("guest-one", false, true, "guest-context"))
        assertEquals("guest-context", SessionContextPolicy.contextId("guest-two", false, true, "guest-context"))
    }
    @Test fun normalTabsUseDefaultContext() {
        assertNull(SessionContextPolicy.contextId("normal", false, false, "guest-context"))
    }
}
