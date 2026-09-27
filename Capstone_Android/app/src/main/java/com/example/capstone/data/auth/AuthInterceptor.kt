package com.example.capstone.data.auth

import com.example.capstone.data.remote.parseErrorDetail
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Where the interceptor gets tokens. Implemented by [MsalAuthManager]; faked in tests. */
interface TokenProvider {
    /**
     * An access token for the web end, obtained without any UI, or null when
     * the person has to sign in interactively. [forceRefresh] skips MSAL's
     * cache. Blocking: called on OkHttp's thread, never the main thread.
     *
     * @throws IOException when the token could not be fetched for a reason
     *   that is not "sign in again" (no network, say).
     */
    @Throws(IOException::class)
    fun accessTokenBlocking(forceRefresh: Boolean): String?

    /** The session is over; the app should send the person back to sign-in. */
    fun onSignInRequired(reason: String)
}

/** A request failed because the person must sign in again. An IOException so OkHttp can carry it. */
class SignInRequiredException(message: String) : IOException(message)

/**
 * Adds `Authorization: Bearer <token>` to every request.
 *
 * On a 401 it asks for a fresh token once (forceRefresh) and retries. A
 * second 401, or no token at all, ends the session: [TokenProvider.onSignInRequired]
 * fires and the call fails with [SignInRequiredException].
 */
class AuthInterceptor(private val tokens: TokenProvider) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokens.accessTokenBlocking(forceRefresh = false)
            ?: signInRequired("You're not signed in. Sign in with Microsoft.")

        val first = chain.proceed(chain.request().withBearer(token))
        if (first.code != HTTP_UNAUTHORIZED) return first
        first.close()

        val fresh = tokens.accessTokenBlocking(forceRefresh = true)
            ?: signInRequired("Your sign-in has expired. Sign in again.")

        val second = chain.proceed(chain.request().withBearer(fresh))
        if (second.code != HTTP_UNAUTHORIZED) return second

        val detail = try {
            parseErrorDetail(second.body?.string())
        } catch (e: IOException) {
            null
        } finally {
            second.close()
        }
        signInRequired(
            "The web end refused your sign-in (401${detail?.let { ": $it" } ?: ""}). " +
                "Sign in again. If this keeps happening, check that this build's client id " +
                "(webend.local.clientId or webend.deployed.clientId) is that web end's AZURE_CLIENT_ID."
        )
    }

    private fun signInRequired(reason: String): Nothing {
        tokens.onSignInRequired(reason)
        throw SignInRequiredException(reason)
    }

    private fun okhttp3.Request.withBearer(token: String) =
        newBuilder().header("Authorization", "Bearer $token").build()

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }
}
