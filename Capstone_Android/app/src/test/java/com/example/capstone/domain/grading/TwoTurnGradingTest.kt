package com.example.capstone.domain.grading

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Two-turn grading (session 9). Neutral values only: no real paper's answers. */
class TwoTurnGradingTest {

    private class FakeModel(replies: List<String>, private val raiseOnCall: Int? = null) : GradingModel {
        override val modelId = "FAKE_MODEL"
        override val supportsVision = true
        override val maxNumTokens = 4096
        override val imageTokens = 280
        private val replies = replies.toMutableList()
        val calls = mutableListOf<Triple<String, String, List<ByteArray>>>()

        override suspend fun complete(systemPrompt: String, userText: String, images: List<ByteArray>): String {
            calls += Triple(systemPrompt, userText, images)
            if (raiseOnCall == calls.size) error("engine went away")
            return replies.removeAt(0)
        }
    }

    private val threePartQuestion =
        "A block of mass \$2 kg\$ is pushed with \$6N\$. a) Find the acceleration. (Marks: 5)" +
            "b) Find the speed after \$2\$ s. (Marks: 5)c)  Find the acceleration with \$12N\n\$.(Marks: 5)"
    private val modelAnswer = "a) acceleration = 3\nb) speed = 6\nc) acceleration = 6"
    private val crop = "PNG".toByteArray() // not an image: "written on"

    private fun item(
        questionText: String = threePartQuestion,
        groundTruthText: String = modelAnswer,
        maxScore: Int = 15
    ) = AnswerToGrade(
        answerBoxId = "b2", label = "", maxScore = maxScore, questionText = questionText,
        groundTruthText = groundTruthText, crops = listOf(crop)
    )

    private fun grade(model: FakeModel, item: AnswerToGrade = item(), threshold: Double = 60.0) = runBlocking {
        BoxGrader(model, Fixtures::decodeGray, GradingConfig(confidenceThreshold = threshold, twoTurn = true)).grade(item)
    }

    // ---- Parts -----------------------------------------------------------------

    @Test
    fun `parts and their marks are read from the question text`() {
        assertThat(QuestionParts.detect(threePartQuestion)).containsExactly(
            QuestionPart("a", 5.0), QuestionPart("b", 5.0), QuestionPart("c", 5.0)
        ).inOrder()
    }

    @Test
    fun `a question without labelled parts is one part`() {
        assertThat(QuestionParts.detect("find x : \$x^2-4=0\$\n\$(Marks: 5)"))
            .containsExactly(QuestionPart(QuestionParts.WHOLE, null))
        assertThat(QuestionParts.detect("Let f(a) = 3 and g(b) = 4. Find f(a) + g(b) (formula)."))
            .containsExactly(QuestionPart(QuestionParts.WHOLE, null))
    }

    @Test
    fun `bracketed labels and marks written as N marks work too`() {
        assertThat(QuestionParts.detect("(a) State the law [2 marks] (b) Use it, 3 marks"))
            .containsExactly(QuestionPart("a", 2.0), QuestionPart("b", 3.0)).inOrder()
    }

    // ---- Turn 1's transcript -------------------------------------------------------

    @Test
    fun `the transcript is split by part, working may run over lines, NOT ANSWERED is seen`() {
        val parts = QuestionParts.detect(threePartQuestion)
        val t = TwoTurnParser.transcript("TRANSCRIPT:\na) p = 6/2\n= 3\nb) q = 0 + 3x2 = 6\nc) NOT ANSWERED", parts)
        assertThat(t.getValue("a").text).isEqualTo("p = 6/2\n= 3")
        assertThat(t.getValue("b").notAnswered).isFalse()
        assertThat(t.getValue("c").notAnswered).isTrue()
        assertThat(TwoTurnParser.transcriptText(parts, t)).isEqualTo("a) p = 6/2\n= 3\nb) q = 0 + 3x2 = 6\nc) NOT ANSWERED")
    }

