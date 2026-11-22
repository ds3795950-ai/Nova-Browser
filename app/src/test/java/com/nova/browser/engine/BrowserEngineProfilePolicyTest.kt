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


class CefriumRuntimeAvailabilityTest {
    @Test fun supportsPublishedNativeAbis() {
        assertTrue(CefriumRuntimeAvailability.supportsAny(listOf("arm64-v8a", "armeabi-v7a")))
        assertTrue(CefriumRuntimeAvailability.supportsAny(listOf("x86_64")))
    }

    @Test fun rejectsUnsupportedOrEmptyAbiLists() {
        assertFalse(CefriumRuntimeAvailability.supportsAny(listOf("armeabi-v7a", "x86")))
        assertFalse(CefriumRuntimeAvailability.supportsAny(emptyList()))
    }
}
