package com.example.capstone.ui.screens

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.capstone.CapstoneApplication
import com.example.capstone.data.auth.AuthState
import com.example.capstone.data.auth.SignInRequiredException
import com.example.capstone.data.remote.userMessage
import com.example.capstone.data.repository.AuthRepository
import com.example.capstone.domain.model.Me
import com.microsoft.identity.client.exception.MsalUserCancelException
import kotlinx.coroutines.launch

sealed interface SignInUiState {
    data object Checking : SignInUiState

    /** local.properties is missing something; sign-in cannot work in this build. */
    data class NotConfigured(val problems: List<String>) : SignInUiState

    data class SignedOut(val message: String? = null) : SignInUiState

    data object Working : SignInUiState

    /** Signed in, but the web end says this is not a student. */
    data class NotAStudent(val me: Me) : SignInUiState

    /** Signed in as a student; the screen moves on to the courses. */
    data class Ready(val me: Me) : SignInUiState

    /** Signed in with Microsoft, but `GET /api/me` failed for another reason. */
    data class Error(val message: String) : SignInUiState
}

class SignInViewModel(
    private val authRepository: AuthRepository,
    private val configProblems: List<String>,
    private val baseUrl: String
) : ViewModel() {

    var uiState: SignInUiState by mutableStateOf(SignInUiState.Checking)
        private set

    init {
        if (configProblems.isNotEmpty()) {
            uiState = SignInUiState.NotConfigured(configProblems)
        } else {
            viewModelScope.launch {
                when (val state = authRepository.start()) {
                    is AuthState.SignedIn -> loadMe()
                    is AuthState.SignedOut -> uiState = SignInUiState.SignedOut(state.message)
                    AuthState.Starting -> uiState = SignInUiState.SignedOut()
                }
            }
        }
    }

    fun signIn(activity: Activity) {
        if (uiState is SignInUiState.NotConfigured || uiState is SignInUiState.Working) return
        viewModelScope.launch {
            uiState = SignInUiState.Working
            authRepository.signIn(activity).fold(
                onSuccess = { loadMe() },
                onFailure = { e ->
                    uiState = SignInUiState.SignedOut(
                        if (e is MsalUserCancelException) "Sign-in was cancelled."
                        else "Sign-in failed: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
            )
        }
    }

    fun retry() {
        viewModelScope.launch { loadMe() }
    }

    fun signOut() {
        viewModelScope.launch {
            uiState = SignInUiState.Working
            authRepository.signOut()
            uiState = SignInUiState.SignedOut()
        }
    }

    private suspend fun loadMe() {
        uiState = SignInUiState.Working
        authRepository.loadMe().fold(
            onSuccess = { me ->
                uiState = if (me.isStudent) SignInUiState.Ready(me) else SignInUiState.NotAStudent(me)
            },
            onFailure = { e ->
                uiState = if (e is SignInRequiredException) {
                    SignInUiState.SignedOut(e.message)
                } else {
                    SignInUiState.Error(e.userMessage(baseUrl))
                }
            }
        )
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container =
                    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as CapstoneApplication).container
                SignInViewModel(
                    authRepository = container.authRepository,
                    configProblems = container.configProblems,
                    baseUrl = container.baseUrl
                )
            }
        }
    }
}
