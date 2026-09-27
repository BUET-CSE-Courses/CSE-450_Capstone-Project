package com.example.capstone.data.remote

import com.example.capstone.data.auth.AuthInterceptor
import com.example.capstone.data.auth.TokenProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Hands out tokens in order: the first call gets [silent], a forced refresh
 * gets [refreshed]. Records every call and every sign-in request.
 */
class FakeTokens(
    var silent: String? = "token-1",
    var refreshed: String? = "token-2"
) : TokenProvider {
    val calls = mutableListOf<Boolean>()
    val signInReasons = mutableListOf<String>()

    override fun accessTokenBlocking(forceRefresh: Boolean): String? {
        calls += forceRefresh
        return if (forceRefresh) refreshed else silent
    }

    override fun onSignInRequired(reason: String) {
        signInReasons += reason
    }
}

/** The app's real ApiService and AuthInterceptor, pointed at [server]'s "/api/". */
fun apiFor(server: MockWebServer, tokens: TokenProvider = FakeTokens()): ApiService =
    Retrofit.Builder()
        .baseUrl(server.url("/api/"))
        .client(OkHttpClient.Builder().addInterceptor(AuthInterceptor(tokens)).build())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ApiService::class.java)

fun resourceText(path: String): String =
    checkNotNull(object {}.javaClass.classLoader?.getResource(path)) { "missing test resource $path" }
        .readText()
