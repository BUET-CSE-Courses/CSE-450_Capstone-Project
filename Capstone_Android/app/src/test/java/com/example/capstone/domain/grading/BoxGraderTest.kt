package com.example.capstone.domain.grading

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The per-box engine. First the grade_one cases from
 * Script-Checker-Web-End/backend/tests/test_grading.py (c69eea2), then the
 * phone's own: CONFIDENCE, fallback and the token budget.
 *
 * The server's replies carry no CONFIDENCE line. Where a server test expects a
 * mark, the port adds `CONFIDENCE: 90`; the same reply without it is its own
 * test, and goes to fallback.
 */
class BoxGraderTest {

    /** The server test's FakeProvider: scripted replies, or raises if told to. */
    private class FakeModel(
        replies: List<String> = emptyList(),
        private val raises: Throwable? = null,
        override val supportsVision: Boolean = true,
        override val maxNumTokens: Int = 4096,
        override val imageTokens: Int = 576
    ) : GradingModel {
        override val modelId = "FAKE_MODEL"
        private val replies = replies.toMutableList()
        val calls = mutableListOf<Triple<String, String, List<ByteArray>>>()

        override suspend fun complete(systemPrompt: String, userText: String, images: List<ByteArray>): String {
            calls += Triple(systemPrompt, userText, images)
            raises?.let { throw it }
            return if (replies.isNotEmpty()) replies.removeAt(0) else "SCORE: 1\nFEEDBACK: ok\nCONFIDENCE: 90"
        }
    }

    private var now = 1_000L

    private fun grader(model: GradingModel, threshold: Double = 60.0) = BoxGrader(
        model = model,
        decodeGray = Fixtures::decodeGray,
        config = GradingConfig(confidenceThreshold = threshold),
        clock = { now.also { now += 7 } }
    )

    /** Bytes that are not an image: looks_blank cannot open them, so "written on". */
    private val notAnImage = "PNG".toByteArray()

    private fun item(
        maxScore: Int = 5,
        crops: List<ByteArray> = listOf(notAnImage),
        groundTruthText: String = "x = 3",
        questionImages: List<ByteArray> = emptyList(),
        groundTruthImages: List<ByteArray> = emptyList(),
        blockedReason: String? = null
    ) = AnswerToGrade(
        answerBoxId = "a1",
        label = "a",
        maxScore = maxScore,
        questionText = "Solve 2x+4=10",
        groundTruthText = groundTruthText,
        questionImages = questionImages,
        groundTruthImages = groundTruthImages,
        crops = crops,
        blockedReason = blockedReason
    )

    private fun grade(model: GradingModel, item: AnswerToGrade = item(), threshold: Double = 60.0) =
        runBlocking { grader(model, threshold).grade(item) }

    // ---- Ported from test_grading.py ---------------------------------------

    @Test
    fun `test_grades_an_answer`() {
        val model = FakeModel(listOf("SCORE: 4\nFEEDBACK: Correct approach.\nCONFIDENCE: 90"))
        val result = grade(model)
        assertThat(result.score).isEqualTo(4.0)
        assertThat(result.status).isEqualTo(BoxStatus.GRADED)
    }

