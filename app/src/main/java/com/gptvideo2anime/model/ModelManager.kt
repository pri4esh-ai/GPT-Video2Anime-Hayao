package com.gptvideo2anime.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class ModelManager(
    private val context: Context
) {

    companion object {
        private const val MODEL_DIR = "models"
        private const val ANIME_ASSET = "models/AnimeGANv3_Hayao_36.onnx"
        private const val ANIME_NAME = "AnimeGANv3_Hayao_36.onnx"
        private const val ANIME_SHA256 = "95ba7b219073fd5b12f569bc38056ffd3019cf4caf15b1feb9f73d1286c9f69d"
        private const val BUFFER_SIZE = 1024 * 1024 // 1MB buffer
    }

    private val modelDirectory = File(context.filesDir, MODEL_DIR)
    private val animeFile = File(modelDirectory, ANIME_NAME)

    /**
     * Retrieves the absolute path to the verified ONNX model.
     * Offloads SHA-256 calculation to Dispatchers.IO to prevent main-thread lag.
     */
    suspend fun animeModelPath(): String? = withContext(Dispatchers.IO) {
        if (isValid(animeFile, ANIME_SHA256)) {
            animeFile.absolutePath
        } else {
            null
        }
    }

    /**
     * Non-blocking suspension check to determine if all models are present and valid.
     */
    suspend fun areAllModelsInstalled(): Boolean = withContext(Dispatchers.IO) {
        animeModelPath() != null
    }

    /**
     * Non-blocking suspension query for UI status messaging.
     */
    suspend fun modelStatus(): String = withContext(Dispatchers.IO) {
        if (areAllModelsInstalled()) {
            "Hayao model ready"
        } else {
            "Installing Hayao model..."
        }
    }

    /**
     * Ensures models are unpacked from assets to internal storage and verified.
     */
    suspend fun ensureModels(
        onLog: ((String) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        if (!modelDirectory.exists()) {
            modelDirectory.mkdirs()
        }

        copyAssetIfNeeded(
            assetName = ANIME_ASSET,
            target = animeFile,
            expectedSha = ANIME_SHA256,
            onLog = onLog
        )

        require(isValid(animeFile, ANIME_SHA256)) {
            "Hayao model installation failed verification."
        }

        onLog?.invoke("Hayao model ready.")
    }

    private fun copyAssetIfNeeded(
        assetName: String,
        target: File,
        expectedSha: String,
        onLog: ((String) -> Unit)?
    ) {
        if (isValid(target, expectedSha)) {
            return
        }

        onLog?.invoke("Installing ${target.name}...")

        val temp = File(target.parentFile, "${target.name}.part")

        try {
            if (temp.exists()) {
                temp.delete()
            }

            context.assets.open(assetName).use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output, bufferSize = BUFFER_SIZE)
                }
            }

            val actualSha = sha256(temp)

            require(actualSha.equals(expectedSha, ignoreCase = true)) {
                "SHA-256 mismatch for ${target.name}. Expected: $expectedSha, Found: $actualSha"
            }

            if (target.exists()) {
                target.delete()
            }

            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (e: Exception) {
            if (temp.exists()) {
                temp.delete()
            }
            throw e
        }
    }

    private fun isValid(
        file: File,
        expectedSha: String
    ): Boolean {
        if (!file.exists() || file.length() <= 0L) {
            return false
        }

        return try {
            sha256(file).equals(expectedSha, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")

        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var count: Int
            while (input.read(buffer).also { count = it } >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count)
                }
            }
        }

        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte)
        }
    }
}
