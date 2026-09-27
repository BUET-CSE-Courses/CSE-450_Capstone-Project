package com.example.capstone.domain.grading

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Test

/**
 * The port checked against the server's own code, not against a reading of it.
 *
 * `src/test/resources/grading/` was produced by `make_fixtures.py` (next to it),
 * which imports Script-Checker-Web-End/backend/services/grading.py at c69eea2
 * and records what GRADING_SYSTEM_PROMPT, build_user_message,
 * parse_grading_response and looks_blank actually return. The four PNGs are
 * the server test's own `_image()` crops. To refresh after grading.py changes:
 *
 *     python app/src/test/resources/grading/make_fixtures.py <web end>/backend <out dir>
 *
 * then copy the outputs over these.
 */
class ServerParityTest {

    private val expectations: JsonObject =
        JsonParser.parseString(Fixtures.text("server_expectations.json")).asJsonObject

    @Test
    fun `system prompt is the server's word for word plus one CONFIDENCE line`() {
        val server = Fixtures.text("server_prompt.txt")
        assertThat(GRADING_SYSTEM_PROMPT.replaceFirst(CONFIDENCE_FORMAT_LINE, "")).isEqualTo(server)
        assertThat(GRADING_SYSTEM_PROMPT.split(CONFIDENCE_FORMAT_LINE)).hasSize(2)
    }

    /** The phone's deliberate difference (session 7): mark and confidence before the free text. */
    @Test
    fun `the reply format is TRANSCRIPT, SCORE, CONFIDENCE, FEEDBACK`() {
        val lines = GRADING_SYSTEM_PROMPT.trimEnd('\n').split("\n")
        assertThat(lines.takeLast(4)).containsExactly(
            "TRANSCRIPT: <what is actually written, or NOTHING WRITTEN>",
            "SCORE: <a number from 0 to the maximum, or UNREADABLE>",
            "CONFIDENCE: <a number from 0 to 100>",
            "FEEDBACK: <one or two sentences addressed to the student>"
        ).inOrder()
    }

    @Test
    fun `user message matches build_user_message`() {
        val cases = expectations.getAsJsonObject("user_messages")
        assertThat(cases.size()).isAtLeast(5)
        for ((name, case) in cases.entrySet()) {
            val input = case.asJsonObject.getAsJsonObject("in")
            fun count(key: String) = input.get(key)?.asInt ?: 0
            val item = AnswerToGrade(
                answerBoxId = "a1",
                label = input.get("label").asString,
                maxScore = input.get("max_score").asInt,
                questionText = input.get("question_text").asString,
                groundTruthText = input.get("ground_truth_text").asString,
                questionImages = List(count("question_images")) { byteArrayOf(1) },
                groundTruthImages = List(count("ground_truth_images")) { byteArrayOf(2) },
                crops = List(count("crops")) { byteArrayOf(3) }
            )
            assertWithMessage(name).that(buildUserMessage(item))
                .isEqualTo(case.asJsonObject.get("out").asString)
        }
    }

    @Test
    fun `every reply parses exactly as parse_grading_response does`() {
        val cases = expectations.getAsJsonObject("replies")
        assertThat(cases.size()).isAtLeast(20)
        for ((name, case) in cases.entrySet()) {
            val c = case.asJsonObject
            val expected = c.getAsJsonObject("parsed")
            val actual = parseGradingResponse(c.get("text").asString, c.get("max").asInt)

            val expectedScore = expected.get("score").takeUnless { it.isJsonNull }?.asDouble
            assertWithMessage("$name score").that(actual.score).isEqualTo(expectedScore)
            assertWithMessage("$name feedback").that(actual.feedback).isEqualTo(expected.get("feedback").asString)
            assertWithMessage("$name unreadable").that(actual.unreadable).isEqualTo(expected.get("unreadable").asBoolean)
            assertWithMessage("$name parse_error").that(actual.parseError).isEqualTo(expected.get("parse_error").asBoolean)
        }
    }

    @Test
    fun `blank detection matches looks_blank on the server test's own crops`() {
        val cases = expectations.getAsJsonObject("looks_blank")
        assertThat(cases.keySet()).containsExactly(
            "pure_white", "photographed_grey", "faint_short_answer", "full_working"
        )
        for ((name, expected) in cases.entrySet()) {
            val gray = Fixtures.gray("$name.png")
            assertWithMessage(name).that(BlankDetector.looksBlank(gray)).isEqualTo(expected.asBoolean)
        }
    }
}
