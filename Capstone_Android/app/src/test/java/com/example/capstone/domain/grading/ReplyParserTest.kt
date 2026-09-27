package com.example.capstone.domain.grading

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The parsing cases from Script-Checker-Web-End/backend/tests/test_grading.py
 * (section "Parsing the model's reply", c69eea2), one test per server test,
 * then the phone's own: TRANSCRIPT handling and CONFIDENCE.
 */
class ReplyParserTest {

    // ---- Ported from test_grading.py ---------------------------------------

    @Test
    fun `test_parses_a_normal_reply`() {
        val parsed = parseGradingResponse("SCORE: 3\nFEEDBACK: Good method, arithmetic slip.", 5)
        assertThat(parsed.score).isEqualTo(3.0)
        assertThat(parsed.feedback).isEqualTo("Good method, arithmetic slip.")
        assertThat(parsed.parseError).isFalse()
    }

    @Test
    fun `test_parses_a_decimal_mark`() {
        assertThat(parseGradingResponse("SCORE: 2.5\nFEEDBACK: Half credit.", 5).score).isEqualTo(2.5)
    }

    @Test
    fun `test_unreadable_is_not_a_zero`() {
        val parsed = parseGradingResponse("SCORE: UNREADABLE\nFEEDBACK: Photo is blurred.", 5)
        assertThat(parsed.unreadable).isTrue()
        assertThat(parsed.score).isNull()
    }

    @Test
    fun `test_unparseable_replies_are_flagged_not_guessed`() {
        for (reply in listOf("I think this deserves about 4 marks", "SCORE: banana\nFEEDBACK: hmm", "")) {
            assertThat(parseGradingResponse(reply, 5).parseError).isTrue()
        }
    }

    @Test
    fun `test_scores_outside_the_maximum_are_rejected`() {
        for (score in listOf("9", "-2")) {
            val parsed = parseGradingResponse("SCORE: $score\nFEEDBACK: x", 5)
            assertThat(parsed.parseError).isTrue()
            assertThat(parsed.score).isNull()
        }
    }

    // ---- TRANSCRIPT and NOTHING WRITTEN ------------------------------------

    @Test
    fun `full reply gives transcript score and feedback`() {
        val parsed = parseGradingResponse(
            "TRANSCRIPT: 2x = 6\nx = 3\nSCORE: 4\nFEEDBACK: Correct method.", 5
        )
        assertThat(parsed.transcript).isEqualTo("2x = 6\nx = 3")
        assertThat(parsed.score).isEqualTo(4.0)
        assertThat(parsed.feedback).isEqualTo("Correct method.")
    }

    @Test
    fun `NOTHING WRITTEN is a zero whatever score the model gave`() {
        val parsed = parseGradingResponse("TRANSCRIPT: NOTHING WRITTEN\nSCORE: 3\nFEEDBACK: Nice.", 5)
        assertThat(parsed.score).isEqualTo(0.0)
        assertThat(parsed.feedback).isEqualTo(NOTHING_WRITTEN_FEEDBACK)
        assertThat(parsed.parseError).isFalse()
    }

    @Test
    fun `the other empty-page phrasings count as nothing written`() {
        for (t in listOf("(blank)", "n/a", "N/A", "---", "none.", "Empty", "no answer")) {
            assertThat(parseGradingResponse("TRANSCRIPT: $t\nSCORE: 2\nFEEDBACK: ok", 5).score).isEqualTo(0.0)
        }
    }

    @Test
    fun `a sentence that starts with nothing is not nothing written`() {
        val parsed = parseGradingResponse("TRANSCRIPT: nothing to add, x = 3\nSCORE: 1\nFEEDBACK: ok", 5)
        assertThat(parsed.score).isEqualTo(1.0)
    }

    @Test
    fun `an empty TRANSCRIPT line runs on into SCORE, as on the server`() {
        // TRANSCRIPT:\s* eats the newline, so the lookahead never sees
        // "\nSCORE:" and the transcript is the rest of the reply.
        val parsed = parseGradingResponse("TRANSCRIPT:\nSCORE: 2\nFEEDBACK: ok", 5)
        assertThat(parsed.score).isEqualTo(2.0)
        assertThat(parsed.transcript).isEqualTo("SCORE: 2\nFEEDBACK: ok")
    }

    // ---- Missing sections and odd formatting -------------------------------

    @Test
    fun `missing SCORE is a parse error`() {
        val parsed = parseGradingResponse("TRANSCRIPT: x = 3\nFEEDBACK: ok", 5)
        assertThat(parsed.parseError).isTrue()
        assertThat(parsed.feedback).isEqualTo("ok")
    }

    @Test
    fun `missing FEEDBACK still gives the score with empty feedback`() {
        val parsed = parseGradingResponse("TRANSCRIPT: x = 3\nSCORE: 3", 5)
        assertThat(parsed.score).isEqualTo(3.0)
        assertThat(parsed.feedback).isEmpty()
    }

    @Test
    fun `missing TRANSCRIPT leaves transcript null`() {
        assertThat(parseGradingResponse("SCORE: 3\nFEEDBACK: ok", 5).transcript).isNull()
    }

    @Test
    fun `lowercase section names and CRLF line ends parse`() {
        assertThat(parseGradingResponse("transcript: x=3\nscore: 5\nfeedback: ok", 5).score).isEqualTo(5.0)
        val crlf = parseGradingResponse("TRANSCRIPT: x = 3\r\nSCORE: 3\r\nFEEDBACK: Good.\r\n", 5)
        assertThat(crlf.score).isEqualTo(3.0)
        assertThat(crlf.feedback).isEqualTo("Good.")
    }

