package com.example.capstone.domain.grading

/**
 * Fits a box's images into the model's context.
 *
 * The server attaches every question figure, every model-answer image and
 * every crop (grading.py:460). Qwen2-VL on the phone has 4096 tokens and each
 * image costs `ModelSpec.imageTokens`, so that does not always fit. The rule:
 * - count images x [imageTokens], plus the text, plus room for the reply;
 * - over budget: drop question figures first (last first), then model-answer
 *   images (last first);
 * - never drop a student crop;
 * - still over: the box cannot be marked here.
 *
 * The user message is rebuilt after every drop, so it always describes the
 * images that are actually attached.
 */
class TokenBudget(
    private val maxNumTokens: Int,
    private val imageTokens: Int,
    private val replyReserveTokens: Int = DEFAULT_REPLY_RESERVE_TOKENS
) {

    sealed interface Fit {
        /** [item] carries only the images that fit. */
        data class Fits(
            val item: AnswerToGrade,
            val estimatedTokens: Int,
            val droppedQuestionImages: Int,
            val droppedModelAnswerImages: Int
        ) : Fit

        data class OverBudget(val estimatedTokens: Int, val reason: String) : Fit
    }

    fun fit(item: AnswerToGrade, systemPrompt: String = GRADING_SYSTEM_PROMPT): Fit {
        val questionImages = item.questionImages.toMutableList()
        val modelAnswerImages = item.groundTruthImages.toMutableList()
        // A model answer that is only images cannot lose all of them: the box
        // would be marked with no answer key while the message says one is
        // "provided as an image below".
        val modelAnswerIsImageOnly = pyStrip(item.groundTruthText).isEmpty() && modelAnswerImages.isNotEmpty()

        while (true) {
            val candidate = item.withImages(questionImages.toList(), modelAnswerImages.toList())
            val estimate = estimate(candidate, systemPrompt)
            if (estimate <= maxNumTokens) {
                return Fit.Fits(
                    item = candidate,
                    estimatedTokens = estimate,
                    droppedQuestionImages = item.questionImages.size - questionImages.size,
                    droppedModelAnswerImages = item.groundTruthImages.size - modelAnswerImages.size
                )
            }
            when {
                questionImages.isNotEmpty() -> questionImages.removeAt(questionImages.lastIndex)
                modelAnswerImages.size > (if (modelAnswerIsImageOnly) 1 else 0) ->
                    modelAnswerImages.removeAt(modelAnswerImages.lastIndex)
                else -> return Fit.OverBudget(
                    estimatedTokens = estimate,
                    reason = overBudgetReason(candidate, estimate, modelAnswerIsImageOnly)
                )
            }
        }
    }

    /** Images x [imageTokens] + text + [replyReserveTokens]. */
    fun estimate(item: AnswerToGrade, systemPrompt: String = GRADING_SYSTEM_PROMPT): Int {
        val images = item.questionImages.size + item.groundTruthImages.size + item.crops.size
        return images * imageTokens +
            estimateTextTokens(systemPrompt) +
            estimateTextTokens(buildUserMessage(item)) +
            replyReserveTokens
    }

    private fun overBudgetReason(item: AnswerToGrade, estimate: Int, modelAnswerIsImageOnly: Boolean) =
        buildString {
            append("Too large for the phone's model: about $estimate tokens against $maxNumTokens ")
            append("(${item.crops.size} student image(s)")
            if (item.groundTruthImages.isNotEmpty()) {
                append(", ${item.groundTruthImages.size} model-answer image(s)")
            }
            append(" at $imageTokens each)")
            if (modelAnswerIsImageOnly) append("; the model answer is only an image and cannot be left out")
            append(".")
        }

    companion object {
        /** Room left for TRANSCRIPT, SCORE, FEEDBACK and CONFIDENCE. Plan §D.3. */
        const val DEFAULT_REPLY_RESERVE_TOKENS = 300

        /**
         * Characters per token, estimated on the low side so the estimate is
         * high. UNVERIFIED against Qwen2-VL's tokenizer on device; English
         * usually runs near 4 characters per token.
         */
        const val CHARS_PER_TOKEN = 3

        fun estimateTextTokens(text: String): Int = (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
    }
}
