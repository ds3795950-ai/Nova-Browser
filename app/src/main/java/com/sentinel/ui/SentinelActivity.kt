package com.sentinel.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.nova.browser.NovaVpnFirewallActivity

/** Legacy entry point; opens Nova VPN & Firewall settings. */
class SentinelActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, NovaVpnFirewallActivity::class.java))
        finish()
    }
}
