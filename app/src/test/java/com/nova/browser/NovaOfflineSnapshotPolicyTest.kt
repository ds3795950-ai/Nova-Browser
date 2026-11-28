package com.nova.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaOfflineSnapshotPolicyTest {
    @Test fun linksMustBeHttp() {
        assertTrue(NovaOfflineSnapshotPolicy.canAddLink("https://example.com/a"))
        assertFalse(NovaOfflineSnapshotPolicy.canAddLink("about:blank"))
        assertFalse(NovaOfflineSnapshotPolicy.canAddLink(null))
    }

    @Test fun snapshotsNotClaimedSupported() {
        assertFalse(NovaOfflineSnapshotPolicy.snapshotSupported("GeckoView"))
        assertFalse(NovaOfflineSnapshotPolicy.snapshotSupported("Cefrium"))
        assertTrue(NovaOfflineSnapshotPolicy.userMessageForSnapshotAttempt("Cefrium").contains("save the link"))
        assertTrue(NovaOfflineSnapshotPolicy.userMessageForSnapshotAttempt("Cefrium").contains("not available"))
    }
}
