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

// Defined directly here to prevent Unresolved Reference cascades
data class UiState(
    val isModelReady: Boolean = false,
    val status: String = "Initializing...",
    val error: String? = null,
    val selectedVideo: Uri? = null
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
}
