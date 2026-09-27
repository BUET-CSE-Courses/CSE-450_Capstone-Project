package com.example.capstone.domain.grading

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Session 7 rule b: the SCORE forms the phone accepts, and the ones it will not guess at. */
class ScoreFormatTest {

    private fun score(line: String, max: Int = 15): Double? =
        parseReply("TRANSCRIPT: a) b)\nSCORE: $line\nCONFIDENCE: 80\nFEEDBACK: ok", max).grade.score

    private fun parseError(line: String, max: Int = 15) =
        parseReply("TRANSCRIPT: a) b)\nSCORE: $line\nCONFIDENCE: 80\nFEEDBACK: ok", max).grade.parseError

    @Test
    fun `plain numbers, as before`() {
        assertThat(score("10")).isEqualTo(10.0)
        assertThat(score("4.5")).isEqualTo(4.5)
        assertThat(score("0")).isEqualTo(0.0)
        assertThat(score("10 (both parts correct)")).isEqualTo(10.0)
    }

    @Test
    fun `N over the box's marks`() {
        assertThat(score("10/15")).isEqualTo(10.0)
        assertThat(score("10 / 15")).isEqualTo(10.0)
        assertThat(score("10/15 marks")).isEqualTo(10.0)
        assertThat(score("10 out of 15")).isEqualTo(10.0)
        assertThat(score("10 OUT OF 15 marks")).isEqualTo(10.0)
    }

    @Test
    fun `N marks`() {
        assertThat(score("10 marks")).isEqualTo(10.0)
        assertThat(score("1 mark")).isEqualTo(1.0)
    }

    @Test
    fun `a denominator that is not the box's marks is not guessed at`() {
        assertThat(parseError("10/20")).isTrue()
        assertThat(parseError("5 out of 5")).isTrue()
        assertThat(score("10/20")).isNull()
    }

    @Test
    fun `above the maximum stays a parse error`() {
        assertThat(parseError("16")).isTrue()
        assertThat(parseError("16/15")).isTrue()
        assertThat(parseError("16 out of 15")).isTrue()
    }

    @Test
    fun `sums, several numbers and no number are ambiguous`() {
        assertThat(parseError("5 + 5 = 10")).isTrue()
        assertThat(parseError("a) 5 b) 5")).isTrue()
        assertThat(parseError("5, 5")).isTrue()
        assertThat(parseError("full marks")).isTrue()
    }

    @Test
    fun `UNREADABLE and NOTHING WRITTEN behave as on the server`() {
        assertThat(parseReply("TRANSCRIPT: x\nSCORE: UNREADABLE\nCONFIDENCE: 10", 15).grade.unreadable).isTrue()
        val blank = parseReply("TRANSCRIPT: NOTHING WRITTEN\nSCORE: 0\nCONFIDENCE: 90", 15).grade
        assertThat(blank.score).isEqualTo(0.0)
        assertThat(blank.parseError).isFalse()
    }
}
