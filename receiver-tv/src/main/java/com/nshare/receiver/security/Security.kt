package com.nshare.receiver.security

import android.content.Context

/** How AirPlay senders must authenticate. Applies from the next connection. */
enum class AirPlaySecurity { NONE, SCREEN_PIN, PASSWORD }

/**
 * Security settings of the receiver (TV settings screen). Encryption of NShare streams is always on
 * and therefore not a setting.
 */
class SecuritySettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("security", Context.MODE_PRIVATE)

    /** NShare: require a PIN shown on the TV the first time a device connects. */
    var nspPinPairing: Boolean
        get() = prefs.getBoolean("nsp_pin", false)
        set(v) = prefs.edit().putBoolean("nsp_pin", v).apply()

    /** Ask on the TV before a second device takes over the screen. */
    var approveTakeover: Boolean
        get() = prefs.getBoolean("approve_takeover", false)
        set(v) = prefs.edit().putBoolean("approve_takeover", v).apply()

    var airPlaySecurity: AirPlaySecurity
        get() = prefs.getString("airplay_security", null)?.let { runCatching { AirPlaySecurity.valueOf(it) }.getOrNull() } ?: AirPlaySecurity.NONE
        set(v) = prefs.edit().putString("airplay_security", v.name).apply()

    var airPlayPassword: String
        get() = prefs.getString("airplay_password", "") ?: ""
        set(v) = prefs.edit().putString("airplay_password", v).apply()
}

/** NShare senders this TV trusts, keyed by certificate fingerprint (hex). */
class TrustedDevices(context: Context) {
    data class Device(val fingerprint: String, val name: String, val addedAt: Long)

    private val prefs = context.applicationContext.getSharedPreferences("trusted_devices", Context.MODE_PRIVATE)

    fun isTrusted(fingerprint: String): Boolean = prefs.contains(fingerprint)

    fun trust(fingerprint: String, name: String) {
        prefs.edit().putString(fingerprint, "${System.currentTimeMillis()}|$name").apply()
    }

    fun remove(fingerprint: String) = prefs.edit().remove(fingerprint).apply()

    fun clear() = prefs.edit().clear().apply()

    fun all(): List<Device> = prefs.all.mapNotNull { (fp, v) ->
        val s = v as? String ?: return@mapNotNull null
        val at = s.substringBefore('|').toLongOrNull() ?: 0L
        Device(fp, s.substringAfter('|'), at)
    }.sortedByDescending { it.addedAt }
}
