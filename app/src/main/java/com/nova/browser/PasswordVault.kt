package com.nova.browser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.Autocomplete
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM encrypted local login vault. The AES key never leaves Android Keystore. */
class PasswordVault(context: Context) {
    private val prefs = context.getSharedPreferences("nova_password_vault", Context.MODE_PRIVATE)
    // noBackupFilesDir prevents Android Auto Backup from restoring ciphertext without its Keystore key.
    private val vaultFile = File(context.noBackupFilesDir, "nova-password-vault.bin")
    private val vaultTempFile = File(vaultFile.parentFile, vaultFile.name + ".tmp")
    private val alias = "nova.password.vault.aes.v1"
    private val entriesCache = VaultEntriesCache<List<Autocomplete.LoginEntry>>()

    @Synchronized fun all(): List<Autocomplete.LoginEntry> {
        completePendingClear()
        return entriesCache.getOrLoad { decryptEntries() }
    }

    private fun decryptEntries(): List<Autocomplete.LoginEntry> {
        val bytes = readEncryptedBlob() ?: return emptyList()
        require(bytes.size > 12) { "Password vault data is invalid" }
        val iv = bytes.copyOfRange(0, 12)
        val encrypted = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        val json = JSONArray(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        val metadata = readJsonPreference(META_KEY)
        val tombstones = readJsonPreference(TOMBSTONES_KEY)
        return buildList {
            for (i in 0 until json.length()) {
                val entry = runCatching {
                    val fields = PasswordVaultValidation.fromJson(json.getJSONObject(i))
                    Autocomplete.LoginEntry.Builder().guid(fields.guid)
                        .origin(fields.origin).formActionOrigin(fields.formActionOrigin).httpRealm(fields.httpRealm)
                        .username(fields.username).password(fields.password).build()
                }.getOrNull()
                if (entry != null) {
                    val key = credentialKey(entry.origin, entry.username, entry.formActionOrigin, entry.httpRealm)
                    val legacyKey = legacyCredentialKey(entry.origin, entry.username)
                    val deletedAt = maxOf(tombstones.optLong(key), tombstones.optLong(legacyKey))
                    val updatedAt = maxOf(metadata.optLong(key), metadata.optLong(legacyKey))
                    if (deletedAt == 0L || updatedAt > deletedAt) add(entry)
                }
            }
        }
    }

    @Synchronized fun save(login: Autocomplete.LoginEntry) {
        validateLoginEntry(login)
        val entries = all().filterNot { existing ->
            existing.guid == login.guid || (
                existing.origin == login.origin &&
                    existing.username == login.username &&
                    existing.formActionOrigin == login.formActionOrigin &&
                    existing.httpRealm == login.httpRealm
                )
        }.toMutableList()
        entries.add(login)
        persist(entries)
        val key = credentialKey(login.origin, login.username, login.formActionOrigin, login.httpRealm)
        val legacyKey = legacyCredentialKey(login.origin, login.username)
        val metadata = readJsonPreference(META_KEY)
        val tombstones = readJsonPreference(TOMBSTONES_KEY)
        metadata.put(key, maxOf(System.currentTimeMillis(), tombstones.optLong(key) + 1L, tombstones.optLong(legacyKey) + 1L))
        tombstones.remove(key)
        tombstones.remove(legacyKey)
        check(prefs.edit().putString(META_KEY, metadata.toString()).putString(TOMBSTONES_KEY, tombstones.toString()).commit()) {
            "Could not persist password-vault sync metadata"
        }
    }

    @Synchronized fun delete(guid: String) {
        val entries = all()
        val removed = entries.firstOrNull { it.guid == guid } ?: return
        val key = credentialKey(removed.origin, removed.username, removed.formActionOrigin, removed.httpRealm)
        val metadata = readJsonPreference(META_KEY)
        val tombstones = readJsonPreference(TOMBSTONES_KEY)
        val legacyKey = legacyCredentialKey(removed.origin, removed.username)
        tombstones.put(key, maxOf(System.currentTimeMillis(), metadata.optLong(key) + 1L, metadata.optLong(legacyKey) + 1L))
        metadata.remove(key)
        metadata.remove(legacyKey)
        check(prefs.edit().putString(META_KEY, metadata.toString()).putString(TOMBSTONES_KEY, tombstones.toString()).commit()) {
            "Could not persist password deletion marker"
        }
        val remaining = entries.filterNot { it.guid == guid }
        entriesCache.replace(remaining)
        persist(remaining)
    }

    @Synchronized fun exportForSync(): JSONArray {
        val array = JSONArray()
        all().forEach { login ->
            array.put(JSONObject().put("guid", login.guid ?: java.util.UUID.randomUUID().toString())
                .put("origin", login.origin).put("formActionOrigin", login.formActionOrigin ?: "")
                .put("httpRealm", login.httpRealm ?: "").put("username", login.username).put("password", login.password))
        }
        return array
    }

    @Synchronized fun exportSyncBundle(): JSONObject {
        val metadata = readJsonPreference(META_KEY)
        val credentials = JSONArray()
        all().forEach { login ->
            val key = credentialKey(login.origin, login.username, login.formActionOrigin, login.httpRealm)
            credentials.put(JSONObject().put("guid", login.guid ?: java.util.UUID.randomUUID().toString())
                .put("origin", login.origin).put("formActionOrigin", login.formActionOrigin ?: "")
                .put("httpRealm", login.httpRealm ?: "").put("username", login.username).put("password", login.password)
                .put("updatedAt", metadata.optLong(key, metadata.optLong(legacyCredentialKey(login.origin, login.username)))))
        }
        return JSONObject().put("schema", 2).put("credentials", credentials).put("deleted", readJsonPreference(TOMBSTONES_KEY))
    }

    @Synchronized fun importSyncBundle(bundle: JSONObject) {
        require(bundle.optInt("schema", 1) in 1..2) { "Unsupported password-vault sync schema" }
        val merged = mergeSyncBundles(exportSyncBundle(), bundle)
        val credentials = merged.optJSONArray("credentials") ?: JSONArray()
        val deleted = merged.optJSONObject("deleted") ?: JSONObject()
        require(credentials.length() <= 2000) { "Cloud password vault is unexpectedly large" }
        require(deleted.length() <= 4000) { "Cloud password deletion history is unexpectedly large" }
        val tombstoneKeys = deleted.keys()
        while (tombstoneKeys.hasNext()) {
            val key = tombstoneKeys.next()
            require(key.length <= 8192 && deleted.optLong(key, -1L) in 1 until Long.MAX_VALUE) { "Invalid password-vault deletion marker" }
        }
        val entries = mutableListOf<Autocomplete.LoginEntry>()
        val metadata = JSONObject()
        val seenGuids = HashSet<String>()
        for (i in 0 until credentials.length()) {
            val item = credentials.getJSONObject(i)
            val fields = PasswordVaultValidation.fromJson(item)
            require(seenGuids.add(fields.guid)) { "Duplicate saved-login identifier in cloud vault" }
            val entry = Autocomplete.LoginEntry.Builder().guid(fields.guid)
                .origin(fields.origin).formActionOrigin(fields.formActionOrigin).httpRealm(fields.httpRealm)
                .username(fields.username).password(fields.password).build()
            entries.add(entry)
            val updatedAt = item.optLong("updatedAt", 0L)
            require(updatedAt in 0 until Long.MAX_VALUE) { "Invalid saved-login update timestamp" }
            metadata.put(credentialKey(fields.origin, fields.username, fields.formActionOrigin, fields.httpRealm), updatedAt)
        }
        persist(entries)
        check(prefs.edit().putString(META_KEY, metadata.toString())
            .putString(TOMBSTONES_KEY, deleted.toString()).commit()) {
            "Could not persist imported password-vault metadata"
        }
    }

    @Synchronized fun importFromSync(array: JSONArray) {
        require(array.length() <= 2000) { "Cloud password vault is unexpectedly large" }
        val merged = all().associateBy { credentialKey(it.origin, it.username, it.formActionOrigin, it.httpRealm) }.toMutableMap()
        val metadata = readJsonPreference(META_KEY)
        val tombstones = readJsonPreference(TOMBSTONES_KEY)
        val seenGuids = merged.mapNotNull { (key, entry) -> entry.guid?.let { it to key } }.toMap().toMutableMap()
        val now = System.currentTimeMillis()
        for (i in 0 until array.length()) {
            val fields = PasswordVaultValidation.fromJson(array.getJSONObject(i))
            val key = credentialKey(fields.origin, fields.username, fields.formActionOrigin, fields.httpRealm)
            val previousKey = seenGuids[fields.guid]
            require(previousKey == null || previousKey == key) { "Duplicate saved-login identifier in imported vault" }
            seenGuids[fields.guid] = key
            val entry = Autocomplete.LoginEntry.Builder().guid(fields.guid)
                .origin(fields.origin).formActionOrigin(fields.formActionOrigin).httpRealm(fields.httpRealm)
                .username(fields.username).password(fields.password).build()
            val legacyKey = legacyCredentialKey(fields.origin, fields.username)
            merged[key] = entry
            metadata.put(key, maxOf(now, tombstones.optLong(key) + 1L, tombstones.optLong(legacyKey) + 1L))
            tombstones.remove(key)
            tombstones.remove(legacyKey)
        }
        persist(merged.values.toList())
        check(prefs.edit().putString(META_KEY, metadata.toString()).putString(TOMBSTONES_KEY, tombstones.toString()).commit()) {
            "Could not persist imported password-vault metadata"
        }
    }

    @Synchronized fun clear() {
        check(prefs.edit().putBoolean(CLEAR_IN_PROGRESS_KEY, true).commit()) {
            "Could not begin password-vault deletion"
        }
        completePendingClear()
        entriesCache.replace(emptyList())
    }

    private fun completePendingClear() {
        if (!prefs.getBoolean(CLEAR_IN_PROGRESS_KEY, false)) return
        check(!vaultFile.exists() || vaultFile.delete()) { "Could not remove password-vault data file" }
        check(!vaultTempFile.exists() || vaultTempFile.delete()) { "Could not remove interrupted password-vault data" }
        check(prefs.edit().remove(KEY_BLOB).remove(META_KEY).remove(TOMBSTONES_KEY)
            .remove(CLEAR_IN_PROGRESS_KEY).commit()) { "Could not finalize password-vault deletion" }
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
        entriesCache.replace(emptyList())
    }

    private fun validateLoginEntry(login: Autocomplete.LoginEntry) {
        val guid = login.guid
        val formActionOrigin = login.formActionOrigin
        val httpRealm = login.httpRealm
        PasswordVaultValidation.validateWebOrigin(login.origin)
        formActionOrigin?.takeIf { it.isNotBlank() }?.let { PasswordVaultValidation.validateWebOrigin(it) }
        require(guid.isNullOrBlank() || guid.length <= 256) { "Saved login identifier is too long" }
        require(login.username.length <= 4096) { "Saved login username is too long" }
        require(login.password.length <= 16_384) { "Saved login password is too long" }
        require(httpRealm == null || httpRealm.length <= 4096) { "Saved login HTTP realm is too long" }
    }

    private fun persist(entries: List<Autocomplete.LoginEntry>) {
        val json = JSONArray()
        entries.forEach { login ->
            validateLoginEntry(login)
            json.put(JSONObject().put("guid", login.guid ?: java.util.UUID.randomUUID().toString())
                .put("origin", login.origin).put("formActionOrigin", login.formActionOrigin ?: "")
                .put("httpRealm", login.httpRealm ?: "").put("username", login.username).put("password", login.password))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        try {
            FileOutputStream(vaultTempFile).use { it.write(packed); it.fd.sync() }
            Os.rename(vaultTempFile.absolutePath, vaultFile.absolutePath)
        } catch (error: Exception) {
            vaultTempFile.delete()
            throw IllegalStateException("Could not securely write password vault", error)
        }
        check(prefs.edit().remove(KEY_BLOB).commit()) { "Could not finalize password-vault storage" }
        entriesCache.replace(entries.toList())
    }

    private fun readEncryptedBlob(): ByteArray? {
        if (prefs.getBoolean(CLEAR_IN_PROGRESS_KEY, false)) {
            completePendingClear()
            return null
        }
        if (vaultFile.exists() || vaultTempFile.exists()) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.getKey(alias, null) !is SecretKey) {
                throw IllegalStateException("Password-vault ciphertext exists but its Android Keystore key is missing; refusing to replace it.")
            }
        }
        var primaryError: Throwable? = null
        if (vaultFile.exists()) {
            val current = runCatching { vaultFile.readBytes() }.getOrElse { primaryError = it; null }
            if (current != null) {
                val valid = try {
                    validateVaultBlob(current)
                    true
                } catch (error: Exception) {
                    primaryError = error
                    false
                }
                if (valid) {
                    vaultTempFile.delete()
                    if (prefs.contains(KEY_BLOB)) check(prefs.edit().remove(KEY_BLOB).commit()) {
                        "Could not remove legacy password-vault ciphertext"
                    }
                    return current
                }
            }
        }
        if (vaultTempFile.exists()) {
            val candidate = try {
                vaultTempFile.readBytes().also { validateVaultBlob(it) }
            } catch (error: Exception) {
                vaultTempFile.delete()
                if (primaryError == null) primaryError = error
                null
            }
            if (candidate != null) {
                val renamed = try {
                    Os.rename(vaultTempFile.absolutePath, vaultFile.absolutePath)
                    true
                } catch (error: Exception) {
                    vaultTempFile.delete()
                    if (primaryError == null) primaryError = error
                    false
                }
                if (renamed) {
                    if (prefs.contains(KEY_BLOB)) check(prefs.edit().remove(KEY_BLOB).commit()) {
                        "Could not remove legacy password-vault ciphertext"
                    }
                    return candidate
                }
            }
        }
        val legacy = prefs.getString(KEY_BLOB, null)
        if (legacy == null) {
            if (primaryError != null) throw IllegalStateException("Saved password vault is damaged and no recoverable copy exists", primaryError)
            return null
        }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.getKey(alias, null) !is SecretKey) {
            throw IllegalStateException("This password vault was restored without its Android Keystore key and cannot be decrypted. Create a new vault on this device.")
        }
        val bytes = Base64.decode(legacy, Base64.NO_WRAP)
        validateVaultBlob(bytes)
        try {
            FileOutputStream(vaultTempFile).use { it.write(bytes); it.fd.sync() }
            Os.rename(vaultTempFile.absolutePath, vaultFile.absolutePath)
        } catch (error: Exception) {
            vaultTempFile.delete()
            throw IllegalStateException("Could not recover password vault from its protected legacy copy", error)
        }
        check(prefs.edit().remove(KEY_BLOB).commit()) { "Could not finish password-vault migration" }
        return bytes
    }

    private fun validateVaultBlob(packed: ByteArray) {
        require(packed.size > 12) { "Saved password vault is truncated" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        val plaintext = cipher.doFinal(packed.copyOfRange(12, packed.size))
        JSONArray(String(plaintext, Charsets.UTF_8))
    }

    private fun secretKey(): SecretKey {
        completePendingClear()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(!vaultFile.exists() && !vaultTempFile.exists() && !prefs.contains(KEY_BLOB)) {
            "This password vault exists but its Android Keystore key is missing; refusing to replace the key and destroy access to saved passwords."
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private fun readJsonPreference(key: String): JSONObject {
        val raw = prefs.getString(key, null) ?: return JSONObject()
        return try {
            JSONObject(raw).also { json ->
                require(json.length() <= 4000) { "Password-vault sync metadata is unexpectedly large" }
                val keys = json.keys()
                while (keys.hasNext()) {
                    val entryKey = keys.next()
                    val timestamp = json.optLong(entryKey, -1L)
                    val validTimestamp = if (key == TOMBSTONES_KEY) timestamp in 1 until Long.MAX_VALUE
                        else timestamp in 0 until Long.MAX_VALUE
                    require(entryKey.length <= 8192 && validTimestamp) {
                        "Password-vault sync metadata contains an invalid timestamp"
                    }
                }
            }
        } catch (error: Exception) {
            throw IllegalStateException("Password-vault sync metadata is damaged; refusing to discard deletion history", error)
        }
    }
    private fun credentialKey(origin: String, username: String, formActionOrigin: String? = null, httpRealm: String? = null) =
        "$origin|$username|${formActionOrigin.orEmpty()}|${httpRealm.orEmpty()}"

    private fun legacyCredentialKey(origin: String, username: String) = "$origin|$username"

    companion object {
        private const val KEY_BLOB = "encrypted_entries_v1"
        private const val META_KEY = "credential_updated_at_v1"
        private const val TOMBSTONES_KEY = "credential_tombstones_v1"
        private const val CLEAR_IN_PROGRESS_KEY = "vault_clear_in_progress_v1"

        /** Merges credentials and deletion markers from two devices; newer explicit deletes win. */
        internal fun decodeSyncBundle(serialized: String): JSONObject {
            val value = serialized.trim()
            return if (value.startsWith("{")) JSONObject(value) else JSONObject().put("schema", 1)
                .put("credentials", JSONArray(value)).put("deleted", JSONObject())
        }

        internal fun mergeSyncBundles(local: JSONObject, remote: JSONObject): JSONObject {
            val remoteCredentials = remote.optJSONArray("credentials") ?: JSONArray()
            val localCredentials = local.optJSONArray("credentials") ?: JSONArray()
            require(remoteCredentials.length() <= 2000 && localCredentials.length() <= 2000) { "Password-vault bundle is unexpectedly large" }
            val tombstones = JSONObject()
            for (source in listOf(remote.optJSONObject("deleted") ?: JSONObject(), local.optJSONObject("deleted") ?: JSONObject())) {
                require(source.length() <= 4000) { "Password-vault deletion history is unexpectedly large" }
                val keys = source.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val timestamp = source.optLong(key, -1L)
                    require(key.length <= 8192 && timestamp in 1 until Long.MAX_VALUE) { "Invalid password-vault deletion marker" }
                    tombstones.put(key, maxOf(tombstones.optLong(key), timestamp))
                }
            }
            val merged = linkedMapOf<String, JSONObject>()
            for (source in listOf(remoteCredentials, localCredentials)) {
                for (i in 0 until source.length()) {
                    val item = source.getJSONObject(i)
                    val key = item.optString("origin") + "|" + item.optString("username") + "|" +
                        item.optString("formActionOrigin") + "|" + item.optString("httpRealm")
                    val previous = merged[key]
                    if (previous == null || item.optLong("updatedAt") >= previous.optLong("updatedAt")) merged[key] = item
                }
            }
            val kept = JSONArray()
            merged.forEach { (key, item) ->
                val legacyKey = item.optString("origin") + "|" + item.optString("username")
                val deletedAt = maxOf(tombstones.optLong(key), tombstones.optLong(legacyKey))
                if (deletedAt == 0L || item.optLong("updatedAt") > deletedAt) kept.put(item)
            }
            return JSONObject().put("schema", 2).put("credentials", kept).put("deleted", tombstones)
        }
    }
}
