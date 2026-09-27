package com.example.capstone.data.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

sealed interface AuthState {
    /** MSAL is loading its account cache. */
    data object Starting : AuthState

    /** No account. [message] says why, when there is a reason worth showing. */
    data class SignedOut(val message: String? = null) : AuthState

    data class SignedIn(val username: String) : AuthState
}

/**
 * MSAL for Android, single-account mode. The Entra access token is the web
 * end's bearer token as is; there is no backend token exchange.
 *
 * MSAL keeps the account and refreshes tokens in its own encrypted cache, so
 * the app stores no token of its own.
 */
class MsalAuthManager(
    private val context: Context,
    private val clientId: String,
    private val signatureHash: String
) : TokenProvider {

    private val scopes = listOf(MsalConfig.scope(clientId))
    private val initLock = Mutex()

    @Volatile
    private var app: ISingleAccountPublicClientApplication? = null

    private val _state = MutableStateFlow<AuthState>(AuthState.Starting)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Loads MSAL and any cached account. Safe to call repeatedly. */
    suspend fun start(): AuthState {
        val pca = try {
            client()
        } catch (e: Exception) {
            Log.e(TAG, "MSAL did not start", e)
            return AuthState.SignedOut("Microsoft sign-in could not start: ${e.message}")
                .also { _state.value = it }
        }
        val account = withContext(Dispatchers.IO) {
            try {
                pca.currentAccount.currentAccount
            } catch (e: Exception) {
                Log.w(TAG, "could not read the cached account", e)
                null
            }
        }
        val next = account?.let { AuthState.SignedIn(it.username) }
            ?: (_state.value as? AuthState.SignedOut ?: AuthState.SignedOut())
        _state.value = next
        return next
    }

    /**
     * Interactive sign-in in a browser tab. If MSAL still holds an account
     * whose token can no longer be refreshed, this re-authenticates it
     * ("sign in again") instead of failing with "already signed in".
     */
    suspend fun signIn(activity: Activity): Result<String> = try {
        val pca = client()
        val existing = withContext(Dispatchers.IO) { pca.currentAccount.currentAccount }
        val result = suspendCancellableCoroutine { cont ->
            val callback = object : AuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    cont.resume(Result.success(authenticationResult))
                }

                override fun onError(exception: MsalException) {
                    cont.resume(Result.failure(exception))
                }

                override fun onCancel() {
                    cont.resume(Result.failure(IOException("Sign-in was cancelled.")))
                }
            }
            val params = SignInParameters.builder()
                .withActivity(activity)
                .withScopes(scopes)
                .withCallback(callback)
                .build()
            if (existing != null) pca.signInAgain(params) else pca.signIn(params)
        }
        result.map { auth ->
            val username = auth.account.username
            _state.value = AuthState.SignedIn(username)
            username
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Removes the account from MSAL's cache. */
    suspend fun signOut(message: String? = null) {
        try {
            val pca = client()
            withContext(Dispatchers.IO) { pca.signOut() }
        } catch (e: Exception) {
            Log.w(TAG, "sign-out failed", e)
        }
        _state.value = AuthState.SignedOut(message)
    }

    override fun accessTokenBlocking(forceRefresh: Boolean): String? {
        // After process death the back stack can be restored straight onto a
        // signed-in screen, before the sign-in screen ever called start().
        // Load MSAL here rather than treat the student as signed out.
        val pca = app ?: try {
            runBlocking { client() }
        } catch (e: Exception) {
            throw IOException("Microsoft sign-in could not start: ${e.message}", e)
        }
        val account: IAccount = try {
            pca.currentAccount.currentAccount
        } catch (e: MsalException) {
            throw IOException("Could not read the signed-in account: ${e.message}", e)
        } ?: return null

        val params = AcquireTokenSilentParameters.Builder()
            .forAccount(account)
            .fromAuthority(pca.configuration.defaultAuthority.authorityURL.toString())
            .withScopes(scopes)
            .forceRefresh(forceRefresh)
            .build()
        return try {
            pca.acquireTokenSilent(params).accessToken
        } catch (e: MsalUiRequiredException) {
            Log.i(TAG, "silent token needs UI: ${e.errorCode}")
            null
        } catch (e: MsalException) {
            // Not a sign-in problem (no network, say). Fail the call, keep the session.
            throw IOException("Could not get a sign-in token: ${e.errorCode}: ${e.message}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while getting a sign-in token", e)
        }
    }

    override fun onSignInRequired(reason: String) {
        _state.value = AuthState.SignedOut(reason)
    }

    private suspend fun client(): ISingleAccountPublicClientApplication {
        app?.let { return it }
        return initLock.withLock {
            app ?: withContext(Dispatchers.IO) {
                val redirect = MsalConfig.redirectUri(context.packageName, signatureHash)
                // Written on every start so a rebuilt APK with new local.properties
                // values never reads a stale file. noBackupFilesDir keeps it out of backups.
                val file = File(context.noBackupFilesDir, CONFIG_FILE_NAME)
                file.writeText(MsalConfig.json(clientId, redirect))
                PublicClientApplication.createSingleAccountPublicClientApplication(context, file)
            }.also { app = it }
        }
    }

    private companion object {
        const val TAG = "MsalAuth"
        const val CONFIG_FILE_NAME = "msal_config.json"
    }
}
