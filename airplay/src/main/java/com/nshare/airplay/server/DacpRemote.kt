package com.nshare.airplay.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Remote control of the sender's playback (DACP), as used by AirPlay audio: the sender announces
 * `DACP-ID` and `Active-Remote` in its RTSP requests and advertises `_dacp._tcp` as
 * "iTunes_Ctrl_<DACP-ID>". Commands are plain HTTP GETs to /ctrl-int/1/<command> carrying the
 * Active-Remote token (e.g. playpause, nextitem, previtem).
 */
class DacpRemote(
    context: Context,
    private val dacpId: String,
    private val activeRemote: String,
    private val senderAddress: String,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "nshare-dacp").apply { isDaemon = true } }
    @Volatile private var host: String? = null
    @Volatile private var port = 0

    fun send(command: String) = executor.execute {
        Log.i(TAG, "$command requested (DACP-ID $dacpId, known port $port)")
        if (port == 0 && !discover()) {
            Log.w(TAG, "sender's remote control service not found (iTunes_Ctrl_$dacpId)")
            return@execute
        }
        try {
            val h = (host ?: senderAddress).substringBefore('%') // drop an IPv6 scope id
            // IPv6 literals (iPhones often connect over IPv6) must be bracketed in a URL.
            val url = URL("http://${if (h.contains(':')) "[$h]" else h}:$port/ctrl-int/1/$command")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 2_000
            c.readTimeout = 2_000
            c.setRequestProperty("Active-Remote", activeRemote)
            val code = c.responseCode
            c.disconnect()
            Log.i(TAG, "$command -> $code")
            if (code >= 400) port = 0 // look the service up again next time
        } catch (e: Exception) {
            Log.w(TAG, "$command failed: ${e.message}")
            port = 0
        }
    }

    fun close() = executor.shutdownNow()

    /** Finds the sender's `_dacp._tcp` service for this DACP-ID (up to 3 s). */
    private fun discover(): Boolean {
        val found = CountDownLatch(1)
        val wanted = "iTunes_Ctrl_$dacpId"
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceName.equals(wanted, ignoreCase = true)) return
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        host = r.host?.hostAddress
                        port = r.port
                        Log.i(TAG, "remote control service at $host:$port")
                        found.countDown()
                    }

                    override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) {}
                })
            }

            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = found.countDown()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        return try {
            nsd.discoverServices("_dacp._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
            found.await(3, TimeUnit.SECONDS)
            port != 0
        } catch (e: Exception) {
            Log.w(TAG, "DACP discovery failed: ${e.message}")
            false
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    companion object {
        private const val TAG = "NShare.Dacp"
    }
}
