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
    // New uploads stay at 16 MiB; older releases could create backups up to 32 MiB.
    private const val MAX_WRITE_PLAINTEXT_BYTES = 16 * 1024 * 1024
    private const val MAX_READ_PLAINTEXT_BYTES = 32 * 1024 * 1024
    private const val MAX_READ_CIPHERTEXT_BYTES = MAX_READ_PLAINTEXT_BYTES + 16
    private const val MAX_CIPHERTEXT_B64_CHARS = ((MAX_READ_CIPHERTEXT_BYTES + 2) / 3) * 4
    private fun utf8ByteCount(value: String, stopAfter: Int): Int {
        var total = 0
        var index = 0
        while (index < value.length) {
            val first = value[index]
            val codePoint = if (Character.isHighSurrogate(first) && index + 1 < value.length && Character.isLowSurrogate(value[index + 1])) {
                Character.toCodePoint(first, value[index + 1])
            } else first.code
            total += when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
            if (total > stopAfter) return total
            index += if (codePoint > 0xffff) 2 else 1
        }
        return total
    }
    internal fun isPlaintextWithinLimit(value: String): Boolean =
        utf8ByteCount(value, MAX_WRITE_PLAINTEXT_BYTES) <= MAX_WRITE_PLAINTEXT_BYTES
    private fun isLegacyPlaintextWithinLimit(value: String): Boolean =
        utf8ByteCount(value, MAX_READ_PLAINTEXT_BYTES) <= MAX_READ_PLAINTEXT_BYTES
    internal fun isCiphertextWithinLimit(encodedLength: Int): Boolean =
        encodedLength in 1..MAX_CIPHERTEXT_B64_CHARS
    private fun b64(bytes: ByteArray) = java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)
    private fun unb64(value: String) = java.util.Base64.getDecoder().decode(value)

    fun encrypt(plainText: String, passphrase: CharArray): JSONObject {
        require(passphrase.size >= 10) { "Choose a sync passphrase with at least 10 characters" }
        require(isPlaintextWithinLimit(plainText)) { "New encrypted backups are limited to 16 MiB" }
        val plaintextBytes = plainText.toByteArray(Charsets.UTF_8)
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
                .put("ciphertext", b64(cipher.doFinal(plaintextBytes)))
        } finally { keyBytes.fill(0) }
    }

    fun decrypt(envelope: JSONObject, passphrase: CharArray): String {
        require(passphrase.size >= 10) { "Choose a sync passphrase with at least 10 characters" }
        require(envelope.optInt("version") == 1 && envelope.optString("algorithm") == "AES-256-GCM") { "Unsupported cloud backup format" }
        require(envelope.optString("kdf") == "PBKDF2-HMAC-SHA256") { "Unsupported key derivation" }
        val iterations = envelope.optInt("iterations", ITERATIONS)
        require(iterations in 100_000..1_000_000) { "Invalid encryption parameters" }
        val saltText = envelope.getString("salt")
        val ivText = envelope.getString("iv")
        val ciphertextText = envelope.getString("ciphertext")
        require(saltText.length <= 24 && ivText.length <= 16) { "Invalid encrypted backup" }
        require(isCiphertextWithinLimit(ciphertextText.length)) { "Encrypted backup exceeds the 32 MiB legacy read limit" }
        val salt = unb64(saltText)
        val iv = unb64(ivText)
        val ciphertext = unb64(ciphertextText)
        require(salt.size == 16 && iv.size == 12 && ciphertext.size in 16..MAX_READ_CIPHERTEXT_BYTES) { "Invalid encrypted backup" }
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            val plaintext = String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            require(isLegacyPlaintextWithinLimit(plaintext)) { "Decrypted backup exceeds the 32 MiB legacy read limit" }
            plaintext
        } finally { keyBytes.fill(0) }
    }
}

/** Supabase Auth + PostgREST client. Only the publishable key is embedded in the APK. */
class CloudSyncConflictException(message: String) : IllegalStateException(message)

internal data class NovaCloudDownload(
    val snapshot: JSONObject?,
    val encryptedVaultPayloads: List<JSONObject>
)

internal object NovaCloudBundle {
    const val FORMAT = "nova-encrypted-sync-bundle-v1"

    fun pack(snapshot: JSONObject, vault: JSONObject): JSONObject =
        JSONObject().put("format", FORMAT).put("browser", snapshot).put("vault", vault)

    fun unpack(snapshot: JSONObject?, legacyVault: JSONObject?): Pair<JSONObject?, JSONObject?> {
        if (snapshot?.optString("format") == FORMAT) {
            val browser = snapshot.optJSONObject("browser")
            val vault = snapshot.optJSONObject("vault")
            require(browser != null && vault != null) { "Encrypted cloud bundle is incomplete" }
            return Pair(browser, vault)
        }
        return Pair(snapshot, legacyVault)
    }
}

