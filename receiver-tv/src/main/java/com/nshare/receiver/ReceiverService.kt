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
import com.nshare.airplay.ClientInfo
import com.nshare.airplay.ReceiverListener
import com.nshare.airplay.SessionSinks
import com.nshare.airplay.server.AirPlayServer
import com.nshare.airplay.time.ClockSync
import com.nshare.common.AppLanguage
import com.nshare.protocol.Caps
import com.nshare.receiver.nsp.NspServer
import com.nshare.receiver.ui.MainActivity

/** Keeps the AirPlay receiver running and advertised while the TV is on. */
class ReceiverService : Service() {
    private var server: AirPlayServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nspServer: NspServer? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        val config = DeviceIdentity.load(this)
        multicastLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("nshare-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        val listener = object : ReceiverListener {
            override fun onSessionStart(client: ClientInfo, clock: ClockSync, disconnect: () -> Unit): SessionSinks =
                PlayerHub.startSession(this@ReceiverService, client, clock, disconnect = disconnect)
        }
        startNsp(config)
        server = AirPlayServer(this, config, listener).also {
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
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
