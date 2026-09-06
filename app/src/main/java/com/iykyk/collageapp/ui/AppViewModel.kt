package com.iykyk.collageapp.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iykyk.collageapp.collage.CollageGenerator
import com.iykyk.collageapp.pipeline.PersonResult
import com.iykyk.collageapp.pipeline.PipelineProgress
import com.iykyk.collageapp.pipeline.VideoPipeline
import com.iykyk.collageapp.util.MediaStoreSaver
import com.iykyk.collageapp.util.ShareUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UiState {
    data object Idle : UiState
    data class Processing(val progress: PipelineProgress) : UiState
    data class Result(val people: List<PersonResult>, val collage: Bitmap) : UiState
    data class Error(val message: String) : UiState
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var pipeline: VideoPipeline? = null

    fun processVideo(uri: Uri, videoLabel: String) {
        viewModelScope.launch {
            _uiState.value = UiState.Processing(PipelineProgress(PipelineProgress.Phase.EXTRACTING, 0, 1))
            try {
                val people = withContext(Dispatchers.Default) {
                    val p = VideoPipeline(getApplication()).also { pipeline = it }
                    // Mirror pipeline progress into UI state as it runs.
                    val job = launch {
                        p.progress.collect { _uiState.value = UiState.Processing(it) }
                    }
                    val result = p.process(uri)
                    job.cancel()
                    p.close()
                    result
                }
                val collage = withContext(Dispatchers.Default) {
                    CollageGenerator.render(people, title = videoLabel)
                }
                _uiState.value = UiState.Result(people, collage)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Processing failed")
            }
        }
    }

    fun saveAndShare(share: Boolean, onComplete: (Boolean) -> Unit = {}) {
        val state = _uiState.value
        if (state !is UiState.Result) {
            onComplete(false)
            return
        }
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                MediaStoreSaver.saveToGallery(getApplication(), state.collage, "collage_${System.currentTimeMillis()}")
            }
            val success = uri != null
            if (success && share) {
                ShareUtil.shareImage(getApplication(), uri)
            }
            withContext(Dispatchers.Main) {
                onComplete(success)
            }
        }
    }

    fun reset() {
        _uiState.value = UiState.Idle
    }
}
