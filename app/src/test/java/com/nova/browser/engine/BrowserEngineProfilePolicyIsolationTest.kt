package com.nova.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserEngineProfilePolicyIsolationTest {
    @Test fun modeResolverKeepsPrivateAndGuestDistinctFromNormal() {
        assertEquals(BrowserProfileKind.NORMAL, BrowserEngineProfilePolicy.kindFor(false, false))
        assertEquals(BrowserProfileKind.PRIVATE, BrowserEngineProfilePolicy.kindFor(true, false))
        assertEquals(BrowserProfileKind.GUEST, BrowserEngineProfilePolicy.kindFor(false, true))
    }

    @Test fun onlyNormalCanUsePersistentCefriumProfile() {
        assertEquals(BrowserEngineProfilePolicy.NORMAL_CEFRIUM_PROFILE, BrowserEngineProfilePolicy.cefriumPersistentProfile(BrowserProfileKind.NORMAL))
        assertNull(BrowserEngineProfilePolicy.cefriumPersistentProfile(BrowserProfileKind.PRIVATE))
        assertNull(BrowserEngineProfilePolicy.cefriumPersistentProfile(BrowserProfileKind.GUEST))
    }

    @Test fun guestContextIsExplicitAndNormalPrivateDoNotShareIt() {
        assertEquals("guest-session", BrowserEngineProfilePolicy.geckoContextId(BrowserProfileKind.GUEST, "guest-session"))
        assertNull(BrowserEngineProfilePolicy.geckoContextId(BrowserProfileKind.NORMAL, "guest-session"))
        assertNull(BrowserEngineProfilePolicy.geckoContextId(BrowserProfileKind.PRIVATE, "guest-session"))
        assertTrue(BrowserEngineProfilePolicy.isEphemeral(BrowserProfileKind.PRIVATE))
        assertTrue(BrowserEngineProfilePolicy.isEphemeral(BrowserProfileKind.GUEST))
    }
}
