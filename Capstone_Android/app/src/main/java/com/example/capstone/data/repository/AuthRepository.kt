package com.example.capstone.data.repository

import android.app.Activity
import com.example.capstone.data.auth.AuthState
import com.example.capstone.data.auth.MsalAuthManager
import com.example.capstone.data.remote.ApiService
import com.example.capstone.data.remote.UserDto
import com.example.capstone.domain.model.Me
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Microsoft sign-in plus "who am I" on the web end. The web end creates the
 * user row on the first authenticated call, so [loadMe] is also what
 * registers a new student.
 */
class AuthRepository(
    private val auth: MsalAuthManager,
    private val api: ApiService
) {
    val state: StateFlow<AuthState> get() = auth.state

    private val _me = MutableStateFlow<Me?>(null)
    /** The last `GET /api/me`, or null before it or after sign-out. */
    val me: StateFlow<Me?> = _me.asStateFlow()

    suspend fun start(): AuthState = auth.start()

    suspend fun signIn(activity: Activity): Result<String> = auth.signIn(activity)

    suspend fun loadMe(): Result<Me> = runCatching { api.me().toDomain() }
        .onSuccess { _me.value = it }

    suspend fun signOut(message: String? = null) {
        _me.value = null
        auth.signOut(message)
    }
}

internal fun UserDto.toDomain() = Me(displayName = displayName, email = email, role = role)
