package com.nova.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestModePolicyTest {
    @Test fun normalTabsCanPersistOwnerBrowsingRecords() {
        assertTrue(GuestModePolicy.mayPersistBrowsingData(isPrivate = false, isGuest = false))
    }

    @Test fun privateAndGuestTabsNeverPersistOwnerBrowsingRecords() {
        assertFalse(GuestModePolicy.mayPersistBrowsingData(isPrivate = true, isGuest = false))
        assertFalse(GuestModePolicy.mayPersistBrowsingData(isPrivate = false, isGuest = true))
        assertFalse(GuestModePolicy.mayPersistBrowsingData(isPrivate = true, isGuest = true))
    }

    @Test fun guestCannotReadOwnerBrowsingRecords() {
        assertFalse(GuestModePolicy.mayReadOwnerData(isGuest = true))
        assertTrue(GuestModePolicy.mayReadOwnerData(isGuest = false))
    }

    @Test fun ownerCredentialsStayUnavailableWhileAnyEphemeralTabExists() {
        assertFalse(GuestModePolicy.mayUseOwnerCredentials(hasPrivateOrGuestTab = true))
        assertTrue(GuestModePolicy.mayUseOwnerCredentials(hasPrivateOrGuestTab = false))
    }

    @Test fun guestRequiresAnEphemeralEngineContext() {
        assertTrue(GuestModePolicy.requiresEphemeralStorage(isGuest = true))
        assertFalse(GuestModePolicy.requiresEphemeralStorage(isGuest = false))
    }

    @Test fun fullModeMatrixOnlyAllowsNormalModeToPersistOwnerData() {
        for (isPrivate in listOf(false, true)) {
            for (isGuest in listOf(false, true)) {
                assertEqualsExpected(
                    expected = !isPrivate && !isGuest,
                    actual = GuestModePolicy.mayPersistBrowsingData(isPrivate, isGuest)
                )
            }
        }
    }

    private fun assertEqualsExpected(expected: Boolean, actual: Boolean) {
        if (expected) assertTrue(actual) else assertFalse(actual)
    }
}
