package dev.tvlauncher.muse.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.tvlauncher.R

/**
 * Keeps the TV connected to its Muse while other apps are on screen. A foreground service,
 * so Android doesn't reclaim the launcher's process mid-session; it stops itself once
 * there's no pairing.
 */
class MuseLinkService : Service() {
    private val stopWhenUnpaired: (LinkState) -> Unit = { if (it == LinkState.NotPaired) stopSelf() }
    private var listening = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() needs its own startForeground(), even when the service
        // is already in the foreground; skipping it crashes the app ten seconds later.
        startInForeground()
        MuseRuntime.start(this) // already running: just retry now
        if (!listening) {
            listening = true
            MuseRuntime.addListener(stopWhenUnpaired) // after start(), which leaves "not paired"
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.muse), NotificationManager.IMPORTANCE_MIN),
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.logo_muse)
            .setContentTitle(getString(R.string.muse))
            .setContentText(getString(R.string.muse_link_notification))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        MuseRuntime.removeListener(stopWhenUnpaired)
        MuseRuntime.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "muse_link"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MuseLinkService::class.java))
        }
    }
}
