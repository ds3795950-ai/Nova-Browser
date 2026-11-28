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
    private var stopTimeout: Runnable? = null
    private var stopPoll: Runnable? = null
    private var socksPortAttempts = 0
    private var onReady: (() -> Unit)? = null
    private var onFailure: ((String) -> Unit)? = null
    private var onStopped: (() -> Unit)? = null
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
        if (state == State.STOPPING) {
            onFailure("Guest Tor is still disconnecting; wait for the proxy reset")
            return
        }

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

    /** Reserve the shared GeckoView runtime for Guest while its proxy may be in Tor mode. */
    fun blocksNormalGeckoSessions(): Boolean = state != State.STOPPED

    fun statusText(): String = when (state) {
        State.STARTING -> "Starting embedded Tor and building a circuit…"
        State.WAITING_FOR_PROXY -> "Tor circuit built; securing GeckoView routing…"
        State.READY -> "Tor connected · Guest SOCKS5 routing and WebRTC protection active"
        State.FAILED -> "Tor unavailable · $lastError"
        State.STOPPING -> if (lastError.startsWith("Recovery required:")) {
            "Guest routing reset unconfirmed · ${lastError.removePrefix("Recovery required:").trim()}"
        } else "Stopping Guest Tor…"
        State.STOPPED -> "Tor is not running outside Guest Mode"
    }

    /**
     * Stop Tor and wait until the WebExtension acknowledges that its runtime-wide proxy
     * has left Guest mode. Callers that will resume Normal Gecko sessions must supply
     * [onStopped]; lifecycle teardown may use the immediate no-callback path.
     */
    fun stop(onStopped: (() -> Unit)? = null) {
        if (state == State.STOPPED && NovaBrowserVpnController.isTorGuestRoutingDisabledConfirmed()) {
            onStopped?.invoke()
            return
        }
        this.onStopped = onStopped
        if (state == State.STOPPING) {
            checkStopReadiness()
            return
        }
        state = State.STOPPING
        lastError = "Waiting for the proxy extension to confirm Guest Tor is disabled"
        // Keep the status listener alive while requesting the reset. Even Activity teardown
        // without a completion callback must not release the shared Gecko runtime early.
        stopInternal(disableProxy = false, clearStatusListener = false)
        onReady = null
        onFailure = null
        NovaBrowserVpnController.setTorRoutingStatusListener {
            mainHandler.post { checkStopReadiness() }
        }
        NovaBrowserVpnController.setTorGuestRouting(false, 0)
        stopTimeout = Runnable {
            stopTimeout = null
            stopPoll?.let(mainHandler::removeCallbacks)
            stopPoll = null
            lastError = "Recovery required: close and reopen Nova to reset the shared GeckoView proxy safely"
            // Never release Normal sessions without a matching reset acknowledgement. The UI
            // exposes the recovery action instead of leaving the user with a silent spinner.
            NovaBrowserVpnController.notifyStatusChanged()
        }
        mainHandler.postDelayed(stopTimeout!!, 15_000L)
        checkStopReadiness()
    }

    private fun checkStopReadiness() {
        if (state != State.STOPPING) return
        if (NovaBrowserVpnController.isTorGuestRoutingDisabledConfirmed()) {
            stopTimeout?.let(mainHandler::removeCallbacks)
            stopPoll?.let(mainHandler::removeCallbacks)
            stopTimeout = null
            stopPoll = null
            NovaBrowserVpnController.setTorRoutingStatusListener(null)
            state = State.STOPPED
            lastError = "Tor is not running"
            NovaBrowserVpnController.notifyStatusChanged()
            NovaBrowserVpnController.onGuestTorStopped()
            val callback = onStopped
            onStopped = null
            callback?.invoke()
            return
        }
        if (stopTimeout != null && stopPoll == null) {
            stopPoll = Runnable {
                stopPoll = null
                checkStopReadiness()
            }
            mainHandler.postDelayed(stopPoll!!, 250L)
        }
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
        stopTimeout?.let(mainHandler::removeCallbacks)
        stopPoll?.let(mainHandler::removeCallbacks)
        startTimeout = null
        proxyTimeout = null
        proxyPoll = null
        socksPortPoll = null
        stopTimeout = null
        stopPoll = null
        socksPortAttempts = 0
    }

    private fun stopInternal(disableProxy: Boolean, clearStatusListener: Boolean = true) {
        cancelTimeouts()
        if (clearStatusListener) NovaBrowserVpnController.setTorRoutingStatusListener(null)
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

internal object DeferredExternalLinkOwnershipPolicy {
    /** An Intent extra is trusted only when it matches Nova's private one-time token and URL. */
    fun isTrusted(suppliedToken: String?, storedToken: String?, storedUrl: String?, incomingUrl: String): Boolean =
        !suppliedToken.isNullOrBlank() && suppliedToken == storedToken && storedUrl == incomingUrl
}

internal object CrossEngineNavigationPolicy {
    /** Only GET requests can be replayed after a browser-engine handoff. */
    fun canTransfer(method: String): Boolean = method.equals("GET", ignoreCase = true)
}

internal object PendingExternalLinkPolicy {
    fun canOpen(
        pendingForGuest: Boolean,
        activeTabIsGuest: Boolean,
        guestStartPending: Boolean,
        torProxyReserved: Boolean,
        guestTorReady: Boolean
    ): Boolean = if (pendingForGuest) {
        activeTabIsGuest && !guestStartPending && guestTorReady
    } else {
        !activeTabIsGuest && !guestStartPending && !torProxyReserved
    }

    fun shouldDiscardGuestLink(
        pendingForGuest: Boolean,
        activeTabIsGuest: Boolean,
        guestStartPending: Boolean,
        torProxyReserved: Boolean
    ): Boolean = pendingForGuest && !activeTabIsGuest && !guestStartPending && !torProxyReserved
}

internal object GuestTorIsolationPolicy {
    /** The GeckoView proxy is runtime-wide; Private tabs must not share it with Guest Tor. */
    fun canStart(hasPrivateTabs: Boolean): Boolean = !hasPrivateTabs

    /** Suspend every persistent Normal session while Guest owns the browser network context. */
    fun shouldSuspendNormalTab(isGuest: Boolean, isPrivate: Boolean): Boolean = !isGuest && !isPrivate

    /** Never recreate a Gecko session while the runtime-wide proxy may still be in Tor mode. */
    fun shouldDeferTabRestore(guestStartPending: Boolean, activeTabIsGuest: Boolean, torReady: Boolean): Boolean =
        guestStartPending || (activeTabIsGuest && !torReady)

    fun shouldBlockNonGuestSession(isGuest: Boolean, torProxyReserved: Boolean): Boolean =
        !isGuest && torProxyReserved

    /** Keep the runtime barrier for Normal links, but allow links into a verified Guest tab. */
    fun shouldBlockPendingExternalLink(isGuest: Boolean, torProxyReserved: Boolean, guestTorReady: Boolean): Boolean =
        if (isGuest) !guestTorReady else torProxyReserved
}

internal object GuestTorRoutingPolicy {
    fun shouldClearGuestContextMarker(guestTabCount: Int): Boolean = guestTabCount <= 0

    fun shouldStopTorAfterGuestCreationFailure(guestTabCount: Int): Boolean = guestTabCount <= 0

    fun newlyCreatedGuestTabIds(existingGuestIds: Set<String>, currentGuestIds: List<String>): List<String> =
        currentGuestIds.filterNot { it in existingGuestIds }

    fun canEnableProxy(torBootstrapped: Boolean, socksPort: Int): Boolean =
        torBootstrapped && socksPort in 1..65535

    fun canEnterGuest(torBootstrapped: Boolean, socksPort: Int, proxyBridgeReady: Boolean): Boolean =
        canEnableProxy(torBootstrapped, socksPort) && proxyBridgeReady
}
