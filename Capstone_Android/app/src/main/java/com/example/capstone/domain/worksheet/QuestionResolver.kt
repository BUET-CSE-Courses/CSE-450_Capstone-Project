package com.example.capstone.domain.worksheet

import com.example.capstone.domain.model.Assignment
import com.example.capstone.domain.model.Question
import com.example.capstone.extractor.AnswerCrop

/**
 * Maps extracted [AnswerCrop]s onto the [Question]s they answer.
 *
 * This is the single place where the pack's answer box ids meet this app's
 * question ids. Everything downstream - grading, the per-box results - depends
 * on this class and on nothing else about how ids arrive. If the join ever
 * changes shape, it changes here.
 *
 * A box printed across a page break has one crop per part (its segments, in
 * [Assignment.layout]). It resolves only when every part is present, and its
 * crops come back in part order, which is the order the server grades them in.
 *
 * Three rules, all deliberate:
 *
 * 1. **The key is the paper plus the box id, never the bare box id.** A
 *    resolver is scoped to one paper, and a crop carrying a different question
 *    id is rejected even if its box id matches.
 *
 * 2. **Never a silent skip.** A crop with no question, or a question missing a
 *    crop for any of its parts, fails the whole resolution and names the
 *    offending ids. The failure being avoided is a script where one answer
 *    quietly never got graded - or was graded on half of itself. Both look like
 *    success from the outside.
 *
 * 3. **No partial result is offered.** A caller handed a shorter list than it
 *    asked for is very likely to grade it and report success.
 */
class QuestionResolver private constructor(
    /** The assignment this resolver is scoped to. */
    val assignmentId: String,
    /** The paper (question id) this resolver is scoped to. */
    val externalQuestionId: String,
    private val questionsByBoxId: Map<String, Question>,
    /** How many parts (segments) each box has. */
    private val partCounts: Map<String, Int>
) {

    /** Answer box ids this resolver can resolve, in question order. */
    val knownBoxIds: Set<String>
        get() = questionsByBoxId.keys

    /**
     * Pairs every question with all of its crops.
     *
     * Returns [Resolution.Resolved] only when there is exactly one crop per
     * (box, part), for every part of every box, and every crop came from this
     * resolver's paper. Anything else is [Resolution.Failed], carrying the
     * specific ids so a caller can say which answer box could not be read
     * rather than "something went wrong".
     *
     * The returned answers are ordered by question, the teacher's document
     * order, regardless of the order the pages were photographed in.
     */
    fun resolve(crops: List<AnswerCrop>): Resolution {
        val fromOtherQuestion = crops
            .filter { it.externalQuestionId != externalQuestionId }
            .map { it.externalQuestionId }
            .distinct()
            .sorted()

        val mine = crops.filter { it.externalQuestionId == externalQuestionId }

        val duplicateCropIds = mine
            .groupingBy { partName(it.externalAnswerBoxId, it.part) }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()

        // A part number the layout does not have is as foreign as an unknown box.
        val cropsWithoutQuestion = mine
            .filter { crop ->
                val parts = partCounts[crop.externalAnswerBoxId]
                !questionsByBoxId.containsKey(crop.externalAnswerBoxId) ||
                    parts == null || crop.part !in 0 until parts
            }
            .map { partName(it.externalAnswerBoxId, it.part) }
            .distinct()
            .sorted()

        val cropsByBox = mine.groupBy { it.externalAnswerBoxId }
        fun missing(boxId: String): List<Int> {
            val have = cropsByBox[boxId].orEmpty().map { it.part }.toSet()
            return (0 until partCounts.getValue(boxId)).filterNot { it in have }
        }
        val missingParts = questionsByBoxId.keys.flatMap { boxId -> missing(boxId).map { partName(boxId, it) } }
        val questionsWithoutCrop = questionsByBoxId
            .filterKeys { missing(it).isNotEmpty() }
            .values
            .map { it.id }
            .sorted()

        if (fromOtherQuestion.isNotEmpty() ||
            duplicateCropIds.isNotEmpty() ||
            cropsWithoutQuestion.isNotEmpty() ||
            questionsWithoutCrop.isNotEmpty()
        ) {
            return Resolution.Failed(
                assignmentId = assignmentId,
                externalQuestionId = externalQuestionId,
                cropsFromOtherQuestion = fromOtherQuestion,
                cropsWithoutQuestion = cropsWithoutQuestion,
                questionsWithoutCrop = questionsWithoutCrop,
                duplicateCropIds = duplicateCropIds,
                missingParts = missingParts
            )
        }

        val resolved = questionsByBoxId.entries.map { (boxId, question) ->
            ResolvedAnswer(question = question, crops = cropsByBox.getValue(boxId).sortedBy { it.part })
        }

        return Resolution.Resolved(resolved)
    }

    companion object {
        /** "ab_x" for a box's first part, "ab_x part 2" for the rest, as the paper prints them. */
        internal fun partName(boxId: String, part: Int): String =
            if (part == 0) boxId else "$boxId part ${part + 1}"

        /**
         * Builds a resolver for [assignment].
         *
         * Fails rather than resolving anything when the assignment cannot
         * support the join at all:
         *
         * - it has no external question id: there is nothing on paper to match;
         * - it has no printed layout, so the parts of each box are unknown;
         * - it has no questions;
         * - no question carries an answer box id;
         * - only some questions carry one, which would make any resolution
         *   necessarily incomplete;
         * - two questions carry the same one: the join is not trustworthy and
         *   must not be used;
         * - a question's box is not printed anywhere in the layout.
         */
        fun forAssignment(assignment: Assignment): ResolverCreation {
            val externalQuestionId = assignment.externalQuestionId
            if (externalQuestionId.isNullOrBlank()) {
                return ResolverCreation.Unavailable(
                    assignmentId = assignment.id,
                    reason = "Assignment ${assignment.id} has no question id, so there is " +
                        "no printed paper to resolve crops against."
                )
            }

            val layout = assignment.layout
                ?: return ResolverCreation.Unavailable(
                    assignmentId = assignment.id,
                    reason = "Assignment ${assignment.id} has no printed layout, so its " +
                        "answer boxes' parts are unknown."
                )

            if (assignment.questions.isEmpty()) {
                return ResolverCreation.Unavailable(
                    assignmentId = assignment.id,
                    reason = "Assignment ${assignment.id} has no questions."
                )
            }

            val unlinked = assignment.questions
                .filter { it.externalAnswerBoxId.isNullOrBlank() }
                .map { it.id }

            if (unlinked.size == assignment.questions.size) {
                return ResolverCreation.Unavailable(
                    assignmentId = assignment.id,
                    reason = "Assignment ${assignment.id} carries no answer box ids, " +
                        "although it names paper $externalQuestionId."
                )
            }

            if (unlinked.isNotEmpty()) {
                return ResolverCreation.Unavailable(
                    assignmentId = assignment.id,
                    reason = "Assignment ${assignment.id} is partially linked: " +
                        "questions $unlinked have no answer box id while the rest " +
                        "do. Resolving would silently drop them."
                )
            }

            val byBoxId = LinkedHashMap<String, Question>(assignment.questions.size)
            for (question in assignment.questions) {
                val boxId = question.externalAnswerBoxId!!
                val previous = byBoxId.put(boxId, question)
                if (previous != null) {
                    return ResolverCreation.Unavailable(
                        assignmentId = assignment.id,
                        reason = "Assignment ${assignment.id} has two questions " +
                            "(${previous.id} and ${question.id}) sharing answer box " +
                            "id \"$boxId\". The join is ambiguous and must not be used."
                    )
                }
            }

            val partCounts = layout.answerBoxes.associate { it.externalAnswerBoxId to it.segments.size }
            val notPrinted = byBoxId.keys.filter { (partCounts[it] ?: 0) < 1 }
            if (notPrinted.isNotEmpty()) {
                return ResolverCreation.Unavailable(
                    assignmentId = assignment.id,
                    reason = "Assignment ${assignment.id}: answer boxes $notPrinted are not " +
                        "printed anywhere in its layout."
                )
            }

            return ResolverCreation.Available(
                QuestionResolver(
                    assignmentId = assignment.id,
                    externalQuestionId = externalQuestionId,
                    questionsByBoxId = byBoxId,
                    partCounts = partCounts
                )
            )
        }
    }
}

