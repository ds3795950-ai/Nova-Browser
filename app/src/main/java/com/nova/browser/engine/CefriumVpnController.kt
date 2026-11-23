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

    private data class ProfileApplyResult(val proxyReady: Boolean = false, val trafficBlocked: Boolean = false,
        val webRtcProtected: Boolean = false, val disabledCleanly: Boolean = false)
    private data class ApplySummary(val profileCount: Int, val allProxyReady: Boolean, val allTrafficBlocked: Boolean,
        val allWebRtcProtected: Boolean, val allDisabledCleanly: Boolean)

    fun setStatusListener(listener: ((String) -> Unit)?) { statusListener = listener; listener?.invoke(status) }
    fun statusText(): String = status
    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) credentials = null
        val summary = applyAllProfiles()
        publish(if (value) enabledStatus(summary) else CefriumVpnStatusPolicy.disabledStatus(summary.allDisabledCleanly))
    }

    fun updateCredentials(config: CefriumVpnCredentials?) {
        if (!enabled) { credentials = null; return }
        credentials = config?.takeIf { it.host.isNotBlank() && it.port in 1..65535 && it.username.isNotBlank() && it.password.isNotBlank() }
        publish(enabledStatus(applyAllProfiles()))
    }

    fun registerProfile(profile: CefriumProfile) {
        profiles[profile] = WeakReference(profile)
        val summary = applyAllProfiles()
        if (enabled) publish(enabledStatus(summary))
        else if (!summary.allDisabledCleanly) publish("Could not clear 1VPN proxy settings on a new Cefrium profile")
    }

    fun refreshProfiles() {
        val summary = applyAllProfiles()
        if (enabled) publish(enabledStatus(summary))
        else if (!summary.allDisabledCleanly) publish("Could not clear 1VPN proxy settings on every Cefrium profile")
    }

    fun provideProxyCredentials(host: String, port: Int, isProxy: Boolean, callback: (String, String) -> Unit, cancel: () -> Unit) {
        val config = credentials
        if (!enabled || !isProxy || config == null || !config.host.equals(host, ignoreCase = true) || config.port != port) {
            cancel(); return
        }
        callback(config.username, config.password)
    }

    private fun applyAllProfiles(): ApplySummary {
        val results = mutableListOf<ProfileApplyResult>()
        val iterator = profiles.entries.iterator()
        while (iterator.hasNext()) {
            val profile = iterator.next().value.get()
            if (profile == null) iterator.remove() else results += apply(profile)
        }
        return ApplySummary(results.size, results.all { it.proxyReady }, results.all { it.trafficBlocked },
            results.all { it.webRtcProtected }, results.all { it.disabledCleanly })
    }

    private fun apply(profile: CefriumProfile): ProfileApplyResult {
        if (!enabled) {
            val proxyCleared = runCatching { profile.clearProxy() }.getOrDefault(false)
            val rtcRestored = runCatching { profile.setWebRtcIpHandlingPolicy(CefriumProfile.WEBRTC_DEFAULT) }.getOrDefault(false)
            return ProfileApplyResult(disabledCleanly = proxyCleared && rtcRestored)
        }
        val config = credentials
        val requestedProxy = if (config == null) FAIL_CLOSED_PROXY else "https://" + config.host + ":" + config.port
        val requestedProxyApplied = runCatching { profile.setProxy(requestedProxy) }.getOrDefault(false)
        val failClosedApplied = if (config == null) requestedProxyApplied else if (!requestedProxyApplied)
            runCatching { profile.setProxy(FAIL_CLOSED_PROXY) }.getOrDefault(false) else false
        val rtcProtected = runCatching { profile.setWebRtcIpHandlingPolicy(CefriumProfile.WEBRTC_DISABLE_NON_PROXIED_UDP) }.getOrDefault(false)
        runCatching {
            profile.setProxyErrorListener { _, _, error, mainFrame ->
                if (mainFrame) publish("1VPN proxy unavailable (error " + error + "); direct fallback is disabled")
            }
        }
        return ProfileApplyResult(config != null && requestedProxyApplied, requestedProxyApplied || failClosedApplied, rtcProtected)
    }

    private fun enabledStatus(summary: ApplySummary): String = CefriumVpnStatusPolicy.enabledStatus(
        hasCredentials = credentials != null,
        location = credentials?.location.orEmpty(),
        profileCount = summary.profileCount,
        allProxyReady = summary.allProxyReady,
        allTrafficBlocked = summary.allTrafficBlocked,
        allWebRtcProtected = summary.allWebRtcProtected
    )

    private fun publish(message: String) { status = message; statusListener?.invoke(message) }
}
