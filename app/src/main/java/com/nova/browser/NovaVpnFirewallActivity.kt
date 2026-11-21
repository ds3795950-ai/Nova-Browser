package com.nova.browser

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Browser-only VPN and firewall controls. No Android VpnService is started here. */
open class NovaVpnFirewallActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var vpnToggle: Switch
    private lateinit var firewallToggle: Switch
    private lateinit var locationButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "VPN & Firewall"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(24), dp(22), dp(20))
        }
        val heading = TextView(this).apply {
            text = "Nova VPN & Firewall"
            textSize = 23f
            setPadding(0, 0, 0, dp(12))
        }
        val intro = TextView(this).apply {
            text = "Both controls affect Nova Browser only. Other Android apps continue using your normal network."
            textSize = 14f
            setPadding(0, 0, 0, dp(18))
        }
        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, dp(14))
        }
        vpnToggle = Switch(this).apply {
            text = "Enable 1VPN for Nova"
            textSize = 17f
            isChecked = NovaBrowserVpnController.isVpnEnabled()
            setOnCheckedChangeListener { _, enabled ->
                NovaBrowserVpnController.setVpnEnabled(enabled)
                refreshStatus()
            }
        }
        locationButton = Button(this).apply {
            text = "Server: ${selectedLocationLabel()}  ▾"
            setOnClickListener { chooseLocation() }
        }
        val vpnDetails = TextView(this).apply {
            text = "Free 1VPN locations: Singapore, Amsterdam and Los Angeles. India server locations are premium on the provider's free catalogue. 1VPN advertises unlimited free traffic without an account."
            textSize = 13f
            setPadding(dp(4), 0, dp(4), dp(16))
        }
        val firewallHeading = TextView(this).apply {
            text = "Nova Firewall"
            textSize = 19f
            setPadding(0, dp(14), 0, dp(4))
        }
        firewallToggle = Switch(this).apply {
            text = "Enable browser firewall"
            textSize = 17f
            isChecked = NovaBrowserVpnController.isFirewallEnabled()
            setOnCheckedChangeListener { _, enabled ->
                NovaBrowserVpnController.setFirewallEnabled(enabled)
                refreshStatus()
            }
        }
        val firewallDetails = TextView(this).apply {
            text = "Uses GeckoView's built-in Enhanced Tracking Protection to block known trackers and tracking content. NovaShield's Aggressive mode adds stricter fingerprinting and cryptomining protection. It does not filter other apps or act as a device-wide packet firewall."
            textSize = 13f
            setPadding(dp(4), 0, dp(4), dp(16))
        }
        val privacy = TextView(this).apply {
            text = "1VPN is a third-party provider. When enabled, browser requests are routed through its free proxy servers. See 1vpn.org/privacy-policy for its privacy terms. WebRTC protection is applied where supported. If all proxy servers fail, GeckoView may fall back to the browser's default route; treat this as best-effort browser proxying, not a leak-proof tunnel."
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        }

        listOf(heading, intro, status, vpnToggle, locationButton, vpnDetails, firewallHeading, firewallToggle, firewallDetails, privacy).forEach {
            root.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        scroll.applySystemBarPadding()
        NovaBrowserVpnController.setStatusListener { runOnUiThread { refreshStatus() } }
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        NovaBrowserVpnController.setStatusListener(null)
        super.onDestroy()
    }

    private fun chooseLocation() {
        val servers = NovaVpnLocations.free
        val labels = servers.map { "${it.city}, ${it.country} · Free" }.toTypedArray()
        val current = servers.indexOfFirst { it.id == NovaBrowserVpnController.selectedLocationId() }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Choose 1VPN free server")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                NovaBrowserVpnController.setLocation(servers[which].id)
                locationButton.text = "Server: ${selectedLocationLabel()}  ▾"
                dialog.dismiss()
                refreshStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun selectedLocationLabel(): String {
        val location = NovaVpnLocations.find(NovaBrowserVpnController.selectedLocationId())
            ?: NovaVpnLocations.find(NovaVpnLocations.DEFAULT_ID)!!
        return "${location.city}, ${location.country}"
    }

    private fun refreshStatus() {
        if (!::status.isInitialized) return
        val enabled = NovaBrowserVpnController.isVpnEnabled()
        val raw = NovaBrowserVpnController.statusText()
        status.text = when {
            !enabled -> "VPN: Off"
            raw.contains("error", ignoreCase = true) || raw.contains("disconnected", ignoreCase = true) || raw.contains("unavailable", ignoreCase = true) -> "VPN: $raw"
            !NovaBrowserVpnController.isProxySupported() -> "VPN: Waiting for the 1VPN proxy backend. $raw"
            else -> "VPN: On · ${selectedLocationLabel()} · browser proxy configured. Open an IP-check page to verify the exit IP."
        }
        if (::locationButton.isInitialized) {
            locationButton.text = "Server: ${selectedLocationLabel()}  ▾"
            locationButton.isEnabled = true
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
