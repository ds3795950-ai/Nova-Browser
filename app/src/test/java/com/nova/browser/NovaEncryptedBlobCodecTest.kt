package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator

class NovaEncryptedBlobCodecTest {
    private fun testKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun encryptedProfileDataRoundTrips() {
        val key = testKey()
        val plaintext = "[{\"url\":\"https://example.com/private-path\"}]"
        val encrypted = NovaEncryptedBlobCodec.encrypt(plaintext, "history", key)
        assertEquals(plaintext, NovaEncryptedBlobCodec.decrypt(encrypted, "history", key))
    }

    @Test
    fun ciphertextCannotBeMovedBetweenStoragePurposes() {
        val key = testKey()
        val encrypted = NovaEncryptedBlobCodec.encrypt("bookmark data", "bookmarks", key)
        assertThrows(GeneralSecurityException::class.java) {
            NovaEncryptedBlobCodec.decrypt(encrypted, "history", key)
        }
    }

    @Test
    fun modifiedCiphertextFailsAuthentication() {
        val key = testKey()
        val encrypted = NovaEncryptedBlobCodec.encrypt("history data", "history", key)
        val prefix = "nova-aesgcm-v1:"
        val index = prefix.length
        val replacement = if (encrypted[index] == 'A') 'B' else 'A'
        val tampered = encrypted.substring(0, index) + replacement + encrypted.substring(index + 1)
        assertThrows(GeneralSecurityException::class.java) {
            NovaEncryptedBlobCodec.decrypt(tampered, "history", key)
        }
    }
}
