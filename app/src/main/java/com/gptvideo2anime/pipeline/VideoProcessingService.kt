package com.gptvideo2anime.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VideoProcessingService : Service() {

    companion object {
        const val CHANNEL_ID = "VideoProcessingChannel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_VIDEO_URI = "extra_video_uri"
        const val ACTION_STOP = "com.gptvideo2anime.STOP_PROCESSING"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            processingJob?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val videoUri = intent?.getParcelableExtra<Uri>(EXTRA_VIDEO_URI)
            ?: run {
                stopSelf()
                return START_NOT_STICKY
            }

        // FIXED: Must call startForeground immediately to prevent System ANR/Crash
        val notification = buildNotification("Initializing video processing...")
        startForeground(NOTIFICATION_ID, notification)

        processingJob = serviceScope.launch {
            try {
                val processor = VideoProcessor(applicationContext)
                processor.processVideo(
                    uri = videoUri,
                    strength = 100,
                    onProgress = { progress, _, message ->
                        updateNotification("$message ($progress%)")
                    }
                )
                updateNotification("Processing complete! Check output folder.")
            } catch (e: Exception) {
                updateNotification("Error: ${e.localizedMessage ?: "Unknown error"}")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress for anime video conversion"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, VideoProcessingService::class.java).apply {
            action = ACTION_STOP
        }
        // FIXED: FLAG_IMMUTABLE is strictly required for API 31+
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPT Video2Anime")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
