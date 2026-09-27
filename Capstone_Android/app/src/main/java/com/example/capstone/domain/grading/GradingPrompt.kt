package com.example.capstone.domain.grading

/*
 * Ported from Script-Checker-Web-End/backend/services/grading.py
 * (GRADING_SYSTEM_PROMPT, lines 32-71; AnswerToGrade, lines 128-145;
 * build_user_message, lines 380-415) at commit c69eea2 on main. The file was
 * last changed in 04b436f.
 *
 * Word for word, with exactly one addition: the CONFIDENCE line, placed
 * between SCORE and FEEDBACK. Nothing else may differ. If grading.py changes,
 * change this file and ServerParityTest together.
 *
 * DELIBERATE DIFFERENCE from the web end (session 7, plan §H): the phone asks
 * for TRANSCRIPT, SCORE, CONFIDENCE, FEEDBACK, so the mark and the confidence
 * come before the free text. On device a looping FEEDBACK ran until the context
 * was full and the CONFIDENCE line after it was never written; now the phone
 * also stops generating after the first FEEDBACK line
 * ([RunawayGuard.feedbackLineEnd]). The server's prompt, used by the teacher's
 * Grade button and by fallback, still ends with FEEDBACK and has no CONFIDENCE.
 */

/** The one line the phone adds to the server's reply format, straight after SCORE. */
const val CONFIDENCE_FORMAT_LINE: String = "CONFIDENCE: <a number from 0 to 100>\n"

/** The server's system prompt plus one reply line, `CONFIDENCE: <a number from 0 to 100>`, after SCORE. */
const val GRADING_SYSTEM_PROMPT: String =
    "You are marking a handwritten exam answer.\n" +
        "\n" +
        "You are given the question, the official model answer, and images of what " +
        "the student actually wrote.\n" +
        "\n" +
        "FIRST, transcribe exactly what appears in the student's images. Copy only " +
        "marks that are actually there. Do not complete, correct or infer any step. " +
        "If the images contain no writing at all, the transcript is NOTHING WRITTEN.\n" +
        "\n" +
        "THEN award a mark, based only on your own transcript.\n" +
        "\n" +
        "Never credit a step that does not appear in your transcript. The question " +
        "and model answer are given to you for comparison only — they are NOT the " +
        "student's work, and reproducing them as if the student wrote them is a " +
        "serious error.\n" +
        "\n" +
        "If the transcript is NOTHING WRITTEN, the score is 0.\n" +
        "If there is writing but you cannot make it out, the score is UNREADABLE.\n" +
        "\n" +
        "Otherwise mark the reasoning, not the handwriting: ignore untidiness, " +
        "crossings-out and spelling.\n" +
        "\n" +
        "If the model answer sets out a marking scheme — a breakdown of how many " +
        "marks each step or criterion is worth — follow it exactly. Award marks " +
        "for each criterion the transcript satisfies and none for those it does " +
        "not, and say in your feedback which criteria were met. The teacher's " +
        "scheme overrides your own judgement about what the work deserves.\n" +
        "\n" +
        "If it sets out no scheme, award partial credit for work that is correct " +
        "as far as it goes, and full marks for a different but valid method.\n" +
        "\n" +
        "Never exceed MAXIMUM MARK, even if a scheme in the model answer totals " +
        "something higher.\n" +
        "\n" +
        "Reply in exactly this format and nothing else:\n" +
        "\n" +
        "TRANSCRIPT: <what is actually written, or NOTHING WRITTEN>\n" +
        "SCORE: <a number from 0 to the maximum, or UNREADABLE>\n" +
        CONFIDENCE_FORMAT_LINE +
        "FEEDBACK: <one or two sentences addressed to the student>\n"

/**
 * One answer box paired with the model answer it is marked against. Mirrors the
 * server's `AnswerToGrade`.
 *
 * Images are the bytes as served or cropped. The model adapter prepares them
 * for inference; blank detection reads [crops] as they are, as the server does.
 * A plain class because ByteArray lists make generated equality meaningless.
 */