/** One question with every part of its answer, in part order. */
data class ResolvedAnswer(
    val question: Question,
    val crops: List<AnswerCrop>
) {
    init {
        require(crops.isNotEmpty()) { "an answer has at least one crop" }
    }

    val answerBoxId: String get() = crops.first().externalAnswerBoxId

    /** The box's served order index. */
    val orderIndex: Int get() = crops.first().orderIndex
}

/** Outcome of building a [QuestionResolver] for an assignment. */
sealed interface ResolverCreation {
    data class Available(val resolver: QuestionResolver) : ResolverCreation

    /**
     * The assignment cannot be resolved against at all. [reason] is written to
     * be logged verbatim, not shown to a student.
     */
    data class Unavailable(
        val assignmentId: String,
        val reason: String
    ) : ResolverCreation
}

/** Outcome of resolving a set of crops. */
sealed interface Resolution {

    /** Every part of every question has exactly one crop. */
    data class Resolved(val answers: List<ResolvedAnswer>) : Resolution

    /**
     * The crop set and the question set do not correspond.
     *
     * @param cropsFromOtherQuestion question ids on crops that did not come
     *   from this paper - a photo of the wrong sheet.
     * @param cropsWithoutQuestion answer boxes (or parts) extracted from the
     *   right paper that belong to no question here - a stale layout.
     * @param questionsWithoutCrop our question ids missing a crop for at least
     *   one part - a missing page, or a box the extractor could not cut.
     * @param duplicateCropIds answer boxes (or parts) extracted more than once.
     * @param missingParts every missing part by name, e.g. "ab_x part 2".
     */
    data class Failed(
        val assignmentId: String,
        val externalQuestionId: String,
        val cropsFromOtherQuestion: List<String>,
        val cropsWithoutQuestion: List<String>,
        val questionsWithoutCrop: List<Int>,
        val duplicateCropIds: List<String>,
        val missingParts: List<String> = emptyList()
    ) : Resolution {

        /** One line naming every mismatch. Log this; do not discard it. */
        val message: String
            get() = buildString {
                append("Cannot resolve crops for assignment ")
                append(assignmentId)
                append(" (paper ")
                append(externalQuestionId)
                append("): ")
                val parts = mutableListOf<String>()
                if (cropsFromOtherQuestion.isNotEmpty()) {
                    parts += "crops from another question $cropsFromOtherQuestion"
                }
                if (cropsWithoutQuestion.isNotEmpty()) {
                    parts += "crops with no question $cropsWithoutQuestion"
                }
                if (questionsWithoutCrop.isNotEmpty()) {
                    parts += "questions with no crop $questionsWithoutCrop" +
                        if (missingParts.isNotEmpty()) " (missing $missingParts)" else ""
                }
                if (duplicateCropIds.isNotEmpty()) {
                    parts += "duplicate crops $duplicateCropIds"
                }
                append(parts.joinToString("; "))
            }
    }
}
