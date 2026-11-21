package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException

class CloudCryptoTest {
    @Test
    fun failedPassphraseAttemptDoesNotPoisonDecryptionKeyCache() {
        val correct = "correct horse battery staple".toCharArray()
        val wrong = "incorrect passphrase".toCharArray()
        val cache = CloudCrypto.DecryptionKeyCache()
        try {
            val envelope = CloudCrypto.encrypt("encrypted Nova snapshot", correct)
            assertThrows(GeneralSecurityException::class.java) {
                CloudCrypto.decrypt(envelope, wrong, cache)
            }
            assertEquals("encrypted Nova snapshot", CloudCrypto.decrypt(envelope, correct, cache))
        } finally {
            correct.fill('\u0000')
            wrong.fill('\u0000')
            cache.clear()
        }
    }

    @Test
    fun unsupportedKdfIsRejected() {
        val passphrase = "correct horse battery staple".toCharArray()
        try {
            val envelope = CloudCrypto.encrypt("snapshot", passphrase).put("kdf", "unsupported")
            assertThrows(IllegalArgumentException::class.java) {
                CloudCrypto.decrypt(envelope, passphrase)
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }
}
