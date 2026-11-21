package com.nova.browser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/** Client-side encryption for cross-device sync. The sync passphrase is never stored or uploaded. */
internal object CloudCrypto {
    private const val ITERATIONS = 310_000
    private const val KEY_BITS = 256
    private fun b64(bytes: ByteArray) = java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)
    private fun unb64(value: String) = java.util.Base64.getDecoder().decode(value)

    fun encrypt(plainText: String, passphrase: CharArray): JSONObject {
        require(passphrase.size >= 10) { "Choose a sync passphrase with at least 10 characters" }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            JSONObject().put("version", 1).put("algorithm", "AES-256-GCM")
                .put("kdf", "PBKDF2-HMAC-SHA256").put("iterations", ITERATIONS)
                .put("salt", b64(salt)).put("iv", b64(iv))
                .put("ciphertext", b64(cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))))
        } finally { keyBytes.fill(0) }
    }

    fun decrypt(envelope: JSONObject, passphrase: CharArray): String {
        require(envelope.optInt("version") == 1 && envelope.optString("algorithm") == "AES-256-GCM") { "Unsupported cloud backup format" }
        val iterations = envelope.optInt("iterations", ITERATIONS)
        require(iterations in 100_000..1_000_000) { "Invalid encryption parameters" }
        val salt = unb64(envelope.getString("salt"))
        val iv = unb64(envelope.getString("iv"))
        require(salt.size == 16 && iv.size == 12) { "Invalid encrypted backup" }
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(unb64(envelope.getString("ciphertext"))), Charsets.UTF_8)
        } finally { keyBytes.fill(0) }
    }
}

/** Supabase Auth + PostgREST client. Only the publishable key is embedded in the APK. */
class CloudSyncConflictException(message: String) : IllegalStateException(message)

class NovaCloudSync(context: Context) {
    companion object {
        private const val BASE_URL = BuildConfig.SUPABASE_URL
        private const val API_KEY = BuildConfig.SUPABASE_PUBLISHABLE_KEY
        private const val KEY_ALIAS = "nova.cloud.session.aes.v1"

        internal fun hasUpdatedRow(response: String): Boolean = org.json.JSONArray(response).length() > 0
    }
    private val appContext = context.applicationContext
    private val sessionFile = File(appContext.noBackupFilesDir, "nova-cloud-session.bin")
    @Volatile private var snapshotVersion: String? = null
    @Volatile private var vaultVersion: String? = null

    fun sendEmailOtp(email: String) {
        val clean = email.trim().lowercase()
        require(clean.length <= 254 && android.util.Patterns.EMAIL_ADDRESS.matcher(clean).matches()) { "Enter a valid email address" }
        request("POST", "/auth/v1/otp", JSONObject().put("email", clean).put("create_user", true).toString())
    }

    fun verifyEmailOtp(email: String, code: String) {
        val cleanCode = code.trim()
        require(cleanCode.matches(Regex("[0-9]{6}"))) { "Enter the numeric code from your email" }
        val result = request("POST", "/auth/v1/verify", JSONObject().put("type", "email")
            .put("email", email.trim().lowercase()).put("token", cleanCode).toString())
        persistSessionFromAuthResponse(result)
    }

    /**
     * Create a new Nova Cloud account with email + password.
     * Supabase may require email confirmation depending on project settings.
     * On success the user is signed in (when confirmation is disabled) or a
     * confirmation-required message is returned via exception message.
     */
    fun signUpWithPassword(email: String, password: String) {
        val clean = email.trim().lowercase()
        require(clean.length <= 254 && android.util.Patterns.EMAIL_ADDRESS.matcher(clean).matches()) {
            "Enter a valid email address"
        }
        require(password.length >= 8) { "Password must be at least 8 characters" }
        require(password.length <= 72) { "Password is too long" }
        val body = JSONObject()
            .put("email", clean)
            .put("password", password)
            .toString()
        val result = request("POST", "/auth/v1/signup", body)
        val json = JSONObject(result)
        // When email confirmation is required, Supabase returns user but no session.
        if (json.has("access_token") && json.has("refresh_token")) {
            persistSessionFromAuthResponse(result)
        } else if (json.optJSONObject("user") != null) {
            throw IllegalStateException(
                "Account created. Check your email to confirm the address, then sign in."
            )
        } else {
            throw IllegalStateException("Sign-up failed. Try a different email or sign in instead.")
        }
    }

