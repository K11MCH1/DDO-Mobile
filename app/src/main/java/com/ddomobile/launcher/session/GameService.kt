package com.ddomobile.launcher.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.ddomobile.launcher.GameActivity

/** Keeps the app process in the foreground while a session runs, so Android doesn't drop the game mid-dungeon. */
class GameService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Game session", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, GameActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Dungeons & Dragons Online is running")
            .setContentText("Tap to return to the game")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ddomobile:session").apply { acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.takeIf { it.isHeld }?.release()
        wake = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "session"

        fun start(context: Context) = context.startForegroundService(Intent(context, GameService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, GameService::class.java))
    }
}
