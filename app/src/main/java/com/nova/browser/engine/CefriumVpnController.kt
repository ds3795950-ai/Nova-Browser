package com.nova.browser.engine

import com.cefrium.CefriumProfile
import java.lang.ref.WeakReference
import java.util.WeakHashMap

data class CefriumVpnCredentials(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val location: String
)

/** Authenticated 1VPN proxy for Cefrium. Credentials are memory-only; fixed proxy has no direct fallback. */
object CefriumVpnController {
    private const val FAIL_CLOSED_PROXY = "https://127.0.0.1:9"
    private var enabled = false
    private var credentials: CefriumVpnCredentials? = null
    private var status = "VPN off"
    private val profiles = WeakHashMap<CefriumProfile, WeakReference<CefriumProfile>>()
    private var statusListener: ((String) -> Unit)? = null

    fun setStatusListener(listener: ((String) -> Unit)?) {
        statusListener = listener
        listener?.invoke(status)
    }
    fun statusText(): String = status
    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            credentials = null
            applyAllProfiles()
            publish("VPN off")
        } else {
            applyAllProfiles()
            publish(if (credentials == null) "Connecting to 1VPN…" else "Applying 1VPN proxy…")
        }
    }

    fun updateCredentials(config: CefriumVpnCredentials?) {
        credentials = config?.takeIf {
            it.host.isNotBlank() && it.port in 1..65535 &&
                it.username.isNotBlank() && it.password.isNotBlank()
        }
        if (!enabled) return
        applyAllProfiles()
        publish(if (credentials == null) "1VPN configuration unavailable; Cefrium traffic is blocked"
            else "1VPN proxy configured · " + credentials!!.location.uppercase())
    }

    fun registerProfile(profile: CefriumProfile) {
        profiles[profile] = WeakReference(profile)
        apply(profile)
    }
    fun refreshProfiles() = applyAllProfiles()

    fun provideProxyCredentials(
        host: String, port: Int, isProxy: Boolean,
        callback: (String, String) -> Unit, cancel: () -> Unit
    ) {
        val config = credentials
        if (!enabled || !isProxy || config == null ||
            !config.host.equals(host, ignoreCase = true) || config.port != port
        ) {
            cancel()
            return
        }
        callback(config.username, config.password)
    }

    private fun applyAllProfiles() {
        val iterator = profiles.entries.iterator()
        while (iterator.hasNext()) {
            val profile = iterator.next().value.get()
            if (profile == null) iterator.remove() else apply(profile)
        }
    }

    private fun apply(profile: CefriumProfile) {
        if (!enabled) {
            val proxyCleared = runCatching { profile.clearProxy() }.getOrDefault(false)
            val rtcRestored = runCatching {
                profile.setWebRtcIpHandlingPolicy(CefriumProfile.WEBRTC_DEFAULT)
            }.getOrDefault(false)
            if (!proxyCleared || !rtcRestored) publish("Could not disable Cefrium 1VPN cleanly")
            return
        }
        val config = credentials
        val proxyServer = if (config == null) FAIL_CLOSED_PROXY
            else "https://" + config.host + ":" + config.port
        val proxyApplied = runCatching { profile.setProxy(proxyServer) }.getOrDefault(false)
        val rtcProtected = runCatching {
            profile.setWebRtcIpHandlingPolicy(CefriumProfile.WEBRTC_DISABLE_NON_PROXIED_UDP)
        }.getOrDefault(false)
        runCatching {
            profile.setProxyErrorListener { _, _, error, mainFrame ->
                if (mainFrame) publish("1VPN proxy unavailable (error " + error + "); direct fallback is disabled")
            }
        }
        if (!proxyApplied || !rtcProtected) {
            publish("Cefrium rejected 1VPN proxy or WebRTC protection; traffic is blocked")
        }
    }

    private fun publish(message: String) {
        status = message
        statusListener?.invoke(message)
    }
}