    /** Sign in with email + password. */
    fun signInWithPassword(email: String, password: String) {
        val clean = email.trim().lowercase()
        require(clean.length <= 254 && android.util.Patterns.EMAIL_ADDRESS.matcher(clean).matches()) {
            "Enter a valid email address"
        }
        require(password.isNotEmpty()) { "Enter your password" }
        val body = JSONObject()
            .put("email", clean)
            .put("password", password)
            .toString()
        val result = request("POST", "/auth/v1/token?grant_type=password", body)
        persistSessionFromAuthResponse(result)
    }

    /**
     * Request a password-reset email. Supabase sends a recovery link using the
     * project's "Reset Password" template. The user completes the reset in the browser.
     */
    fun requestPasswordReset(email: String) {
        val clean = email.trim().lowercase()
        require(clean.length <= 254 && android.util.Patterns.EMAIL_ADDRESS.matcher(clean).matches()) {
            "Enter a valid email address"
        }
        val body = JSONObject().put("email", clean).toString()
        request("POST", "/auth/v1/recover", body)
    }

    private fun persistSessionFromAuthResponse(raw: String) {
        val session = JSONObject(raw)
        require(session.has("access_token") && session.has("refresh_token")) {
            "Supabase did not return a valid session"
        }
        session.put("expires_at", System.currentTimeMillis() / 1000 + session.optLong("expires_in", 3600))
        val userId = session.optJSONObject("user")?.optString("id")
            ?: session.optString("user_id")
        session.put("user_id", userId)
        require(session.optString("user_id").isNotBlank()) {
            "Supabase session did not include a user ID"
        }
        // Ensure user object exists for signedInEmail()
        if (session.optJSONObject("user") == null && session.has("email")) {
            session.put("user", JSONObject().put("email", session.getString("email")).put("id", userId))
        }
        saveSession(session)
    }

