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
import com.example.capstone.domain.model.StudentAssignment
import kotlinx.coroutines.launch

sealed interface AssignmentsUiState {
    data object Loading : AssignmentsUiState
    data class Success(val assignments: List<StudentAssignment>) : AssignmentsUiState
    data class Error(val message: String) : AssignmentsUiState
}

/** One course's finalized papers, with this student's status on each. */
class AssignmentsViewModel(
    private val assignmentRepository: AssignmentRepository,
    private val baseUrl: String,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val courseId: String = checkNotNull(savedStateHandle.get<String>("courseId"))
    val courseTitle: String = savedStateHandle.get<String>("title").orEmpty()

    var uiState: AssignmentsUiState by mutableStateOf(AssignmentsUiState.Loading)
        private set

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            uiState = AssignmentsUiState.Loading
            uiState = assignmentRepository.assignments(courseId).fold(
                onSuccess = { AssignmentsUiState.Success(it) },
                onFailure = { AssignmentsUiState.Error("Couldn't load assignments. ${it.userMessage(baseUrl)}") }
            )
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container =
                    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as CapstoneApplication).container
                AssignmentsViewModel(
                    assignmentRepository = container.assignmentRepository,
                    baseUrl = container.baseUrl,
                    savedStateHandle = createSavedStateHandle()
                )
            }
        }
    }
}
