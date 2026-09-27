package com.example.capstone.domain.grading

import kotlinx.coroutines.CancellationException

/** Tunables for [BoxGrader]. */
data class GradingConfig(
    /** A reply with CONFIDENCE below this goes to fallback. 0..100. */
    val confidenceThreshold: Double = DEFAULT_CONFIDENCE_THRESHOLD,
    val replyReserveTokens: Int = TokenBudget.DEFAULT_REPLY_RESERVE_TOKENS,
    /**
     * Append [PHONE_FORMAT_REMINDER] to the user message. OFF: on device (session 7) the same
     * box 1 image that scored 5/5 twice got a reply that stopped after TRANSCRIPT once this was on.
     */
    val formatReminder: Boolean = false,
    /**
     * Send the model each crop cut to its writing, label painted out ([CropTrim]). OFF by
     * default; a debug toggle (session 9). The session 7 version (the bare
     * [BlankDetector.writtenArea] strip, label kept) got "[no handwriting]" for box 2 in the
     * same run as [formatReminder], so the two could not be told apart.
     */
    val trimCrops: Boolean = false,
    /**
     * Mark in two conversations ([TRANSCRIBE_SYSTEM_PROMPT], then [MARK_SYSTEM_PROMPT]; see
     * TwoTurnGrading.kt). Needs a typed model answer. False here, but real grading turns it on
     * ([com.example.capstone.data.local.GradingDebugSettings]; the user's decision, session 9b);
     * plain is a debug option.
     */
    val twoTurn: Boolean = false
) {
    companion object {
        const val DEFAULT_CONFIDENCE_THRESHOLD = 60.0
    }
}

/**
 * Marks one answer box. The phone's `grade_one` (grading.py:418-487), in the
 * same order:
 *
 * 1. blocked by the pack: [BoxStatus.NEEDS_REVIEW], no model call;
 * 2. no crop: [BoxStatus.NEEDS_FALLBACK] (the server may have cropped it);
 * 3. every crop blank: [BoxStatus.BLANK], score 0, no model call;
 * 4. one model call with [GRADING_SYSTEM_PROMPT] and [phoneUserMessage];
 * 5. [parseReply]. UNREADABLE, a parse error, an out-of-range score, or a
 *    missing, invalid or low CONFIDENCE: [BoxStatus.NEEDS_FALLBACK].
 *
 * The phone adds two checks the server does not need, both before the call:
 * a text-only model, and the token budget ([TokenBudget]). It sends no picture
 * of a model answer that has text ([withoutRenderedModelAnswer]), and, when
 * [GradingConfig.trimCrops] is on, each crop trimmed by [CropTrim].
 *
 * One repair turn (session 7, phone only): a reply with no readable SCORE that
 * was not a runaway gets [REPAIR_PROMPT] once, in the same conversation. Its
 * SCORE and CONFIDENCE are then used; still nothing, and the box goes to review.
 *
 * @param decodeGray decodes a crop for [BlankDetector]; null when it cannot.
 * @param clock milliseconds, for [BoxGradeResult.durationMs].
 */
