package com.nova.browser

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.StringReader
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** One Android VPN service: WireGuard remote tunnel plus OS-enforced app exclusions and DNS protection. */
open class NovaVpnActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var firewallSummary: TextView
    private lateinit var importButton: Button
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private val worker = Executors.newSingleThreadExecutor()
    private val preferences by lazy { getSharedPreferences("nova_vpn_firewall", MODE_PRIVATE) }
    private val backend by lazy { GoBackend(applicationContext) }
    private val tunnel = object : Tunnel {
        override fun getName(): String = "Nova"
        override fun onStateChange(newState: Tunnel.State) {
            runOnUiThread { renderState(newState) }
        }
    }

    private val importConfig = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    val raw = withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error("Could not read this file")
                    }
                    val parsed = Config.parse(BufferedReader(StringReader(raw)))
                    require(parsed.getPeers().isNotEmpty()) { "The configuration has no VPN server peer." }
                    require(parsed.getInterface().getAddresses().isNotEmpty()) { "The configuration needs an Interface Address." }
                    EncryptedWireGuardConfig.write(this@NovaVpnActivity, raw)
                    status.text = "Configuration imported and encrypted on this device."
                    Toast.makeText(this@NovaVpnActivity, "WireGuard configuration imported", Toast.LENGTH_SHORT).show()
                    updateButtons()
                } catch (e: Exception) {
                    Toast.makeText(this@NovaVpnActivity, "Invalid config: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) connectNow()
        else Toast.makeText(this, "VPN permission was not granted", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Nova VPN & Firewall"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        status = TextView(this).apply { textSize = 18f; text = "Checking VPN status…" }
        val explanation = TextView(this).apply {
            textSize = 14f
            text = "Nova uses one WireGuard tunnel for the remote VPN. App blocking uses Android's VPN lockdown setting; DNS protection routes DNS through AdGuard's filtering resolvers. Import a .conf file from a VPN provider or your own server. Nova does not include VPN servers."
            setPadding(0, 18, 0, 16)
        }
        firewallSummary = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, 14) }
        importButton = Button(this).apply { text = "Import WireGuard .conf"; setOnClickListener { importConfig.launch(arrayOf("*/*")) } }
        val appRulesButton = Button(this).apply { text = "Choose apps to block"; setOnClickListener { chooseBlockedApps() } }
        val dnsButton = Button(this).apply { text = "DNS protection"; setOnClickListener { chooseDnsProtection() } }
        val lockdownButton = Button(this).apply { text = "Set up Android VPN kill switch"; setOnClickListener { openLockdownSettings() } }
        connectButton = Button(this).apply { text = "Connect"; setOnClickListener { requestConnect() } }
        disconnectButton = Button(this).apply { text = "Disconnect"; setOnClickListener { disconnect() } }
        listOf(status, explanation, firewallSummary, importButton, appRulesButton, dnsButton, lockdownButton, connectButton, disconnectButton).forEach {
            root.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10 })
        }
        setContentView(root)
        updateFirewallSummary()
        updateButtons()
        refreshState()
    }

    private fun chooseBlockedApps() {
        val apps = queryLaunchableApps()
        if (apps.isEmpty()) {
            Toast.makeText(this, "No launchable apps were found.", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = preferences.getStringSet(KEY_BLOCKED_APPS, emptySet()).orEmpty().toMutableSet()
        val labels = apps.map { "${it.second}  (${it.first})" }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].first in selected }
        AlertDialog.Builder(this)
            .setTitle("Apps to block")
            .setMultiChoiceItems(labels, checked) { _, index, isChecked ->
                if (isChecked) selected.add(apps[index].first) else selected.remove(apps[index].first)
            }
            .setPositiveButton("Save") { _, _ ->
                preferences.edit().putStringSet(KEY_BLOCKED_APPS, selected.toSet()).apply()
                updateFirewallSummary()
                Toast.makeText(this, "App rules saved. Enable Android VPN lockdown for them to block traffic.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun queryLaunchableApps(): List<Pair<String, String>> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcher, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == packageName) null else pkg to info.loadLabel(packageManager).toString()
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    private fun chooseDnsProtection() {
        val modes = arrayOf(
            "Off · Keep the DNS servers in my VPN profile",
            "AdGuard DNS · Blocks ads, trackers and phishing",
            "AdGuard Family · Also filters adult domains"
        )
        val current = preferences.getInt(KEY_DNS_MODE, 0).coerceIn(0, 2)
        AlertDialog.Builder(this)
            .setTitle("DNS protection")
            .setSingleChoiceItems(modes, current) { dialog, which ->
                preferences.edit().putInt(KEY_DNS_MODE, which).apply()
                updateFirewallSummary()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openLockdownSettings() {
        Toast.makeText(this, "In Android VPN settings, select Nova as Always-on VPN and enable Block connections without VPN. Without lockdown, excluded apps can bypass the tunnel instead of being blocked.", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, "Open Android Settings → Network & Internet → VPN.", Toast.LENGTH_LONG).show()
        }
    }

    private fun selectedInstalledApps(): Set<String> {
        val saved = preferences.getStringSet(KEY_BLOCKED_APPS, emptySet()).orEmpty()
        return saved.filterTo(linkedSetOf()) { pkg ->
            if (pkg == packageName) return@filterTo false
            try {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun effectiveConfig(raw: String): String {
        val blockedApps = selectedInstalledApps()
        val dnsMode = preferences.getInt(KEY_DNS_MODE, 0)
        return NovaFirewallConfig.apply(raw, blockedApps, dnsMode)
    }

    private fun requestConnect() {
        if (!EncryptedWireGuardConfig.exists(this)) {
            Toast.makeText(this, "Import a WireGuard .conf file first", Toast.LENGTH_LONG).show()
            return
        }
        val permission = VpnService.prepare(this)
        if (permission != null) vpnPermission.launch(permission) else connectNow()
    }

    private fun connectNow() {
        status.text = "Connecting to VPN server…"
        setBusy(true)
        worker.execute {
            try {
                val raw = EncryptedWireGuardConfig.read(this)
                val configText = effectiveConfig(raw)
                val config = Config.parse(BufferedReader(StringReader(configText)))
                backend.setState(tunnel, Tunnel.State.UP, config)
                runOnUiThread { renderState(Tunnel.State.UP) }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Connection failed: ${e.message ?: "unknown error"}"
                    setBusy(false)
                }
            }
        }
    }

    private fun disconnect() {
        setBusy(true)
        worker.execute {
            try { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            catch (e: Exception) { runOnUiThread { status.text = "Disconnect error: ${e.message}" } }
            runOnUiThread { renderState(Tunnel.State.DOWN) }
        }
    }

    private fun refreshState() {
        worker.execute {
            val state = try { backend.getState(tunnel) } catch (_: Exception) { Tunnel.State.DOWN }
            runOnUiThread { renderState(state) }
        }
    }

    private fun renderState(state: Tunnel.State) {
        status.text = when (state) {
            Tunnel.State.UP -> "Connected · WireGuard remote tunnel active"
            Tunnel.State.TOGGLE -> "Changing VPN state…"
            else -> if (EncryptedWireGuardConfig.exists(this)) "Disconnected · Configuration ready" else "Disconnected · Import a WireGuard configuration"
        }
        setBusy(false)
        updateButtons()
        updateFirewallSummary()
    }

    private fun updateFirewallSummary() {
        val appCount = selectedInstalledApps().size
        val dns = when (preferences.getInt(KEY_DNS_MODE, 0)) {
            1 -> "AdGuard DNS filtering"
            2 -> "AdGuard Family filtering"
            else -> "DNS protection off"
        }
        firewallSummary.text = "App block rules: $appCount (requires Android lockdown)\n$dns\nNote: app exclusions are enforced as blocks only when Android's Always-on VPN + Block connections without VPN are enabled."
    }

    private fun setBusy(busy: Boolean) {
        importButton.isEnabled = !busy
        connectButton.isEnabled = !busy
        disconnectButton.isEnabled = !busy
    }

    private fun updateButtons() {
        val hasConfig = EncryptedWireGuardConfig.exists(this)
        connectButton.isEnabled = hasConfig
        disconnectButton.isEnabled = true
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val KEY_BLOCKED_APPS = "blocked_apps"
        const val KEY_DNS_MODE = "dns_mode"
    }
}

/** AES-GCM encrypted config storage; the AES key never leaves Android Keystore. */
private object EncryptedWireGuardConfig {
    private const val ALIAS = "nova_wireguard_config_key"
    private const val FILE = "wireguard.conf.enc"

    fun exists(context: android.content.Context) = context.getFileStreamPath(FILE).isFile

    fun write(context: android.content.Context, plaintext: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        context.openFileOutput(FILE, android.content.Context.MODE_PRIVATE).use { out ->
            out.write(cipher.iv.size)
            out.write(cipher.iv)
            out.write(encrypted)
        }
    }

    fun read(context: android.content.Context): String {
        val bytes = context.openFileInput(FILE).use { it.readBytes() }
        require(bytes.isNotEmpty()) { "Stored configuration is empty" }
        val ivSize = bytes[0].toInt() and 0xff
        require(ivSize in 12..16 && bytes.size > ivSize + 1) { "Stored configuration is damaged" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 1, ivSize))
        return cipher.doFinal(bytes, 1 + ivSize, bytes.size - 1 - ivSize).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
}
