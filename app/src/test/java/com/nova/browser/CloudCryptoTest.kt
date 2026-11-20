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

    @Test fun encryptedBundleKeepsBrowserAndVaultInOneRevision() {
        val browser = JSONObject().put("ciphertext", "browser-ciphertext")
        val vault = JSONObject().put("ciphertext", "vault-ciphertext")
        val bundle = NovaCloudBundle.pack(browser, vault)
        val unpacked = NovaCloudBundle.unpack(bundle, null)
        assertEquals(browser.toString(), unpacked.first?.toString())
        assertEquals(vault.toString(), unpacked.second?.toString())
        assertEquals(NovaCloudBundle.FORMAT, bundle.getString("format"))
    }

    @Test fun legacyTwoRowBackupRemainsReadable() {
        val browser = JSONObject().put("version", 1).put("ciphertext", "old-browser")
        val vault = JSONObject().put("version", 1).put("ciphertext", "old-vault")
        val unpacked = NovaCloudBundle.unpack(browser, vault)
        assertEquals(browser.toString(), unpacked.first?.toString())
        assertEquals(vault.toString(), unpacked.second?.toString())
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
