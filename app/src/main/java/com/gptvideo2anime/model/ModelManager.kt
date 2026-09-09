package com.gptvideo2anime.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class ModelManager(private val context: Context) {

    companion object {
        private const val MODEL_DIR = "models"

        private const val ANIME_ASSET = "models/AnimeGANv3_Hayao_36.onnx"
        private const val ENHANCER_ASSET = "models/RealESR-AnimeVideo-v3_x4.onnx"

        private const val ANIME_NAME = "AnimeGANv3_Hayao_36.onnx"
        private const val ENHANCER_NAME = "RealESR-AnimeVideo-v3_x4.onnx"

        private const val ANIME_SHA256 =
            "95ba7b219073fd5b12f569bc38056ffd3019cf4caf15b1feb9f73d1286c9f69d"

        private const val ENHANCER_SHA256 =
            "00ece3ac21c43ee31459216b5174b2cea0c5325044c5142aeb840f4890e175ff"
    }

    private val modelDirectory = File(context.filesDir, MODEL_DIR)

    private val animeFile = File(modelDirectory, ANIME_NAME)
    private val enhancerFile = File(modelDirectory, ENHANCER_NAME)

    fun animeModelPath(): String? =
        if (animeFile.exists()) animeFile.absolutePath else null

    fun enhancerModelPath(): String? =
        if (enhancerFile.exists()) enhancerFile.absolutePath else null

    fun areAllModelsInstalled(): Boolean =
        isValid(animeFile, ANIME_SHA256) &&
        isValid(enhancerFile, ENHANCER_SHA256)

    fun modelStatus(): String {
        return when {
            areAllModelsInstalled() ->
                "AnimeGANv3 + RealESRGAN ready"

            animeFile.exists() ->
                "AnimeGANv3 ready"

            else ->
                "Installing bundled models..."
        }
    }

    suspend fun ensureModels(
        onLog: ((String) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {

        modelDirectory.mkdirs()

        copyAssetIfNeeded(
            assetName = ANIME_ASSET,
            target = animeFile,
            expectedSha = ANIME_SHA256,
            onLog = onLog
        )

        copyAssetIfNeeded(
            assetName = ENHANCER_ASSET,
            target = enhancerFile,
            expectedSha = ENHANCER_SHA256,
            onLog = onLog
        )

        onLog?.invoke("All models are ready.")
    }

    private fun copyAssetIfNeeded(
        assetName: String,
        target: File,
        expectedSha: String,
        onLog: ((String) -> Unit)?
    ) {

        if (isValid(target, expectedSha)) {
            onLog?.invoke("${target.name} already installed.")
            return
        }

        onLog?.invoke("Installing ${target.name}...")

        val temp = File(target.parentFile, "${target.name}.part")

        context.assets.open(assetName).use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output)
            }
        }

        val actualSha = sha256(temp)

        require(actualSha.equals(expectedSha, true)) {
            "SHA mismatch for ${target.name}"
        }

        if (target.exists()) target.delete()

        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }

        onLog?.invoke("${target.name} installed.")
    }

    private fun isValid(
        file: File,
        expectedSha: String
    ): Boolean {

        if (!file.exists()) return false
        if (file.length() <= 0L) return false

        return try {
            sha256(file).equals(expectedSha, true)
        } catch (_: Exception) {
            false
        }
    }

    private fun sha256(file: File): String {

        val digest = MessageDigest.getInstance("SHA-256")

        file.inputStream().use { input ->

            val buffer = ByteArray(1024 * 1024)

            while (true) {

                val count = input.read(buffer)

                if (count < 0) break

                if (count > 0) {
                    digest.update(buffer, 0, count)
                }
            }
        }

        return digest.digest()
            .joinToString("") {
                "%02x".format(it)
            }
    }
}
