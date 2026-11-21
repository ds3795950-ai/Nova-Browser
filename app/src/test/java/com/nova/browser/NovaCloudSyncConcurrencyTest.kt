package com.nova.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaCloudSyncConcurrencyTest {
    @Test fun emptyPostgrestRepresentationMeansNoRowWasUpdated() {
        assertFalse(NovaCloudSync.hasUpdatedRow("[]"))
    }

    @Test fun returnedPostgrestRowMeansConditionalWriteSucceeded() {
        assertTrue(NovaCloudSync.hasUpdatedRow("[{\"version\":\"next\"}]"))
    }
}
