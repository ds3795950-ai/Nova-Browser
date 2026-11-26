package com.nova.browser.engine

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts Cefrium's serialized navigation state before it enters ordinary app preferences. */
object CefriumTabStateCipher {
    private const val KEY_ALIAS = "nova_cefrium_tab_state_v1"
    private const val VERSION: Byte = 2
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128
    private const val MAX_STATE_BYTES = 512 * 1024

    @Synchronized
    fun encrypt(plain: ByteArray, profileName: String): ByteArray? = runCatching {
        require(plain.size in 1..MAX_STATE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        require(profileName.isNotBlank() && profileName.length <= 128)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(profileName.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain)
        ByteBuffer.allocate(2 + iv.size + encrypted.size)
            .put(VERSION).put(iv.size.toByte()).put(iv).put(encrypted).array()
    }.getOrNull()

    @Synchronized
    fun decrypt(encoded: ByteArray, profileName: String): ByteArray? = runCatching {
        require(encoded.size in (2 + IV_LENGTH + 1)..(MAX_STATE_BYTES + 64) && encoded[0] == VERSION)
        val ivLength = encoded[1].toInt() and 0xff
        require(ivLength == IV_LENGTH && encoded.size > 2 + ivLength)
        val iv = encoded.copyOfRange(2, 2 + ivLength)
        val ciphertext = encoded.copyOfRange(2 + ivLength, encoded.size)
        require(profileName.isNotBlank() && profileName.length <= 128)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(profileName.toByteArray(Charsets.UTF_8))
        cipher.doFinal(ciphertext)
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
