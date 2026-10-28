package dev.lmchat.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.lmchat.LMChatApplication
import dev.lmchat.MainActivity
import dev.lmchat.R

/** Keeps user-initiated SSE downloads alive when the activity is stopped or locked. */
class StreamingService : Service() {
    private var stoppingWhenIdle = false
    private val container get() = (application as LMChatApplication).container
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        container.streamingKeepAlive.serviceRunning = true
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Active replies", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Keeps replies running while LMChat is in the background" },
        )
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LMChat:Streaming")
            .apply { acquire(6 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) container.chatRepository.stopAllStreams()
        if (container.streamingKeepAlive.activeChats.value.isEmpty()) {
            stoppingWhenIdle = true
            stopSelfResult(startId)
        } else {
            stoppingWhenIdle = false
        }
        // A dead process cannot resume an SSE request; preserve its saved partial reply.
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        container.chatRepository.stopAllStreams()
        stopSelf()
    }

    override fun onDestroy() {
        container.streamingKeepAlive.serviceRunning = false
        if (!stoppingWhenIdle) container.chatRepository.stopAllStreams()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, StreamingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_streaming_notification)
            .setContentTitle("Generating replies")
            .setContentText("LMChat is receiving a response from your server")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "Stop replies", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "active_replies"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.lmchat.STOP_REPLIES"
    }
}
