package com.example.capstone.domain.grading

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RunawayGuardTest {

    /** The session 7 shape: one 67-character clause, repeated until the context ran out. */
    private val clause = "correctly applied the method to find the solutions, and also then  "

    private val looping = "TRANSCRIPT: x = 1\nSCORE: 5\nFEEDBACK: The student has " + clause.repeat(40)

    @Test
    fun `the session 7 loop is caught and cut to one copy`() {
        assertThat(clause.length).isEqualTo(67)
        val n = checkNotNull(RunawayGuard.cutLength(looping))
        // The loop may be found starting a character early (the space before it matches the
        // clause's own last space), so compare without trailing spaces.
        assertThat(looping.substring(0, n).trimEnd())
            .isEqualTo("TRANSCRIPT: x = 1\nSCORE: 5\nFEEDBACK: The student has $clause".trimEnd())
    }

    @Test
    fun `caught as soon as the third copy is complete, even mid-stream`() {
        val prefix = "FEEDBACK: The student has "
        assertThat(RunawayGuard.cutLength(prefix + clause.repeat(2))).isNull()
        assertThat(RunawayGuard.cutLength(prefix + clause.repeat(3))).isNotNull()
        // Stopping part-way through a copy is still a loop.
        assertThat(RunawayGuard.cutLength(prefix + clause.repeat(3) + clause.take(10))).isNotNull()
    }

    @Test
    fun `an ordinary reply is not a loop`() {
        val replies = listOf(
            "TRANSCRIPT: x^2-2x+1=0\n(x-1)^2=0\nx=1\nSCORE: 5\nFEEDBACK: Correct factorisation and root.\nCONFIDENCE: 90",
            "TRANSCRIPT: NOTHING WRITTEN\nSCORE: 0\nFEEDBACK: Nothing was written.\nCONFIDENCE: 95",
            "TRANSCRIPT: x = 1, x = 1, x = 1, x = 1\nSCORE: 1\nFEEDBACK: ok\nCONFIDENCE: 70",
            ""
        )
        for (r in replies) assertThat(RunawayGuard.cutLength(r)).isNull()
    }

    @Test
    fun `short repeats below the minimum period are left alone`() {
        assertThat(RunawayGuard.cutLength("TRANSCRIPT: " + "ab".repeat(8))).isNull()
    }

    @Test
    fun `cut marks the reply and wasCutOff sees it`() {
        val cut = RunawayGuard.cut(looping)
        assertThat(cut).endsWith(RunawayGuard.CUT_MARKER)
        assertThat(RunawayGuard.wasCutOff(cut)).isTrue()
        assertThat(RunawayGuard.wasCutOff("SCORE: 1\nFEEDBACK: ok\nCONFIDENCE: 90")).isFalse()
        assertThat(RunawayGuard.cut("SCORE: 1")).isEqualTo("SCORE: 1")
    }

    @Test
    fun `stops at the end of the first FEEDBACK line once SCORE and CONFIDENCE came first`() {
        val head = "TRANSCRIPT: x = 1\nSCORE: 4\nCONFIDENCE: 80\nFEEDBACK: Good work."
        assertThat(RunawayGuard.feedbackLineEnd("TRANSCRIPT: x = 1\nSCORE: 4\n")).isNull()
        assertThat(RunawayGuard.feedbackLineEnd("TRANSCRIPT: x = 1\nSCORE: 4\nCONFIDENCE: 80\nFEEDBACK:")).isNull()
        // The line is not finished until its newline arrives.
        assertThat(RunawayGuard.feedbackLineEnd(head)).isNull()
        assertThat(RunawayGuard.feedbackLineEnd("$head\nMore text")).isEqualTo(head.length)
    }

    @Test
    fun `never stops before SCORE and CONFIDENCE both have a value`() {
        // Rule a: the early stop may only end FEEDBACK.
        assertThat(RunawayGuard.feedbackLineEnd("SCORE:\nCONFIDENCE: 80\nFEEDBACK: ok\n")).isNull()
        assertThat(RunawayGuard.feedbackLineEnd("SCORE: 4\nCONFIDENCE:\nFEEDBACK: ok\n")).isNull()
        assertThat(RunawayGuard.feedbackLineEnd("FEEDBACK: ok\nSCORE: 4\nCONFIDENCE: 80\n")).isNull()
        assertThat(RunawayGuard.feedbackLineEnd("a) The student wrote: \"a = 4\"\n")).isNull()
        assertThat(RunawayGuard.feedbackLineEnd("SCORE: 4\nCONFIDENCE: 80\nFEEDBACK: ok\n")).isNotNull()
    }

    @Test
    fun `never stops early on a reply in the server's order`() {
        // CONFIDENCE after FEEDBACK: the whole reply is needed.
        val serverOrder = "TRANSCRIPT: x = 1\nSCORE: 4\nFEEDBACK: Good work.\nCONFIDENCE: 80"
        assertThat(RunawayGuard.feedbackLineEnd(serverOrder)).isNull()
        assertThat(RunawayGuard.markCameBeforeFeedback(serverOrder)).isFalse()
        assertThat(RunawayGuard.markCameBeforeFeedback("SCORE: 4\nCONFIDENCE: 80\nFEEDBACK: ok")).isTrue()
        // A "SCORE:" inside the transcript line does not count as the SCORE line.
        assertThat(RunawayGuard.markCameBeforeFeedback("TRANSCRIPT: SCORE: CONFIDENCE:\nFEEDBACK: ok")).isFalse()
    }

    @Test
    fun `streamed pieces join whether they are deltas or the whole reply so far`() {
        val deltas = StringBuilder()
        listOf("SCORE", ": 4\n", "FEEDBACK: ok").forEach { RunawayGuard.appendStreamed(deltas, it) }
        assertThat(deltas.toString()).isEqualTo("SCORE: 4\nFEEDBACK: ok")

        val cumulative = StringBuilder()
        listOf("SCORE", "SCORE: 4\n", "SCORE: 4\nFEEDBACK: ok").forEach { RunawayGuard.appendStreamed(cumulative, it) }
        assertThat(cumulative.toString()).isEqualTo("SCORE: 4\nFEEDBACK: ok")
    }
}