class NovaCloudSync(context: Context) {
    companion object {
        private const val BASE_URL = BuildConfig.SUPABASE_URL
        private const val API_KEY = BuildConfig.SUPABASE_PUBLISHABLE_KEY
        private const val KEY_ALIAS = "nova.cloud.session.aes.v1"
    }
    private val appContext = context.applicationContext
    private val sessionFile = File(appContext.noBackupFilesDir, "nova-cloud-session.bin")
    @Volatile private var snapshotVersion: String? = null
    @Volatile private var vaultVersion: String? = null
    @Volatile private var snapshotUpdatedAt: String? = null
    @Volatile private var vaultUpdatedAt: String? = null

    /** Create a Supabase account using email and password. */
    fun signUpWithPassword(email: String, password: String) {
        val clean = email.trim().lowercase()
        require(clean.length <= 254 && android.util.Patterns.EMAIL_ADDRESS.matcher(clean).matches()) {
            "Enter a valid email address"
        }
        require(password.length >= 8) { "Password must be at least 8 characters" }
        require(password.length <= 72) { "Password is too long" }
        val body = JSONObject().put("email", clean).put("password", password).toString()
        val result = request("POST", "/auth/v1/signup", body)
        val json = JSONObject(result)
        if (json.has("access_token") && json.has("refresh_token")) {
            persistSessionFromAuthResponse(result)
        } else if (json.optJSONObject("user") != null ||
            (json.optString("id").isNotBlank() && json.optString("email").isNotBlank() && json.has("confirmation_sent_at"))) {
            throw IllegalStateException("Account created. Check your email to confirm the address, then sign in.")
        } else {
            throw IllegalStateException("Sign-up could not be completed. Check the address and try again.")
        }
    }

    /** Sign in to Nova Cloud using email and password. */
    fun signInWithPassword(email: String, password: String) {
        val clean = email.trim().lowercase()
        require(clean.length <= 254 && android.util.Patterns.EMAIL_ADDRESS.matcher(clean).matches()) {
            "Enter a valid email address"
        }
        require(password.isNotEmpty()) { "Enter your password" }
        val result = request(
            "POST",
            "/auth/v1/token?grant_type=password",
            JSONObject().put("email", clean).put("password", password).toString()
        )
        persistSessionFromAuthResponse(result)
    }

    /** Ask Supabase to send a password-recovery link without revealing whether the email exists. */
    fun requestPasswordReset(email: String): Boolean {
        val clean = email.trim().lowercase()
        require(clean.length <= 254 && android.util.Patterns.EMAIL_ADDRESS.matcher(clean).matches()) {
            "Enter a valid email address"
        }
        // The result reports transport acceptance only; it never reveals whether the account exists.
        return runCatching {
            request("POST", "/auth/v1/recover", JSONObject().put("email", clean).toString())
            true
        }.getOrElse {
            android.util.Log.w("NovaCloudSync", "Password recovery request failed", it)
            false
        }
    }

    private fun persistSessionFromAuthResponse(raw: String) {
        val session = JSONObject(raw)
        require(session.has("access_token") && session.has("refresh_token")) {
            "Supabase did not return a valid session"
        }
        session.put("expires_at", System.currentTimeMillis() / 1000 + session.optLong("expires_in", 3600))
        val userId = session.optJSONObject("user")?.optString("id") ?: session.optString("user_id")
        session.put("user_id", userId)
        require(session.optString("user_id").isNotBlank()) { "Supabase session did not include a user ID" }
        if (session.optJSONObject("user") == null && session.has("email")) {
            session.put("user", JSONObject().put("email", session.getString("email")).put("id", userId))
        }
        saveSession(session)
    }

    fun sendEmailOtp(email: String) {
        val clean = email.trim().lowercase()
        require(clean.length <= 254 && android.util.Patterns.EMAIL_ADDRESS.matcher(clean).matches()) { "Enter a valid email address" }
        request("POST", "/auth/v1/otp", JSONObject().put("email", clean).put("create_user", true).toString())
    }

    fun verifyEmailOtp(email: String, code: String) {
        val cleanCode = NovaEmailOtpPolicy.normalizeCode(code)
        val result = request("POST", "/auth/v1/verify", JSONObject().put("type", "email")
            .put("email", email.trim().lowercase()).put("token", cleanCode).toString())
        val session = JSONObject(result)
        require(session.has("access_token") && session.has("refresh_token")) { "Supabase did not return a valid session" }
        session.put("expires_at", System.currentTimeMillis() / 1000 + session.optLong("expires_in", 3600))
        session.put("user_id", session.optJSONObject("user")?.optString("id") ?: "")
        require(session.optString("user_id").isNotBlank()) { "Supabase session did not include a user ID" }
        saveSession(session)
    }

