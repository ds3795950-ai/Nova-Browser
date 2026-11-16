package com.nova.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CloudCryptoTest {
    @Test fun encryptedEnvelopeRoundTrips() {
        val text = "{\"bookmarks\":[{\"url\":\"https://example.com\"}],\"history\":[]}"
        val envelope = CloudCrypto.encrypt(text, "correct-horse-battery".toCharArray())
        assertEquals(text, CloudCrypto.decrypt(envelope, "correct-horse-battery".toCharArray()))
        assertNotEquals(text, envelope.toString())
        assertEquals(1, envelope.getInt("version"))
        assertEquals("AES-256-GCM", envelope.getString("algorithm"))
    }

    @Test fun wrongPassphraseCannotDecryptBackup() {
        val envelope = CloudCrypto.encrypt("private browser data", "first-secure-passphrase".toCharArray())
        try {
            CloudCrypto.decrypt(envelope, "different-secure-passphrase".toCharArray())
            throw AssertionError("Wrong passphrase unexpectedly decrypted the backup")
        } catch (_: javax.crypto.AEADBadTagException) { }
    }

    @Test(expected = IllegalArgumentException::class)
    fun weakPassphraseRejected() {
        CloudCrypto.encrypt("data", "short".toCharArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedEnvelopeRejected() {
        CloudCrypto.decrypt(JSONObject().put("version", 9), "correct-horse-battery".toCharArray())
    }
}
