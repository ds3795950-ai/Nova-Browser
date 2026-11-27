package com.nova.browser

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import org.torproject.jni.TorService

/**
 * Owns the embedded Tor daemon for Guest Mode. Guest navigation is not released until Tor has
 * built a circuit and the GeckoView proxy extension confirms SOCKS5, remote DNS and WebRTC
 * protection. Any loss of that chain closes Guest Mode instead of falling back to DIRECT.
 */
internal object TorGuestRoutingController {
    private enum class State { STOPPED, STARTING, WAITING_FOR_PROXY, READY, STOPPING, FAILED }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var state = State.STOPPED
    private var appContext: Context? = null
    private var torService: TorService? = null
    private var isBound = false
    private var receiver: BroadcastReceiver? = null
    private var receiverRegistered = false
    private var socksPort = 0
    private var startTimeout: Runnable? = null
    private var proxyTimeout: Runnable? = null
    private var proxyPoll: Runnable? = null
    private var socksPortPoll: Runnable? = null
    private var socksPortAttempts = 0
    private var onReady: (() -> Unit)? = null
    private var onFailure: ((String) -> Unit)? = null
    private var lastError = "Tor is not running"

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            torService = (binder as? TorService.LocalBinder)?.service
            if (torService == null) fail("The embedded Tor service did not provide its control binder")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            torService = null
            if (state != State.STOPPING && state != State.STOPPED) {
                fail("The embedded Tor service disconnected")
            }
        }
    }

    fun start(context: Context, onReady: () -> Unit, onFailure: (String) -> Unit) {
        this.onReady = onReady
        this.onFailure = onFailure
        if (state == State.READY && NovaBrowserVpnController.isTorGuestRoutingReady()) {
            onReady()
            return
        }
        if (state == State.STARTING || state == State.WAITING_FOR_PROXY) return

        stopInternal(disableProxy = true)
        state = State.STARTING
        lastError = "Starting embedded Tor…"
        val applicationContext = context.applicationContext
        appContext = applicationContext
        NovaBrowserVpnController.setTorRoutingStatusListener {
            mainHandler.post { checkProxyReadiness() }
        }

        val statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    TorService.ACTION_STATUS -> {
                        when (intent.getStringExtra(TorService.EXTRA_STATUS)) {
                            TorService.STATUS_ON -> onTorCircuitReady()
                            TorService.STATUS_STOPPING, TorService.STATUS_OFF -> {
                                if (state != State.STOPPING && state != State.STOPPED) {
                                    fail("Tor stopped before Guest Mode could be secured")
                                }
                            }
                        }
                    }
                    TorService.ACTION_ERROR -> fail(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "Tor reported a startup error")
                }
            }
        }
        receiver = statusReceiver
        val filter = IntentFilter().apply {
            addAction(TorService.ACTION_STATUS)
            addAction(TorService.ACTION_ERROR)
        }
        try {
            ContextCompat.registerReceiver(applicationContext, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            isBound = applicationContext.bindService(Intent(applicationContext, TorService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
            if (!isBound) {
                fail("Android refused to bind the embedded Tor service")
                return
            }
        } catch (error: Throwable) {
            fail(error.localizedMessage ?: "Could not start embedded Tor")
            return
        }

        startTimeout = Runnable { fail("Tor did not build a circuit within 3 minutes") }
        mainHandler.postDelayed(startTimeout!!, 180_000L)
    }

    fun isReady(): Boolean = state == State.READY && NovaBrowserVpnController.isTorGuestRoutingReady()

    fun statusText(): String = when (state) {
        State.STARTING -> "Starting embedded Tor and building a circuit…"
        State.WAITING_FOR_PROXY -> "Tor circuit built; securing GeckoView routing…"
        State.READY -> "Tor connected · Guest SOCKS5 routing and WebRTC protection active"
        State.FAILED -> "Tor unavailable · $lastError"
        State.STOPPING -> "Stopping Guest Tor…"
        State.STOPPED -> "Tor is not running outside Guest Mode"
    }

    fun stop() {
        if (state == State.STOPPED) {
            NovaBrowserVpnController.setTorGuestRouting(false, 0)
            return
        }
        state = State.STOPPING
        stopInternal(disableProxy = true)
        state = State.STOPPED
        lastError = "Tor is not running"
        onReady = null
        onFailure = null
    }

    private fun onTorCircuitReady() {
        if (state != State.STARTING) return
        val port = torService?.socksPort ?: 0
        if (!GuestTorRoutingPolicy.canEnableProxy(torBootstrapped = true, socksPort = port)) {
            // Tor can announce its first circuit just before the control thread publishes
            // net/listeners/socks. Retry briefly rather than treating that ordering as failure.
            if (socksPortAttempts++ < 40) {
                if (socksPortPoll == null) {
                    socksPortPoll = Runnable {
                        socksPortPoll = null
                        onTorCircuitReady()
                    }
                    mainHandler.postDelayed(socksPortPoll!!, 250L)
                }
                return
            }
            fail("Tor built a circuit but did not expose a valid local SOCKS port")
            return
        }
        socksPortAttempts = 0
        socksPortPoll?.let(mainHandler::removeCallbacks)
        socksPortPoll = null
        socksPort = port
        state = State.WAITING_FOR_PROXY
        lastError = "Waiting for the GeckoView proxy bridge"
        NovaBrowserVpnController.setTorGuestRouting(true, socksPort)
        proxyTimeout = Runnable { fail("GeckoView did not confirm Tor proxy and WebRTC leak protection") }
        mainHandler.postDelayed(proxyTimeout!!, 30_000L)
        checkProxyReadiness()
    }

    private fun checkProxyReadiness() {
        if (state == State.READY) {
            if (!NovaBrowserVpnController.isTorGuestRoutingReady()) {
                fail("The GeckoView Tor proxy bridge disconnected")
            }
            return
        }
        if (state != State.WAITING_FOR_PROXY) return
        val ready = NovaBrowserVpnController.isTorGuestRoutingReady()
        if (GuestTorRoutingPolicy.canEnterGuest(torBootstrapped = true, socksPort = socksPort, proxyBridgeReady = ready)) {
            state = State.READY
            cancelTimeouts()
            lastError = ""
            onReady?.invoke()
            return
        }
        if (proxyPoll == null) {
            proxyPoll = Runnable {
                proxyPoll = null
                checkProxyReadiness()
            }
            mainHandler.postDelayed(proxyPoll!!, 250L)
        }
    }

    private fun fail(reason: String) {
        if (state == State.STOPPING || state == State.STOPPED || state == State.FAILED) return
        state = State.FAILED
        lastError = reason
        val callback = onFailure
        stopInternal(disableProxy = true)
        state = State.FAILED
        callback?.invoke(reason)
    }

    private fun cancelTimeouts() {
        startTimeout?.let(mainHandler::removeCallbacks)
        proxyTimeout?.let(mainHandler::removeCallbacks)
        proxyPoll?.let(mainHandler::removeCallbacks)
        socksPortPoll?.let(mainHandler::removeCallbacks)
        startTimeout = null
        proxyTimeout = null
        proxyPoll = null
        socksPortPoll = null
        socksPortAttempts = 0
    }

    private fun stopInternal(disableProxy: Boolean) {
        cancelTimeouts()
        NovaBrowserVpnController.setTorRoutingStatusListener(null)
        if (disableProxy) NovaBrowserVpnController.setTorGuestRouting(false, 0)
        val context = appContext
        if (receiverRegistered && context != null && receiver != null) {
            runCatching { context.unregisterReceiver(receiver) }
        }
        receiverRegistered = false
        receiver = null
        if (isBound && context != null) runCatching { context.unbindService(serviceConnection) }
        isBound = false
        torService = null
        socksPort = 0
        appContext = null
    }
}

internal object GuestTorRoutingPolicy {
    fun isCurrentAcknowledgement(expectedRequestId: Long, acknowledgedRequestId: Long): Boolean =
        expectedRequestId > 0L && acknowledgedRequestId == expectedRequestId

    fun canEnableProxy(torBootstrapped: Boolean, socksPort: Int): Boolean =
        torBootstrapped && socksPort in 1..65535

    fun canEnterGuest(torBootstrapped: Boolean, socksPort: Int, proxyBridgeReady: Boolean): Boolean =
        canEnableProxy(torBootstrapped, socksPort) && proxyBridgeReady
}
