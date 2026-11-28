package com.nova.browser

import org.junit.Assert.assertEquals
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

    @Test fun vpnCredentialBootstrapNeverRunsWhileGuestTorOwnsTheRuntime() {
        assertTrue(VpnCredentialBootstrapPolicy.shouldStart(true, false, false))
        assertFalse(VpnCredentialBootstrapPolicy.shouldStart(true, true, true))
        assertFalse(VpnCredentialBootstrapPolicy.shouldStart(true, false, true))
        assertFalse(VpnCredentialBootstrapPolicy.shouldStart(false, false, false))
    }

    @Test fun guestCreationRollbackIdentifiesOnlyNewTabs() {
        assertEquals(listOf("new"), GuestTorRoutingPolicy.newlyCreatedGuestTabIds(setOf("old"), listOf("old", "new")))
        assertTrue(GuestTorRoutingPolicy.newlyCreatedGuestTabIds(setOf("old"), listOf("old")).isEmpty())
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

    @Test fun pendingExternalLinksNeverCrossGuestAndNormalContexts() {
        assertTrue(PendingExternalLinkPolicy.canOpen(true, true, false, true, true))
        assertFalse(PendingExternalLinkPolicy.canOpen(true, false, false, false, false))
        assertTrue(PendingExternalLinkPolicy.shouldDiscardGuestLink(true, false, false, false))
        assertFalse(PendingExternalLinkPolicy.shouldDiscardGuestLink(true, false, true, true))
        assertFalse(PendingExternalLinkPolicy.shouldDiscardGuestLink(false, false, false, false))
        assertTrue(PendingExternalLinkPolicy.canOpen(false, false, false, false, false))
        assertFalse(PendingExternalLinkPolicy.canOpen(false, true, false, true, false))
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

    @Test fun deferredLinkOwnershipRequiresNovaTokenAndMatchingUrl() {
        assertTrue(DeferredExternalLinkOwnershipPolicy.isTrusted(
            "private-token", "private-token", "https://example.com", "https://example.com"
        ))
        assertFalse(DeferredExternalLinkOwnershipPolicy.isTrusted(
            "attacker-token", "private-token", "https://example.com", "https://example.com"
        ))
        assertFalse(DeferredExternalLinkOwnershipPolicy.isTrusted(
            "private-token", "private-token", "https://example.com", "https://attacker.example"
        ))
        assertFalse(DeferredExternalLinkOwnershipPolicy.isTrusted(
            null, "private-token", "https://example.com", "https://example.com"
        ))
    }

    @Test fun crossEngineNavigationOnlyTransfersGetRequests() {
        assertTrue(CrossEngineNavigationPolicy.canTransfer("GET"))
        assertTrue(CrossEngineNavigationPolicy.canTransfer("get"))
        assertFalse(CrossEngineNavigationPolicy.canTransfer("POST"))
        assertFalse(CrossEngineNavigationPolicy.canTransfer("PUT"))
    }

    @Test fun guestCleanupMarkerIsPreservedWhileAnyGuestTabExists() {
        assertTrue(GuestTorRoutingPolicy.shouldClearGuestContextMarker(0))
        assertFalse(GuestTorRoutingPolicy.shouldClearGuestContextMarker(1))
        assertFalse(GuestTorRoutingPolicy.shouldClearGuestContextMarker(3))
    }

    @Test fun failedAdditionalGuestTabDoesNotStopExistingGuestTorSession() {
        assertTrue(GuestTorRoutingPolicy.shouldStopTorAfterGuestCreationFailure(0))
        assertFalse(GuestTorRoutingPolicy.shouldStopTorAfterGuestCreationFailure(1))
        assertFalse(GuestTorRoutingPolicy.shouldStopTorAfterGuestCreationFailure(2))
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
