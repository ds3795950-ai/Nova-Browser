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
    private val alias = "nova.password.vault.aes.v1"
    private val entriesCache = VaultEntriesCache<List<Autocomplete.LoginEntry>>()

    @Synchronized fun all(): List<Autocomplete.LoginEntry> = entriesCache.getOrLoad { decryptEntries() }

    private fun decryptEntries(): List<Autocomplete.LoginEntry> {
        val bytes = readEncryptedBlob() ?: return emptyList()
        require(bytes.size > 12) { "Password vault data is invalid" }
        val iv = bytes.copyOfRange(0, 12)
        val encrypted = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        val json = JSONArray(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        return buildList {
            for (i in 0 until json.length()) {
                val entry = runCatching {
                    val item = json.getJSONObject(i)
                    Autocomplete.LoginEntry.Builder()
                        .guid(item.optString("guid"))
                        .origin(item.getString("origin"))
                        .formActionOrigin(item.optString("formActionOrigin").ifBlank { null })
                        .httpRealm(item.optString("httpRealm").ifBlank { null })
                        .username(item.getString("username"))
                        .password(item.getString("password"))
                        .build()
                }.getOrNull()
                if (entry != null) add(entry)
            }
        }
    }

    @Synchronized fun save(login: Autocomplete.LoginEntry) {
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
        prefs.edit().putString(META_KEY, metadata.toString()).putString(TOMBSTONES_KEY, tombstones.toString()).apply()
    }

    @Synchronized fun delete(guid: String) {
        val entries = all()
        val removed = entries.firstOrNull { it.guid == guid }
        persist(entries.filterNot { it.guid == guid })
        if (removed != null) {
            val key = credentialKey(removed.origin, removed.username, removed.formActionOrigin, removed.httpRealm)
            val metadata = readJsonPreference(META_KEY)
            val tombstones = readJsonPreference(TOMBSTONES_KEY)
            val legacyKey = legacyCredentialKey(removed.origin, removed.username)
            tombstones.put(key, maxOf(System.currentTimeMillis(), metadata.optLong(key) + 1L, metadata.optLong(legacyKey) + 1L))
            metadata.remove(key)
            metadata.remove(legacyKey)
            prefs.edit().putString(META_KEY, metadata.toString()).putString(TOMBSTONES_KEY, tombstones.toString()).apply()
        }
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
        val merged = mergeSyncBundles(exportSyncBundle(), bundle)
        val credentials = merged.optJSONArray("credentials") ?: JSONArray()
        require(credentials.length() <= 2000) { "Cloud password vault is unexpectedly large" }
        val entries = mutableListOf<Autocomplete.LoginEntry>()
        val metadata = JSONObject()
        for (i in 0 until credentials.length()) {
            val item = credentials.getJSONObject(i)
            val origin = item.getString("origin")
            require(origin.startsWith("https://") || origin.startsWith("http://")) { "Invalid saved login origin" }
            val username = item.getString("username")
            val entry = Autocomplete.LoginEntry.Builder().guid(item.optString("guid").ifBlank { java.util.UUID.randomUUID().toString() })
                .origin(origin).formActionOrigin(item.optString("formActionOrigin").ifBlank { null })
                .httpRealm(item.optString("httpRealm").ifBlank { null })
                .username(username).password(item.getString("password")).build()
            entries.add(entry)
            metadata.put(credentialKey(origin, username, entry.formActionOrigin, entry.httpRealm), item.optLong("updatedAt"))
        }
        persist(entries)
        prefs.edit().putString(META_KEY, metadata.toString())
            .putString(TOMBSTONES_KEY, (merged.optJSONObject("deleted") ?: JSONObject()).toString()).apply()
    }

    @Synchronized fun importFromSync(array: JSONArray) {
        require(array.length() <= 2000) { "Cloud password vault is unexpectedly large" }
        val merged = all().associateBy { credentialKey(it.origin, it.username, it.formActionOrigin, it.httpRealm) }.toMutableMap()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val origin = item.getString("origin")
            require(origin.startsWith("https://") || origin.startsWith("http://")) { "Invalid saved login origin" }
            val entry = Autocomplete.LoginEntry.Builder().guid(item.optString("guid").ifBlank { java.util.UUID.randomUUID().toString() })
                .origin(origin).formActionOrigin(item.optString("formActionOrigin").ifBlank { null })
                .httpRealm(item.optString("httpRealm").ifBlank { null })
                .username(item.getString("username")).password(item.getString("password")).build()
            merged[credentialKey(entry.origin, entry.username, entry.formActionOrigin, entry.httpRealm)] = entry
        }
        persist(merged.values.toList())
    }

    @Synchronized fun clear() {
        prefs.edit().remove(KEY_BLOB).remove(META_KEY).remove(TOMBSTONES_KEY).apply()
        vaultFile.delete()
        entriesCache.replace(emptyList())
    }

    private fun persist(entries: List<Autocomplete.LoginEntry>) {
        val json = JSONArray()
        entries.forEach { login ->
            json.put(JSONObject().put("guid", login.guid ?: java.util.UUID.randomUUID().toString())
                .put("origin", login.origin).put("formActionOrigin", login.formActionOrigin ?: "")
                .put("httpRealm", login.httpRealm ?: "").put("username", login.username).put("password", login.password))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        val temporary = File(vaultFile.parentFile, vaultFile.name + ".tmp")
        FileOutputStream(temporary).use { it.write(packed); it.fd.sync() }
        try {
            Os.rename(temporary.absolutePath, vaultFile.absolutePath)
        } catch (error: Exception) {
            temporary.delete()
            throw IllegalStateException("Could not securely write password vault", error)
        }
        prefs.edit().remove(KEY_BLOB).apply()
        entriesCache.replace(entries.toList())
    }

    private fun readEncryptedBlob(): ByteArray? {
        if (vaultFile.exists()) return vaultFile.readBytes()
        val legacy = prefs.getString(KEY_BLOB, null) ?: return null
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.getKey(alias, null) !is SecretKey) {
            // An OS backup may have restored preferences without the non-exportable Keystore key.
            // The ciphertext cannot be decrypted, so do not invent a replacement key and overwrite it silently.
            throw IllegalStateException("This password vault was restored without its Android Keystore key and cannot be decrypted. Create a new vault on this device.")
        }
        val bytes = Base64.decode(legacy, Base64.NO_WRAP)
        val temporary = File(vaultFile.parentFile, vaultFile.name + ".tmp")
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        if (!temporary.renameTo(vaultFile)) throw IllegalStateException("Could not migrate password vault to protected storage")
        prefs.edit().remove(KEY_BLOB).apply()
        return bytes
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(!vaultFile.exists() && !prefs.contains(KEY_BLOB)) {
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

    private fun readJsonPreference(key: String): JSONObject = runCatching { JSONObject(prefs.getString(key, "{}") ?: "{}") }.getOrDefault(JSONObject())
    private fun credentialKey(origin: String, username: String, formActionOrigin: String? = null, httpRealm: String? = null) =
        "$origin|$username|${formActionOrigin.orEmpty()}|${httpRealm.orEmpty()}"

    private fun legacyCredentialKey(origin: String, username: String) = "$origin|$username"

    companion object {
        private const val KEY_BLOB = "encrypted_entries_v1"
        private const val META_KEY = "credential_updated_at_v1"
        private const val TOMBSTONES_KEY = "credential_tombstones_v1"

        /** Merges credentials and deletion markers from two devices; newer explicit deletes win. */
        internal fun decodeSyncBundle(serialized: String): JSONObject {
            val value = serialized.trim()
            return if (value.startsWith("{")) JSONObject(value) else JSONObject().put("schema", 1)
                .put("credentials", JSONArray(value)).put("deleted", JSONObject())
        }

        internal fun mergeSyncBundles(local: JSONObject, remote: JSONObject): JSONObject {
            val tombstones = JSONObject()
            for (source in listOf(remote.optJSONObject("deleted") ?: JSONObject(), local.optJSONObject("deleted") ?: JSONObject())) {
                val keys = source.keys()
                while (keys.hasNext()) { val key = keys.next(); tombstones.put(key, maxOf(tombstones.optLong(key), source.optLong(key))) }
            }
            val merged = linkedMapOf<String, JSONObject>()
            for (source in listOf(remote.optJSONArray("credentials") ?: JSONArray(), local.optJSONArray("credentials") ?: JSONArray())) {
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
