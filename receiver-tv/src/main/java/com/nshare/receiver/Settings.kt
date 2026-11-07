package com.nshare.receiver

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.util.Base64
import com.nshare.airplay.ReceiverConfig
import com.nshare.airplay.crypto.Identity
import com.nshare.receiver.player.LatencyMode
import java.security.SecureRandom
import java.util.UUID

/** User preferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var latencyMode: LatencyMode
        get() = prefs.getString("latency_mode", null)
            ?.let { runCatching { LatencyMode.valueOf(it) }.getOrNull() } ?: LatencyMode.BALANCED
        set(v) = prefs.edit().putString("latency_mode", v.name).apply()

    /** Positive values delay audio relative to video. */
    var avOffsetMs: Int
        get() = prefs.getInt("av_offset_ms", 0)
        set(v) = prefs.edit().putInt("av_offset_ms", v).apply()

    var showStats: Boolean
        get() = prefs.getBoolean("show_stats", false)
        set(v) = prefs.edit().putBoolean("show_stats", v).apply()
}

/** Stable identity of this receiver: name, hardware-style id and pairing key. */
object DeviceIdentity {
    fun load(context: Context): ReceiverConfig {
        val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        var pairingId = prefs.getString("pairing_id", null)
        var secret = prefs.getString("identity_secret", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        if (deviceId == null || pairingId == null || secret == null || secret.size != 32) {
            val mac = ByteArray(6).also { SecureRandom().nextBytes(it) }
            mac[0] = ((mac[0].toInt() and 0xfe) or 0x02).toByte() // locally administered, unicast
            deviceId = mac.joinToString(":") { "%02X".format(it) }
            pairingId = UUID.randomUUID().toString()
            secret = Identity.generateSecret()
            prefs.edit()
                .putString("device_id", deviceId)
                .putString("pairing_id", pairingId)
                .putString("identity_secret", Base64.encodeToString(secret, Base64.NO_WRAP))
                .apply()
        }
        val name = prefs.getString("name", null) ?: ("NShare-" + deviceId.replace(":", "").takeLast(4))
        val uhd = supportsHevc4k60()
        Log.i("NShare.Identity", "advertising ${if (uhd) "3840x2160 HEVC" else "1920x1080 H.264"}")
        return ReceiverConfig(
            name = name,
            deviceId = deviceId,
            pairingId = pairingId,
            identitySecret = secret,
            displayWidth = if (uhd) 3840 else 1920,
            displayHeight = if (uhd) 2160 else 1080,
            hevc = uhd,
        )
    }

    /** True if a hardware HEVC decoder can do 3840x2160 at 60 fps. */
    private fun supportsHevc4k60(): Boolean = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || info.isHardwareAccelerated) &&
            runCatching {
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).videoCapabilities
                    .areSizeAndRateSupported(3840, 2160, 60.0)
            }.getOrDefault(false)
    }
}
