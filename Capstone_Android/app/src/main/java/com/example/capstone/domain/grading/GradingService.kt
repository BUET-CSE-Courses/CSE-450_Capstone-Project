package com.example.capstone.domain.grading

/**
 * Marks one answer box. Marks are per box; there is no whole-submission grade.
 *
 * App code depends on this interface. [BoxGrader] is the implementation: it
 * decides blank, blocked, budget and confidence, and asks a [GradingModel] only
 * when a mark is really needed.
 */
interface GradingService {
    /** Never throws, except for cancellation. Every failure is a [BoxStatus]. */
    suspend fun grade(item: AnswerToGrade): BoxGradeResult
}

/**
 * One model call, the phone's side of the server's `LLMProvider.complete`.
 *
 * Takes the system prompt, the user text and the images in the order the text
 * describes them. Returns the reply verbatim. May throw; [BoxGrader] turns that
 * into [BoxStatus.NEEDS_FALLBACK].
 */
interface GradingModel {
    /** Recorded on every result the model produced, e.g. `QWEN2_VL_2B`. */
    val modelId: String
    val supportsVision: Boolean
    /** Context size: prompt, images and reply together. */
    val maxNumTokens: Int
    /** What one image costs out of [maxNumTokens]. */
    val imageTokens: Int

    suspend fun complete(systemPrompt: String, userText: String, images: List<ByteArray>): String

    /**
     * [complete], then, when [followUp] returns a message for the first reply, one more turn
     * in the same conversation. A model that cannot keep a conversation gives no second reply.
     */
    suspend fun completeWithFollowUp(
        systemPrompt: String,
        userText: String,
        images: List<ByteArray>,
        followUp: (String) -> String?
    ): ModelReplies = ModelReplies(complete(systemPrompt, userText, images), null)
}

/** The model's reply and, if one was asked for, its reply to the follow-up. */
data class ModelReplies(val first: String, val followUp: String?)

enum class BoxStatus {
    /** The model's mark, with enough confidence to stand. */
    GRADED,

    /** No ink on any crop. Score 0 and no model call, as on the server. */
    BLANK,

    /** The phone could not mark it reliably. The server re-marks it, or a teacher does. */
    NEEDS_FALLBACK,

    /** The pack says the box cannot be marked automatically. Only a teacher can. */
    NEEDS_REVIEW
}

/**
 * The result for one answer box.
 *
 * [score] is non-null only for [BoxStatus.GRADED] and [BoxStatus.BLANK]. It is
 * a float in 0..[maxScore], as on the server, and never rounded.
 */
data class BoxGradeResult(
    val answerBoxId: String,
    val score: Double?,
    val maxScore: Int,
    val transcript: String?,
    val feedback: String?,
    /** The parsed CONFIDENCE, 0..100, or null when missing or invalid. */
    val confidence: Double?,
    val status: BoxStatus,
    /** Why the box is not [BoxStatus.GRADED]; null when it is. */
    val reason: String?,
    /** The model's reply verbatim, or null when the model was not called. */
    val rawReply: String?,
    /** The model that was called, or null when none was. */
    val modelId: String?,
    val durationMs: Long
)
