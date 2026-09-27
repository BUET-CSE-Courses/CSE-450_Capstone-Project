package com.example.capstone.domain.grading

import com.example.capstone.data.remote.OnDeviceGradesDto
import kotlinx.coroutines.delay

/**
 * Polls the student's own grades after the results post, so boxes the server re-marks
 * (fallback) fill in as they finish. Every [intervalMs] (5 s); stops at "graded" or
 * "failed", or after [timeoutMs] (10 min). A failed fetch is reported and polling goes on.
 */
class GradesPoller(
    private val intervalMs: Long = 5_000L,
    private val timeoutMs: Long = 10 * 60_000L,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    sealed interface Update {
        data class Grades(val grades: OnDeviceGradesDto) : Update
        data class FetchFailed(val error: Throwable) : Update
    }

    sealed interface End {
        /** The paper reached "graded" or "failed". */
        data class Terminal(val grades: OnDeviceGradesDto) : End

        /** Still not done after [timeoutMs]. [last] is the newest marks seen, if any. */
        data class TimedOut(val last: OnDeviceGradesDto?) : End
    }

    /**
     * @param fetchFirst false when fresh grades are already on screen (the post's reply):
     *   the first fetch then waits one interval.
     */
    suspend fun poll(
        fetch: suspend () -> Result<OnDeviceGradesDto>,
        fetchFirst: Boolean = true,
        onUpdate: (Update) -> Unit
    ): End {
        val started = clock()
        var last: OnDeviceGradesDto? = null
        if (!fetchFirst) sleep(intervalMs)
        while (true) {
            fetch().fold(
                onSuccess = { grades ->
                    last = grades
                    onUpdate(Update.Grades(grades))
                    if (isTerminal(grades.gradingStatus)) return End.Terminal(grades)
                },
                onFailure = { onUpdate(Update.FetchFailed(it)) }
            )
            if (clock() - started >= timeoutMs) return End.TimedOut(last)
            sleep(intervalMs)
        }
    }

    companion object {
        fun isTerminal(status: String): Boolean =
            status == OnDeviceGradesDto.GRADED || status == OnDeviceGradesDto.FAILED
    }
}
