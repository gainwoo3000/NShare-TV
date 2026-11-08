package com.nshare.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.nshare.airplay.AirPlayAccess
import com.nshare.airplay.AirPlayAccessMode
import com.nshare.airplay.ClientInfo
import com.nshare.airplay.ReceiverListener
import com.nshare.airplay.SessionSinks
import com.nshare.airplay.server.AirPlayServer
import com.nshare.airplay.time.ClockSync
import com.nshare.common.AppLanguage
import com.nshare.protocol.Caps
import com.nshare.receiver.security.AirPlaySecurity
import com.nshare.receiver.security.SecuritySettings
import com.nshare.receiver.security.TrustedDevices
import com.nshare.receiver.nsp.NspServer
import com.nshare.receiver.ui.MainActivity

/** Keeps the AirPlay receiver running and advertised while the TV is on. */
class ReceiverService : Service() {
    private var server: AirPlayServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nspServer: NspServer? = null
    private val securityPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // pw / status flags in the AirPlay TXT records follow the security setting.
        if (key == "airplay_security" || key == "airplay_password") server?.refreshAdvertisement()
    }

    override fun onCreate() {
        super.onCreate()
        PlayerHub.attach(this)
        startAsForeground()
        val config = DeviceIdentity.load(this)
        multicastLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("nshare-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        val listener = object : ReceiverListener {
            override fun onSessionStart(client: ClientInfo, clock: ClockSync, disconnect: () -> Unit): SessionSinks =
                PlayerHub.startSession(this@ReceiverService, client, clock, key = airPlayKey(client), disconnect = disconnect)

            override fun approveSession(client: ClientInfo): Boolean =
                PlayerHub.requestApproval(this@ReceiverService, client.name ?: client.model ?: client.address, airPlayKey(client))
        }
        startNsp(config)
        getSharedPreferences("security", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(securityPrefsListener)
        server = AirPlayServer(this, config, listener, AirPlayAccessAdapter(this)).also {
            try {
                it.start()
                PlayerHub.receiverName = config.name
                PlayerHub.receiverPort = it.port
            } catch (e: Exception) {
                Log.e(TAG, "failed to start AirPlay receiver", e)
            }
        }
    }

    private fun startNsp(config: com.nshare.airplay.ReceiverConfig) {
        val caps = Caps(config.name, config.displayWidth, config.displayHeight, config.maxFps, config.hevc)
        nspServer = NspServer(this, caps).also {
            try {
                it.start()
            } catch (e: Exception) {
                Log.e(TAG, "failed to start NShare receiver", e)
            }
        }
    }

    private fun airPlayKey(client: ClientInfo) = "airplay:" + (client.deviceId ?: client.address)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        getSharedPreferences("security", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(securityPrefsListener)
        server?.stop()
        server = null
        nspServer?.stop()
        nspServer = null
        multicastLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_MIN),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "NShare.Service"
        private const val CHANNEL = "receiver"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ReceiverService::class.java))
        }
    }
}

/** Starts the receiver after the TV boots so it is always discoverable. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                ReceiverService.start(context)
            } catch (e: Exception) {
                Log.w("NShare.Boot", "could not start receiver: ${e.message}")
            }
        }
    }
}

/** Maps the TV security settings onto the AirPlay receiver's access control. */
private class AirPlayAccessAdapter(context: Context) : AirPlayAccess {
    private val settings = SecuritySettings(context)
    private val trusted = TrustedDevices(context)

    override val mode: AirPlayAccessMode
        get() = when (settings.airPlaySecurity) {
            AirPlaySecurity.NONE -> AirPlayAccessMode.OPEN
            AirPlaySecurity.SCREEN_PIN -> AirPlayAccessMode.SCREEN_PIN
            // A password that was never set would lock everyone out; stay open until one is saved.
            AirPlaySecurity.PASSWORD -> if (settings.airPlayPassword.isEmpty()) AirPlayAccessMode.OPEN else AirPlayAccessMode.PASSWORD
        }
    override val password: String get() = settings.airPlayPassword

    override fun showPin(pin: String?) = PlayerHub.showAirPlayPin(pin)

    // Apple devices paired with the on-screen PIN share the trusted-devices list (removable in settings).
    override fun isPairedClient(publicKeyHex: String) = trusted.isTrusted(KEY_PREFIX + publicKeyHex)
    override fun rememberClient(publicKeyHex: String, name: String) = trusted.trust(KEY_PREFIX + publicKeyHex, name)

    private companion object {
        const val KEY_PREFIX = "airplay-"
    }
}
