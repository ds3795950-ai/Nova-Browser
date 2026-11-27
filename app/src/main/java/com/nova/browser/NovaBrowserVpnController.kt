package com.nova.browser

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import com.nova.browser.engine.CefriumVpnController
import com.nova.browser.engine.CefriumVpnCredentials

/** Pure fail-closed decision for browser navigation while 1VPN is requested. */
internal object BrowserVpnNavigationPolicy {
    fun shouldBlockNavigation(vpnEnabled: Boolean, routingReady: Boolean): Boolean = vpnEnabled && !routingReady
}

/** Controls the bundled 1VPN browser proxy. It never creates an Android VpnService. */
object NovaBrowserVpnController {
    const val EXTENSION_ID = "1vpn-internal@nova.browser"
    private const val NATIVE_APP = "com.nova.browser.vpn"
    private const val EXTENSION_URI = "resource://android/assets/nova_1vpn/"

    private var runtime: GeckoRuntime? = null
    private var preferences: SharedPreferences? = null
    private var nativePort: WebExtension.Port? = null
    private var credentialBootstrapSession: GeckoSession? = null
    private var credentialBootstrapTimeout: Runnable? = null
    private var statusListener: (() -> Unit)? = null
    private var torRoutingStatusListener: (() -> Unit)? = null

