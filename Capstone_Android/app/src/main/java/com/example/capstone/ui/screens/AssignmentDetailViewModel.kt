package com.example.capstone.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.capstone.CapstoneApplication
import com.example.capstone.data.remote.userMessage
import com.example.capstone.data.repository.AssignmentRepository
import com.example.capstone.data.repository.CachedPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AssignmentDetailUiState {
    data object Loading : AssignmentDetailUiState

    /** [downloadError] is set when a re-download failed but a cached pack is still shown. */
    data class Success(
        val cached: CachedPack,
        val downloading: Boolean = false,
        val downloadError: String? = null
    ) : AssignmentDetailUiState

    data class Error(val message: String) : AssignmentDetailUiState
}

/**
 * One paper's assignment pack. Shows the cached copy at once, and downloads
 * it when there is none, or when the student asks.
 */
class AssignmentDetailViewModel(
    private val assignmentRepository: AssignmentRepository,
    private val baseUrl: String,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val questionId: String = checkNotNull(savedStateHandle.get<String>("questionId"))

    var uiState: AssignmentDetailUiState by mutableStateOf(AssignmentDetailUiState.Loading)
        private set

    init {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { assignmentRepository.cachedPack(questionId) }
            if (cached != null) uiState = AssignmentDetailUiState.Success(cached) else download()
        }
    }

    fun download() {
        val shown = uiState as? AssignmentDetailUiState.Success
        if (shown?.downloading == true) return
        viewModelScope.launch {
            uiState = shown?.copy(downloading = true, downloadError = null) ?: AssignmentDetailUiState.Loading
            val result = withContext(Dispatchers.IO) { assignmentRepository.downloadPack(questionId) }
            uiState = result.fold(
                onSuccess = { AssignmentDetailUiState.Success(it) },
                onFailure = { e ->
                    val message = "Couldn't download the assignment. ${e.userMessage(baseUrl)}"
                    shown?.copy(downloading = false, downloadError = message)
                        ?: AssignmentDetailUiState.Error(message)
                }
            )
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container =
                    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as CapstoneApplication).container
                AssignmentDetailViewModel(
                    assignmentRepository = container.assignmentRepository,
                    baseUrl = container.baseUrl,
                    savedStateHandle = createSavedStateHandle()
                )
            }
        }
    }
}
