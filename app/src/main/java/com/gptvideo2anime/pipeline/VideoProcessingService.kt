package com.gptvideo2anime.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.gptvideo2anime.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VideoProcessingService : Service() {

    companion object {
        const val ACTION_START = "com.gptvideo2anime.action.START_PROCESSING"
        const val EXTRA_INPUT_URI = "com.gptvideo2anime.extra.INPUT_URI"
        const val EXTRA_STRENGTH = "com.gptvideo2anime.extra.STRENGTH"

        const val ACTION_PROGRESS = "com.gptvideo2anime.PROGRESS"
        const val EXTRA_STAGE = "stage"
        const val EXTRA_CURRENT = "current"
        const val EXTRA_TOTAL = "total"

        private const val CHANNEL_ID = "video_processing"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var modelManager: ModelManager
    private lateinit var videoProcessor: VideoProcessor

    override fun onCreate() {
        super.onCreate()

        modelManager = ModelManager(this)
        videoProcessor = VideoProcessor(this)

        createNotificationChannel()
        promoteToForeground("Preparing video processing...")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val inputUri = intent.getStringExtra(EXTRA_INPUT_URI)
        val strength = intent.getIntExtra(EXTRA_STRENGTH, 40)

        if (inputUri.isNullOrBlank()) {
            sendProgress("Failed", 0, 0)
            updateNotification("No input video selected")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                processVideo(
                    inputUri = inputUri,
                    strength = strength,
                    startId = startId
                )
            } catch (exception: Exception) {
                handleProcessingError(exception, startId)
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun processVideo(
        inputUri: String,
        strength: Int,
        startId: Int
    ) {
        updateNotification("Checking AI model...")

        // Resolved method matching ModelManager.kt API
        val modelPath = modelManager.animeModelPath()
        if (modelPath.isNullOrBlank()) {
            throw IllegalStateException("Failed to locate or download AnimeGAN ONNX model.")
        }

        updateNotification("Opening video stream...")

        val result = videoProcessor.processVideo(
            uri = Uri.parse(inputUri),
            strength = strength
        ) { current, total, stage ->
            sendProgress(stage, current, total)
            updateNotification(
                if (total > 0) "$stage ($current/$total)" else stage
            )
        }

        Log.i("VideoProcessingService", "Processing completed: ${result.outputFile.absolutePath}")

        sendProgress("Complete", 1, 1)
        updateNotification("Processing complete")

        stopForegroundService()
        stopSelf(startId)
    }

    private fun handleProcessingError(exception: Exception, startId: Int) {
        Log.e("VideoProcessingService", "Processing failed", exception)

        val message = exception.localizedMessage ?: "Unknown processing error"

        sendProgress("Failed", 0, 0)
        updateNotification("Failed: $message")

        stopForegroundService()
        stopSelf(startId)
    }

    private fun promoteToForeground(text: String) {
        val notification = createNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Must match foregroundServiceType="mediaProcessing" in AndroidManifest.xml
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            } else {
                0
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun sendProgress(stage: String, current: Int, total: Int) {
        sendBroadcast(
            Intent(ACTION_PROGRESS).apply {
                setPackage(packageName)
                putExtra(EXTRA_STAGE, stage)
                putExtra(EXTRA_CURRENT, current)
                putExtra(EXTRA_TOTAL, total)
            }
        )
    }

    private fun createNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("GPT Video2Anime")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Video Processing Service",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