    @Test
    fun `the student's own label copied after the prompt's is dropped (device reply shape)`() {
        // Session 9, Gemma 4 E2B: "a) a) …", "c) c) UNREADABLE". Values here are neutral.
        val parts = QuestionParts.detect(threePartQuestion)
        val t = TwoTurnParser.transcript("a) a) p = 6/2 = 3\nb) (b) q = 6\nc) c) UNREADABLE", parts)
        assertThat(t.getValue("a").text).isEqualTo("p = 6/2 = 3")
        assertThat(t.getValue("b").text).isEqualTo("q = 6")
        assertThat(t.getValue("c").unreadable).isTrue()
        val n = TwoTurnParser.transcript("a) a) p\nb) b) q\nc) c) NOT ANSWERED", parts)
        assertThat(n.getValue("c").notAnswered).isTrue()
    }

    @Test
    fun `that device reply marks part c 0 and says so`() {
        val model = FakeModel(listOf("a) a) p = 3\nb) b) q = 6\nc) c) UNREADABLE", "PARTS: a=5 b=5 c=0\nCONFIDENCE: 95\nFEEDBACK: Good."))
        val result = grade(model)
        assertThat(result.status).isEqualTo(BoxStatus.GRADED)
        assertThat(result.score).isEqualTo(10.0)
        assertThat(result.feedback).isEqualTo("Good. Part c not answered or not readable (0 marks).")
    }

    @Test
    fun `turn 1 is told NOT ANSWERED for no writing and UNREADABLE only for illegible writing`() {
        assertThat(TRANSCRIBE_SYSTEM_PROMPT).contains("If there is no writing for something, write NOT ANSWERED, not UNREADABLE.")
        assertThat(transcribeUserMessage(threePartQuestion, QuestionParts.detect(threePartQuestion)))
            .contains("Write UNREADABLE only for writing that is there but cannot be made out.")
    }

    @Test
    fun `a one-part transcript is everything after TRANSCRIPT`() {
        val parts = listOf(QuestionPart(QuestionParts.WHOLE, null))
        val t = TwoTurnParser.transcript("TRANSCRIPT: x = 2\nx = -2", parts)
        assertThat(t.getValue(QuestionParts.WHOLE).text).isEqualTo("x = 2\nx = -2")
    }

    // ---- Turn 2's PARTS ------------------------------------------------------------

    @Test
    fun `PARTS needs every part once, nothing else, each within its marks`() {
        val parts = QuestionParts.detect(threePartQuestion)
        assertThat(TwoTurnParser.marks("PARTS: a=5 b=2.5 c=0", parts, 15))
            .isEqualTo(TwoTurnParser.Marks.Valid(mapOf("a" to 5.0, "b" to 2.5, "c" to 0.0)))
        assertThat(TwoTurnParser.marks("PARTS: a=5 b=5", parts, 15)).isInstanceOf(TwoTurnParser.Marks.Invalid::class.java)
        assertThat(TwoTurnParser.marks("PARTS: a=5 b=5 c=5 d=1", parts, 15)).isInstanceOf(TwoTurnParser.Marks.Invalid::class.java)
        assertThat(TwoTurnParser.marks("PARTS: a=5 a=5 c=5", parts, 15)).isInstanceOf(TwoTurnParser.Marks.Invalid::class.java)
        assertThat(TwoTurnParser.marks("PARTS: a=6 b=5 c=0", parts, 15)).isInstanceOf(TwoTurnParser.Marks.Invalid::class.java)
        assertThat(TwoTurnParser.marks("SCORE: 10", parts, 15)).isInstanceOf(TwoTurnParser.Marks.Invalid::class.java)
    }

