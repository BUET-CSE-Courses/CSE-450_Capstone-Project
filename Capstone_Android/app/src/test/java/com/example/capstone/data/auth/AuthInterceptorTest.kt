package com.example.capstone.data.auth

import com.example.capstone.data.remote.FakeTokens
import com.example.capstone.data.remote.apiFor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

class AuthInterceptorTest {

    private lateinit var server: MockWebServer

    private val meJson =
        """{"id":"u1","email":"s@example.com","display_name":"Sam Student","role":"student"}"""

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `every request carries the bearer token`() = runBlocking {
        server.enqueue(MockResponse().setBody(meJson))
        val tokens = FakeTokens(silent = "abc.def.ghi")

        apiFor(server, tokens).me()

        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/api/me")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer abc.def.ghi")
        assertThat(tokens.calls).containsExactly(false)
        assertThat(tokens.signInReasons).isEmpty()
    }

    @Test
    fun `a 401 refreshes the token silently and retries once`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Token expired"}"""))
        server.enqueue(MockResponse().setBody(meJson))
        val tokens = FakeTokens(silent = "stale", refreshed = "fresh")

        val me = apiFor(server, tokens).me()

        assertThat(me.displayName).isEqualTo("Sam Student")
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer stale")
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer fresh")
        assertThat(tokens.calls).containsExactly(false, true).inOrder()
        assertThat(tokens.signInReasons).isEmpty()
    }

    @Test
    fun `a second 401 ends the session and asks to sign in again`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid audience"}"""))
        val tokens = FakeTokens(silent = "t1", refreshed = "t2")

        val error = assertThrows(SignInRequiredException::class.java) {
            runBlocking { apiFor(server, tokens).me() }
        }

        assertThat(server.requestCount).isEqualTo(2)
        assertThat(error.message).contains("Invalid audience")
        assertThat(error.message).contains("Sign in again")
        assertThat(tokens.signInReasons).hasSize(1)
    }

    @Test
    fun `a 401 with no refreshable token asks to sign in without a retry`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val tokens = FakeTokens(silent = "t1", refreshed = null)

        assertThrows(SignInRequiredException::class.java) {
            runBlocking { apiFor(server, tokens).me() }
        }

        assertThat(server.requestCount).isEqualTo(1)
        assertThat(tokens.signInReasons).hasSize(1)
    }

    @Test
    fun `no account means no request at all`() {
        val tokens = FakeTokens(silent = null)

        assertThrows(SignInRequiredException::class.java) {
            runBlocking { apiFor(server, tokens).me() }
        }

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(tokens.signInReasons).hasSize(1)
    }

    @Test
    fun `other errors pass through untouched`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"Teacher role required"}"""))
        val tokens = FakeTokens()

        val error = assertThrows(HttpException::class.java) {
            runBlocking { apiFor(server, tokens).me() }
        }

        assertThat(error.code()).isEqualTo(403)
        assertThat(tokens.calls).containsExactly(false)
        assertThat(tokens.signInReasons).isEmpty()
    }
}