class BoxGrader(
    private val model: GradingModel,
    private val decodeGray: (ByteArray) -> GrayImage?,
    private val config: GradingConfig = GradingConfig(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /**
     * Applies a [CropTrim.Plan] to a crop, for the model's input only; null leaves the crop
     * whole.
     */
    private val cropTo: (ByteArray, CropTrim.Plan) -> ByteArray? = { _, _ -> null }
) : GradingService {

    override suspend fun grade(item: AnswerToGrade): BoxGradeResult {
        val startedAt = clock()

        fun result(
            status: BoxStatus,
            reason: String?,
            score: Double? = null,
            transcript: String? = null,
            feedback: String? = null,
            confidence: Double? = null,
            rawReply: String? = null,
            modelId: String? = null
        ) = BoxGradeResult(
            answerBoxId = item.answerBoxId,
            score = score,
            maxScore = item.maxScore,
            transcript = transcript,
            feedback = feedback,
            confidence = confidence,
            status = status,
            reason = reason,
            rawReply = rawReply,
            modelId = modelId,
            durationMs = clock() - startedAt
        )

        item.blockedReason?.let { return result(BoxStatus.NEEDS_REVIEW, it) }

        if (item.crops.isEmpty()) {
            return result(BoxStatus.NEEDS_FALLBACK, NO_CROP)
        }

        // Settled before any model call: the model cannot be trusted to notice
        // a blank box, and an unattempted answer is a real 0. The printed label
        // is left out: on a phone photo it alone was enough to count as ink.
        val grays = item.crops.map { grayOrNull(it) }
        if (grays.withIndex().all { (i, gray) -> BlankDetector.looksBlank(gray, item.cropLabelRegions.getOrNull(i)) }) {
            return result(BoxStatus.BLANK, null, score = 0.0, feedback = NOTHING_WRITTEN_FEEDBACK)
        }

        if (!model.supportsVision) {
            return result(BoxStatus.NEEDS_FALLBACK, "The active model (${model.modelId}) cannot read images")
        }

        val fitted = when (
            val fit = TokenBudget(model.maxNumTokens, model.imageTokens, config.replyReserveTokens)
                .fit(withoutRenderedModelAnswer(item).let { if (config.trimCrops) trimmedToWriting(it, grays) else it })
        ) {
            is TokenBudget.Fit.OverBudget -> return result(BoxStatus.NEEDS_FALLBACK, fit.reason)
            is TokenBudget.Fit.Fits -> fit.item
        }

        if (config.twoTurn) return gradeTwoTurn(item, fitted, startedAt)

        val replies = try {
            val userText = if (config.formatReminder) phoneUserMessage(fitted) else buildUserMessage(fitted)
            model.completeWithFollowUp(GRADING_SYSTEM_PROMPT, userText, imagesInOrder(fitted)) { first ->
                if (needsRepair(first, item.maxScore)) REPAIR_PROMPT else null
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return result(
                BoxStatus.NEEDS_FALLBACK,
                "$REQUEST_FAILED: ${t.message ?: t.javaClass.simpleName}",
                modelId = model.modelId
            )
        }
        val raw = replies.first
        // Both replies are kept for the audit trail.
        val stored = replies.followUp?.let { raw + REPAIR_SEPARATOR + it } ?: raw

        val cutOff = RunawayGuard.wasCutOff(raw)
        val kept = raw.removeSuffix(RunawayGuard.CUT_MARKER)
        val firstReply = parseReply(kept, item.maxScore)
        // The repair's answer counts only for the mark and the confidence; what the student
        // wrote and the feedback still come from the first reply.
        val repaired = replies.followUp?.let { parseReply(it.removeSuffix(RunawayGuard.CUT_MARKER), item.maxScore) }
            ?.takeIf { !it.grade.parseError && !it.grade.unreadable && it.grade.score != null }
        val reply = if (repaired != null) {
            ParsedReply(
                grade = firstReply.grade.copy(score = repaired.grade.score, parseError = false),
                confidence = repaired.confidence
            )
        } else firstReply
        val parsed = reply.grade
        val confidence = (reply.confidence as? Confidence.Valid)?.value

        fun fallback(reason: String) = result(
            BoxStatus.NEEDS_FALLBACK,
            reason,
            transcript = parsed.transcript,
            feedback = parsed.feedback.ifEmpty { null },
            confidence = confidence,
            rawReply = stored,
            modelId = model.modelId
        )

        // A loop that began in the FEEDBACK, after SCORE and CONFIDENCE (the phone's order),
        // leaves the mark standing, with the feedback cut to one copy. Anywhere else it is
        // not trusted.
        if (cutOff && !RunawayGuard.markCameBeforeFeedback(kept)) return fallback(RUNAWAY)

        // Same reasons, word for word, as grade_one.
        if (parsed.unreadable) return fallback("Model could not read the answer")
        if (parsed.parseError) return fallback("Could not read a valid mark from the model's reply")

        when (val c = reply.confidence) {
            Confidence.Missing -> return fallback("The model's reply has no CONFIDENCE line")
            is Confidence.Unparsable -> return fallback("The model's CONFIDENCE is not a number: \"${c.text}\"")
            is Confidence.OutOfRange -> return fallback(
                "The model's CONFIDENCE ${formatNumber(c.value)} is outside 0..100"
            )
            is Confidence.Valid -> if (c.value < config.confidenceThreshold) {
                return fallback(
                    "The model's CONFIDENCE ${formatNumber(c.value)} is below " +
                        formatNumber(config.confidenceThreshold)
                )
            }
        }

        return result(
            BoxStatus.GRADED,
            null,
            score = parsed.score,
            transcript = parsed.transcript,
            feedback = parsed.feedback,
            confidence = confidence,
            rawReply = stored,
            modelId = model.modelId
        )
    }

    /**
     * The two-turn flow (TwoTurnGrading.kt), after the same blocked / no crop / blank / vision /
     * budget checks as the one-turn flow. Turn 1 gets [fitted]'s crops and the question only;
     * turn 2 is text only. The posted transcript is turn 1's; the raw reply keeps both turns.
     */
    private suspend fun gradeTwoTurn(item: AnswerToGrade, fitted: AnswerToGrade, startedAt: Long): BoxGradeResult {
        var transcriptText: String? = null
        var raw: String? = null
        var confidence: Double? = null

        fun result(status: BoxStatus, reason: String?, score: Double? = null, feedback: String? = null) = BoxGradeResult(
            answerBoxId = item.answerBoxId,
            score = score,
            maxScore = item.maxScore,
            transcript = transcriptText,
            feedback = feedback?.ifEmpty { null },
            confidence = confidence,
            status = status,
            reason = reason,
            rawReply = raw,
            modelId = if (raw != null) model.modelId else null,
            durationMs = clock() - startedAt
        )
        fun review(reason: String, feedback: String? = null) = result(BoxStatus.NEEDS_FALLBACK, reason, feedback = feedback)

        if (item.groundTruthText.isBlank()) return review(NO_TYPED_MODEL_ANSWER)
        val parts = QuestionParts.detect(item.questionText)

        suspend fun call(system: String, user: String, images: List<ByteArray>): Result<String> = try {
            Result.success(model.complete(system, user, images))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
        fun failed(t: Throwable) = review("$REQUEST_FAILED: ${t.message ?: t.javaClass.simpleName}")

        // Turn 1: the crops and the question, nothing to copy an answer from.
        val first = call(TRANSCRIBE_SYSTEM_PROMPT, transcribeUserMessage(item.questionText, parts), fitted.crops)
            .getOrElse { return failed(it) }
        raw = TURN_1_HEADER + first
        if (RunawayGuard.wasCutOff(first)) return review(RUNAWAY)
        val transcript = TwoTurnParser.transcript(first, parts)
        transcriptText = TwoTurnParser.transcriptText(parts, transcript)
        // Parts that get 0 without being marked (session 9c, on device: Gemma 4 E2B wrote
        // "c) UNREADABLE" for a part the student left empty, even when told NOT ANSWERED):
        // written as NOT ANSWERED, written as UNREADABLE, or left out of the transcript. Only
        // when nothing at all was read does the box go to review.
        val zeroed = parts.filter { p -> transcript[p.label]?.let { it.notAnswered || it.unreadable } ?: true }
        if (zeroed.size == parts.size) {
            return review(
                when {
                    transcript.values.any { it.unreadable } -> "Model could not read the answer"
                    transcript.isEmpty() -> "$TRANSCRIPT_MISSING_PARTS: ${parts.joinToString { it.label }}"
                    else -> NOTHING_TRANSCRIBED
                }
            )
        }
        val zeroNotes = zeroed.filter { it.label != QuestionParts.WHOLE }.joinToString(" ") { p ->
            if (transcript[p.label]?.unreadable == true) "Part ${p.label} not answered or not readable (0 marks)."
            else "Part ${p.label} not answered (0 marks)."
        }

        // Turn 2: a new conversation, text only. It sees every zeroed part as NOT ANSWERED.
        val forMarking = parts.joinToString("\n") { p ->
            if (p in zeroed) "${p.label}) ${PartTranscript.NOT_ANSWERED}" else "${p.label}) ${transcript.getValue(p.label).text}"
        }.takeIf { parts.size > 1 || parts[0].label != QuestionParts.WHOLE } ?: transcriptText!!
        val second = call(MARK_SYSTEM_PROMPT, markUserMessage(item, parts, forMarking), emptyList())
            .getOrElse { return failed(it) }
        raw = TURN_1_HEADER + first + TURN_2_SEPARATOR + second
        val kept = second.removeSuffix(RunawayGuard.CUT_MARKER)
        if (RunawayGuard.wasCutOff(second) && !RunawayGuard.markCameBeforeFeedback(kept)) return review(RUNAWAY)
        val feedback = listOf(TwoTurnParser.feedback(kept), zeroNotes).filter { it.isNotEmpty() }.joinToString(" ")
        val c = parseConfidence(kept)
        confidence = (c as? Confidence.Valid)?.value

        val marks = when (val m = TwoTurnParser.marks(kept, parts, item.maxScore)) {
            is TwoTurnParser.Marks.Invalid -> return review("$PARTS_UNREADABLE (${m.why})", feedback)
            is TwoTurnParser.Marks.Valid -> m.byPart
        }
        // A zeroed part is 0, whatever turn 2 gave it.
        val total = parts.sumOf { p -> if (p in zeroed) 0.0 else marks.getValue(p.label) }
        if (total > item.maxScore) {
            return review(
                "The parts add up to ${TwoTurnParser.fmt(total)}, more than the box's ${item.maxScore} marks",
                feedback
            )
        }
        when (c) {
            Confidence.Missing -> return review("The model's reply has no CONFIDENCE line", feedback)
            is Confidence.Unparsable -> return review("The model's CONFIDENCE is not a number: \"${c.text}\"", feedback)
            is Confidence.OutOfRange -> return review("The model's CONFIDENCE ${formatNumber(c.value)} is outside 0..100", feedback)
            is Confidence.Valid -> if (c.value < config.confidenceThreshold) {
                return review(
                    "The model's CONFIDENCE ${formatNumber(c.value)} is below ${formatNumber(config.confidenceThreshold)}",
                    feedback
                )
            }
        }
        return result(BoxStatus.GRADED, null, score = total, feedback = feedback)
    }

    /**
     * Each crop as [CropTrim] plans it (the writing plus a margin, the printed label painted
     * out), for the model's input only. A crop that cannot be measured or cut goes whole.
     */
    private fun trimmedToWriting(item: AnswerToGrade, grays: List<GrayImage?>): AnswerToGrade {
        val trimmed = item.crops.mapIndexed { i, png ->
            val gray = grays.getOrNull(i) ?: return@mapIndexed png
            val plan = CropTrim.plan(gray, item.cropLabelRegions.getOrNull(i)) ?: return@mapIndexed png
            try {
                cropTo(png, plan)
            } catch (e: Exception) {
                null
            } ?: png
        }
        return item.withCrops(trimmed)
    }

    /** A crop that cannot be decoded counts as written on, as on the server. */
    private fun grayOrNull(png: ByteArray): GrayImage? = try {
        decodeGray(png)
    } catch (e: Exception) {
        null
    }

    companion object {
        /**
         * DELIBERATE DIFFERENCE from the web end (session 7): when the model answer has text,
         * its images are not sent to the phone's model. The pack's model-answer images are the
         * finalize-time rendering of the model answer (`GroundTruthImage`); on the session 7
         * paper both were just the typed text drawn as a picture. Qwen2-VL 2B read that picture
         * as the student's work: it listed part (c), which only the model answer had, as
         * written "in the third image", ignored the reply format and hit the 300-token cap with
         * no SCORE. Each such image also costs 576 tokens and a vision encode (~half of a
         * box's time). Question figures stay; a text-less model answer keeps its images.
         * Cost: a figure inside a model answer that also has text is not shown on the phone.
         */
        fun withoutRenderedModelAnswer(item: AnswerToGrade): AnswerToGrade =
            if (item.groundTruthText.isBlank() || item.groundTruthImages.isEmpty()) item
            else item.withImages(item.questionImages, emptyList())

        /** grade_one's own reason for a box with no crop. */
        const val NO_CROP = "No extracted answer image for this box"

        /** Start of the reason when the model call threw. */
        const val REQUEST_FAILED = "Grading request failed"

        /** Start of the reason when grading a box threw outside the model call (the runner's catch). */
        const val PHONE_FAILED = "Grading on the phone failed"

        /** Between the first reply and the repair reply in the stored raw reply. */
        const val REPAIR_SEPARATOR = "\n[repair]\n"

        /**
         * A reply earns [REPAIR_PROMPT] when it has no readable mark: a parse error that is not
         * UNREADABLE, and not a reply the phone cut off for looping.
         */
        fun needsRepair(reply: String, maxScore: Int): Boolean {
            if (RunawayGuard.wasCutOff(reply)) return false
            val g = parseReply(reply, maxScore).grade
            return g.parseError && !g.unreadable
        }

        /** Two-turn: before the first reply in the stored raw reply. */
        const val TURN_1_HEADER = "[turn 1: transcript]\n"

        /** Two-turn: between the two replies in the stored raw reply. */
        const val TURN_2_SEPARATOR = "\n[turn 2: marks]\n"

        const val NO_TYPED_MODEL_ANSWER = "Two-turn grading needs a typed model answer"
        const val TRANSCRIPT_MISSING_PARTS = "The model's transcript does not list every part"
        const val NOTHING_TRANSCRIBED = "The phone found writing but the model transcribed none"
        const val PARTS_UNREADABLE = "Could not read a valid mark for every part from the model's reply"

        /** The phone stopped the model because its reply was looping ([RunawayGuard]). */
        const val RUNAWAY = "The model's reply kept repeating itself, so the phone stopped it"

        private fun formatNumber(value: Double): String =
            if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()
    }
}
