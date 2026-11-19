package com.nova.browser

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

open class NovaVpnFirewallActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var vpnSwitch: Switch
    private lateinit var locations: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "VPN & Firewall · 1VPN"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(TextView(this).apply { text = "1VPN browser proxy"; textSize = 22f })
        root.addView(TextView(this).apply {
            text = "Routes Nova browser traffic only. It is not a full-device VPN."
            textSize = 14f
            setPadding(0, 12, 0, 20)
        })
        vpnSwitch = Switch(this).apply {
            text = "Enable 1VPN"
            isChecked = NovaBrowserVpnController.isVpnEnabled()
            setOnCheckedChangeListener { _, enabled ->
                NovaBrowserVpnController.setVpnEnabled(enabled)
                renderStatus()
            }
        }
        root.addView(vpnSwitch)
        root.addView(TextView(this).apply {
            text = "Server location"; textSize = 16f; setPadding(0, 24, 0, 8)
        })
        locations = Spinner(this)
        locations.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            NovaVpnLocations.all.map { it.name + " · " + it.country + " · Free" }
        )
        locations.setSelection(NovaVpnLocations.all.indexOfFirst {
            it.id == NovaBrowserVpnController.selectedLocationId()
        }.coerceAtLeast(0))
        locations.isEnabled = !NovaBrowserVpnController.isVpnEnabled()
        root.addView(locations)
        root.addView(Button(this).apply {
            text = "Apply location"
            setOnClickListener {
                val selected = NovaVpnLocations.all[locations.selectedItemPosition]
                NovaBrowserVpnController.setLocation(selected.id)
                if (NovaBrowserVpnController.isVpnEnabled()) NovaBrowserVpnController.setVpnEnabled(true)
                renderStatus()
            }
        })
        status = TextView(this).apply { textSize = 14f; setPadding(0, 24, 0, 16) }
        root.addView(status)
        root.addView(Button(this).apply { text = "Close"; setOnClickListener { finish() } })
        setContentView(root)
        NovaBrowserVpnController.setStatusListener { mainHandler.post { renderStatus() } }
        renderStatus()
    }

    private fun renderStatus() {
        status.text = NovaBrowserVpnController.statusText()
        locations.isEnabled = !NovaBrowserVpnController.isVpnEnabled()
    }

    override fun onDestroy() {
        NovaBrowserVpnController.setStatusListener(null)
        super.onDestroy()
    }
}
