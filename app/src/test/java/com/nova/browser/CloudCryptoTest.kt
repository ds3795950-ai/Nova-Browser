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

    @Test fun modifiedCiphertextCannotDecryptBackup() {
        val envelope = CloudCrypto.encrypt("authenticated browser backup", "correct-horse-battery".toCharArray())
        val bytes = java.util.Base64.getDecoder().decode(envelope.getString("ciphertext"))
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        envelope.put("ciphertext", java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes))
        try {
            CloudCrypto.decrypt(envelope, "correct-horse-battery".toCharArray())
            throw AssertionError("Modified ciphertext unexpectedly decrypted")
        } catch (_: javax.crypto.AEADBadTagException) { }
    }

    @Test fun encryptedBackupSizeIsBoundedByUtf8BytesBeforeEncryption() {
        assertEquals(true, CloudCrypto.isPlaintextWithinLimit("€".repeat(100)))
        assertEquals(false, CloudCrypto.isPlaintextWithinLimit("€".repeat(5_592_406)))
        assertEquals(true, CloudCrypto.isPlaintextWithinLimit("a".repeat(16 * 1024 * 1024)))
        assertEquals(false, CloudCrypto.isPlaintextWithinLimit("a".repeat(16 * 1024 * 1024 + 1)))
        assertEquals(true, CloudCrypto.isCiphertextWithinLimit(44_739_264))
        assertEquals(false, CloudCrypto.isCiphertextWithinLimit(44_739_265))
    }

    @Test fun eachBackupUsesFreshSaltAndIv() {
        val passphrase = "correct-horse-battery".toCharArray()
        val first = CloudCrypto.encrypt("same backup", passphrase)
        val second = CloudCrypto.encrypt("same backup", passphrase)
        assertNotEquals(first.getString("salt"), second.getString("salt"))
        assertNotEquals(first.getString("iv"), second.getString("iv"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedKeyDerivationRejected() {
        val envelope = CloudCrypto.encrypt("data", "correct-horse-battery".toCharArray())
        envelope.put("kdf", "PBKDF2-SHA1")
        CloudCrypto.decrypt(envelope, "correct-horse-battery".toCharArray())
    }

    @Test fun encryptedBundleKeepsBrowserAndVaultInOneRevision() {
        val browser = JSONObject().put("ciphertext", "browser-ciphertext")
        val vault = JSONObject().put("ciphertext", "vault-ciphertext")
        val bundle = NovaCloudBundle.pack(browser, vault)
        val staleLegacyVault = JSONObject().put("ciphertext", "stale-legacy-vault")
        val unpacked = NovaCloudBundle.unpack(bundle, staleLegacyVault)
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
    fun incompleteAtomicBundleIsRejected() {
        NovaCloudBundle.unpack(JSONObject().put("format", NovaCloudBundle.FORMAT)
            .put("browser", JSONObject().put("ciphertext", "browser-only")), null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun weakPassphraseRejected() {
        CloudCrypto.encrypt("data", "short".toCharArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun weakPassphraseRejectedOnDecryptToo() {
        val envelope = CloudCrypto.encrypt("data", "correct-horse-battery".toCharArray())
        CloudCrypto.decrypt(envelope, "short".toCharArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedCiphertextRejectedBeforeDecryption() {
        val envelope = CloudCrypto.encrypt("data", "correct-horse-battery".toCharArray())
        envelope.put("ciphertext", "AA")
        CloudCrypto.decrypt(envelope, "correct-horse-battery".toCharArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedEnvelopeRejected() {
        CloudCrypto.decrypt(JSONObject().put("version", 9), "correct-horse-battery".toCharArray())
    }
}
