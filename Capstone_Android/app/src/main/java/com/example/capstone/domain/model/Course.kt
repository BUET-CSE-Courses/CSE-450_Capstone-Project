package com.example.capstone.domain.model

/** The signed-in person, from `GET /api/me`. */
data class Me(
    val displayName: String,
    val email: String,
    /** "student", "teacher" or "admin" (`backend/security.py`, `require_teacher`). */
    val role: String
) {
    /**
     * This app is for students. Teachers and admins use the website. Admin is
     * blocked too, because the web end treats it as a teacher role
     * (`require_teacher` accepts both).
     */
    val isStudent: Boolean get() = role == "student"
}

/** A course the student takes. */
data class Course(
    val id: String,
    val title: String,
    val teacherName: String,
    val archived: Boolean,
    val studentCount: Int
)

/** A finalized paper in one course, with this student's progress on it. */
data class StudentAssignment(
    val questionId: String,
    val courseId: String,
    val title: String,
    val totalMarks: Int,
    val pageCount: Int?,
    val submissionId: String?,
    val submittedPages: Int,
    val handedIn: Boolean,
    /** The submission's `grading_status`, or null before anything is uploaded. */
    val gradingStatus: String?,
    val released: Boolean,
    val earned: Double?,
    val maxScore: Int?
) {
    /** One line for the list. Marks only once released, as the web end serves them. */
    val status: String
        get() = when {
            released && earned != null && maxScore != null ->
                "Marks released: ${formatMark(earned)} / $maxScore"
            released -> "Marks released"
            handedIn -> when (gradingStatus) {
                "queued", "grading" -> "Handed in, being marked"
                "graded" -> "Handed in, marked (waiting for release)"
                "failed" -> "Handed in, marking failed"
                else -> "Handed in"
            }
            submittedPages > 0 -> {
                val of = pageCount?.let { " of $it" } ?: ""
                "$submittedPages$of page(s) uploaded, not handed in"
            }
            else -> "Not started"
        }

    private fun formatMark(value: Double): String =
        if (value == Math.floor(value)) value.toLong().toString()
        else String.format(java.util.Locale.ROOT, "%.1f", value)
}