    @Test
    fun `a one-part PARTS line may be just the number`() {
        val parts = listOf(QuestionPart(QuestionParts.WHOLE, null))
        assertThat(TwoTurnParser.marks("PARTS: answer=4", parts, 5)).isEqualTo(TwoTurnParser.Marks.Valid(mapOf("answer" to 4.0)))
        assertThat(TwoTurnParser.marks("PARTS: 4", parts, 5)).isEqualTo(TwoTurnParser.Marks.Valid(mapOf("answer" to 4.0)))
        assertThat(TwoTurnParser.marks("PARTS: answer=6", parts, 5)).isInstanceOf(TwoTurnParser.Marks.Invalid::class.java)
    }

    @Test
    fun `the early stop takes PARTS in place of SCORE`() {
        assertThat(RunawayGuard.feedbackLineEnd("PARTS: a=1 b=0\nCONFIDENCE: 90\nFEEDBACK: good.\n")).isNotNull()
        assertThat(RunawayGuard.feedbackLineEnd("PARTS:\nCONFIDENCE: 90\nFEEDBACK: good.\n")).isNull()
    }

    // ---- The flow ------------------------------------------------------------------

    @Test
    fun `a partial answer, turn 1 sees no model answer, turn 2 no image, NOT ANSWERED counts 0`() {
        val model = FakeModel(
            listOf(
                "a) p = 6/2 = 3\nb) q = 0 + 3x2 = 6\nc) NOT ANSWERED",
                // The model gives part c marks anyway: the phone does not count them.
                "PARTS: a=5 b=5 c=5\nCONFIDENCE: 95\nFEEDBACK: Parts a and b are right."
            )
        )
        val result = grade(model)

        assertThat(result.status).isEqualTo(BoxStatus.GRADED)
        assertThat(result.score).isEqualTo(10.0)
        assertThat(result.confidence).isEqualTo(95.0)
        assertThat(result.transcript).isEqualTo("a) p = 6/2 = 3\nb) q = 0 + 3x2 = 6\nc) NOT ANSWERED")
        assertThat(result.feedback).isEqualTo("Parts a and b are right. Part c not answered (0 marks).")
        assertThat(result.rawReply).isEqualTo(
            BoxGrader.TURN_1_HEADER + "a) p = 6/2 = 3\nb) q = 0 + 3x2 = 6\nc) NOT ANSWERED" +
                BoxGrader.TURN_2_SEPARATOR + "PARTS: a=5 b=5 c=5\nCONFIDENCE: 95\nFEEDBACK: Parts a and b are right."
        )

        val (sys1, user1, images1) = model.calls[0]
        assertThat(sys1).isEqualTo(TRANSCRIBE_SYSTEM_PROMPT)
        assertThat(images1.single()).isEqualTo(crop)
        assertThat(user1).doesNotContain("acceleration = 3")
        assertThat(user1).doesNotContain("MODEL ANSWER")
        assertThat(user1).contains("a) <exactly what the student wrote for part a, or NOT ANSWERED>")

        val (sys2, user2, images2) = model.calls[1]
        assertThat(sys2).isEqualTo(MARK_SYSTEM_PROMPT)
        assertThat(images2).isEmpty()
        assertThat(user2).contains("MODEL ANSWER:\n$modelAnswer")
        assertThat(user2).contains("TRANSCRIPT OF THE STUDENT'S ANSWER:\na) p = 6/2 = 3")
        assertThat(user2).contains("MARKS PER PART: a) 5, b) 5, c) 5")
        assertThat(user2).contains("PARTS: a=<mark> b=<mark> c=<mark>")
    }

    @Test
    fun `a one-part answer is marked the same way`() {
        val model = FakeModel(listOf("TRANSCRIPT: x = 2", "PARTS: answer=5\nCONFIDENCE: 100\nFEEDBACK: Right."))
        val result = grade(model, item(questionText = "find x : \$2x=4\$ (Marks: 5)", groundTruthText = "x = 2", maxScore = 5))
        assertThat(result.status).isEqualTo(BoxStatus.GRADED)
        assertThat(result.score).isEqualTo(5.0)
        assertThat(result.transcript).isEqualTo("x = 2")
    }

