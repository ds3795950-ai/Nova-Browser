package com.nova.browser.engine

import org.junit.Assert.*
import org.junit.Test

class BrowserEngineProfilePolicyTest {
    @Test fun onlyNormalProfileGetsPersistentCefriumProfile() {
        assertNotNull(BrowserEngineProfilePolicy.cefriumPersistentProfile(BrowserProfileKind.NORMAL))
        assertNull(BrowserEngineProfilePolicy.cefriumPersistentProfile(BrowserProfileKind.PRIVATE))
        assertNull(BrowserEngineProfilePolicy.cefriumPersistentProfile(BrowserProfileKind.GUEST))
    }

    @Test fun guestContextIsRequiredOnlyForGuest() {
        assertEquals("guest-ctx", BrowserEngineProfilePolicy.geckoContextId(BrowserProfileKind.GUEST, "guest-ctx"))
        assertNull(BrowserEngineProfilePolicy.geckoContextId(BrowserProfileKind.GUEST, " "))
        assertNull(BrowserEngineProfilePolicy.geckoContextId(BrowserProfileKind.PRIVATE, "guest-ctx"))
        assertNull(BrowserEngineProfilePolicy.geckoContextId(BrowserProfileKind.NORMAL, "guest-ctx"))
    }
}
