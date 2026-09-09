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

        private const val ANIME_ASSET =
            "models/AnimeGANv3_Hayao_36.onnx"

        private const val ANIME_NAME =
            "AnimeGANv3_Hayao_36.onnx"

        private const val ANIME_SHA256 =
            "95ba7b219073fd5b12f569bc38056ffd3019cf4caf15b1feb9f73d1286c9f69d"
    }

    private val modelDirectory =
        File(context.filesDir, MODEL_DIR)

    private val animeFile =
        File(modelDirectory, ANIME_NAME)

    fun animeModelPath(): String? {
        return if (isValid(animeFile, ANIME_SHA256)) {
            animeFile.absolutePath
        } else {
            null
        }
    }

    fun areAllModelsInstalled(): Boolean {
        return animeModelPath() != null
    }

    fun modelStatus(): String {
        return if (areAllModelsInstalled()) {
            "Hayao model ready"
        } else {
            "Installing Hayao model..."
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

        require(animeModelPath() != null) {
            "Hayao model installation failed."
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

        val temp = File(
            target.parentFile,
            "${target.name}.part"
        )

        context.assets.open(assetName).use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(
                    output,
                    bufferSize = 1024 * 1024
                )
            }
        }

        val actualSha = sha256(temp)

        require(actualSha.equals(expectedSha, true)) {
            "SHA mismatch for ${target.name}"
        }

        if (target.exists()) {
            target.delete()
        }

        if (!temp.renameTo(target)) {
            temp.copyTo(
                target,
                overwrite = true
            )
            temp.delete()
        }
    }

    private fun isValid(
        file: File,
        expectedSha: String
    ): Boolean {
        if (!file.exists()) {
            return false
        }

        if (file.length() <= 0L) {
            return false
        }

        return try {
            sha256(file).equals(
                expectedSha,
                ignoreCase = true
            )
        } catch (_: Exception) {
            false
        }
    }

    private fun sha256(
        file: File
    ): String {
        val digest =
            MessageDigest.getInstance("SHA-256")

        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)

            while (true) {
                val count = input.read(buffer)

                if (count < 0) {
                    break
                }

                if (count > 0) {
                    digest.update(
                        buffer,
                        0,
                        count
                    )
                }
            }
        }

        return digest.digest().joinToString("") {
            "%02x".format(it)
        }
    }
}