    @Test
    fun `a part left out of the transcript counts 0, turn 2 sees it NOT ANSWERED, the feedback says so`() {
        // Turn 2 gives part c marks anyway; the phone does not count them.
        val model = FakeModel(listOf("a) p = 3\nb) q = 6", "PARTS: a=5 b=4 c=5\nCONFIDENCE: 90\nFEEDBACK: Well done."))
        val result = grade(model)
        assertThat(result.status).isEqualTo(BoxStatus.GRADED)
        assertThat(result.score).isEqualTo(9.0)
        assertThat(result.feedback).isEqualTo("Well done. Part c not answered (0 marks).")
        assertThat(model.calls[1].second).contains("TRANSCRIPT OF THE STUDENT'S ANSWER:\na) p = 3\nb) q = 6\nc) NOT ANSWERED")
        // The posted transcript is turn 1's, as written.
        assertThat(result.transcript).isEqualTo("a) p = 3\nb) q = 6\nc)")
    }

    @Test
    fun `a transcript with no part at all goes to review`() {
        val model = FakeModel(listOf("I can see some working."))
        val result = grade(model)
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(result.reason).isEqualTo("${BoxGrader.TRANSCRIPT_MISSING_PARTS}: a, b, c")
        assertThat(model.calls).hasSize(1)
    }

    @Test
    fun `nothing transcribed although the phone found writing goes to review`() {
        val model = FakeModel(listOf("a) NOT ANSWERED\nb) NOT ANSWERED\nc) NOT ANSWERED"))
        val result = grade(model)
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(result.reason).isEqualTo(BoxGrader.NOTHING_TRANSCRIBED)
        assertThat(model.calls).hasSize(1)
    }

    @Test
    fun `an unreadable part counts 0 when another part was read`() {
        val result = grade(FakeModel(listOf("a) UNREADABLE\nb) q = 6\nc) NOT ANSWERED", "PARTS: a=3 b=5 c=0\nCONFIDENCE: 90\nFEEDBACK: ok")))
        assertThat(result.status).isEqualTo(BoxStatus.GRADED)
        assertThat(result.score).isEqualTo(5.0)
        assertThat(result.feedback).isEqualTo("ok Part a not answered or not readable (0 marks). Part c not answered (0 marks).")
    }

    @Test
    fun `every part unreadable, or a one-part answer unreadable, goes to review`() {
        assertThat(grade(FakeModel(listOf("a) UNREADABLE\nb) UNREADABLE\nc) NOT ANSWERED"))).reason)
            .isEqualTo("Model could not read the answer")
        val one = grade(
            FakeModel(listOf("TRANSCRIPT: UNREADABLE")),
            item(questionText = "find x (Marks: 5)", groundTruthText = "x = 2", maxScore = 5)
        )
        assertThat(one.reason).isEqualTo("Model could not read the answer")
    }

    // ---- Session 9c device run (submission 430c109c-…, Gemma 4 E2B, two-turn) --------
    // Turn 1's replies verbatim, with the answer values as placeholders <…>.

    @Test
    fun `device run box 2, c) UNREADABLE for the empty part, now 10 of 15 with a note`() {
        val turn1 = """a) ${'$'}a = \frac{F}{m} = \frac{<F1>}{<M1>} = <A1> \text{ m s}^{-2}${'$'}
b) ${'$'}v = u + at = 0 + <A1> \times <T1> = <V1> \text{ m s}^{-1}${'$'}
c) UNREADABLE"""
        // Turn 2 was never reached on device (the old rule sent the box to review); a plausible reply.
        val model = FakeModel(listOf(turn1, "PARTS: a=5 b=5 c=0\nCONFIDENCE: 95\nFEEDBACK: Parts a and b are correct."))
        val result = grade(model)
        assertThat(result.status).isEqualTo(BoxStatus.GRADED)
        assertThat(result.score).isEqualTo(10.0)
        assertThat(result.feedback).isEqualTo("Parts a and b are correct. Part c not answered or not readable (0 marks).")
        assertThat(model.calls[1].second).contains("\nc) NOT ANSWERED\n")
        assertThat(model.calls[1].second).contains("b) \$v = u + at")
    }

