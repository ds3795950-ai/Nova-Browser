package com.nova.browser

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object NovaEncryptedBlobCodec {
    private const val PREFIX = "nova-aesgcm-v1:"
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128

    fun encrypt(plaintext: String, purpose: String, key: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(purpose.toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val payload = cipher.iv + encrypted
        return PREFIX + java.util.Base64.getEncoder().encodeToString(payload)
    }

    @Throws(GeneralSecurityException::class)
    fun decrypt(blob: String, purpose: String, key: SecretKey): String {
        require(blob.startsWith(PREFIX)) { "Unsupported encrypted browser-data format" }
        val payload = java.util.Base64.getDecoder().decode(blob.removePrefix(PREFIX))
        if (payload.size < IV_LENGTH_BYTES + TAG_LENGTH_BITS / 8) {
            throw GeneralSecurityException("Encrypted browser data is truncated")
        }
        val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        cipher.updateAAD(purpose.toByteArray(StandardCharsets.UTF_8))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }
}

/** Encrypts normal-profile history and bookmarks at rest; Guest/private data never reaches this store. */
internal object NovaLocalDataStore {
    const val BOOKMARKS_PREF_KEY = "bookmarks_secure_v1"
    const val HISTORY_PREF_KEY = "history_secure_v1"
    const val BOOKMARK_TOMBSTONES_PREF_KEY = "bookmark_tombstones_secure_v1"
    const val BOOKMARKS_BACKUP_PREF_KEY = "bookmarks_corrupt_backup_secure_v1"
    const val HISTORY_BACKUP_PREF_KEY = "history_corrupt_backup_secure_v1"

    private const val PREFS_NAME = "nova_local"
    private const val KEY_ALIAS = "com.nova.browser.profile-data.v1"
    private const val BOOKMARKS_PURPOSE = "bookmarks"
    private const val HISTORY_PURPOSE = "history"
    private const val BOOKMARK_TOMBSTONES_PURPOSE = "bookmark_tombstones"

    fun readBookmarksJson(context: Context): String? = readEncryptedOrMigrate(
        context, BOOKMARKS_PREF_KEY, BOOKMARKS_PURPOSE, "bookmarks_v2", "bookmarks",
        "bookmarks_v2_corrupt_backup", BOOKMARKS_BACKUP_PREF_KEY, "bookmarks_corrupt_backup"
    ) { values ->
        JSONArray().also { array ->
            values.sorted().forEach { url ->
                array.put(JSONObject().put("url", url).put("title", Uri.parse(url).host ?: url)
                    .put("folder", "General").put("addedAt", 0L))
            }
        }.toString()
    }

    fun readHistoryJson(context: Context): String? = readEncryptedOrMigrate(
        context, HISTORY_PREF_KEY, HISTORY_PURPOSE, "history_v2", "history",
        "history_v2_corrupt_backup", HISTORY_BACKUP_PREF_KEY, "history_corrupt_backup"
    ) { values ->
        JSONArray().also { array ->
            values.sorted().forEachIndexed { index, url ->
                val id = UUID.nameUUIDFromBytes("$url|0|$index".toByteArray(StandardCharsets.UTF_8)).toString()
                array.put(JSONObject().put("id", id).put("url", url)
                    .put("title", Uri.parse(url).host ?: url).put("visitedAt", 0L))
            }
        }.toString()
    }

    fun writeBookmarksJson(context: Context, json: String) {
        migrateLegacyBackups(context)
        val encrypted = encrypt(context, BOOKMARKS_PURPOSE, json)
        val editor = prefs(context).edit().putString(BOOKMARKS_PREF_KEY, encrypted)
            .remove("bookmarks_v2").remove("bookmarks").remove("bookmarks_v2_corrupt_backup")
        check(editor.commit()) { "Could not persist encrypted bookmarks" }
    }

    fun writeHistoryJson(context: Context, json: String) {
        migrateLegacyBackups(context)
        val encrypted = encrypt(context, HISTORY_PURPOSE, json)
        val editor = prefs(context).edit().putString(HISTORY_PREF_KEY, encrypted)
            .remove("history_v2").remove("history").remove("history_v2_corrupt_backup")
        check(editor.commit()) { "Could not persist encrypted browsing history" }
    }


    fun readBookmarkTombstonesJson(context: Context): String? {
        migrateLegacyBackups(context)
        val p = prefs(context)
        p.getString(BOOKMARK_TOMBSTONES_PREF_KEY, null)?.let {
            return decrypt(context, BOOKMARK_TOMBSTONES_PURPOSE, it)
        }
        val legacy = p.getString("bookmark_tombstones", null) ?: return null
        val encrypted = encrypt(context, BOOKMARK_TOMBSTONES_PURPOSE, legacy)
        check(p.edit().putString(BOOKMARK_TOMBSTONES_PREF_KEY, encrypted).remove("bookmark_tombstones").commit()) {
            "Could not migrate bookmark deletion metadata to encrypted storage"
        }
        return legacy
    }

    fun writeBookmarkTombstonesJson(context: Context, json: String) {
        migrateLegacyBackups(context)
        val encrypted = encrypt(context, BOOKMARK_TOMBSTONES_PURPOSE, json)
        check(prefs(context).edit().putString(BOOKMARK_TOMBSTONES_PREF_KEY, encrypted)
            .remove("bookmark_tombstones").commit()) { "Could not persist encrypted bookmark deletion metadata" }
    }

    fun clearHistory(context: Context, clearedAt: Long) {
        val editor = prefs(context).edit().putLong("history_cleared_at", clearedAt)
            .remove(HISTORY_PREF_KEY).remove(HISTORY_BACKUP_PREF_KEY)
            .remove("history").remove("history_v2").remove("history_v2_corrupt_backup")
        check(editor.commit()) { "Could not clear encrypted browsing history" }
    }

    fun migrateLegacyBackups(context: Context) {
        migrateLegacyBackup(context, "history_v2_corrupt_backup", HISTORY_BACKUP_PREF_KEY, "history_corrupt_backup")
        migrateLegacyBackup(context, "bookmarks_v2_corrupt_backup", BOOKMARKS_BACKUP_PREF_KEY, "bookmarks_corrupt_backup")
    }

    fun encrypt(context: Context, purpose: String, plaintext: String): String =
        NovaEncryptedBlobCodec.encrypt(plaintext, purpose, getOrCreateKey(context))

    fun decrypt(context: Context, purpose: String, blob: String): String =
        NovaEncryptedBlobCodec.decrypt(blob, purpose, getExistingKey())

    private fun readEncryptedOrMigrate(
        context: Context,
        encryptedKey: String,
        purpose: String,
        legacyJsonKey: String,
        legacySetKey: String,
        legacyBackupKey: String,
        encryptedBackupKey: String,
        backupPurpose: String,
        legacySetToJson: (Set<String>) -> String
    ): String? {
        migrateLegacyBackup(context, legacyBackupKey, encryptedBackupKey, backupPurpose)
        val p = prefs(context)
        p.getString(encryptedKey, null)?.let { return decrypt(context, purpose, it) }
        val legacyJson = p.getString(legacyJsonKey, null)
        val legacySet = p.getStringSet(legacySetKey, emptySet()).orEmpty()
        val legacy = legacyJson ?: legacySet.takeIf { it.isNotEmpty() }?.let(legacySetToJson) ?: return null
        val encrypted = encrypt(context, purpose, legacy)
        val editor = p.edit().putString(encryptedKey, encrypted).remove(legacyJsonKey).remove(legacySetKey)
        check(editor.commit()) { "Could not migrate legacy browser data to encrypted storage" }
        return legacy
    }

    private fun migrateLegacyBackup(context: Context, legacyKey: String, encryptedKey: String, purpose: String) {
        val p = prefs(context)
        val legacy = p.getString(legacyKey, null) ?: return
        if (p.contains(encryptedKey)) {
            check(p.edit().remove(legacyKey).commit()) { "Could not remove legacy plaintext recovery data" }
            return
        }
        val encrypted = encrypt(context, purpose, legacy)
        check(p.edit().putString(encryptedKey, encrypted).remove(legacyKey).commit()) {
            "Could not encrypt legacy browser-data recovery copy"
        }
    }

    private fun getExistingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        return store.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw GeneralSecurityException("The browser-data encryption key is missing; refusing to replace it")
    }

    private fun getOrCreateKey(context: Context): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val p = prefs(context)
        check(!p.contains(BOOKMARKS_PREF_KEY) && !p.contains(HISTORY_PREF_KEY) &&
            !p.contains(BOOKMARKS_BACKUP_PREF_KEY) && !p.contains(HISTORY_BACKUP_PREF_KEY) &&
            !p.contains(BOOKMARK_TOMBSTONES_PREF_KEY)) {
            "Encrypted browser data exists but its Android Keystore key is missing; refusing to overwrite it"
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
