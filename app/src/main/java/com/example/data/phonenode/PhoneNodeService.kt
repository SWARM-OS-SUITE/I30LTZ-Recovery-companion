package com.example.data.phonenode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Keeps the phone-node server running while "This phone as a node" is on. It listens on loopback (for the USB
 * route) and on the phone's Tailscale address, and follows Tailscale coming and going.
 */
class PhoneNodeService : Service() {
    private var server: PhoneNodeServer? = null
    private var watcher: ScheduledExecutorService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification("Starting…"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (e: Exception) { // the system refused a foreground start (for example a background restart): do not run hidden
            PhoneNode.report(false, emptyList(), emptyList(), "Android would not let the service start in the foreground: ${e.message}")
            stopSelf()
            return
        }
        val srv = PhoneNodeServer(
            auth = PhoneNode.auth(this), telemetry = AndroidPhoneTelemetry(this), version = com.example.BuildConfig.VERSION_NAME,
            port = PhoneNodeServer.DEFAULT_PORT, log = { PhoneNode.event(applicationContext, it) },
        )
        server = srv
        watcher = Executors.newSingleThreadScheduledExecutor().also { it.scheduleWithFixedDelay({ runCatching { tick(srv) } }, 0, 5, TimeUnit.SECONDS) }
    }

    private fun tick(srv: PhoneNodeServer) {
        val tailnet = LocalAddresses.tailnet()
        val errors = srv.listenOn(setOf("127.0.0.1") + tailnet)
        val listening = srv.bound().map { "${it.key}:${it.value}" }
        val message = when {
            errors.isNotEmpty() -> "Could not listen on ${errors.keys.joinToString()}: ${errors.values.first()}. Is another app using port ${PhoneNodeServer.DEFAULT_PORT}?"
            tailnet.isEmpty() -> "Tailscale is not connected on this phone, so only the USB route works for now."
            else -> ""
        }
        PhoneNode.report(true, listening, tailnet, message)
        val paired = PhoneNode.auth(this).devices().size
        val where = if (tailnet.isNotEmpty()) "${tailnet.first()}:${PhoneNodeServer.DEFAULT_PORT}" else "this phone only"
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification("Visible to your paired devices at $where · $paired paired"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!PhoneNode.isEnabled(this)) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        watcher?.shutdownNow()
        server?.stop()
        PhoneNode.report(false, emptyList(), emptyList(), "")
        super.onDestroy()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Phone node", NotificationManager.IMPORTANCE_MIN)
                .apply { description = "Shows that this phone is visible to your own paired devices" }
        )
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("I30LTZ phone node")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_MIN)
            // Without this Android may hold the notification back for ~10 s; the person should see at once that the phone is visible.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 7437
        private const val CHANNEL = "phone_node"
    }
}

/** Starts the phone node again after a reboot or an app update, if it was switched on. */
class PhoneNodeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            PhoneNode.sync(context)
        }
    }
}
