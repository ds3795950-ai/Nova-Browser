package com.nova.browser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
    private val alias = "nova.password.vault.aes.v1"

    @Synchronized fun all(): List<Autocomplete.LoginEntry> {
        val packed = prefs.getString(KEY_BLOB, null) ?: return emptyList()
        val bytes = Base64.decode(packed, Base64.NO_WRAP)
        require(bytes.size > 12) { "Password vault data is invalid" }
        val iv = bytes.copyOfRange(0, 12)
        val encrypted = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        val json = JSONArray(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        return (0 until json.length()).map { i ->
            val item = json.getJSONObject(i)
            Autocomplete.LoginEntry.Builder()
                .guid(item.optString("guid"))
                .origin(item.getString("origin"))
                .formActionOrigin(item.optString("formActionOrigin").ifBlank { null })
                .httpRealm(item.optString("httpRealm").ifBlank { null })
                .username(item.getString("username"))
                .password(item.getString("password"))
                .build()
        }
    }

    @Synchronized fun save(login: Autocomplete.LoginEntry) {
        val entries = all().filterNot { it.guid == login.guid || (it.origin == login.origin && it.username == login.username) }.toMutableList()
        entries.add(login)
        persist(entries)
    }

    @Synchronized fun delete(guid: String) = persist(all().filterNot { it.guid == guid })

    @Synchronized fun clear() { prefs.edit().remove(KEY_BLOB).apply() }

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
        prefs.edit().putString(KEY_BLOB, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    companion object { private const val KEY_BLOB = "encrypted_entries_v1" }
}
