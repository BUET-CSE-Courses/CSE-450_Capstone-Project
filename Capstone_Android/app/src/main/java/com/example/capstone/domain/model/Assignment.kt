package com.example.capstone.domain.model

import com.example.capstone.extractor.Layout

/**
 * One question of an assignment.
 *
 * [marks], [modelAnswer], [rubric] and [externalAnswerBoxId] are nullable
 * because the assignment list endpoint does not serve them - only the
 * assignment detail endpoint does. Null therefore means "not fetched on this
 * path", not "the server has no value".
 *
 * [modelAnswer] has a third state: an empty string, meaning the question was
 * imported from the teacher worksheet system, which serves no marking data, and
 * the teacher has not filled it in yet. Ungradeable, like null, but for a
 * different reason - see [isGradeable].
 */
data class Question(
    val id: Int,
    val text: String,
    val marks: Int? = null,
    val modelAnswer: String? = null,
    val rubric: String? = null,
    /**
     * The teacher worksheet system's answer box id, when this question came
     * from an imported worksheet. Null otherwise.
     *
     * Never treat this as globally unique: it is unique only within its
     * assignment. Resolve it through QuestionResolver, which is scoped to one
     * assignment and one external question id.
     */
    val externalAnswerBoxId: String? = null
) {
    /**
     * True when there is enough here to grade against: a mark ceiling and a
     * non-blank model answer. False for a list-endpoint question and for an
     * imported question whose marking data the teacher has not supplied.
     */
    val isGradeable: Boolean
        get() = marks != null && !modelAnswer.isNullOrBlank()
}

/**
 * A paper as the scan and on-device grading screens use it, built from the
 * cached assignment pack (AssignmentRepository.worksheetFor). Each box's
 * parts (segments) are in [layout].
 */
data class Assignment(
    /** The web end's question id (the paper), also the pack's `question_id`. */
    val id: String,
    val title: String,
    /** Optional: the server column is nullable. */
    val description: String?,
    val questions: List<Question> = emptyList(),
    val isCompleted: Boolean = false,
    /**
     * The teacher worksheet system's question id this assignment was imported
     * from. Null for locally created assignments. The other half of the join
     * key: an answer box id means nothing without it.
     */
    val externalQuestionId: String? = null,
    /**
     * Printed-page geometry from the pack; null when the paper has no page
     * size.
     *
     * This is the extractor's own [Layout] type, built from what the server
     * serves. Nothing on this side computes marker positions or page size: a
     * wrong constant baked into an APK cannot be corrected, a served one can.
     */
    val layout: Layout? = null,
    /** Printed pages, the pack's `page_count`; null when the server has none. */
    val pageCount: Int? = null
)
