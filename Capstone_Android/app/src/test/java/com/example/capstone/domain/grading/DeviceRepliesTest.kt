package com.example.capstone.domain.grading

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Every real Qwen2-VL reply from the session 7 device runs (`grading/device_replies_session7.json`,
 * box 2's answer-key values replaced by placeholders), through the phone's grader as it is now.
 */
class DeviceRepliesTest {

    private val cases: Map<String, JsonObject> =
        JsonParser.parseString(Fixtures.text("device_replies_session7.json")).asJsonObject
            .getAsJsonArray("cases").associate { c -> c.asJsonObject.get("name").asString to c.asJsonObject }

    private fun reply(name: String) = cases.getValue(name).get("reply").asString
    private fun max(name: String) = cases.getValue(name).get("maxScore").asInt

    /** Scripted first reply, and a scripted answer to the follow-up if the grader asks. */
    private class FollowUpModel(private val first: String, private val second: String? = null) : GradingModel {
        override val modelId = "FAKE"
        override val supportsVision = true
        override val maxNumTokens = 4096
        override val imageTokens = 576
        val followUpsAsked = mutableListOf<String>()
        var userText: String? = null

        override suspend fun complete(systemPrompt: String, userText: String, images: List<ByteArray>) =
            error("the grader must use completeWithFollowUp")

        override suspend fun completeWithFollowUp(
            systemPrompt: String,
            userText: String,
            images: List<ByteArray>,
            followUp: (String) -> String?
        ): ModelReplies {
            this.userText = userText
            val ask = followUp(first) ?: return ModelReplies(first, null)
            followUpsAsked += ask
            return ModelReplies(first, second)
        }
    }

    private fun grade(model: GradingModel, maxScore: Int, config: GradingConfig = GradingConfig()) = runBlocking {
        BoxGrader(model, decodeGray = { null }, config = config).grade(
            AnswerToGrade(
                answerBoxId = "b", label = "", maxScore = maxScore, questionText = "q",
                groundTruthText = "m", crops = listOf(byteArrayOf(1))
            )
        )
    }

    @Test
    fun `all six device replies are in the fixture`() {
        assertThat(cases.keys).containsExactly(
            "run1_box1_loop", "run1_box2_blank_recital", "run4_box1", "run4_box2_freeform",
            "run5_box1", "run5_box2_stopped"
        )
    }

    @Test
    fun `run 1 box 1, the loop, is cut by the guard and goes to review without a repair`() {
        val streamed = RunawayGuard.cut(reply("run1_box1_loop"))
        assertThat(RunawayGuard.wasCutOff(streamed)).isTrue()
        val model = FollowUpModel(streamed)
        val result = grade(model, max("run1_box1_loop"))
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(result.reason).isEqualTo(BoxGrader.RUNAWAY)
        assertThat(model.followUpsAsked).isEmpty()
    }

    @Test
    fun `run 1 box 2, the recited model answer, has no mark and earns the repair`() {
        assertThat(BoxGrader.needsRepair(reply("run1_box2_blank_recital"), 15)).isTrue()
    }

    @Test
    fun `run 4 box 1 and run 5 box 1 are graded 5 of 5 with no repair`() {
        for (name in listOf("run4_box1", "run5_box1")) {
            val model = FollowUpModel(reply(name))
            val result = grade(model, max(name))
            assertThat(result.status).isEqualTo(BoxStatus.GRADED)
            assertThat(result.score).isEqualTo(5.0)
            assertThat(result.confidence).isEqualTo(100.0)
            assertThat(model.followUpsAsked).isEmpty()
        }
    }

    @Test
    fun `run 4 box 2, free text to the cap, is marked from the repair`() {
        val model = FollowUpModel(reply("run4_box2_freeform"), "SCORE: 10\nCONFIDENCE: 75")
        val result = grade(model, 15)
        assertThat(model.followUpsAsked).containsExactly(REPAIR_PROMPT)
        assertThat(result.status).isEqualTo(BoxStatus.GRADED)
        assertThat(result.score).isEqualTo(10.0)
        assertThat(result.confidence).isEqualTo(75.0)
        // Both replies are stored for the audit trail.
        assertThat(result.rawReply).isEqualTo(reply("run4_box2_freeform") + BoxGrader.REPAIR_SEPARATOR + "SCORE: 10\nCONFIDENCE: 75")
    }

    @Test
    fun `run 5 box 2, stopped after 20 tokens, is marked from a repair that says 10 of 15`() {
        val result = grade(FollowUpModel(reply("run5_box2_stopped"), "SCORE: 10/15\nCONFIDENCE: 80"), 15)
        assertThat(result.status).isEqualTo(BoxStatus.GRADED)
        assertThat(result.score).isEqualTo(10.0)
    }

    @Test
    fun `a repair that still has no mark leaves the box for review, as before`() {
        val model = FollowUpModel(reply("run5_box2_stopped"), reply("run5_box2_stopped"))
        val result = grade(model, 15)
        assertThat(model.followUpsAsked).hasSize(1)
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(result.reason).isEqualTo("Could not read a valid mark from the model's reply")
    }

    @Test
    fun `a repair above the maximum is not used`() {
        val result = grade(FollowUpModel(reply("run5_box2_stopped"), "SCORE: 16\nCONFIDENCE: 90"), 15)
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(result.score).isNull()
    }

    @Test
    fun `the format reminder is off by default and appended when switched on`() {
        val off = FollowUpModel(reply("run5_box1"))
        grade(off, 5)
        assertThat(off.userText).doesNotContain(PHONE_FORMAT_REMINDER)

        val on = FollowUpModel(reply("run5_box1"))
        grade(on, 5, GradingConfig(formatReminder = true))
        assertThat(on.userText).endsWith(PHONE_FORMAT_REMINDER)
        assertThat(PHONE_FORMAT_REMINDER).contains("add up the marks for all the parts and write only the total")
        assertThat(PHONE_FORMAT_REMINDER).contains("starting with TRANSCRIPT:")
    }
}
