package com.nova.browser

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.torproject.jni.TorService
import java.net.Proxy
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class GuestTorRoutingSmokeTest {
    @Test(timeout = 240_000L)
    fun embeddedTorBootstrapsAndSocksExitIsTor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bootstrapped = CountDownLatch(1)
        val serviceRef = AtomicReference<TorService?>()
        val errorRef = AtomicReference<String?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    TorService.ACTION_STATUS -> if (intent.getStringExtra(TorService.EXTRA_STATUS) == TorService.STATUS_ON) bootstrapped.countDown()
                    TorService.ACTION_ERROR -> {
                        errorRef.set(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "Tor startup failed")
                        bootstrapped.countDown()
                    }
                }
            }
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                serviceRef.set((binder as? TorService.LocalBinder)?.service)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                errorRef.set("Tor service disconnected")
                bootstrapped.countDown()
            }
        }
        val filter = IntentFilter().apply {
            addAction(TorService.ACTION_STATUS)
            addAction(TorService.ACTION_ERROR)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        var bound = false
        try {
            bound = context.bindService(Intent(context, TorService::class.java), connection, Context.BIND_AUTO_CREATE)
            assertTrue("Could not bind embedded Tor service", bound)
            assertTrue("Tor did not bootstrap within 180 seconds", bootstrapped.await(180, TimeUnit.SECONDS))
            assertTrue("Tor service error: ${errorRef.get()}", errorRef.get() == null)
            var socksPort = serviceRef.get()?.socksPort ?: 0
            val portDeadline = System.currentTimeMillis() + 10_000L
            while (socksPort !in 1..65535 && System.currentTimeMillis() < portDeadline) {
                Thread.sleep(250L)
                socksPort = serviceRef.get()?.socksPort ?: 0
            }
            assertTrue("Tor did not expose a SOCKS port", socksPort in 1..65535)

            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            val connectionToCheck = (URL("https://check.torproject.org/api/ip").openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = 60_000
                readTimeout = 60_000
                requestMethod = "GET"
            }
            try {
                assertTrue("Tor check endpoint returned HTTP ${connectionToCheck.responseCode}", connectionToCheck.responseCode == 200)
                val body = connectionToCheck.inputStream.bufferedReader().use { it.readText() }
                assertTrue("SOCKS endpoint did not exit through Tor: $body", JSONObject(body).optBoolean("IsTor", false))
            } finally {
                connectionToCheck.disconnect()
            }
        } finally {
            if (bound) runCatching { context.unbindService(connection) }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
