package com.example.capstone.data.remote

import com.google.gson.annotations.SerializedName

/**
 * Wire shapes for courses and assignments. Every name here is the web end's
 * own, from Script-Checker-Web-End `backend/schemas.py`. Timestamps stay
 * strings: the app only shows them.
 */

/** `CourseOut`, served by `GET /api/courses` and `POST /api/courses/join`. */
data class CourseDto(
    val id: String,
    val title: String,
    @SerializedName("join_code")
    val joinCode: String,
    @SerializedName("teacher_id")
    val teacherId: String,
    @SerializedName("teacher_name")
    val teacherName: String,
    val archived: Boolean,
    @SerializedName("created_at")
    val createdAt: String,
    @SerializedName("student_count")
    val studentCount: Int,
    /**
     * "teacher" or "student": how the caller relates to this course. The list
     * route returns both kinds (`routers/courses.py` list_my_courses).
     */
    @SerializedName("my_role")
    val myRole: String = "student"
)

/**
 * `JoinRequest`, the body of `POST /api/courses/join`. 1 to 32 characters;
 * the server strips and upper-cases it.
 */
data class JoinRequest(
    @SerializedName("join_code")
    val joinCode: String
)

/**
 * `StudentAssignment`, served by `GET /api/student/assignments?course_id=`.
 * Finalized papers only. [earned] and [maxScore] stay null until the teacher
 * releases the marks.
 */
data class StudentAssignmentDto(
    @SerializedName("question_id")
    val questionId: String,
    @SerializedName("course_id")
    val courseId: String,
    val title: String?,
    @SerializedName("total_marks")
    val totalMarks: Int,
    @SerializedName("page_count")
    val pageCount: Int?,
    @SerializedName("finalized_at")
    val finalizedAt: String?,
    @SerializedName("submission_id")
    val submissionId: String?,
    @SerializedName("submitted_pages")
    val submittedPages: Int = 0,
    @SerializedName("handed_in")
    val handedIn: Boolean = false,
    /** The submission's `grading_status`: ungraded, queued, grading, graded or failed. */
    @SerializedName("submission_status")
    val submissionStatus: String?,
    val released: Boolean,
    val earned: Double?,
    @SerializedName("max_score")
    val maxScore: Int?
)
