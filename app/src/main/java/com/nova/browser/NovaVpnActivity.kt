package com.nova.browser

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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

/** WireGuard remote VPN client. Configurations are encrypted with an Android Keystore key. */
open class NovaVpnActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var importButton: Button
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private val worker = Executors.newSingleThreadExecutor()
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
        title = "Nova VPN"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        status = TextView(this).apply { textSize = 18f; text = "Checking VPN status…" }
        val explanation = TextView(this).apply {
            textSize = 14f
            text = "Connect Nova to a remote WireGuard VPN server. Import a .conf file from a VPN provider or your own server. Nova does not include a paid VPN server or create an account for you."
            setPadding(0, 18, 0, 22)
        }
        importButton = Button(this).apply { text = "Import WireGuard .conf"; setOnClickListener { importConfig.launch(arrayOf("*/*")) } }
        connectButton = Button(this).apply { text = "Connect"; setOnClickListener { requestConnect() } }
        disconnectButton = Button(this).apply { text = "Disconnect"; setOnClickListener { disconnect() } }
        listOf(status, explanation, importButton, connectButton, disconnectButton).forEach {
            root.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 })
        }
        setContentView(root)
        updateButtons()
        refreshState()
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
                val config = Config.parse(BufferedReader(StringReader(raw)))
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
            Tunnel.State.UP -> "Connected » WireGuard tunnel active"
            Tunnel.State.TOGGLE -> "Changing VPN state…"
            else -> if (EncryptedWireGuardConfig.exists(this)) "Disconnected · Configuration ready" else "Disconnected » Import a WireGuard configuration"
        }
        setBusy(false)
        updateButtons()
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
