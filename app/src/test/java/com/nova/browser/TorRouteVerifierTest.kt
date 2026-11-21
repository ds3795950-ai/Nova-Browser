package com.nova.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorRouteVerifierTest {
    @Test
    fun recognizesTorEgressResponse() {
        assertTrue(TorRouteVerifier.parseIsTor("{\"IsTor\":true}"))
    }

    @Test
    fun rejectsNonTorAndMalformedResponses() {
        assertFalse(TorRouteVerifier.parseIsTor("{\"IsTor\":false}"))
        assertFalse(TorRouteVerifier.parseIsTor("{}"))
        assertFalse(TorRouteVerifier.parseIsTor("not-json"))
    }
}