    @Test
    fun `test_answer_with_no_model_answer_is_sent_for_manual_review`() {
        val model = FakeModel()
        val result = grade(
            model,
            item(groundTruthText = "", blockedReason = "No model answer is paired with this answer box")
        )
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_REVIEW)
        assertThat(result.score).isNull()
        assertThat(result.reason).isEqualTo("No model answer is paired with this answer box")
        assertThat(model.calls).isEmpty() // never wastes a model call on it
    }

    @Test
    fun `test_answer_with_no_extracted_crop_is_sent_for_manual_review`() {
        // The phone flags it for fallback instead: the server may have a crop.
        val model = FakeModel()
        val result = grade(model, item(crops = emptyList()))
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(result.reason).isEqualTo("No extracted answer image for this box")
        assertThat(model.calls).isEmpty()
    }

    @Test
    fun `test_a_failing_model_call_does_not_raise`() {
        val result = grade(FakeModel(raises = RuntimeException("connection reset")))
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(result.reason).contains("connection reset")
        assertThat(result.reason).startsWith("Grading request failed: ")
        assertThat(result.modelId).isEqualTo("FAKE_MODEL")
    }

    @Test
    fun `test_prompt_says_which_images_are_the_model_answer`() {
        val model = FakeModel()
        val gt = byteArrayOf(71, 84)
        val s1 = byteArrayOf(83, 49)
        val s2 = byteArrayOf(83, 50)
        grade(model, item(groundTruthText = "", groundTruthImages = listOf(gt), crops = listOf(s1, s2)))

        val (system, user, images) = model.calls.single()
        assertThat(system).isEqualTo(GRADING_SYSTEM_PROMPT)
        assertThat(images).containsExactly(gt, s1, s2).inOrder()
        assertThat(user).contains("first 1 image(s) are the official MODEL ANSWER")
        assertThat(user).contains("remaining 2 image(s) are the STUDENT'S")
    }

    @Test
    fun `test_a_question_figure_is_sent_and_named`() {
        val model = FakeModel()
        val fig = byteArrayOf(70)
        val gt = byteArrayOf(71)
        val s1 = byteArrayOf(83)
        grade(
            model,
            // The server test's model answer has text too; on the phone a model answer with text
            // is sent as text only (BoxGrader.withoutRenderedModelAnswer), so this one is image-only.
            item(groundTruthText = "", questionImages = listOf(fig), groundTruthImages = listOf(gt), crops = listOf(s1))
        )

        val (_, user, images) = model.calls.single()
        assertThat(images).containsExactly(fig, gt, s1).inOrder()
        assertThat(user).contains("first 1 image(s) are figures belonging to the QUESTION")
        assertThat(user).contains("next 1 image(s) are the official MODEL ANSWER")
        assertThat(user).contains("remaining 1 image(s) are the STUDENT'S")
    }

    @Test
    fun `test_blank_answer_scores_zero_without_calling_the_model`() {
        val model = FakeModel(listOf("SCORE: 3\nFEEDBACK: nice working\nCONFIDENCE: 99"))
        val result = grade(model, item(crops = listOf(Fixtures.bytes("pure_white.png"))))

        assertThat(result.score).isEqualTo(0.0)
        assertThat(result.status).isEqualTo(BoxStatus.BLANK) // unattempted is a real mark
        assertThat(result.feedback).isEqualTo("Nothing was written in this answer box.")
        assertThat(result.reason).isNull()
        assertThat(result.rawReply).isNull()
        assertThat(result.modelId).isNull()
        assertThat(model.calls).isEmpty() // and costs nothing
    }

    @Test
    fun `test_a_written_answer_still_reaches_the_model`() {
        val model = FakeModel(listOf("SCORE: 4\nFEEDBACK: good\nCONFIDENCE: 80"))
        val result = grade(model, item(crops = listOf(Fixtures.png(listOf("2x = 6", "x = 3")))))
        assertThat(result.score).isEqualTo(4.0)
        assertThat(model.calls).hasSize(1)
    }

    // ---- Blank across parts --------------------------------------------------

    @Test
    fun `a box is blank only when every part is blank`() {
        val white = Fixtures.bytes("pure_white.png")
        val written = Fixtures.bytes("faint_short_answer.png")
        val model = FakeModel()
        assertThat(grade(model, item(crops = listOf(white, white))).status).isEqualTo(BoxStatus.BLANK)
        assertThat(grade(model, item(crops = listOf(white, written))).status).isEqualTo(BoxStatus.GRADED)
        assertThat(model.calls).hasSize(1)
    }

    @Test
    fun `a decoder that throws counts as written on`() {
        val model = FakeModel()
        val result = runBlocking {
            BoxGrader(model, decodeGray = { error("boom") }).grade(item())
        }
        assertThat(result.status).isEqualTo(BoxStatus.GRADED)
        assertThat(model.calls).hasSize(1)
    }

    // ---- Replies -------------------------------------------------------------

    @Test
    fun `normal reply is graded with every field filled in`() {
        val raw = "TRANSCRIPT: 2x = 6, x = 3\nSCORE: 4.5\nFEEDBACK: Correct; show the check.\nCONFIDENCE: 85"
        val result = grade(FakeModel(listOf(raw)))

        assertThat(result).isEqualTo(
            BoxGradeResult(
                answerBoxId = "a1",
                score = 4.5,
                maxScore = 5,
                transcript = "2x = 6, x = 3",
                feedback = "Correct; show the check.",
                confidence = 85.0,
                status = BoxStatus.GRADED,
                reason = null,
                rawReply = raw,
                modelId = "FAKE_MODEL",
                durationMs = 7
            )
        )
    }

    @Test
    fun `NOTHING WRITTEN from the model is a graded zero`() {
        val result = grade(FakeModel(listOf("TRANSCRIPT: NOTHING WRITTEN\nSCORE: 0\nFEEDBACK: -\nCONFIDENCE: 95")))
        assertThat(result.status).isEqualTo(BoxStatus.GRADED)
        assertThat(result.score).isEqualTo(0.0)
        assertThat(result.feedback).isEqualTo(NOTHING_WRITTEN_FEEDBACK)
        assertThat(result.transcript).isEqualTo("NOTHING WRITTEN")
    }

    @Test
    fun `NOTHING WRITTEN overrides a score the model gave anyway`() {
        val result = grade(FakeModel(listOf("TRANSCRIPT: nothing written\nSCORE: 3\nFEEDBACK: ok\nCONFIDENCE: 95")))
        assertThat(result.score).isEqualTo(0.0)
    }

    @Test
    fun `UNREADABLE goes to fallback with the server's reason`() {
        val raw = "TRANSCRIPT: scribbles\nSCORE: UNREADABLE\nFEEDBACK: Too faint.\nCONFIDENCE: 90"
        val result = grade(FakeModel(listOf(raw)))
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(result.reason).isEqualTo("Model could not read the answer")
        assertThat(result.score).isNull()
        assertThat(result.feedback).isEqualTo("Too faint.")
        assertThat(result.rawReply).isEqualTo(raw)
    }

    @Test
    fun `malformed reply goes to fallback with the server's reason`() {
        for (raw in listOf("I think this deserves about 4 marks", "SCORE: banana\nFEEDBACK: hmm", "")) {
            val result = grade(FakeModel(listOf(raw)))
            assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
            assertThat(result.reason).isEqualTo("Could not read a valid mark from the model's reply")
            assertThat(result.score).isNull()
        }
    }

    @Test
    fun `score above the maximum goes to fallback, never clamped`() {
        val result = grade(FakeModel(listOf("SCORE: 9\nFEEDBACK: great\nCONFIDENCE: 99")))
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(result.score).isNull()
        assertThat(result.reason).isEqualTo("Could not read a valid mark from the model's reply")
    }

    @Test
    fun `negative score goes to fallback`() {
        val result = grade(FakeModel(listOf("SCORE: -1\nFEEDBACK: bad\nCONFIDENCE: 99")))
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(result.score).isNull()
    }

    @Test
    fun `missing sections`() {
        // No SCORE: a parse error.
        grade(FakeModel(listOf("TRANSCRIPT: x = 3\nFEEDBACK: ok\nCONFIDENCE: 90"))).let {
            assertThat(it.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
            assertThat(it.reason).isEqualTo("Could not read a valid mark from the model's reply")
        }
        // No FEEDBACK or TRANSCRIPT: marked, as the server marks it.
        grade(FakeModel(listOf("SCORE: 3\nCONFIDENCE: 90"))).let {
            assertThat(it.status).isEqualTo(BoxStatus.GRADED)
            assertThat(it.score).isEqualTo(3.0)
            assertThat(it.feedback).isEmpty()
            assertThat(it.transcript).isNull()
        }
    }

    // ---- CONFIDENCE -------------------------------------------------------------

    @Test
    fun `missing CONFIDENCE goes to fallback`() {
        val result = grade(FakeModel(listOf("SCORE: 4\nFEEDBACK: Correct approach.")))
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(result.reason).isEqualTo("The model's reply has no CONFIDENCE line")
        assertThat(result.score).isNull()
        assertThat(result.confidence).isNull()
    }

    @Test
    fun `low CONFIDENCE goes to fallback`() {
        val result = grade(FakeModel(listOf("SCORE: 4\nFEEDBACK: ok\nCONFIDENCE: 59")))
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(result.reason).isEqualTo("The model's CONFIDENCE 59 is below 60")
        assertThat(result.confidence).isEqualTo(59.0)
        assertThat(result.score).isNull()
    }

    @Test
    fun `CONFIDENCE exactly at the threshold is graded`() {
        assertThat(grade(FakeModel(listOf("SCORE: 4\nCONFIDENCE: 60"))).status).isEqualTo(BoxStatus.GRADED)
    }

    @Test
    fun `the threshold is configurable`() {
        val reply = "SCORE: 4\nFEEDBACK: ok\nCONFIDENCE: 75"
        assertThat(grade(FakeModel(listOf(reply)), threshold = 80.0).status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(grade(FakeModel(listOf(reply)), threshold = 70.0).status).isEqualTo(BoxStatus.GRADED)
        assertThat(GradingConfig().confidenceThreshold).isEqualTo(60.0)
    }

    @Test
    fun `CONFIDENCE out of range goes to fallback`() {
        grade(FakeModel(listOf("SCORE: 4\nFEEDBACK: ok\nCONFIDENCE: 101"))).let {
            assertThat(it.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
            assertThat(it.reason).isEqualTo("The model's CONFIDENCE 101 is outside 0..100")
            assertThat(it.confidence).isNull()
        }
        grade(FakeModel(listOf("SCORE: 4\nFEEDBACK: ok\nCONFIDENCE: -3"))).let {
            assertThat(it.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        }
    }

    @Test
    fun `CONFIDENCE that is not a number goes to fallback`() {
        val result = grade(FakeModel(listOf("SCORE: 4\nFEEDBACK: ok\nCONFIDENCE: very sure")))
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(result.reason).isEqualTo("The model's CONFIDENCE is not a number: \"very sure\"")
    }

    // ---- Model and token budget -------------------------------------------------

    @Test
    fun `a text-only model is never called`() {
        val model = FakeModel(supportsVision = false)
        val result = grade(model)
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(result.reason).isEqualTo("The active model (FAKE_MODEL) cannot read images")
        assertThat(model.calls).isEmpty()
    }

    @Test
    fun `over-budget images drop question figures first, then model-answer images, never crops`() {
        // 4096 tokens, 576 per image, about 1000 for text and reply: room for
        // five images. Two crops + three figures + two model-answer images = 7.
        val model = FakeModel()
        val figures = List(3) { byteArrayOf(70, it.toByte()) }
        val answers = List(2) { byteArrayOf(71, it.toByte()) }
        val crops = List(2) { byteArrayOf(83, it.toByte()) }
        grade(model, item(groundTruthText = "", questionImages = figures, groundTruthImages = answers, crops = crops))

        val (_, user, images) = model.calls.single()
        // Two figures dropped (last first), one kept; both model answers kept.
        assertThat(images).containsExactly(figures[0], answers[0], answers[1], crops[0], crops[1]).inOrder()
        assertThat(user).contains("the first 1 image(s) are figures belonging to the QUESTION")
        assertThat(user).contains("the next 2 image(s) are the official MODEL ANSWER")
        assertThat(user).contains("the remaining 2 image(s) are the STUDENT'S")
    }

    @Test
    fun `model-answer images go once every figure is gone`() {
        val model = FakeModel()
        val figures = List(2) { byteArrayOf(70, it.toByte()) }
        val answers = List(3) { byteArrayOf(71, it.toByte()) }
        val crops = List(3) { byteArrayOf(83, it.toByte()) }
        grade(model, item(groundTruthText = "", questionImages = figures, groundTruthImages = answers, crops = crops))

        val (_, user, images) = model.calls.single()
        assertThat(images).containsExactly(answers[0], answers[1], crops[0], crops[1], crops[2]).inOrder()
        assertThat(user).contains("the first 2 image(s) are the official MODEL ANSWER")
    }

    @Test
    fun `crops alone over budget goes to fallback without a call`() {
        val model = FakeModel()
        val result = grade(model, item(crops = List(7) { byteArrayOf(83, it.toByte()) }))
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(result.reason).startsWith("Too large for the phone's model: about ")
        assertThat(result.reason).contains("7 student image(s)")
        assertThat(model.calls).isEmpty()
    }

    @Test
    fun `an image-only model answer is never dropped to nothing`() {
        // Five crops fit alone; with one model-answer image they do not. The
        // model answer is text-less, so it cannot go: fallback, no call.
        val model = FakeModel()
        val result = grade(
            model,
            item(groundTruthText = "", groundTruthImages = listOf(byteArrayOf(71)), crops = List(5) { byteArrayOf(83, it.toByte()) })
        )
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(result.reason).contains("the model answer is only an image")
        assertThat(model.calls).isEmpty()
    }

    @Test
    fun `budget arithmetic is images times imageTokens plus text plus reply`() {
        val budget = TokenBudget(maxNumTokens = 4096, imageTokens = 576, replyReserveTokens = 300)
        val one = item()
        val text = TokenBudget.estimateTextTokens(GRADING_SYSTEM_PROMPT) +
            TokenBudget.estimateTextTokens(buildUserMessage(one))
        assertThat(budget.estimate(one)).isEqualTo(576 + text + 300)
        assertThat(TokenBudget.estimateTextTokens("abcd")).isEqualTo(2)
        assertThat(TokenBudget.estimateTextTokens("")).isEqualTo(0)
    }

    // ---- Session 7: printed label and runaway replies ------------------------------

    @Test
    fun `a photographed blank box with its label left out is BLANK without a model call`() {
        val model = FakeModel()
        val photo = Fixtures.bytes("phone_blank_box_with_label.png")
        val label = PrintedLabel.region("", 0, 1, 934, 781, 150)
        val result = grade(
            model,
            AnswerToGrade(
                answerBoxId = "a2", label = "", maxScore = 15, questionText = "q", groundTruthText = "m",
                crops = listOf(photo), cropLabelRegions = listOf(label)
            )
        )
        assertThat(result.status).isEqualTo(BoxStatus.BLANK)
        assertThat(result.score).isEqualTo(0.0)
        assertThat(model.calls).isEmpty()
    }

    @Test
    fun `without the label region the same photo goes to the model, as on the server`() {
        val model = FakeModel()
        grade(
            model,
            AnswerToGrade(
                answerBoxId = "a2", label = "", maxScore = 15, questionText = "q", groundTruthText = "m",
                crops = listOf(Fixtures.bytes("phone_blank_box_with_label.png"))
            )
        )
        assertThat(model.calls).hasSize(1)
    }

    @Test
    fun `a reply cut off before its CONFIDENCE goes to fallback, even with a SCORE before the loop`() {
        val cut = "TRANSCRIPT: x = 1\nSCORE: 5\nFEEDBACK: The student has " +
            "correctly applied the method to find the solutions, and also then  " + RunawayGuard.CUT_MARKER
        val result = grade(FakeModel(listOf(cut)))
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(result.reason).isEqualTo(BoxGrader.RUNAWAY)
        assertThat(result.rawReply).isEqualTo(cut)
        assertThat(result.feedback).doesNotContain("cut off by the app")
    }

    @Test
    fun `a loop inside FEEDBACK, after SCORE and CONFIDENCE, leaves the mark standing`() {
        val clause = "correctly applied the method to find the solutions, and also then  "
        val cut = "TRANSCRIPT: x = 1\nSCORE: 4\nCONFIDENCE: 80\nFEEDBACK: The student has " + clause +
            RunawayGuard.CUT_MARKER
        val result = grade(FakeModel(listOf(cut)))
        assertThat(result.status).isEqualTo(BoxStatus.GRADED)
        assertThat(result.score).isEqualTo(4.0)
        assertThat(result.confidence).isEqualTo(80.0)
        assertThat(result.feedback).isEqualTo("The student has ${clause.trim()}")
        assertThat(result.rawReply).isEqualTo(cut)
    }

    @Test
    fun `a reply in the phone's order parses like the server's`() {
        val result = grade(FakeModel(listOf("TRANSCRIPT: x = 3\nSCORE: 5\nCONFIDENCE: 85\nFEEDBACK: Correct.")))
        assertThat(result.status).isEqualTo(BoxStatus.GRADED)
        assertThat(result.score).isEqualTo(5.0)
        assertThat(result.confidence).isEqualTo(85.0)
        assertThat(result.transcript).isEqualTo("x = 3")
        assertThat(result.feedback).isEqualTo("Correct.")
    }

    /**
     * Session 7, box 2 (15 marks, parts a and b answered): Qwen2-VL's real reply, with the
     * model answer's values replaced by placeholders (CLAUDE.md: no answer keys in the repo).
     * Free text per part, "in the first/second/third image", part (c) taken from the model
     * answer's picture, cut at the 300-token cap: no SCORE line anywhere.
     */
    private val box2Reply = Fixtures.text("qwen_box2_freeform_reply_redacted.txt")

    @Test
    fun `the session 7 box 2 reply has no mark to read and goes to fallback`() {
        assertThat(box2Reply).startsWith("a) The student wrote:")
        assertThat(box2Reply).contains("in the third image")
        assertThat(box2Reply).doesNotContainMatch("(?im)^\\s*SCORE:")
        val result = grade(FakeModel(listOf(box2Reply)), item(maxScore = 15))
        assertThat(result.status).isEqualTo(BoxStatus.NEEDS_FALLBACK)
        assertThat(result.reason).isEqualTo("Could not read a valid mark from the model's reply")
        assertThat(result.rawReply).isEqualTo(box2Reply)
    }

    @Test
    fun `a model answer with text is sent as text only, so its picture is not read as the student's work`() {
        val crop = byteArrayOf(83)
        val rendered = byteArrayOf(71)
        val figure = byteArrayOf(81)
        val model = FakeModel()
        grade(
            model,
            item(
                groundTruthText = "a. 7 b. 3 c. 2",
                groundTruthImages = listOf(rendered),
                questionImages = listOf(figure),
                crops = List(1) { crop }
            )
        )
        val (_, userText, images) = model.calls.single()
        assertThat(images.map { it.toList() }).containsExactly(figure.toList(), crop.toList()).inOrder()
        // The user message describes exactly the images sent: none for the model answer.
        val expected = buildUserMessage(
            item(groundTruthText = "a. 7 b. 3 c. 2", questionImages = listOf(figure), crops = List(1) { crop })
        )
        assertThat(userText).isEqualTo(expected)
    }

    @Test
    fun `a model answer that is only a picture still sends the picture`() {
        val rendered = byteArrayOf(71)
        val model = FakeModel()
        grade(model, item(groundTruthText = "", groundTruthImages = listOf(rendered), crops = listOf(byteArrayOf(83))))
        assertThat(model.calls.single().third.map { it.toList() }).contains(rendered.toList())
    }

    @Test
    fun `the model gets each crop cut to its writing, the saved crop is untouched`() {
        val written = Fixtures.png(listOf("a) a = F/m"), width = 900, height = 800)
        val original = written.copyOf()
        val cuts = mutableListOf<BlankDetector.PixelRect>()
        val model = FakeModel()
        val grader = BoxGrader(
            model = model,
            decodeGray = Fixtures::decodeGray,
            config = GradingConfig(trimCrops = true),
            cropTo = { _, plan -> cuts += plan.cut; byteArrayOf(9, 9) }
        )
        val item = item(crops = listOf(written))
        runBlocking { grader.grade(item) }

        val rect = cuts.single()
        assertThat(rect.bottom).isLessThan(800 / 2)
        assertThat(model.calls.single().third.last().toList()).containsExactly(9.toByte(), 9.toByte()).inOrder()
        assertThat(item.crops.single()).isEqualTo(original)
    }

    @Test
    fun `a crop that cannot be cut goes whole`() {
        val written = Fixtures.png(listOf("a) a = F/m"), width = 900, height = 800)
        val model = FakeModel()
        runBlocking {
            BoxGrader(model, Fixtures::decodeGray, GradingConfig(trimCrops = true), cropTo = { _, _ -> null })
                .grade(item(crops = listOf(written)))
        }
        assertThat(model.calls.single().third.last()).isEqualTo(written)
    }

    @Test
    fun `crops go whole while trimming is off, the default`() {
        val written = Fixtures.png(listOf("a) a = F/m"), width = 900, height = 800)
        val model = FakeModel()
        var cut = false
        runBlocking {
            BoxGrader(model, Fixtures::decodeGray, cropTo = { _, _ -> cut = true; byteArrayOf(9) })
                .grade(item(crops = listOf(written)))
        }
        assertThat(cut).isFalse()
        assertThat(model.calls.single().third.last()).isEqualTo(written)
    }

    // ---- Timing -----------------------------------------------------------------

    @Test
    fun `durationMs comes from the clock and is set on every outcome`() {
        assertThat(grade(FakeModel(), item(blockedReason = "x")).durationMs).isEqualTo(7)
        assertThat(grade(FakeModel(), item(crops = emptyList())).durationMs).isEqualTo(7)
    }
}
