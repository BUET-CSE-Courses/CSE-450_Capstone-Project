package com.example.capstone.domain.grading

import com.example.capstone.domain.model.Question
import com.example.capstone.domain.worksheet.ResolvedAnswer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** One graded box as the grading screen shows it: its place, its question, its result. */
data class WorksheetBox(
    val orderIndex: Int,
    val questionText: String,
    val result: BoxGradeResult
)

/**
 * Grades one worksheet, one answer box at a time.
 *
 * Emits a [WorksheetBox] per box as soon as that box is done, so a screen can
 * fill in while the rest is still running. The flow is cold and strictly
 * sequential: collecting it starts the run, cancelling the collection stops it
 * between boxes, and nothing is graded twice. There is one LiteRT-LM engine
 * per process and its calls are serialised behind a mutex, so running boxes in
 * parallel would buy nothing.
 *
 * Marks are per box. Nothing here adds them up into a submission grade.
 */
class WorksheetGrader(private val gradingService: GradingService) {

    /**
     * Grades [answers] in document order (the served `order_index`), whatever
     * order they arrive in. Every box emits exactly one result; a box that
     * cannot be marked comes back as fallback or review, never skipped.
     */
    fun grade(answers: List<ResolvedAnswer>): Flow<WorksheetBox> = flow {
        for (answer in answers.sortedBy { it.orderIndex }) {
            emit(
                WorksheetBox(
                    orderIndex = answer.orderIndex,
                    questionText = answer.question.text,
                    result = gradingService.grade(toAnswerToGrade(answer))
                )
            )
        }
    }.flowOn(Dispatchers.Default)

    companion object {
        /**
         * The server's blocked reasons (services/grading_runner.py:75, :79 at
         * c69eea2), so a box the phone refuses reads the same as one the
         * server refuses.
         */
        const val NO_MARKS_REASON =
            "No marks set for this part — give it a marking scheme, or set them by hand"
        const val NO_MODEL_ANSWER_REASON = "No model answer is paired with this answer box"

        /**
         * Adapts the resolved answer onto [AnswerToGrade]. Every part's crop,
         * in part order, goes into one call, as the server's `grade_one` sends
         * all parts of a box together. Figures and the served `blocked_reason`
         * are not wired in yet (Phase 6).
         *
         * `Question.rubric` is not sent. The server's prompt has no rubric
         * field; a marking scheme belongs in the model answer text.
         */
        fun toAnswerToGrade(answer: ResolvedAnswer): AnswerToGrade {
            val question = answer.question
            return AnswerToGrade(
                answerBoxId = answer.answerBoxId,
                label = "",
                maxScore = question.marks ?: 0,
                questionText = question.text,
                groundTruthText = question.modelAnswer.orEmpty(),
                crops = answer.crops.map { it.png },
                blockedReason = blockedReason(question)
            )
        }

        private fun blockedReason(question: Question): String? = when {
            question.marks == null -> NO_MARKS_REASON
            question.modelAnswer.isNullOrBlank() -> NO_MODEL_ANSWER_REASON
            else -> null
        }
    }
}
