package com.noop.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.noop.R
import com.noop.ui.appLaunchIntent
import fi.iki.elonen.NanoHTTPD

/**
 * Foreground service that keeps [WorkoutHttpServer] listening while the app is backgrounded —
 * otherwise Android tears the process down shortly after the last Activity goes away and the control
 * page would stop responding the moment the phone's screen turns off. Kept as its OWN service rather
 * than folded into [com.noop.ble.WhoopConnectionService]: that service protects the safety-critical
 * BLE link, and this one is a small, independently-toggleable feature that should never be able to
 * destabilize it (or vice versa) by sharing a lifecycle.
 *
 * Only ever started from [com.noop.ui.AppViewModel]'s init (a guaranteed-foreground call path, the
 * same rule [com.noop.ble.WhoopConnectionService] follows to stay clear of Android 12+'s
 * background-service-start restrictions) — never from [com.noop.NoopApplication.onCreate], which can
 * run from a background trigger.
 */
class WorkoutServerService : Service() {

    private var server: WorkoutHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        if (!startForegroundCompat()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (server == null) {
            // Defensive: binding the port can throw (e.g. already in use by another app), which must
            // not crash the process — the workout-control feature is best-effort, never load-bearing.
            server = runCatching {
                WorkoutHttpServer().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            }.getOrNull()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun startForegroundCompat(): Boolean = runCatching {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(getString(R.string.workout_server_notif_title))
            .setContentText(getString(R.string.workout_server_notif_detail, WorkoutHttpServer.PORT))
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    appLaunchIntent(this),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }.isSuccess

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.workout_server_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "noop_workout_server"
        private const val NOTIF_ID = 4301

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, WorkoutServerService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WorkoutServerService::class.java)) }
        }
    }
}
