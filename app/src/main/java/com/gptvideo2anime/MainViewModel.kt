package com.gptvideo2anime

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gptvideo2anime.model.ModelManager
import com.gptvideo2anime.pipeline.VideoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val selectedVideo: Uri? = null,
    val resultVideo: Uri? = null,
    val strength: Int = 40,
    val progress: Float = 0f,
    val isProcessing: Boolean = false,
    val isModelReady: Boolean = false,
    val status: String = "Initializing...",
    val error: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val modelManager = ModelManager(application)
    private val videoProcessor = VideoProcessor(application)

    init {
        initializeModels()
    }

    private fun initializeModels() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update { it.copy(status = "Installing Hayao model...") }
                modelManager.ensureModels()
                _uiState.update {
                    it.copy(
                        isModelReady = true,
                        status = if (it.selectedVideo == null) "Choose a video to begin" else "Ready to process",
                        error = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isModelReady = false,
                        status = "Model setup failed",
                        error = e.message ?: "Unable to install model."
                    )
                }
            }
        }
    }

    fun onVideoSelected(uri: Uri?) {
        if (uri != null) {
            _uiState.update {
                it.copy(
                    selectedVideo = uri,
                    resultVideo = null,
                    progress = 0f,
                    error = null,
                    status = "Video selected"
                )
            }
        }
    }

    fun onStrengthChanged(newStrength: Int) {
        if (!_uiState.value.isProcessing) {
            _uiState.update { it.copy(strength = newStrength) }
        }
    }

    fun resetState() {
        _uiState.update {
            it.copy(
                selectedVideo = null,
                resultVideo = null,
                progress = 0f,
                error = null,
                status = "Choose a video to begin"
            )
        }
    }

    fun processVideo() {
        val inputUri = _uiState.value.selectedVideo ?: return
        val currentStrength = _uiState.value.strength

        _uiState.update {
            it.copy(
                isProcessing = true,
                progress = 0f,
                error = null,
                status = "Preparing video..."
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = videoProcessor.processVideo(
                    uri = inputUri,
                    strength = currentStrength
                ) { current, total, stage ->
                    val value = if (total > 0) {
                        (current.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    _uiState.update {
                        it.copy(
                            progress = value,
                            status = stage
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            resultVideo = Uri.fromFile(result.outputFile),
                            progress = 1f,
                            status = "Your anime video is ready",
                            isProcessing = false
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            status = "Processing failed",
                            error = e.message ?: "Unknown processing error."
                        )
                    }
                }
            }
        }
    }
}
