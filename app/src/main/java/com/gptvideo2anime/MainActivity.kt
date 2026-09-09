package com.gptvideo2anime

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gptvideo2anime.inference.OnnxAnimeEngine
import com.gptvideo2anime.model.ModelManager
import com.gptvideo2anime.pipeline.VideoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var logs: TextView
    private lateinit var scroll: ScrollView
    private lateinit var originalPreview: ImageView
    private lateinit var animePreview: ImageView
    private lateinit var convertButton: Button

    private lateinit var modelManager: ModelManager
    private lateinit var videoProcessor: VideoProcessor

    private var selectedVideo: Uri? = null
    private var processing = false

    private val videoPicker =
        registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri ->

            if (uri == null) {
                appendLog("No video selected.")
                return@registerForActivityResult
            }

            selectedVideo = uri

            status.text = "Video selected."
            appendLog("Video selected.")

            generatePreview(uri)
        }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            appendLog("Permissions checked.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        modelManager = ModelManager(this)
        videoProcessor = VideoProcessor(this)

        buildUi()
        requestPermissions()
        installModels()
    }

    private fun buildUi() {

        val root =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    24,
                    24,
                    24,
                    24
                )

                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
            }

        val title =
            TextView(this).apply {

                text = "GPT Video2Anime"
                textSize = 26f

                setPadding(
                    0,
                    0,
                    0,
                    12
                )
            }

        root.addView(title)

        status =
            TextView(this).apply {

                text = "Preparing models..."
                textSize = 16f

                setPadding(
                    0,
                    0,
                    0,
                    8
                )
            }

        root.addView(status)

        val chooseButton =
            Button(this).apply {

                text = "CHOOSE VIDEO"

                setOnClickListener {

                    if (processing) {
                        appendLog(
                            "Processing is already running."
                        )
                        return@setOnClickListener
                    }

                    videoPicker.launch("video/*")
                }
            }

        root.addView(chooseButton)

        convertButton =
            Button(this).apply {

                text = "CONVERT TO ANIME"

                isEnabled = false

                setOnClickListener {

                    if (processing) {
                        return@setOnClickListener
                    }

                    val input =
                        selectedVideo

                    if (input == null) {

                        appendLog(
                            "Select a video first."
                        )

                        return@setOnClickListener
                    }

                    startStage1(input)
                }
            }

        root.addView(convertButton)

        val previewRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        320
                    )

                setPadding(
                    0,
                    8,
                    0,
                    8
                )
            }

        originalPreview =
            createPreviewImage()

        animePreview =
            createPreviewImage()

        val originalBox =
            createPreviewBox(
                "Original",
                originalPreview
            )

        val animeBox =
            createPreviewBox(
                "Anime",
                animePreview
            )

        previewRow.addView(originalBox)
        previewRow.addView(animeBox)

        root.addView(previewRow)

        logs =
            TextView(this).apply {

                textSize = 13f

                setPadding(
                    4,
                    8,
                    4,
                    8
                )
            }

        scroll =
            ScrollView(this).apply {

                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )

                addView(logs)
            }

        root.addView(scroll)

        setContentView(root)
    }

    private fun createPreviewImage(): ImageView {

        return ImageView(this).apply {

            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

            scaleType =
                ImageView.ScaleType.FIT_CENTER

            setBackgroundColor(
                Color.DKGRAY
            )
        }
    }

    private fun createPreviewBox(
        title: String,
        image: ImageView
    ): LinearLayout {

        return LinearLayout(this).apply {

            orientation =
                LinearLayout.VERTICAL

            layoutParams =
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f
                )

            if (title == "Anime") {
                setPadding(
                    8,
                    0,
                    0,
                    0
                )
            }

            addView(
                TextView(context).apply {

                    text = title

                    gravity =
                        Gravity.CENTER

                    textSize = 14f

                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            32
                        )
                }
            )

            addView(image)

            image.setOnClickListener {

                if (image.drawable != null) {
                    showFullPreview(image)
                }
            }
        }
    }

    private fun installModels() {

        lifecycleScope.launch {

            try {

                modelManager.ensureModels { message ->

                    appendLog(message)
                }

                status.text =
                    "Models ready."

                convertButton.isEnabled =
                    selectedVideo != null

            } catch (e: Exception) {

                status.text =
                    "Model install failed"

                appendLog(
                    "ERROR: ${e.message ?: "Unknown error"}"
                )
            }
        }
    }

    private fun requestPermissions() {

        val permission =
            if (Build.VERSION.SDK_INT >= 33) {

                Manifest.permission.READ_MEDIA_VIDEO

            } else {

                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        if (
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            permissionLauncher.launch(
                arrayOf(permission)
            )
        }
    }

    private fun generatePreview(
        uri: Uri
    ) {

        lifecycleScope.launch {

            try {

                convertButton.isEnabled = false

                appendLog(
                    "Extracting first frame..."
                )

                val frame =
                    withContext(Dispatchers.IO) {

                        extractFirstFrame(uri)
                    }

                originalPreview.setImageBitmap(
                    frame
                )

                originalPreview.invalidate()

                appendLog(
                    "Original preview displayed."
                )

                val modelPath =
                    modelManager.animeModelPath()
                        ?: throw IllegalStateException(
                            "AnimeGANv3 missing."
                        )

                appendLog(
                    "Running AnimeGANv3..."
                )

                val start =
                    SystemClock.elapsedRealtime()

                val anime =
                    withContext(
                        Dispatchers.Default
                    ) {

                        OnnxAnimeEngine(
                            modelPath
                        ).use { engine ->

                            engine.processFrame(
                                frame
                            )
                        }
                    }

                animePreview.setImageBitmap(
                    anime
                )

                animePreview.invalidate()

                val elapsed =
                    SystemClock.elapsedRealtime() -
                        start

                status.text =
                    "Preview ready (${elapsed} ms)"

                appendLog(
                    "Anime preview completed in ${elapsed} ms"
                )

                appendLog(
                    "Anime preview displayed."
                )

                convertButton.isEnabled =
                    true

            } catch (e: Exception) {

                status.text =
                    "Preview failed"

                appendLog(
                    "ERROR: ${e.message ?: "Unknown error"}"
                )

                convertButton.isEnabled =
                    selectedVideo != null
            }
        }
    }

    private fun startStage1(
        input: Uri
    ) {

        if (processing) {
            return
        }

        processing = true
        convertButton.isEnabled = false

        lifecycleScope.launch {

            try {

                appendLog(
                    "Starting Stage 1..."
                )

                val result =
                    withContext(Dispatchers.IO) {

                        videoProcessor.process(
                            input
                        ) { current, total, stage ->

                            runOnUiThread {

                                status.text =
                                    "$stage ($current/$total)"

                                appendLog(
                                    "$stage ($current/$total)"
                                )
                            }
                        }
                    }

                val previewPath =
                    result.testFramePath

                if (
                    !previewPath.isNullOrBlank()
                ) {

                    val file =
                        File(previewPath)

                    if (file.exists()) {

                        appendLog(
                            "Preview file found."
                        )

                        val bitmap =
                            withContext(
                                Dispatchers.IO
                            ) {

                                BitmapFactory.decodeFile(
                                    file.absolutePath
                                )
                            }

                        if (bitmap != null) {

                            animePreview.setImageBitmap(
                                bitmap
                            )

                            animePreview.invalidate()

                            appendLog(
                                "Generated anime preview displayed."
                            )

                        } else {

                            appendLog(
                                "ERROR: Unable to decode generated preview."
                            )
                        }

                    } else {

                        appendLog(
                            "ERROR: Preview file does not exist."
                        )
                    }

                } else {

                    appendLog(
                        "ERROR: VideoProcessor returned no preview path."
                    )
                }

                status.text =
                    "Stage 1 Complete"

            } catch (e: Exception) {

                status.text =
                    "Processing failed"

                appendLog(
                    "ERROR: ${e.message ?: "Unknown error"}"
                )

            } finally {

                processing = false

                convertButton.isEnabled =
                    selectedVideo != null
            }
        }
    }

    private fun extractFirstFrame(
        uri: Uri
    ): Bitmap {

        val retriever =
            MediaMetadataRetriever()

        try {

            retriever.setDataSource(
                this,
                uri
            )

            return retriever.getFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: throw IllegalStateException(
                "Unable to decode first video frame."
            )

        } finally {

            retriever.release()
        }
    }

    private fun showFullPreview(
        source: ImageView
    ) {

        val dialog =
            Dialog(
                this,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen
            )

        val container =
            FrameLayout(this).apply {

                setBackgroundColor(
                    Color.BLACK
                )
            }

        val image =
            ImageView(this).apply {

                setImageDrawable(
                    source.drawable
                )

                scaleType =
                    ImageView.ScaleType.FIT_CENTER

                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
            }

        container.addView(image)

        container.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setContentView(
            container
        )

        dialog.show()
    }

    private fun appendLog(
        text: String
    ) {

        if (!::logs.isInitialized) {
            return
        }

        runOnUiThread {

            logs.append(
                "$text\n"
            )

            if (::scroll.isInitialized) {

                scroll.post {

                    scroll.fullScroll(
                        ScrollView.FOCUS_DOWN
                    )
                }
            }
        }
    }
}
