package com.nova.browser

import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudCryptoTest {
    @Test fun rejectsExcessiveServerSuppliedKdfIterationsBeforeDerivingKey() {
        val envelope = JSONObject()
            .put("version", 1)
            .put("algorithm", "AES-256-GCM")
            .put("iterations", 1_000_000)
        assertThrows(IllegalArgumentException::class.java) {
            CloudCrypto.decrypt(envelope, "correct horse battery".toCharArray())
        }
    }

    @Test fun encryptedPayloadRoundTripsWithTheBoundedWorkFactor() {
        val passphrase = "a sufficiently long sync passphrase".toCharArray()
        val encrypted = CloudCrypto.encrypt("nova-sync", passphrase)
        assertEquals("nova-sync", CloudCrypto.decrypt(encrypted, passphrase))
    }
}
