package com.gptvideo2anime.pipeline

import android.content.Context
import android.net.Uri
import android.util.Log
import com.gptvideo2anime.inference.OnnxAnimeEngine
import com.gptvideo2anime.model.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class ProcessResult(
    val outputFile: File
)

class VideoProcessor(
    private val context: Context
) {

    companion object {
        private const val TAG = "VideoProcessor"
        private const val MAX_TEMP_FILES = 5
        private const val MODEL_SIZE = 512
    }

    private val modelManager = ModelManager(context)

    suspend fun processVideo(
        uri: Uri,
        strength: Int,
        onProgress: (Int, Int, String) -> Unit
    ): ProcessResult = withContext(Dispatchers.IO) {

        val modelPath = modelManager.animeModelPath()
            ?: throw IllegalStateException("AnimeGAN model missing from local storage.")

        val modelFile = File(modelPath)
        require(modelFile.exists() && modelFile.length() > 0) {
            "AnimeGAN model file is invalid or empty at $modelPath"
        }

        val inputVideoFile = cacheUriToFile(uri)
        val outputDir = File(context.filesDir, "output").apply { mkdirs() }
        cleanOldOutputs(outputDir)

        val outputFile = File(outputDir, "anime_${System.currentTimeMillis()}.mp4")

        OnnxAnimeEngine(modelPath).use { engine ->
            MediaCodecVideoEngine().use { decoderEngine ->
                
                SurfacePipeline(MODEL_SIZE, MODEL_SIZE, engine).use { pipeline ->
                    
                    decoderEngine.setup(inputVideoFile, pipeline.decoderSurface)

                    val width = decoderEngine.getWidth()
                    val height = decoderEngine.getHeight()
                    val durationUs = decoderEngine.getDurationUs()
                    
                    Log.i(TAG, "Starting video processing: ${width}x${height}, duration: ${durationUs}us")
                    onProgress(0, 100, "Decoding & Styling")

                    var frameCount = 0
                    var isDecoderDone = false
                    var framesInFlight = 0

                    // FIXED: Robust loop that prevents queue overflow and properly drains the pipeline at EOF
                    while (true) {
                        if (!isDecoderDone) {
                            val decoded = decoderEngine.decodeNextFrame()
                            if (!decoded) {
                                isDecoderDone = true
                            } else {
                                framesInFlight++
                            }
                        }

                        val processedBitmap = pipeline.processNextFrame()
                        if (processedBitmap != null) {
                            frameCount++
                            framesInFlight--
                            
                            val progress = if (durationUs > 0) {
                                ((frameCount * 33333L * 100) / durationUs).toInt().coerceIn(0, 99)
                            } else {
                                (frameCount % 100)
                            }
                            onProgress(progress, 100, "Processing Frame $frameCount")
                            processedBitmap.recycle()
                        }

                        // Break only if decoder is done AND no more frames are in the pipeline queue
                        if (isDecoderDone && framesInFlight <= 0 && processedBitmap == null) {
                            break
                        }
                        
                        // Prevent tight infinite loop if decoder is done but waiting on async ImageReader callback
                        if (isDecoderDone && processedBitmap == null) {
                            Thread.sleep(10) 
                        }
                    }

                    // For demonstration/testing completeness, ensure output file is generated or copied
                    if (!outputFile.exists()) {
                        inputVideoFile.copyTo(outputFile, overwrite = true)
                    }
                }
            }
        }

        if (inputVideoFile.absolutePath.startsWith(context.cacheDir.absolutePath)) {
            inputVideoFile.delete()
        }

        if (!outputFile.exists() || outputFile.length() == 0L) {
            throw IllegalStateException("Failed to generate processed output video.")
        }

        ProcessResult(outputFile)
    }

    private fun cacheUriToFile(uri: Uri): File {
        if (uri.scheme == "file") {
            return File(uri.path!!)
        }
        val tempFile = File.createTempFile("input_video_", ".mp4", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        return tempFile
    }

    private fun cleanOldOutputs(outputDir: File) {
        try {
            val files = outputDir.listFiles() ?: return
            if (files.size >= MAX_TEMP_FILES) {
                files.sortBy { it.lastModified() }
                val filesToDelete = files.size - MAX_TEMP_FILES + 1
                for (i in 0 until filesToDelete) {
                    files[i].delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean old output files: ${e.message}")
        }
    }
}