    fun signedInEmail(): String? = runCatching { loadSession()?.optJSONObject("user")?.optString("email")?.takeIf { it.isNotBlank() } }.getOrNull()

    fun signedInUserId(): String? = runCatching { validSession()?.optString("user_id")?.takeIf { it.isNotBlank() } }.getOrNull()

    fun signOut() {
        runCatching { validSession()?.let { request("POST", "/auth/v1/logout", "{}", it.getString("access_token")) } }
        sessionFile.delete()
        snapshotVersion = null
        vaultVersion = null
        snapshotUpdatedAt = null
        vaultUpdatedAt = null
    }

    internal fun downloadEncrypted(expectedUserId: String): NovaCloudDownload {
        val session = validSession() ?: throw IllegalStateException("Sign in to Nova Cloud first")
        val userId = session.getString("user_id")
        check(userId == expectedUserId) { "Nova Cloud account changed during sync" }
        val token = session.getString("access_token")
        val snapshot = fetchRow("nova_sync_state", "snapshot", userId, token)
        // Always read the legacy vault row as well. Older clients update it independently,
        // so its revision must participate in the next compare-and-swap.
        val legacyVault = fetchRow("nova_encrypted_vault", "payload", userId, token)
        val unpacked = NovaCloudBundle.unpack(snapshot, legacyVault)
        val payloads = buildList {
            unpacked.second?.let { add(it) }
            if (snapshot?.optString("format") == NovaCloudBundle.FORMAT && legacyVault != null) {
                // Do not choose one encrypted vault by row timestamp: each may contain credentials
                // or tombstones absent from the other. Callers decrypt both and merge their contents.
                add(legacyVault)
            }
        }
        return NovaCloudDownload(unpacked.first, payloads)
    }

    fun uploadEncrypted(expectedUserId: String, snapshot: JSONObject, vault: JSONObject) {
        val session = validSession() ?: throw IllegalStateException("Sign in to Nova Cloud first")
        val userId = session.getString("user_id")
        check(userId == expectedUserId) { "Nova Cloud account changed during sync" }
        val token = session.getString("access_token")
        // Keep independent CAS revisions until every legacy client is retired. A legacy
        // vault write after download must make the vault PATCH fail rather than be overwritten.
        upsertRow("nova_encrypted_vault", "payload", userId, vault, token, vaultVersion)
        upsertRow("nova_sync_state", "snapshot", userId, snapshot, token, snapshotVersion)
    }

    private fun fetchRow(table: String, column: String, userId: String, token: String): JSONObject? {
        val result = request("GET", "/rest/v1/$table?select=$column,updated_at&user_id=eq.$userId", null, token)
        val rows = org.json.JSONArray(result)
        if (rows.length() == 0) {
            if (column == "snapshot") {
                snapshotVersion = null
                snapshotUpdatedAt = null
            } else {
                vaultVersion = null
                vaultUpdatedAt = null
            }
            return null
        }
        val row = rows.getJSONObject(0)
        val version = row.optString("updated_at").takeIf { it.isNotBlank() }
        if (column == "snapshot") {
            snapshotVersion = version
            snapshotUpdatedAt = version
        } else {
            vaultVersion = version
            vaultUpdatedAt = version
        }
        return row.optJSONObject(column)
    }

    /** Compare-and-swap prevents two devices syncing the same old revision from silently overwriting each other. */
    private fun upsertRow(table: String, column: String, userId: String, payload: JSONObject, token: String, expectedVersion: String?) {
        val body = JSONObject().put("user_id", userId).put(column, payload)
        val response = if (expectedVersion == null) {
            request("POST", "/rest/v1/$table?on_conflict=user_id", body.toString(), token,
                "resolution=ignore-duplicates,return=representation")
        } else {
            val encodedVersion = java.net.URLEncoder.encode(expectedVersion, "UTF-8")
            request("PATCH", "/rest/v1/$table?user_id=eq.$userId&updated_at=eq.$encodedVersion", body.toString(), token,
                "return=representation")
        }
        val rows = org.json.JSONArray(response)
        if (rows.length() == 0) {
            throw CloudSyncConflictException("Cloud data changed on another device during sync. Run Sync now again to merge the newest copy.")
        }
        val newVersion = rows.getJSONObject(0).optString("updated_at").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Cloud sync response did not include its new revision")
        if (column == "snapshot") {
            snapshotVersion = newVersion
            snapshotUpdatedAt = newVersion
        } else {
            vaultVersion = newVersion
            vaultUpdatedAt = newVersion
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
            // Never forward an auth token to a redirect target.
            instanceFollowRedirects = false
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
                throw IllegalStateException(NovaEmailOtpPolicy.errorMessage(response, status))
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
