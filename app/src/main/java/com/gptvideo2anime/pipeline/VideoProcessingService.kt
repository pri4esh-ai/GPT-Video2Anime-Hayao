// FILE: app/src/main/java/com/gptvideo2anime/pipeline/VideoProcessingService.kt

package com.gptvideo2anime.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
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
        const val ACTION_START =
            "com.gptvideo2anime.action.START_PROCESSING"

        const val EXTRA_INPUT_URI =
            "com.gptvideo2anime.extra.INPUT_URI"

        const val ACTION_PROGRESS =
            "com.gptvideo2anime.PROGRESS"

        const val EXTRA_STAGE = "stage"
        const val EXTRA_CURRENT = "current"
        const val EXTRA_TOTAL = "total"

        private const val CHANNEL_ID = "video_processing"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )

    private lateinit var modelManager: ModelManager
    private lateinit var videoProcessor: VideoProcessor

    override fun onCreate() {
        super.onCreate()

        modelManager = ModelManager(this)
        videoProcessor = VideoProcessor(this)

        createNotificationChannel()

        startForeground(
            NOTIFICATION_ID,
            createNotification("Preparing video processing...")
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (intent?.action != ACTION_START) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val inputUri = intent.getStringExtra(EXTRA_INPUT_URI)

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
                    startId = startId
                )
            } catch (exception: Exception) {
                handleProcessingError(
                    exception = exception,
                    startId = startId
                )
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun processVideo(
        inputUri: String,
        startId: Int
    ) {
        updateNotification("Checking models...")

        modelManager.ensureModels { message ->
            updateNotification(message)
        }

        updateNotification("Opening video...")

        val result = videoProcessor.process(
            uri = android.net.Uri.parse(inputUri)
        ) { current, total, stage ->
            sendProgress(
                stage = stage,
                current = current,
                total = total
            )

            val notificationText =
                if (total > 0) {
                    "$stage ($current/$total)"
                } else {
                    stage
                }

            updateNotification(notificationText)
        }

        Log.i(
            "VideoProcessingService",
            "Processing completed. Preview: ${result.testFramePath}"
        )

        sendProgress(
            stage = "Complete",
            current = 7,
            total = 7
        )

        updateNotification("Processing complete")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun handleProcessingError(
        exception: Exception,
        startId: Int
    ) {
        Log.e(
            "VideoProcessingService",
            "Processing failed",
            exception
        )

        val message =
            exception.message?.takeIf { it.isNotBlank() }
                ?: "Unknown processing error"

        sendProgress(
            stage = "Failed",
            current = 0,
            total = 0
        )

        updateNotification("Failed: $message")

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun sendProgress(
        stage: String,
        current: Int,
        total: Int
    ) {
        sendBroadcast(
            Intent(ACTION_PROGRESS).apply {
                putExtra(EXTRA_STAGE, stage)
                putExtra(EXTRA_CURRENT, current)
                putExtra(EXTRA_TOTAL, total)
            }
        )
    }

    private fun createNotification(
        text: String
    ): Notification {
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(
                    this,
                    CHANNEL_ID
                )
            } else {
                Notification.Builder(this)
            }

        return builder
            .setContentTitle("GPT Video2Anime")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(
        text: String
    ) {
        val manager =
            getSystemService(
                NOTIFICATION_SERVICE
            ) as NotificationManager

        manager.notify(
            NOTIFICATION_ID,
            createNotification(text)
        )

        Log.i(
            "VideoProcessingService",
            text
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager =
            getSystemService(
                NOTIFICATION_SERVICE
            ) as NotificationManager

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Video Processing",
                NotificationManager.IMPORTANCE_LOW
            )

        manager.createNotificationChannel(channel)
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
