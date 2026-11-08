package com.nshare.common

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * Keeps Wi-Fi out of power save while streaming. Power-save beacons add tens to hundreds of
 * milliseconds of jitter, which forces a larger playout buffer. Hold it only while a stream is active.
 */
class WifiLatencyLock(context: Context, tag: String) {
    @Suppress("DEPRECATION")
    private val lock: WifiManager.WifiLock =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            tag,
        ).apply { setReferenceCounted(false) }

    @Synchronized
    fun acquire() {
        if (lock.isHeld) return
        try {
            lock.acquire()
            Log.i("NShare.Wifi", "low-latency Wi-Fi lock held")
        } catch (e: Exception) {
            Log.w("NShare.Wifi", "could not hold Wi-Fi lock: ${e.message}")
        }
    }

    @Synchronized
    fun release() {
        if (lock.isHeld) {
            lock.release()
            Log.i("NShare.Wifi", "low-latency Wi-Fi lock released")
        }
    }
}
