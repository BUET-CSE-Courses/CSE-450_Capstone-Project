package com.example.capstone.domain.grading

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DecodeSettingsTest {

    /** The session 7 device error, verbatim. */
    private val logitsError =
        RuntimeException("Status Code: 3. Message: Logits dimensions must be [batch_size, 1, vocab_size].")

    @Test
    fun `the default keeps the 300-token cap and nothing else`() {
        assertThat(DecodeSettings.DEFAULT.maxOutputTokens).isEqualTo(TokenBudget.DEFAULT_REPLY_RESERVE_TOKENS)
        assertThat(DecodeSettings.DEFAULT.maxOutputTokens).isEqualTo(300)
        assertThat(DecodeSettings.PLAIN.maxOutputTokens).isNull()
    }

    @Test
    fun `the logits error is retried once, plain`() {
        assertThat(DecodeSettings.retryAfter(logitsError, DecodeSettings.DEFAULT)).isEqualTo(DecodeSettings.PLAIN)
        // Once plain, there is nothing left to take off: give up.
        assertThat(DecodeSettings.retryAfter(logitsError, DecodeSettings.PLAIN)).isNull()
    }

    @Test
    fun `other failures are not retried`() {
        assertThat(DecodeSettings.retryAfter(RuntimeException("out of memory"), DecodeSettings.DEFAULT)).isNull()
        assertThat(DecodeSettings.retryAfter(RuntimeException(), DecodeSettings.DEFAULT)).isNull()
    }
}