    fun signedInEmail(): String? = runCatching {
        loadSession()?.optJSONObject("user")?.optString("email")?.takeIf { it.isNotBlank() }
            ?: loadSession()?.optString("email")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun signedInUserId(): String? = runCatching { validSession()?.optString("user_id")?.takeIf { it.isNotBlank() } }.getOrNull()

    fun signOut() {
        runCatching { validSession()?.let { request("POST", "/auth/v1/logout", "{}", it.getString("access_token")) } }
        sessionFile.delete()
    }

    fun downloadEncrypted(): Pair<JSONObject?, JSONObject?> {
        val session = validSession() ?: throw IllegalStateException("Sign in to Nova Cloud first")
        val userId = session.getString("user_id")
        val token = session.getString("access_token")
        val snapshot = fetchRow("nova_sync_state", "snapshot", userId, token)
        val vault = fetchRow("nova_encrypted_vault", "payload", userId, token)
        return Pair(snapshot, vault)
    }

    fun uploadEncrypted(snapshot: JSONObject, vault: JSONObject) {
        val session = validSession() ?: throw IllegalStateException("Sign in to Nova Cloud first")
        val userId = session.getString("user_id")
        val token = session.getString("access_token")
        upsertRow("nova_sync_state", "snapshot", userId, snapshot, token, snapshotVersion)
        upsertRow("nova_encrypted_vault", "payload", userId, vault, token, vaultVersion)
    }

    private fun fetchRow(table: String, column: String, userId: String, token: String): JSONObject? {
        if (table == "nova_sync_state") snapshotVersion = null
        if (table == "nova_encrypted_vault") vaultVersion = null
        val path = "/rest/v1/$table?user_id=eq.$userId&select=$column,version"
        val response = request("GET", path, null, token)
        val array = org.json.JSONArray(response)
        if (array.length() == 0) return null
        val row = array.getJSONObject(0)
        if (table == "nova_sync_state") snapshotVersion = row.optString("version").takeIf { it.isNotBlank() }
        if (table == "nova_encrypted_vault") vaultVersion = row.optString("version").takeIf { it.isNotBlank() }
        return row.optJSONObject(column)
    }

    private fun upsertRow(table: String, column: String, userId: String, payload: JSONObject, token: String, expectedVersion: String?) {
        val nextVersion = java.util.UUID.randomUUID().toString()
        try {
            val response = if (expectedVersion == null) {
                // Insert-only when no row was observed; never overwrite a row created concurrently.
                val body = JSONObject().put("user_id", userId).put(column, payload).put("version", nextVersion).toString()
                request("POST", "/rest/v1/$table", body, token, "return=representation")
            } else {
                // Update only the version downloaded by this client (optimistic concurrency control).
                val body = JSONObject().put(column, payload).put("version", nextVersion).toString()
                request("PATCH", "/rest/v1/$table?user_id=eq.$userId&version=eq.$expectedVersion", body, token, "return=representation")
            }
            if (!hasUpdatedRow(response)) {
                throw CloudSyncConflictException("Cloud data changed on another device during sync. Run Sync now again to merge the newest copy.")
            }
            if (table == "nova_sync_state") snapshotVersion = nextVersion
            if (table == "nova_encrypted_vault") vaultVersion = nextVersion
        } catch (e: IllegalStateException) {
            if (e is CloudSyncConflictException) throw e
            if (e.message?.contains("409") == true || e.message?.contains("conflict", ignoreCase = true) == true) {
                throw CloudSyncConflictException("Cloud data changed on another device during sync. Run Sync now again to merge the newest copy.")
            }
            throw e
        }
    }

    private fun validSession(): JSONObject? {
        val session = loadSession() ?: return null
        if (session.optLong("expires_at") > System.currentTimeMillis() / 1000 + 90) return session
        val refreshed = request("POST", "/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", session.getString("refresh_token")).toString())
        val next = JSONObject(refreshed)
        next.put("expires_at", System.currentTimeMillis() / 1000 + next.optLong("expires_in", 3600))
        next.put("user_id", next.optJSONObject("user")?.optString("id") ?: session.getString("user_id"))
        saveSession(next)
        return next
    }

    private fun request(method: String, path: String, body: String?, bearer: String? = null, prefer: String? = null): String {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("apikey", API_KEY)
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
            setRequestProperty("Accept", "application/json")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
            if (prefer != null) setRequestProperty("Prefer", prefer)
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(response).optString("msg").ifBlank { JSONObject(response).optString("message") } }.getOrNull()
                throw IllegalStateException(message?.take(180)?.ifBlank { "Cloud request failed ($status)" } ?: "Cloud request failed ($status)")
            }
            return response
        } finally { connection.disconnect() }
    }

    private fun loadSession(): JSONObject? {
        if (!sessionFile.exists()) return null
        val packed = sessionFile.readBytes()
        require(packed.size > 12) { "Saved cloud session is damaged; sign in again" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, sessionKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8))
    }

    private fun saveSession(session: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey())
        val temporary = File(sessionFile.parentFile, sessionFile.name + ".tmp")
        FileOutputStream(temporary).use { it.write(cipher.iv + cipher.doFinal(session.toString().toByteArray(Charsets.UTF_8))); it.fd.sync() }
        Os.rename(temporary.absolutePath, sessionFile.absolutePath)
    }

    private fun sessionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}
