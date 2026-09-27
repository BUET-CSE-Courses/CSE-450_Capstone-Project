package com.example.capstone.domain.grading

import com.example.capstone.data.local.GradingRunRecord
import com.example.capstone.data.remote.OnDeviceBoxGradeDto
import com.example.capstone.data.remote.OnDeviceGradesDto
import com.example.capstone.data.remote.OnDeviceRunInfoDto
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkDisplayTest {

    private fun box(
        score: Double? = null,
        provider: String? = "on_device",
        review: Boolean = false,
        reason: String? = null,
        pending: Boolean = false,
        label: String? = "Q1",
        feedback: String? = null,
        max: Int? = 5
    ) = OnDeviceBoxGradeDto(
        answerBoxId = "ab_1", label = label, orderIndex = 0, maxScore = max, score = score,
        feedback = feedback, provider = provider, needsManualReview = review, reviewReason = reason,
        pendingFallback = pending
    )

    private fun grades(released: Boolean = false, earned: Double = 7.0) = OnDeviceGradesDto(
        submissionId = "s", questionId = "q", gradingStatus = "graded", gradingError = null,
        released = released, provisional = !released, earned = earned, maxScore = 10, gradedCount = 2,
        needsReviewCount = 1, pendingFallbackCount = 0, run = OnDeviceRunInfoDto(null, null, null), boxes = emptyList()
    )

    @Test
    fun needsReviewIsNotAZero() {
        val review = MarkDisplay.box(box(review = true, reason = "Fallback unavailable"), null)
        val zero = MarkDisplay.box(box(score = 0.0), null)
        assertThat(review.state).isEqualTo(MarkState.NEEDS_REVIEW)
        assertThat(review.scoreText).isEqualTo("– / 5")
        assertThat(zero.state).isEqualTo(MarkState.SCORED)
        assertThat(zero.scoreText).isEqualTo("0 / 5")
        assertThat(review.scoreText).isNotEqualTo(zero.scoreText)
    }

    @Test
    fun whoMarkedIt() {
        assertThat(MarkDisplay.box(box(score = 3.0, provider = "on_device"), null).markedBy).isEqualTo(MarkedBy.PHONE)
        assertThat(MarkDisplay.box(box(score = 3.0, provider = "self_hosted"), null).markedBy).isEqualTo(MarkedBy.SERVER_MODEL)
        assertThat(MarkDisplay.box(box(score = 3.0, provider = "gemini"), null).markedBy).isEqualTo(MarkedBy.TEACHER)
        assertThat(MarkDisplay.box(box(provider = null), null).markedBy).isEqualTo(MarkedBy.NOBODY)
    }

    @Test
    fun aPhoneRowWhoseScoreDiffersFromWhatThePhonePostedWasChangedByTheTeacher() {
        val local = GradingRunRecord(submissionId = "s", questionId = "q").withResult(
            BoxGradeResult("ab_1", 2.0, 5, "read", "ok", 80.0, BoxStatus.GRADED, null, "raw", "m", 1)
        )
        assertThat(MarkDisplay.box(box(score = 2.0), local).markedBy).isEqualTo(MarkedBy.PHONE)
        val changed = MarkDisplay.box(box(score = 4.0), local)
        assertThat(changed.markedBy).isEqualTo(MarkedBy.TEACHER_CHANGED)
        assertThat(changed.markedByText).isEqualTo("Changed by your teacher")
        // The phone's transcript is only shown for the phone's own mark.
        assertThat(changed.transcript).isNull()
        assertThat(MarkDisplay.box(box(score = 2.0), local).transcript).isEqualTo("read")
    }

    @Test
    fun pendingFallbackSaysItIsComing() {
        val v = MarkDisplay.box(box(pending = true, reason = "Awaiting server re-mark"), null)
        assertThat(v.state).isEqualTo(MarkState.PENDING_FALLBACK)
        assertThat(v.reason).contains("fills in")
    }

    @Test
    fun neverShowsNull() {
        val v = MarkDisplay.box(box(label = null, max = null, provider = null, feedback = "  "), null)
        assertThat(v.label).isEqualTo("Answer 1")
        assertThat(v.scoreText).isEqualTo("– / ?")
        assertThat(v.feedback).isNull()
        listOf(v.label, v.scoreText, v.markedByText, v.reason.orEmpty()).forEach { assertThat(it).doesNotContain("null") }
        assertThat(MarkDisplay.reviewText(null)).doesNotContain("null")
    }

    @Test
    fun bannerAndTotal() {
        assertThat(MarkDisplay.banner(grades())).isEqualTo("Provisional — final after your teacher releases marks.")
        assertThat(MarkDisplay.banner(grades(released = true))).isEqualTo(MarkDisplay.RELEASED_BANNER)
        assertThat(MarkDisplay.totalText(grades(earned = 7.25))).isEqualTo("7.25 / 10")
        assertThat(MarkDisplay.totalNote(grades())).isEqualTo("1 answer needs your teacher")
    }
}