class AnswerToGrade(
    val answerBoxId: String,
    val label: String,
    /** The box's points, or 0 when none are set (`box.points or 0` on the server). */
    val maxScore: Int,
    val questionText: String,
    val groundTruthText: String,
    /** Figures the question itself depends on. */
    val questionImages: List<ByteArray> = emptyList(),
    val groundTruthImages: List<ByteArray> = emptyList(),
    /** The student's crops, one per part, in order. */
    val crops: List<ByteArray> = emptyList(),
    /** Set when this box cannot be marked automatically. The model is never asked. */
    val blockedReason: String? = null,
    /**
     * Phone only: per crop, the printed label [BlankDetector] leaves out ([PrintedLabel]).
     * Empty, or null for a crop, means the server's rule unchanged.
     */
    val cropLabelRegions: List<IgnoreRegion?> = emptyList()
) {
    /** The same box with other crops (the phone's trimmed copies, for the model only). */
    fun withCrops(crops: List<ByteArray>) =
        AnswerToGrade(
            answerBoxId = answerBoxId,
            label = label,
            maxScore = maxScore,
            questionText = questionText,
            groundTruthText = groundTruthText,
            questionImages = questionImages,
            groundTruthImages = groundTruthImages,
            crops = crops,
            blockedReason = blockedReason,
            cropLabelRegions = cropLabelRegions
        )

    /** The same box with a different set of question and model-answer images attached. */
    fun withImages(questionImages: List<ByteArray>, groundTruthImages: List<ByteArray>) =
        AnswerToGrade(
            answerBoxId = answerBoxId,
            label = label,
            maxScore = maxScore,
            questionText = questionText,
            groundTruthText = groundTruthText,
            questionImages = questionImages,
            groundTruthImages = groundTruthImages,
            crops = crops,
            blockedReason = blockedReason,
            cropLabelRegions = cropLabelRegions
        )
}

/** Port of `build_user_message`. Describes the images in the order they are attached. */
fun buildUserMessage(item: AnswerToGrade): String {
    val parts = mutableListOf(
        "QUESTION:\n${pyStrip(item.questionText).ifEmpty { "(not provided)" }}",
        "\nMODEL ANSWER:\n${pyStrip(item.groundTruthText).ifEmpty { "(provided as an image below)" }}",
        "\nMAXIMUM MARK: ${item.maxScore}"
    )
    if (item.label.isNotEmpty()) {
        parts.add(0, "Part: ${item.label}")
    }

    val nQ = item.questionImages.size
    val nGt = item.groundTruthImages.size
    val nCrops = item.crops.size

    val described = mutableListOf<String>()
    if (nQ > 0) {
        described += "the first $nQ image(s) are figures belonging to the QUESTION"
    }
    if (nGt > 0) {
        described += if (nQ > 0) {
            "the next $nGt image(s) are the official MODEL ANSWER"
        } else {
            "the first $nGt image(s) are the official MODEL ANSWER"
        }
    }
    described += if (described.isNotEmpty()) {
        "the remaining $nCrops image(s) are the STUDENT'S handwritten answer, in order"
    } else {
        "$nCrops image(s) of the STUDENT'S handwritten answer, in order"
    }
    parts += "\nIMAGES: " + described.joinToString("; ") + "."

    return parts.joinToString("\n")
}

/**
 * DELIBERATE DIFFERENCE from the web end (session 7): the phone ends every user message with
 * the reply format again, and tells the model to add parts up into one total.
 *
 * Why: on a multi-part question Qwen2-VL 2B ignored the format, which is only in the system
 * turn. Twice it began "a) The student wrote: …" (the question's own a) b) c) layout) and
 * either stopped after 20 tokens or ran to the 300-token cap in paragraphs; neither reply had a
 * SCORE. The single-part box followed the format both times.
 */
const val PHONE_FORMAT_REMINDER: String =
    "\n\nReply in exactly this format and nothing else, starting with TRANSCRIPT:\n" +
        "TRANSCRIPT: <what is actually written, or NOTHING WRITTEN>\n" +
        "SCORE: <ONE number from 0 to MAXIMUM MARK. If the question has parts, add up the " +
        "marks for all the parts and write only the total>\n" +
        CONFIDENCE_FORMAT_LINE +
        "FEEDBACK: <one or two sentences addressed to the student>"

/** What the phone sends as the user turn: [buildUserMessage] (the server's) + [PHONE_FORMAT_REMINDER]. */
fun phoneUserMessage(item: AnswerToGrade): String = buildUserMessage(item) + PHONE_FORMAT_REMINDER

/**
 * Phone only, sent in the same conversation when a reply had no readable SCORE (and was not a
 * runaway): one short second chance at the mark before the box goes to review.
 */
const val REPAIR_PROMPT: String =
    "Reply with exactly two lines: SCORE: <number> and CONFIDENCE: <0-100>"

/** The order the images are attached in, which is the order [buildUserMessage] describes. */
fun imagesInOrder(item: AnswerToGrade): List<ByteArray> =
    item.questionImages + item.groundTruthImages + item.crops
