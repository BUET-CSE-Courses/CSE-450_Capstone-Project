package com.example.capstone.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.capstone.CapstoneApplication
import com.example.capstone.data.remote.userMessage
import com.example.capstone.data.repository.AuthRepository
import com.example.capstone.data.repository.CourseRepository
import com.example.capstone.domain.model.Course
import com.example.capstone.domain.model.Me
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Success(val courses: List<Course>) : HomeUiState
    data class Error(val message: String) : HomeUiState
}

/** The student's courses, and joining one by code. */
class HomeViewModel(
    private val authRepository: AuthRepository,
    private val courseRepository: CourseRepository,
    private val baseUrl: String
) : ViewModel() {

    val me: StateFlow<Me?> = authRepository.me

    var uiState: HomeUiState by mutableStateOf(HomeUiState.Loading)
        private set

    var joinCode by mutableStateOf("")
    var joining by mutableStateOf(false)
        private set

    /** The last join's outcome, success or error, for one line under the field. */
    var joinMessage: String? by mutableStateOf(null)
        private set
    var joinFailed by mutableStateOf(false)
        private set

    init {
        // Restored here after process death without passing sign-in: fetch who we are.
        if (me.value == null) viewModelScope.launch { authRepository.loadMe() }
        loadCourses()
    }

    fun loadCourses() {
        viewModelScope.launch {
            uiState = HomeUiState.Loading
            uiState = courseRepository.myCourses().fold(
                onSuccess = { HomeUiState.Success(it) },
                onFailure = { HomeUiState.Error("Couldn't load your courses. ${it.userMessage(baseUrl)}") }
            )
        }
    }

    fun join() {
        if (joining || joinCode.isBlank()) return
        viewModelScope.launch {
            joining = true
            courseRepository.join(joinCode).fold(
                onSuccess = { course ->
                    joinMessage = "Joined ${course.title}."
                    joinFailed = false
                    joinCode = ""
                    loadCourses()
                },
                onFailure = { e ->
                    joinMessage = "Couldn't join. ${e.userMessage(baseUrl)}"
                    joinFailed = true
                }
            )
            joining = false
        }
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container =
                    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as CapstoneApplication).container
                HomeViewModel(
                    authRepository = container.authRepository,
                    courseRepository = container.courseRepository,
                    baseUrl = container.baseUrl
                )
            }
        }
    }
}