    @Test
    fun `device run box 1, one part, the printed label read as the first word, still 5 of 5`() {
        val turn1 = """TRANSCRIPT: answer
x^2-2x+1=0
${'$'}\Rightarrow${'$'} x^2-2.x+1^2=0
${'$'}\Rightarrow${'$'} (x-1)^2=0
${'$'}\Rightarrow${'$'} x=1"""
        val model = FakeModel(listOf(turn1, "PARTS: answer=5\nCONFIDENCE: 100\nFEEDBACK: You correctly solved the quadratic equation."))
        val result = grade(model, item(questionText = "find x : \$x^2-2x+1=0\$\n\$(Marks: 5)", groundTruthText = "<MODEL1>", maxScore = 5))
        assertThat(result.status).isEqualTo(BoxStatus.GRADED)
        assertThat(result.score).isEqualTo(5.0)
        assertThat(result.feedback).isEqualTo("You correctly solved the quadratic equation.")
    }

    @Test
    fun `unreadable PARTS goes to review with turn 2's feedback kept`() {
        val model = FakeModel(listOf("a) p = 3\nb) q = 6\nc) r = 6", "SCORE: 15\nCONFIDENCE: 90\nFEEDBACK: fine"))
        val result = grade(model)
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(result.reason).startsWith(BoxGrader.PARTS_UNREADABLE)
        assertThat(result.feedback).isEqualTo("fine")
    }

    @Test
    fun `parts adding up to more than the box goes to review`() {
        // No marks stated per part, so each may go up to the box's 5; together they may not.
        val model = FakeModel(listOf("a) p = 3\nb) q = 6", "PARTS: a=4 b=4\nCONFIDENCE: 90\nFEEDBACK: ok"))
        val result = grade(model, item(questionText = "a) Find p. b) Find q.", maxScore = 5))
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(result.reason).isEqualTo("The parts add up to 8, more than the box's 5 marks")
    }

    @Test
    fun `low or missing confidence goes to review, as in one turn`() {
        val low = grade(FakeModel(listOf("a) p\nb) q\nc) r", "PARTS: a=1 b=1 c=1\nCONFIDENCE: 40\nFEEDBACK: ok")))
        assertThat(low.reason).isEqualTo("The model's CONFIDENCE 40 is below 60")
        assertThat(low.confidence).isEqualTo(40.0)
        val none = grade(FakeModel(listOf("a) p\nb) q\nc) r", "PARTS: a=1 b=1 c=1\nFEEDBACK: ok")))
        assertThat(none.reason).isEqualTo("The model's reply has no CONFIDENCE line")
    }

    @Test
    fun `a model answer with no text cannot be marked in two turns, and no call is made`() {
        val model = FakeModel(emptyList())
        val result = grade(model, item(groundTruthText = ""))
        assertThat(result.reason).isEqualTo(BoxGrader.NO_TYPED_MODEL_ANSWER)
        assertThat(model.calls).isEmpty()
    }

    @Test
    fun `a failed second call keeps turn 1 in the raw reply`() {
        val model = FakeModel(listOf("a) p\nb) q\nc) r"), raiseOnCall = 2)
        val result = grade(model)
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(result.reason).isEqualTo("${BoxGrader.REQUEST_FAILED}: engine went away")
        assertThat(result.rawReply).isEqualTo(BoxGrader.TURN_1_HEADER + "a) p\nb) q\nc) r")
        assertThat(result.transcript).isEqualTo("a) p\nb) q\nc) r")
    }

    @Test
    fun `one turn is still the default`() {
        assertThat(GradingConfig().twoTurn).isFalse()
    }
}
