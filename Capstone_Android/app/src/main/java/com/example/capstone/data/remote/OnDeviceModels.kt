package com.example.capstone.data.remote

import com.google.gson.annotations.SerializedName

/*
 * The on-device grading run, Script-Checker-Web-End branch feature/on-device-grading:
 * `backend/routers/on_device.py` routes 3-6, shapes `OnDeviceRunStarted`,
 * `OnDeviceBoxResult`, `OnDeviceResultsIn`, `OnDeviceRunInfo`, `OnDeviceBoxGrade`,
 * `OnDeviceGradesOut`, `OnDeviceResultsOut` and `ReevaluationRequest` in `backend/schemas.py`.
 * Field names are copied from there.
 */

/** `POST .../on-device/start`. Times are ISO-8601 strings as FastAPI writes them. */
data class OnDeviceRunStartedDto(
    @SerializedName("run_token")
    val runToken: String,
    @SerializedName("started_at")
    val startedAt: String?,
    @SerializedName("lease_expires_at")
    val leaseExpiresAt: String?,
    /** Every box minus the protected ones. The results post must cover exactly these. */
    @SerializedName("eligible_box_ids")
    val eligibleBoxIds: List<String>,
    /** Released or teacher-decided boxes. Never posted. */
    @SerializedName("protected_box_ids")
    val protectedBoxIds: List<String>
)

/**
 * One box in the results post. Gson leaves null fields out, which the server reads as
 * its defaults (all None).
 */
data class OnDeviceBoxResultDto(
    @SerializedName("answer_box_id")
    val answerBoxId: String,
    /** "scored", "blank" or "needs_review". */
    val outcome: String,
    /** Required for "scored", 0..points; ignored otherwise. */
    val score: Double? = null,
    /** At most [MAX_FEEDBACK] characters (pydantic `max_length`). */
    val feedback: String? = null,
    /** 0..100. */
    val confidence: Double? = null,
    /** At most [MAX_RAW_RESPONSE] characters. */
    @SerializedName("raw_response")
    val rawResponse: String? = null,
    /** At most [MAX_REVIEW_REASON] characters. */
    @SerializedName("review_reason")
    val reviewReason: String? = null
) {
    companion object {
        const val SCORED = "scored"
        const val BLANK = "blank"
        const val NEEDS_REVIEW = "needs_review"

        // schemas.py OnDeviceBoxResult: a longer string is a 422 for the whole post.
        const val MAX_FEEDBACK = 5000
        const val MAX_RAW_RESPONSE = 20000
        const val MAX_REVIEW_REASON = 500
    }
}

/** `POST .../on-device/results`. */
data class OnDeviceResultsInDto(
    @SerializedName("run_token")
    val runToken: String,
    /** The app's fallbackEnabled flag. The server also needs SELF_HOSTED_LLM_URL. */
    @SerializedName("use_fallback")
    val useFallback: Boolean,
    @SerializedName("fallback_box_ids")
    val fallbackBoxIds: List<String>,
    val results: List<OnDeviceBoxResultDto>
)

data class OnDeviceRunInfoDto(
    @SerializedName("started_at")
    val startedAt: String?,
    @SerializedName("posted_at")
    val postedAt: String?,
    @SerializedName("lease_expires_at")
    val leaseExpiresAt: String?
)

/** One box of `GET .../grades` (my grades). */
data class OnDeviceBoxGradeDto(
    @SerializedName("answer_box_id")
    val answerBoxId: String,
    val label: String?,
    @SerializedName("order_index")
    val orderIndex: Int,
    @SerializedName("max_score")
    val maxScore: Int?,
    /** The teacher's override when there is one, else the model's. Null: no mark yet. */
    val score: Double?,
    val feedback: String?,
    /** "on_device", "self_hosted", a teacher run's provider, or null (no row yet). */
    val provider: String?,
    @SerializedName("needs_manual_review")
    val needsManualReview: Boolean,
    @SerializedName("review_reason")
    val reviewReason: String?,
    /** The server is still re-marking this box. */
    @SerializedName("pending_fallback")
    val pendingFallback: Boolean
)

/**
 * `GET /api/student/submissions/{id}/grades`, and the reply of the results post
 * (`OnDeviceResultsOut` adds [fallback] and [fallbackMessage], which are null here
 * for a my-grades read). **Provisional until [released].**
 */
data class OnDeviceGradesDto(
    @SerializedName("submission_id")
    val submissionId: String,
    @SerializedName("question_id")
    val questionId: String,
    /** ungraded, queued, grading, graded or failed. */
    @SerializedName("grading_status")
    val gradingStatus: String,
    @SerializedName("grading_error")
    val gradingError: String?,
    val released: Boolean,
    val provisional: Boolean,
    val earned: Double,
    @SerializedName("max_score")
    val maxScore: Int,
    @SerializedName("graded_count")
    val gradedCount: Int,
    @SerializedName("needs_review_count")
    val needsReviewCount: Int,
    @SerializedName("pending_fallback_count")
    val pendingFallbackCount: Int,
    val run: OnDeviceRunInfoDto?,
    val boxes: List<OnDeviceBoxGradeDto>,
    /** Results post only: "none", "scheduled", "unavailable" or "completed". */
    val fallback: String? = null,
    @SerializedName("fallback_message")
    val fallbackMessage: String? = null
) {
    companion object {
        const val GRADED = "graded"
        const val FAILED = "failed"
        const val GRADING = "grading"
        const val QUEUED = "queued"
        /** Nothing marked: never graded, or the teacher pressed Reset marks. */
        const val UNGRADED = "ungraded"
    }
}

/** `POST .../re-evaluation`. No box ids means the whole paper. */
data class ReevaluationRequestDto(
    @SerializedName("answer_box_ids")
    val answerBoxIds: List<String>? = null,
    /** At most [MAX_MESSAGE] characters. */
    val message: String? = null
) {
    companion object {
        const val MAX_MESSAGE = 1000
    }
}

data class ReevaluationResponseDto(
    @SerializedName("submission_id")
    val submissionId: String,
    /** False when the course has no teacher to tell. */
    val notified: Boolean
)