    @Test
    fun `the first number on the SCORE line counts`() {
        assertThat(parseGradingResponse("SCORE: 4/5 marks\nFEEDBACK: ok", 5).score).isEqualTo(4.0)
    }

    @Test
    fun `lowercase unreadable with a comment is still unreadable`() {
        val parsed = parseGradingResponse("TRANSCRIPT: scribbles\nSCORE: unreadable, too faint\nFEEDBACK: retake", 5)
        assertThat(parsed.unreadable).isTrue()
    }

    @Test
    fun `zero and the maximum itself are in range`() {
        assertThat(parseGradingResponse("SCORE: 0\nFEEDBACK: wrong", 5).score).isEqualTo(0.0)
        assertThat(parseGradingResponse("SCORE: 5\nFEEDBACK: full", 5).score).isEqualTo(5.0)
        assertThat(parseGradingResponse("SCORE: 5.01\nFEEDBACK: over", 5).parseError).isTrue()
    }

    @Test
    fun `a null reply is a parse error, not a crash`() {
        assertThat(parseGradingResponse(null, 5).parseError).isTrue()
    }

    // ---- CONFIDENCE --------------------------------------------------------

    private val normal =
        "TRANSCRIPT: x = 3\nSCORE: 4\nFEEDBACK: Correct, but show the division.\nCONFIDENCE: 85"

    @Test
    fun `CONFIDENCE after FEEDBACK is read and kept out of the feedback`() {
        val reply = parseReply(normal, 5)
        assertThat(reply.confidence).isEqualTo(Confidence.Valid(85.0))
        assertThat(reply.grade.feedback).isEqualTo("Correct, but show the division.")
        assertThat(reply.grade.score).isEqualTo(4.0)
        assertThat(reply.grade.transcript).isEqualTo("x = 3")
    }

    @Test
    fun `without CONFIDENCE the rest parses exactly as the server would`() {
        val withConfidence = parseReply(normal, 5).grade
        val server = parseGradingResponse(normal.substringBefore("\nCONFIDENCE"), 5)
        assertThat(withConfidence).isEqualTo(server)
    }

    @Test
    fun `missing CONFIDENCE is Missing`() {
        assertThat(parseReply("SCORE: 3\nFEEDBACK: ok", 5).confidence).isEqualTo(Confidence.Missing)
    }

    @Test
    fun `CONFIDENCE out of range is OutOfRange`() {
        assertThat(parseReply("SCORE: 3\nFEEDBACK: ok\nCONFIDENCE: 150", 5).confidence)
            .isEqualTo(Confidence.OutOfRange(150.0))
        assertThat(parseReply("SCORE: 3\nFEEDBACK: ok\nCONFIDENCE: -5", 5).confidence)
            .isEqualTo(Confidence.OutOfRange(-5.0))
    }

    @Test
    fun `CONFIDENCE with no number is Unparsable`() {
        assertThat(parseReply("SCORE: 3\nFEEDBACK: ok\nCONFIDENCE: high", 5).confidence)
            .isEqualTo(Confidence.Unparsable("high"))
    }

    @Test
    fun `CONFIDENCE bounds, a percent sign and lowercase are accepted`() {
        assertThat(parseReply("SCORE: 3\nCONFIDENCE: 0", 5).confidence).isEqualTo(Confidence.Valid(0.0))
        assertThat(parseReply("SCORE: 3\nCONFIDENCE: 100", 5).confidence).isEqualTo(Confidence.Valid(100.0))
        assertThat(parseReply("SCORE: 3\nconfidence: 72%", 5).confidence).isEqualTo(Confidence.Valid(72.0))
    }

    @Test
    fun `a 0-1 style CONFIDENCE is read on the 0-100 scale, so it is low`() {
        assertThat(parseReply("SCORE: 3\nCONFIDENCE: 0.9", 5).confidence).isEqualTo(Confidence.Valid(0.9))
    }

    @Test
    fun `CONFIDENCE on the FEEDBACK line is still read and removed`() {
        val reply = parseReply("SCORE: 3\nFEEDBACK: Good work. CONFIDENCE: 70", 5)
        assertThat(reply.confidence).isEqualTo(Confidence.Valid(70.0))
        assertThat(reply.grade.feedback).isEqualTo("Good work.")
    }

    @Test
    fun `CONFIDENCE before SCORE does not disturb the score`() {
        val reply = parseReply("TRANSCRIPT: 7\nCONFIDENCE: 90\nSCORE: 2\nFEEDBACK: ok", 5)
        assertThat(reply.confidence).isEqualTo(Confidence.Valid(90.0))
        assertThat(reply.grade.score).isEqualTo(2.0)
        assertThat(reply.grade.transcript).isEqualTo("7")
    }

    @Test
    fun `when CONFIDENCE repeats the last one counts`() {
        val reply = parseReply("SCORE: 2\nCONFIDENCE: 90\nFEEDBACK: ok\nCONFIDENCE: 40", 5)
        assertThat(reply.confidence).isEqualTo(Confidence.Valid(40.0))
        assertThat(reply.grade.feedback).isEqualTo("ok")
    }

    @Test
    fun `a word ending in CONFIDENCE is not a CONFIDENCE line`() {
        assertThat(parseReply("SCORE: 2\nFEEDBACK: LOWCONFIDENCE: 10", 5).confidence)
            .isEqualTo(Confidence.Missing)
    }
}
