package com.nova.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestTorRoutingPolicyTest {
    @Test fun guestCannotStartBeforeTorCircuitIsBuilt() {
        assertFalse(GuestTorRoutingPolicy.canEnterGuest(false, 9050, true))
    }

    @Test fun guestCannotStartWithoutValidSocksPort() {
        assertFalse(GuestTorRoutingPolicy.canEnterGuest(true, 0, true))
        assertFalse(GuestTorRoutingPolicy.canEnterGuest(true, 65536, true))
    }

    @Test fun failedGuestCreationRollbackSelectsOnlyTabsAddedByThatAttempt() {
        assertTrue(GuestTorRoutingPolicy.newlyCreatedGuestTabIds(setOf("old"), listOf("old", "new")).contains("new"))
        assertFalse(GuestTorRoutingPolicy.newlyCreatedGuestTabIds(setOf("old"), listOf("old")).isNotEmpty())
        assertFalse(GuestTorRoutingPolicy.newlyCreatedGuestTabIds(emptySet(), listOf("guest")).contains("old"))
    }

    @Test fun failedAdditionalGuestTabDoesNotStopExistingGuestTorSession() {
        assertTrue(GuestTorRoutingPolicy.shouldStopTorAfterGuestCreationFailure(0))
        assertFalse(GuestTorRoutingPolicy.shouldStopTorAfterGuestCreationFailure(1))
        assertFalse(GuestTorRoutingPolicy.shouldStopTorAfterGuestCreationFailure(2))
    }

    @Test fun guestCleanupMarkerIsPreservedWhileAnyGuestTabExists() {
        assertTrue(GuestTorRoutingPolicy.shouldClearGuestContextMarker(0))
        assertFalse(GuestTorRoutingPolicy.shouldClearGuestContextMarker(1))
        assertFalse(GuestTorRoutingPolicy.shouldClearGuestContextMarker(3))
    }

    @Test fun staleProxyAcknowledgementCannotSatisfyCurrentRequest() {
        assertFalse(GuestTorRoutingPolicy.isCurrentAcknowledgement(12L, 11L))
        assertFalse(GuestTorRoutingPolicy.isCurrentAcknowledgement(12L, 0L))
        assertTrue(GuestTorRoutingPolicy.isCurrentAcknowledgement(12L, 12L))
    }

    @Test fun guestCannotStartUntilProxyBridgeConfirmsLeakProtection() {
        assertFalse(GuestTorRoutingPolicy.canEnterGuest(true, 9050, false))
        assertTrue(GuestTorRoutingPolicy.canEnterGuest(true, 9050, true))
    }
}
