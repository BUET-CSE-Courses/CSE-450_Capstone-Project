package com.example.capstone.domain.grading

import com.example.capstone.data.local.GradingRunRecord
import com.example.capstone.data.remote.OnDeviceBoxGradeDto
import com.example.capstone.data.remote.OnDeviceGradesDto
import java.util.Locale

/** What a box's mark is, as the results screen tells them apart. */
enum class MarkState {
    /** A score stands (0 included: a real 0, e.g. a blank box). */
    SCORED,

    /** No score; a teacher has to mark it. Never shown as 0. */
    NEEDS_REVIEW,

    /** No score yet; the server is re-marking it. */
    PENDING_FALLBACK,

    /** No row yet. */
    NOT_MARKED
}

/** Who produced the mark on screen. */
enum class MarkedBy { PHONE, SERVER_MODEL, TEACHER, TEACHER_CHANGED, NOBODY }

/** One box as the results screen shows it. Every text is ready to show; none is ever "null". */
data class BoxMarkView(
    val answerBoxId: String,
    val label: String,
    val state: MarkState,
    /** "3 / 5", "0 / 5", or "– / 5" when there is no score. */
    val scoreText: String,
    val feedback: String?,
    /** What the phone read, only for a mark the phone made. Shown collapsed. */
    val transcript: String?,
    val markedBy: MarkedBy,
    val markedByText: String,
    /** Why it needs review or is not marked, in words; null when scored. */
    val reason: String?
)

/**
 * Turns the own-grades reply (`GET /api/student/submissions/{id}/grades`) into screen text.
 * The marks, the total and the provisional flag come from the server; the phone adds only
 * what the server does not keep: the transcript it read.
 */
object MarkDisplay {

    const val PROVISIONAL_BANNER = "Provisional — final after your teacher releases marks."
    const val RELEASED_BANNER = "Final marks, released by your teacher."

    // services/on_device.py review reasons (web end, feature/on-device-grading).
    private const val AWAITING_REMARK = "Awaiting server re-mark"
    private const val FALLBACK_UNAVAILABLE = "Fallback unavailable"
    private const val REMARK_DID_NOT_FINISH = "Server re-mark did not finish"

    fun banner(g: OnDeviceGradesDto): String = if (g.released) RELEASED_BANNER else PROVISIONAL_BANNER

    /** "7 / 10", from the server's own total (`submission_totals`). */
    fun totalText(g: OnDeviceGradesDto): String = "${formatMark(g.earned)} / ${g.maxScore}"

    /** What the total leaves out, e.g. "1 answer needs your teacher · 1 still being marked". */
    fun totalNote(g: OnDeviceGradesDto): String? {
        val parts = mutableListOf<String>()
        if (g.needsReviewCount > 0) {
            parts += "${g.needsReviewCount} ${if (g.needsReviewCount == 1) "answer needs" else "answers need"} your teacher"
        }
        if (g.pendingFallbackCount > 0) parts += "${g.pendingFallbackCount} still being marked by the server"
        return parts.joinToString(" · ").ifBlank { null }
    }

    fun boxes(g: OnDeviceGradesDto, local: GradingRunRecord?): List<BoxMarkView> =
        g.boxes.sortedBy { it.orderIndex }.map { box(it, local) }

    fun box(b: OnDeviceBoxGradeDto, local: GradingRunRecord?): BoxMarkView {
        val state = when {
            b.score != null -> MarkState.SCORED
            b.pendingFallback -> MarkState.PENDING_FALLBACK
            b.needsManualReview -> MarkState.NEEDS_REVIEW
            else -> MarkState.NOT_MARKED
        }
        val phone = local?.resultFor(b.answerBoxId)
        val markedBy = when (b.provider) {
            null -> MarkedBy.NOBODY
            PROVIDER_PHONE -> if (phone != null && postedScore(phone) != b.score) MarkedBy.TEACHER_CHANGED else MarkedBy.PHONE
            PROVIDER_SERVER_MODEL -> MarkedBy.SERVER_MODEL
            // A teacher pressing Grade on the website: the row carries that server
            // provider's name (grading_runner.py). UNVERIFIED which names appear.
            else -> MarkedBy.TEACHER
        }
        val max = b.maxScore?.toString() ?: "?"
        return BoxMarkView(
            answerBoxId = b.answerBoxId,
            label = b.label?.takeIf { it.isNotBlank() } ?: "Answer ${b.orderIndex + 1}",
            state = state,
            scoreText = "${b.score?.let(::formatMark) ?: "–"} / $max",
            feedback = b.feedback?.takeIf { it.isNotBlank() },
            transcript = if (markedBy == MarkedBy.PHONE) phone?.transcript?.takeIf { it.isNotBlank() } else null,
            markedBy = markedBy,
            markedByText = when (markedBy) {
                MarkedBy.PHONE -> "Marked on your phone"
                MarkedBy.SERVER_MODEL -> "Marked by the server's model"
                MarkedBy.TEACHER -> "Marked by your teacher"
                MarkedBy.TEACHER_CHANGED -> "Changed by your teacher"
                MarkedBy.NOBODY -> "Not marked yet"
            },
            reason = when (state) {
                MarkState.SCORED -> null
                MarkState.PENDING_FALLBACK -> "The server is marking this again. It fills in when ready."
                MarkState.NEEDS_REVIEW -> reviewText(b.reviewReason)
                MarkState.NOT_MARKED -> "No mark yet."
            }
        )
    }

    /** The server's reason in words a student can read. Unknown reasons are shown as sent. */
    fun reviewText(reason: String?): String = when (reason?.trim()) {
        null, "" -> "Your teacher will mark this answer."
        FALLBACK_UNAVAILABLE -> "The phone wasn't sure and server re-marking is unavailable, so your teacher will mark this."
        REMARK_DID_NOT_FINISH -> "The server's re-mark didn't finish, so your teacher will mark this."
        AWAITING_REMARK -> "The server is marking this again."
        else -> "Your teacher will mark this: $reason"
    }

    /** What the phone posted as the score: [MarkState.SCORED] boxes only; null otherwise. */
    private fun postedScore(r: com.example.capstone.data.local.StoredBoxResult): Double? = when (r.status) {
        BoxStatus.GRADED -> r.score
        BoxStatus.BLANK -> 0.0
        else -> null
    }

    /** A float as the server stores it, never rounded: "3", "2.5". */
    fun formatMark(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString()
        else String.format(Locale.ROOT, "%s", value)

    const val PROVIDER_PHONE = "on_device"
    const val PROVIDER_SERVER_MODEL = "self_hosted"
}