    fun initialize(context: Context, geckoRuntime: GeckoRuntime) {
        if (runtime === geckoRuntime) return
        runtime = geckoRuntime
        preferences = context.applicationContext.getSharedPreferences("nova_local", Context.MODE_PRIVATE)
        preferences?.edit()?.putBoolean("nova_tor_guest_enabled", false)?.putInt("nova_tor_guest_socks_port", 0)?.putBoolean("nova_tor_guest_proxy_ready", false)?.putBoolean("nova_tor_guest_configured", false)?.putBoolean("nova_tor_guest_mode", false)?.putBoolean("nova_vpn_routing_ready", false)?.apply()
        CefriumVpnController.setStatusListener { message ->
            preferences?.edit()?.putString("nova_vpn_status", message)?.apply()
            statusListener?.invoke()
        }
        // Restore the persisted toggle before any Cefrium profile is opened. Profiles
        // registered later will receive a fail-closed proxy until the extension handshake.
        CefriumVpnController.setEnabled(preferences?.getBoolean("nova_vpn_enabled", false) == true)

        geckoRuntime.getWebExtensionController()
            .ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
            .accept({ extension ->
                Handler(Looper.getMainLooper()).post {
                    if (extension == null) {
                        preferences?.edit()
                            ?.putBoolean("nova_vpn_proxy_supported", false)
                            ?.putString("nova_vpn_status", "1VPN built-in component could not be installed")
                            ?.apply()
                        statusListener?.invoke()
                    } else {
                        extension.setMessageDelegate(object : WebExtension.MessageDelegate {
                            override fun onConnect(port: WebExtension.Port) {
                                nativePort = port
                                port.setDelegate(object : WebExtension.PortDelegate {
                                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                                        val payload = message as? JSONObject ?: return
                                        val prefs = preferences ?: return
                                        when (payload.optString("type")) {
                                            "proxyCredentials" -> {
                                                val config = CefriumVpnCredentials(
                                                    host = payload.optString("host"),
                                                    port = payload.optInt("port", 443),
                                                    username = payload.optString("username"),
                                                    password = payload.optString("password"),
                                                    location = selectedLocationId()
                                                )
                                                CefriumVpnController.updateCredentials(config)
                                                closeCredentialBootstrap()
                                                prefs.edit().putBoolean("nova_vpn_proxy_supported", true).apply()
                                                statusListener?.invoke()
                                            }
                                            "ready", "status" -> {
                                                prefs.edit()
                                                    .putBoolean("nova_vpn_proxy_supported", payload.optBoolean("supported", false))
                                                    .putBoolean("nova_vpn_webrtc_protected", payload.optBoolean("webrtcProtected", false))
                                                    .putBoolean("nova_vpn_routing_ready", payload.optBoolean("routingReady", false))
                                                    .putBoolean("nova_tor_guest_configured", payload.has("torGuestMode"))
                                                    .putBoolean("nova_tor_guest_mode", payload.optBoolean("torGuestMode", false))
                                                    .putBoolean("nova_tor_guest_proxy_ready", prefs.getBoolean("nova_tor_guest_enabled", false) && payload.optBoolean("torRoutingReady", false) && payload.optBoolean("routingReady", false))
                                                    .putString("nova_vpn_status", payload.optString("status", "Proxy backend ready"))
                                                    .apply()
                                                statusListener?.invoke()
                                                torRoutingStatusListener?.invoke()
                                            }
                                            "error" -> {
                                                prefs.edit()
                                                    .putBoolean("nova_vpn_proxy_supported", false)
                                                    .putBoolean("nova_vpn_routing_ready", false)
                                                    .putString("nova_vpn_status", payload.optString("message", "1VPN proxy error"))
                                                    .apply()
                                                statusListener?.invoke()
                                            }
                                        }
                                    }

                                    override fun onDisconnect(port: WebExtension.Port) {
                                        if (nativePort === port) nativePort = null
                                        closeCredentialBootstrap()
                                        if (isVpnEnabled()) CefriumVpnController.updateCredentials(null)
                                        preferences?.edit()
                                            ?.putBoolean("nova_vpn_routing_ready", false)
                                            ?.putBoolean("nova_tor_guest_proxy_ready", false)
                                            ?.putBoolean("nova_tor_guest_configured", false)
                                            ?.putString("nova_vpn_status", if (isVpnEnabled()) "1VPN bridge disconnected; navigation paused until protection is restored" else "Proxy bridge disconnected; Guest Tor is no longer verified")
                                            ?.apply()
                                        statusListener?.invoke()
                                        torRoutingStatusListener?.invoke()
                                    }
                                })
                                sendCurrentSettings()
                            }
                        }, NATIVE_APP)
                        sendCurrentSettings()
                    }
                }
            }, { error ->
                preferences?.edit()
                    ?.putBoolean("nova_vpn_proxy_supported", false)
                    ?.putString("nova_vpn_status", "Could not load 1VPN: ${error?.localizedMessage ?: "unknown error"}")
                    ?.apply()
                statusListener?.invoke()
            })
    }

    fun setVpnEnabled(enabled: Boolean) {
        CefriumVpnController.setEnabled(enabled)
        if (enabled) CefriumVpnController.updateCredentials(null) else closeCredentialBootstrap()
        preferences?.edit()?.putBoolean("nova_vpn_enabled", enabled)?.putBoolean("nova_vpn_routing_ready", !enabled)?.apply()
        preferences?.edit()?.putString("nova_vpn_status", if (enabled) "Starting 1VPN browser proxy…" else "VPN off")?.apply()
        sendCurrentSettings()
        statusListener?.invoke()
    }

    fun isVpnEnabled(): Boolean = preferences?.getBoolean("nova_vpn_enabled", false) == true

    /** Navigation is denied while the user requested 1VPN but its proxy path is not verified ready. */
    fun isVpnRoutingReady(): Boolean = !isVpnEnabled() || preferences?.getBoolean("nova_vpn_routing_ready", false) == true

    fun setLocation(locationId: String) {
        if (NovaVpnLocations.find(locationId) == null) return
        preferences?.edit()?.putString("nova_vpn_location", locationId)?.apply()
        if (isVpnEnabled()) {
            CefriumVpnController.updateCredentials(null)
            preferences?.edit()?.putBoolean("nova_vpn_routing_ready", false)?.apply()
        }
        sendCurrentSettings()
        statusListener?.invoke()
    }

    fun selectedLocationId(): String =
        preferences?.getString("nova_vpn_location", NovaVpnLocations.DEFAULT_ID) ?: NovaVpnLocations.DEFAULT_ID

    fun statusText(): String = preferences?.getString("nova_vpn_status", "Starting internal 1VPN backend…")
        ?: "Starting internal 1VPN backend…"

    fun isProxySupported(): Boolean = preferences?.getBoolean("nova_vpn_proxy_supported", false) == true

    fun isFirewallEnabled(): Boolean {
        val prefs = preferences ?: return true
        return NovaShield.fromStored(prefs.getString("shield_mode", "STANDARD")) != ShieldMode.OFF
    }

    fun setFirewallEnabled(enabled: Boolean) {
        val current = NovaShield.fromStored(preferences?.getString("shield_mode", "STANDARD"))
        // Remember the user's stricter mode so switching the firewall back on does not downgrade it.
        val mode = if (enabled) {
            val remembered = NovaShield.fromStored(preferences?.getString("shield_mode_before_off", "STANDARD"))
            if (remembered == ShieldMode.OFF) ShieldMode.STANDARD else remembered
        } else ShieldMode.OFF
        val editor = preferences?.edit()
        if (!enabled && current != ShieldMode.OFF) editor?.putString("shield_mode_before_off", current.name)
        editor?.putString("shield_mode", mode.name)?.apply()
        runtime?.let { NovaShield.apply(it, mode) }
        statusListener?.invoke()
    }

    fun setStatusListener(listener: (() -> Unit)?) {
        statusListener = listener
    }

    /** Re-evaluate the current Activity after a routing transition completes. */
    fun notifyStatusChanged() {
        statusListener?.invoke()
    }

    fun setTorRoutingStatusListener(listener: (() -> Unit)?) {
        torRoutingStatusListener = listener
    }

    fun setTorGuestRouting(enabled: Boolean, socksPort: Int) {
        val valid = enabled && socksPort in 1..65535
        preferences?.edit()
            ?.putBoolean("nova_tor_guest_enabled", valid)
            ?.putInt("nova_tor_guest_socks_port", if (valid) socksPort else 0)
            ?.putBoolean("nova_tor_guest_proxy_ready", false)
            ?.putBoolean("nova_tor_guest_configured", false)
            ?.apply()
        sendCurrentSettings()
        torRoutingStatusListener?.invoke()
    }

    fun isTorGuestRoutingReady(): Boolean =
        nativePort != null && preferences?.getBoolean("nova_tor_guest_enabled", false) == true &&
            preferences?.getBoolean("nova_tor_guest_proxy_ready", false) == true

    /** True only after the extension explicitly acknowledges that Guest Tor routing is disabled. */
    fun isTorGuestRoutingDisabledConfirmed(): Boolean =
        nativePort != null && preferences?.getBoolean("nova_tor_guest_configured", false) == true &&
            preferences?.getBoolean("nova_tor_guest_mode", true) == false

    private fun sendCurrentSettings() {
        val prefs = preferences ?: return
        val payload = runCatching {
            JSONObject()
                .put("type", "configure")
                .put("enabled", prefs.getBoolean("nova_vpn_enabled", false))
                .put("location", prefs.getString("nova_vpn_location", NovaVpnLocations.DEFAULT_ID))
                .put("torGuestMode", prefs.getBoolean("nova_tor_guest_enabled", false))
                .put("torSocksPort", prefs.getInt("nova_tor_guest_socks_port", 0))
        }.getOrNull() ?: return
        runCatching { nativePort?.postMessage(payload) }
        if (prefs.getBoolean("nova_vpn_enabled", false)) requestCredentialBootstrap()
    }

    /** A short-lived hidden Gecko session makes one ordinary request so 1VPN's own
     * onAuthRequired handler can hand its resolved credentials to Cefrium. */
    private fun requestCredentialBootstrap() {
        val geckoRuntime = runtime ?: return
        if (nativePort == null || credentialBootstrapSession != null) return
        Handler(Looper.getMainLooper()).postDelayed({
            if (nativePort == null || !isVpnEnabled() || credentialBootstrapSession != null) return@postDelayed
            runCatching {
                val session = GeckoSession()
                credentialBootstrapSession = session
                session.open(geckoRuntime)
                session.setActive(true)
                session.loadUri("https://example.com/")
                val timeout = Runnable {
                    closeCredentialBootstrap()
                    if (isVpnEnabled()) CefriumVpnController.updateCredentials(null)
                }
                credentialBootstrapTimeout = timeout
                Handler(Looper.getMainLooper()).postDelayed(timeout, 15000L)
            }.onFailure {
                closeCredentialBootstrap()
                preferences?.edit()?.putString("nova_vpn_status", "Could not start the 1VPN credential handshake")?.apply()
                statusListener?.invoke()
            }
        }, 300L)
    }

    private fun closeCredentialBootstrap() {
        credentialBootstrapTimeout?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        credentialBootstrapTimeout = null
        credentialBootstrapSession?.let { runCatching { it.close() } }
        credentialBootstrapSession = null
    }
}
