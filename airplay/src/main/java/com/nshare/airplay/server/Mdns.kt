package com.nshare.airplay.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.nshare.airplay.AirPlayAccessMode
import com.nshare.airplay.ReceiverConfig
import com.nshare.airplay.crypto.Identity
import com.nshare.airplay.util.Bytes
import java.io.ByteArrayOutputStream

/** Advertises the receiver as _airplay._tcp and _raop._tcp via the system mDNS responder. */
internal class Mdns(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val listeners = ArrayList<NsdManager.RegistrationListener>()

    fun register(config: ReceiverConfig, identity: Identity, port: Int, mode: AirPlayAccessMode) {
        unregister()
        val raopName = config.deviceId.replace(":", "") + "@" + config.name
        registerService(config.name, "_airplay._tcp", port, airplayTxt(config, identity, mode))
        registerService(raopName, "_raop._tcp", port, raopTxt(config, identity, mode))
    }

    fun unregister() {
        for (l in listeners) {
            try {
                nsd.unregisterService(l)
            } catch (_: Exception) {
            }
        }
        listeners.clear()
    }

    private fun registerService(name: String, type: String, port: Int, txt: List<Pair<String, String>>) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            this.port = port
            for ((k, v) in txt) setAttribute(k, v)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "registered ${info.serviceName} $type on $port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "registration of $name $type failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        listeners += listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    companion object {
        private const val TAG = "NShare.Mdns"

        fun airplayTxt(config: ReceiverConfig, identity: Identity, mode: AirPlayAccessMode = AirPlayAccessMode.OPEN): List<Pair<String, String>> = listOf(
            "deviceid" to config.deviceId,
            "features" to config.featuresString,
            "pw" to (mode != AirPlayAccessMode.OPEN).toString(),
            "flags" to "0x4",
            "model" to ReceiverConfig.MODEL,
            "pk" to Bytes.hex(identity.publicKey),
            "pi" to config.pairingId,
            "srcvers" to ReceiverConfig.SOURCE_VERSION,
            "vv" to "2",
        )

        /** Status flags: 0x8 = PIN required, 0x80 = password required (on top of 0x4). */
        fun raopTxt(config: ReceiverConfig, identity: Identity, mode: AirPlayAccessMode = AirPlayAccessMode.OPEN): List<Pair<String, String>> = listOf(
            "ch" to "2",
            "cn" to "0,1,2,3",
            "da" to "true",
            "et" to "0,3,5",
            "vv" to "2",
            "ft" to config.featuresString,
            "am" to ReceiverConfig.MODEL,
            "md" to "0,1,2",
            "rhd" to "5.6.0.0",
            "pw" to (mode != AirPlayAccessMode.OPEN).toString(),
            "sf" to when (mode) {
                AirPlayAccessMode.OPEN -> "0x4"
                AirPlayAccessMode.SCREEN_PIN -> "0x8c"
                AirPlayAccessMode.PASSWORD -> "0x84"
            },
            "sr" to "44100",
            "ss" to "16",
            "sv" to "false",
            "tp" to "UDP",
            "txtvers" to "1",
            "vs" to ReceiverConfig.SOURCE_VERSION,
            "vn" to "65537",
            "pk" to Bytes.hex(identity.publicKey),
        )

        /** DNS TXT wire format: length-prefixed "key=value" strings. */
        fun txtRecordBytes(entries: List<Pair<String, String>>): ByteArray {
            val out = ByteArrayOutputStream()
            for ((k, v) in entries) {
                val s = "$k=$v".toByteArray()
                out.write(s.size)
                out.write(s)
            }
            return out.toByteArray()
        }
    }
}
