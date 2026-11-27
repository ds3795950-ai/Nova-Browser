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

    @Test fun guestCannotStartUntilProxyBridgeConfirmsLeakProtection() {
        assertFalse(GuestTorRoutingPolicy.canEnterGuest(true, 9050, false))
        assertTrue(GuestTorRoutingPolicy.canEnterGuest(true, 9050, true))
    }

    @Test fun privateTabsBlockGuestTorStartup() {
        assertFalse(GuestTorIsolationPolicy.canStart(hasPrivateTabs = true))
        assertTrue(GuestTorIsolationPolicy.canStart(hasPrivateTabs = false))
    }

    @Test fun guestTorSuspendsEveryPersistentNormalEngineSession() {
        assertTrue(GuestTorIsolationPolicy.shouldSuspendNormalTab(false, false))
        assertFalse(GuestTorIsolationPolicy.shouldSuspendNormalTab(false, true))
        assertFalse(GuestTorIsolationPolicy.shouldSuspendNormalTab(true, false))
    }

    @Test fun allNonGuestTabsAreBlockedWhileGuestTorOwnsTheRuntime() {
        assertTrue(GuestTorIsolationPolicy.shouldBlockNonGuestSession(false, true))
        assertFalse(GuestTorIsolationPolicy.shouldBlockNonGuestSession(true, true))
        assertFalse(GuestTorIsolationPolicy.shouldBlockNonGuestSession(false, false))
    }

    @Test fun pendingExternalLinksRespectGuestRuntimeIsolation() {
        assertTrue(GuestTorIsolationPolicy.shouldBlockPendingExternalLink(false, true, false))
        assertFalse(GuestTorIsolationPolicy.shouldBlockPendingExternalLink(false, false, false))
        assertTrue(GuestTorIsolationPolicy.shouldBlockPendingExternalLink(true, true, false))
        assertFalse(GuestTorIsolationPolicy.shouldBlockPendingExternalLink(true, true, true))
    }

    @Test fun tabRestoreWaitsUntilGuestTorStartupAndCircuitValidationFinish() {
        assertTrue(GuestTorIsolationPolicy.shouldDeferTabRestore(true, false, false))
        assertTrue(GuestTorIsolationPolicy.shouldDeferTabRestore(false, true, false))
        assertFalse(GuestTorIsolationPolicy.shouldDeferTabRestore(false, true, true))
        assertFalse(GuestTorIsolationPolicy.shouldDeferTabRestore(false, false, false))
    }

    @Test fun onlyLatestTorDisableAcknowledgementReleasesNormalSessionBarrier() {
        assertTrue(TorGuestConfigAckPolicy.isLatestDisableAcknowledgement(12L, 12L, 12L, true, false, true))
        assertFalse(TorGuestConfigAckPolicy.isLatestDisableAcknowledgement(12L, 11L, 11L, true, false, true))
        assertFalse(TorGuestConfigAckPolicy.isLatestDisableAcknowledgement(12L, 12L, 11L, true, false, true))
        assertFalse(TorGuestConfigAckPolicy.isLatestDisableAcknowledgement(12L, 12L, 12L, false, false, true))
        assertFalse(TorGuestConfigAckPolicy.isLatestDisableAcknowledgement(12L, 12L, 12L, true, true, true))
        assertFalse(TorGuestConfigAckPolicy.isLatestDisableAcknowledgement(12L, 12L, 12L, true, false, false))
    }
}
