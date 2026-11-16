package com.nova.browser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the sync passphrase only as AES-GCM ciphertext protected by a non-exportable Android Keystore key. */
internal object AutoSyncPassphraseStore {
    private const val KEY_ALIAS = "nova.auto-sync.passphrase.v1"
    private fun file(context: Context) = File(context.applicationContext.noBackupFilesDir, "nova-auto-sync-passphrase.bin")

    fun has(context: Context): Boolean = file(context).isFile

    fun save(context: Context, passphrase: CharArray) {
        val clear = String(passphrase).toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val target = file(context)
            val temporary = File(target.parentFile, target.name + ".tmp")
            FileOutputStream(temporary).use { out -> out.write(cipher.iv + cipher.doFinal(clear)); out.fd.sync() }
            Os.rename(temporary.absolutePath, target.absolutePath)
        } finally { clear.fill(0) }
    }

    fun load(context: Context): CharArray? {
        val target = file(context)
        if (!target.isFile) return null
        val packed = target.readBytes()
        require(packed.size > 12) { "Saved sync key is damaged; sync again manually" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        val clear = cipher.doFinal(packed.copyOfRange(12, packed.size))
        return try { String(clear, Charsets.UTF_8).toCharArray() } finally { clear.fill(0) }
    }

    fun clear(context: Context) {
        file(context).delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}

internal object NovaAutoSync {
    private const val UNIQUE_NAME = "nova-encrypted-cloud-sync"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<NovaAutoSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME) }
}

/** Periodic, network-constrained sync. It never runs without explicit opt-in and a Keystore-wrapped passphrase. */
class NovaAutoSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("nova_local", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("cloud_sync_enabled", false)) return Result.success()
        val passphrase = try { AutoSyncPassphraseStore.load(applicationContext) } catch (error: Exception) {
            prefs.edit().putString("cloud_sync_last_error", "Saved sync key unavailable; run Sync now again").apply()
            return Result.failure()
        } ?: return Result.success()
        try {
            val cloud = NovaCloudSync(applicationContext)
            val userId = cloud.signedInUserId() ?: return Result.retry()
            if (prefs.getString("cloud_sync_user_id", null) != userId) {
                prefs.edit().putString("cloud_sync_last_error", "Cloud account changed; sign in with the linked account").apply()
                return Result.failure()
            }
            val localSnapshot = NovaSyncSnapshotRepository.build(applicationContext)
            val vault = PasswordVault(applicationContext)
            val localVault = vault.exportForSync()
            val remote = cloud.downloadEncrypted()
            val remoteSnapshot = remote.first
            val merged = if (remoteSnapshot == null) localSnapshot else NovaSyncMerge.merge(localSnapshot,
                JSONObject(CloudCrypto.decrypt(remoteSnapshot, passphrase)))
            val vaultMap = linkedMapOf<String, JSONObject>()
            remote.second?.let { encrypted ->
                val remoteVault = JSONArray(CloudCrypto.decrypt(encrypted, passphrase))
                for (i in 0 until remoteVault.length()) {
                    val item = remoteVault.getJSONObject(i)
                    vaultMap[item.optString("origin") + "|" + item.optString("username")] = item
                }
            }
            for (i in 0 until localVault.length()) {
                val item = localVault.getJSONObject(i)
                vaultMap[item.optString("origin") + "|" + item.optString("username")] = item
            }
            val mergedVault = JSONArray().also { out -> vaultMap.values.forEach { out.put(it) } }
            cloud.uploadEncrypted(CloudCrypto.encrypt(merged.toString(), passphrase), CloudCrypto.encrypt(mergedVault.toString(), passphrase))
            NovaSyncSnapshotRepository.apply(applicationContext, merged)
            vault.importFromSync(mergedVault)
            prefs.edit().putLong("cloud_sync_last_success_at", System.currentTimeMillis()).remove("cloud_sync_last_error").apply()
            return Result.success()
        } catch (error: javax.crypto.AEADBadTagException) {
            prefs.edit().putString("cloud_sync_last_error", "Sync passphrase did not match this device's cloud backup").apply()
            return Result.failure()
        } catch (error: Exception) {
            prefs.edit().putString("cloud_sync_last_error", error.message?.take(180) ?: "Temporary cloud sync failure").apply()
            return Result.retry()
        } finally { passphrase.fill('\u0000') }
    }
}
