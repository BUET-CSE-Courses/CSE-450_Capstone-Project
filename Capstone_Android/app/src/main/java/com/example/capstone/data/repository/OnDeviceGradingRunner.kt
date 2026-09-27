package com.example.capstone.data.repository

import com.example.capstone.data.auth.SignInRequiredException
import com.example.capstone.data.local.GradingRunRecord
import com.example.capstone.data.local.GradingRunStore
import com.example.capstone.data.local.RunPhase
import com.example.capstone.data.local.StoredBoxResult
import com.example.capstone.data.remote.ApiService
import com.example.capstone.data.remote.OnDeviceBoxResultDto
import com.example.capstone.data.remote.OnDeviceGradesDto
import com.example.capstone.data.remote.OnDeviceResultsInDto
import com.example.capstone.data.remote.parseErrorDetail
import com.example.capstone.domain.grading.BoxGradeResult
import com.example.capstone.domain.grading.BoxGrader
import com.example.capstone.domain.grading.BoxStatus
import com.example.capstone.domain.grading.GradingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Waits between attempts of one call. Attempts = delays + 1. */
class RetryPolicy(val delaysMs: List<Long> = listOf(2_000L, 5_000L, 15_000L, 30_000L))

/** How a grading run ended. */
sealed interface RunOutcome {
    /** The server acknowledged this phone's results post. [grades] is its reply. */
    data class Posted(val grades: OnDeviceGradesDto) : RunOutcome

    /**
     * The server already has marks this phone did not post (teacher graded on the website,
     * or another run posted). Nothing was posted. [message] says why, for the student.
     */
    data class ServerHasMarks(val grades: OnDeviceGradesDto, val message: String?) : RunOutcome

    /** Stopped; retrying by itself will not help. */
    data class Blocked(val message: String) : RunOutcome

    /** The network failed after every retry. Saved progress stays; run again later. */
    data class RetryLater(val message: String) : RunOutcome
}

/** For the notification and the screen: "Grading box [current] of [total]". */
data class RunProgress(val phase: RunPhase, val current: Int, val total: Int, val message: String?)

/**
 * One on-device grading run (plan decisions 7, 10, 11; web end `routers/on_device.py`):
 *
 * 1. `POST .../on-device/start`: a run token and the eligible boxes;
 * 2. every eligible box graded **one at a time**, each result saved as soon as it exists;
 * 3. **one** `POST .../on-device/results` with the token: every eligible box, and, when
 *    fallback is on, the NEEDS_FALLBACK boxes as `fallback_box_ids`. With fallback off
 *    they are posted as needs review with the phone's own reason and not flagged.
 *
 * Resume: progress is in [GradingRunStore]. A run with every result saved and a token posts
 * with that token first (the server answers a repeat with 200), so a post whose reply was
 * lost is never followed by a second run. Otherwise it calls start again (a new token) and
 * grades only the boxes that have no saved result.
 *
 * 409s: a start refused because the server already has marks (graded, released, a teacher's
 * run, a posted run) ends the run with those marks. A results post refused because the token
 * expired or was superseded restarts with a new token, unless the server has marks by then.
 *
 * Synced means [RunPhase.POSTED], set only from a 2xx reply. There is one run per
 * submission in the process at a time ([locks]); WorkManager's unique work does the same
 * across workers.
 */
class OnDeviceGradingRunner(
    private val api: ApiService,
    private val store: GradingRunStore,
    private val grader: GradingService,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val retry: RetryPolicy = RetryPolicy(),
    private val maxRestarts: Int = 3
) {

    suspend fun run(
        submissionId: String,
        questionId: String,
        fallbackEnabled: Boolean,
        prepare: suspend () -> PrepareResult,
        onProgress: suspend (RunProgress) -> Unit = {},
        /** Debug: flag every box for the server's re-mark ([resultsBody]). */
        forceRemark: Boolean = false
    ): RunOutcome = lockFor(submissionId).withLock {
        Run(submissionId, questionId, fallbackEnabled, prepare, onProgress, forceRemark).go()
    }

    /**
     * Lets a finished or blocked run go again (the server failed the paper, or a teacher
     * reset it). The token is dropped, so the next run starts afresh. Saved box results are
     * kept, so they are not graded again, except as [GradingRunRecord.forGradingAgain] says:
     * all of them go when the teacher reset the marks ([everything]), and results whose model
     * call failed always go.
     */
    suspend fun reopen(submissionId: String, everything: Boolean = false) {
        lockFor(submissionId).withLock {
            store.update(submissionId) {
                it?.forGradingAgain(everything)
                    ?.copy(phase = RunPhase.PREPARING, runToken = null, postedToken = null, message = null)
            }
        }
    }

    private inner class Run(
        val sid: String,
        val questionId: String,
        val fallbackEnabled: Boolean,
        val prepare: suspend () -> PrepareResult,
        val onProgress: suspend (RunProgress) -> Unit,
        val forceRemark: Boolean
    ) {
        lateinit var rec: GradingRunRecord
        var paper: PreparedPaper? = null

        suspend fun go(): RunOutcome {
            val saved = store.get(sid)
            if (saved != null && saved.isFinished) {
                saved.serverGrades?.let { grades ->
                    return if (saved.phase == RunPhase.POSTED) RunOutcome.Posted(grades)
                    else RunOutcome.ServerHasMarks(grades, saved.message)
                }
            }
            rec = save {
                (it ?: GradingRunRecord(submissionId = sid, questionId = questionId))
                    .copy(questionId = questionId, fallbackEnabled = fallbackEnabled, message = null)
            }

            // A run with nothing graded yet checks the photos before it takes the server's lease.
            if (rec.resultsOrEmpty.isEmpty()) {
                progress(RunPhase.PREPARING)
                if (preparedPaper() == null) return blocked
            }

            var needStart = !(rec.runToken != null && rec.hasAllResults)
            var restarts = 0
            while (true) {
                if (needStart) {
                    progress(RunPhase.STARTING)
                    when (val s = call("Starting the grading run") { api.startOnDeviceRun(sid) }) {
                        is Call.Ok -> {
                            rec = save {
                                it!!.copy(
                                    runToken = s.value.runToken,
                                    eligibleBoxIds = s.value.eligibleBoxIds,
                                    protectedBoxIds = s.value.protectedBoxIds
                                )
                            }
                            needStart = false
                        }
                        is Call.Http ->
                            return if (s.code == 409) afterConflict(s.detail, "Couldn't start grading")
                            else block("Couldn't start grading. ${s.describe()}")
                        is Call.Offline -> return retryLater(s.message)
                        is Call.SignIn -> return block(s.message)
                    }
                }

                gradeMissing()?.let { return it }

                progress(RunPhase.POSTING)
                val body = resultsBody(rec, fallbackEnabled, forceRemark)
                when (val r = call("Sending the marks") { api.postOnDeviceResults(sid, body) }) {
                    is Call.Ok -> {
                        rec = save {
                            it!!.copy(
                                phase = RunPhase.POSTED,
                                postedToken = body.runToken,
                                serverGrades = r.value,
                                message = r.value.fallbackMessage,
                                current = 0,
                                total = 0
                            )
                        }
                        return RunOutcome.Posted(r.value)
                    }
                    is Call.Http -> {
                        if (r.code != 409 && r.code != 400) return block("The web end refused the marks. ${r.describe()}")
                        if (r.code == 409) {
                            // Expired, superseded, or no longer active. Has someone else marked it?
                            when (val g = call("Checking your marks") { api.myGrades(sid) }) {
                                is Call.Ok -> if (serverHasMarks(g.value)) return finishElsewhere(g.value)
                                is Call.Http -> return block("Couldn't check your marks. ${g.describe()}")
                                is Call.Offline -> return retryLater(g.message)
                                is Call.SignIn -> return block(g.message)
                            }
                        }
                        // 409: a new token. 400: the eligible boxes changed (a teacher decided
                        // one meanwhile); a new start lists them again.
                        if (++restarts > maxRestarts) {
                            return block("The grading run kept being refused. ${r.describe()}")
                        }
                        rec = save { it!!.copy(runToken = null) }
                        needStart = true
                    }
                    // Token and results stay saved: the next run posts the same thing again.
                    is Call.Offline -> return retryLater(r.message)
                    is Call.SignIn -> return block(r.message)
                }
            }
        }

        /** Grades every eligible box without a saved result, in paper order. Null when done. */
        private suspend fun gradeMissing(): RunOutcome? {
            val eligible = rec.eligibleOrEmpty
            if (eligible.all { rec.resultFor(it) != null }) return null
            val p = preparedPaper() ?: return blocked
            val ordered = eligible.sortedBy { p.boxes[it]?.orderIndex ?: Int.MAX_VALUE }
            for ((i, boxId) in ordered.withIndex()) {
                if (rec.resultFor(boxId) != null) continue
                progress(RunPhase.GRADING, current = i + 1, total = ordered.size)
                val starts = if (rec.gradingBoxId == boxId) rec.gradingBoxStarts else 0
                val result = if (starts >= MAX_BOX_STARTS) {
                    // The app died during this box every time: stop trying, send it to review.
                    BoxGradeResult(
                        answerBoxId = boxId, score = null, maxScore = p.boxes[boxId]?.item?.maxScore ?: 0,
                        transcript = null, feedback = null, confidence = null, status = BoxStatus.NEEDS_FALLBACK,
                        reason = CRASHED, rawReply = null, modelId = null, durationMs = 0L
                    )
                } else {
                    rec = save { it!!.copy(gradingBoxId = boxId, gradingBoxStarts = starts + 1) }
                    gradeOne(boxId, p.boxes[boxId])
                }
                rec = save { it!!.withResult(result).copy(gradingBoxId = null, gradingBoxStarts = 0) }
            }
            return null
        }

        private suspend fun gradeOne(boxId: String, box: PreparedBox?): BoxGradeResult {
            fun fallback(reason: String, max: Int) = BoxGradeResult(
                answerBoxId = boxId, score = null, maxScore = max, transcript = null, feedback = null,
                confidence = null, status = BoxStatus.NEEDS_FALLBACK, reason = reason, rawReply = null,
                modelId = null, durationMs = 0L
            )
            if (box == null) return fallback(NOT_IN_PACK, 0)
            if (box.problem != null && box.item.blockedReason == null) return fallback(box.problem, box.item.maxScore)
            return try {
                grader.grade(box.item)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                fallback("${BoxGrader.PHONE_FAILED}: ${t.message ?: t.javaClass.simpleName}", box.item.maxScore)
            }
        }

        private var blocked: RunOutcome = RunOutcome.Blocked("")

        /** The prepared paper, or null after recording why it could not be prepared. */
        private suspend fun preparedPaper(): PreparedPaper? {
            paper?.let { return it }
            return when (val r = try {
                prepare()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                PrepareResult.Refused("Couldn't read this paper's photos on the phone.", e.message)
            }) {
                is PrepareResult.Ready -> r.paper.also { paper = it }
                is PrepareResult.Refused -> {
                    blocked = block(listOfNotNull(r.message, r.detail).joinToString(" "))
                    null
                }
            }
        }

        private suspend fun afterConflict(detail: String?, what: String): RunOutcome =
            when (val g = call("Checking your marks") { api.myGrades(sid) }) {
                is Call.Ok -> if (serverHasMarks(g.value)) finishElsewhere(g.value)
                else block("$what. ${detail ?: "HTTP 409"}")
                is Call.Http -> block("$what. ${detail ?: "HTTP 409"}")
                is Call.Offline -> retryLater(g.message)
                is Call.SignIn -> block(g.message)
            }

        private suspend fun finishElsewhere(grades: OnDeviceGradesDto): RunOutcome {
            val message = elsewhereMessage(grades)
            rec = save {
                it!!.copy(
                    phase = RunPhase.SERVER_HAS_MARKS,
                    serverGrades = grades,
                    message = message,
                    runToken = null,
                    current = 0,
                    total = 0
                )
            }
            return RunOutcome.ServerHasMarks(grades, message)
        }

        private suspend fun block(message: String): RunOutcome {
            rec = save { it!!.copy(phase = RunPhase.BLOCKED, message = message, current = 0, total = 0) }
            onProgress(RunProgress(RunPhase.BLOCKED, 0, 0, message))
            return RunOutcome.Blocked(message)
        }

        private suspend fun retryLater(message: String): RunOutcome {
            rec = save { it!!.copy(phase = RunPhase.WAITING_FOR_NETWORK, message = message) }
            onProgress(RunProgress(RunPhase.WAITING_FOR_NETWORK, 0, 0, message))
            return RunOutcome.RetryLater(message)
        }

        private suspend fun progress(phase: RunPhase, current: Int = 0, total: Int = 0, message: String? = null) {
            rec = save { it!!.copy(phase = phase, current = current, total = total, message = message) }
            onProgress(RunProgress(phase, current, total, message))
        }

        private suspend fun save(change: (GradingRunRecord?) -> GradingRunRecord): GradingRunRecord =
            store.update(sid, change)!!

        /** Retries network failures and 408/429/5xx with [retry]'s backoff. */
        private suspend fun <T> call(what: String, block: suspend () -> T): Call<T> {
            var attempt = 0
            while (true) {
                val failure: String = try {
                    return Call.Ok(block())
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: SignInRequiredException) {
                    return Call.SignIn(e.message ?: "Sign in again.")
                } catch (e: HttpException) {
                    val detail = try {
                        parseErrorDetail(e.response()?.errorBody()?.string())
                    } catch (ignored: Exception) {
                        null
                    }
                    if (e.code() !in RETRYABLE_CODES) return Call.Http(e.code(), detail)
                    "HTTP ${e.code()}" + (detail?.let { ": $it" } ?: "")
                } catch (e: IOException) {
                    "${e.javaClass.simpleName}: ${e.message ?: "no detail"}"
                }
                if (attempt >= retry.delaysMs.size) return Call.Offline("$what failed: $failure")
                val wait = retry.delaysMs[attempt++]
                rec = save {
                    it!!.copy(
                        phase = RunPhase.WAITING_FOR_NETWORK,
                        message = "$what failed ($failure). Trying again in ${(wait + 999) / 1000} s."
                    )
                }
                onProgress(RunProgress(RunPhase.WAITING_FOR_NETWORK, 0, 0, rec.message))
                sleep(wait)
            }
        }
    }

    private sealed interface Call<out T> {
        data class Ok<T>(val value: T) : Call<T>
        data class Http(val code: Int, val detail: String?) : Call<Nothing> {
            fun describe() = if (detail != null) "$code: $detail" else "HTTP $code"
        }
        data class Offline(val message: String) : Call<Nothing>
        data class SignIn(val message: String) : Call<Nothing>
    }

    companion object {
        const val NOT_IN_PACK = "This answer box isn't in the copy of the paper saved on this phone"

        /** Grading one box may start this many times; after that the app is taken to crash on it. */
        const val MAX_BOX_STARTS = 2

        /** Starts with [BoxGrader.PHONE_FAILED], so "Grade again" tries the box once more. */
        val CRASHED = "${BoxGrader.PHONE_FAILED}: the app stopped while grading this answer, $MAX_BOX_STARTS times"

        private val RETRYABLE_CODES = setOf(408, 429, 500, 502, 503, 504)

        private val locks = ConcurrentHashMap<String, Mutex>()
        private fun lockFor(submissionId: String): Mutex = locks.getOrPut(submissionId) { Mutex() }

        /**
         * True when the server has (or is producing) marks this run must not overwrite:
         * graded (or released), a teacher's run queued or going (no phone start time), or a
         * phone run already posted and waiting for the server's re-mark.
         */
        fun serverHasMarks(g: OnDeviceGradesDto): Boolean = when (g.gradingStatus) {
            OnDeviceGradesDto.GRADED, OnDeviceGradesDto.QUEUED -> true
            OnDeviceGradesDto.GRADING -> g.run?.startedAt == null || g.run?.postedAt != null
            else -> false
        }

        fun elsewhereMessage(g: OnDeviceGradesDto): String = when {
            g.gradingStatus == OnDeviceGradesDto.GRADED && g.released -> "Your teacher has released the marks."
            g.gradingStatus == OnDeviceGradesDto.GRADED -> "This paper was already marked, so the phone's marks were not sent."
            g.gradingStatus == OnDeviceGradesDto.GRADING && g.run?.postedAt != null ->
                "The marks were already sent; the server is finishing some answers."
            else -> "Your teacher is marking this on the website."
        }

        /**
         * The results post for [rec]: every eligible box, in the order start listed them.
         * With [forceRemark] (debug switch "Force server re-mark", session 9) every eligible box is
         * flagged and `use_fallback` is sent true, whatever the phone's results and
         * [fallbackEnabled] say, so the server's self-hosted re-mark can be tested end to end.
         * NEEDS_FALLBACK boxes are flagged only when [fallbackEnabled]; otherwise they are plain
         * needs review with the phone's reason. Strings are cut to the server's limits, since
         * one over-long field would refuse the whole post (422).
         */
        fun resultsBody(rec: GradingRunRecord, fallbackEnabled: Boolean, forceRemark: Boolean = false): OnDeviceResultsInDto {
            val token = checkNotNull(rec.runToken) { "no run token" }
            val results = rec.eligibleOrEmpty.map { id ->
                toDto(checkNotNull(rec.resultFor(id)) { "no result for $id" })
            }
            val flagged = if (forceRemark) {
                rec.eligibleOrEmpty
            } else if (fallbackEnabled) {
                rec.eligibleOrEmpty.filter { rec.resultFor(it)?.status == BoxStatus.NEEDS_FALLBACK }
            } else emptyList()
            return OnDeviceResultsInDto(
                runToken = token,
                useFallback = fallbackEnabled || forceRemark,
                fallbackBoxIds = flagged,
                results = results
            )
        }

        fun toDto(r: StoredBoxResult): OnDeviceBoxResultDto {
            val feedback = r.feedback?.take(OnDeviceBoxResultDto.MAX_FEEDBACK)
            val raw = r.rawReply?.take(OnDeviceBoxResultDto.MAX_RAW_RESPONSE)
            val confidence = r.confidence?.takeIf { it in 0.0..100.0 }
            val reason = r.reason?.take(OnDeviceBoxResultDto.MAX_REVIEW_REASON)
            return when {
                r.status == BoxStatus.GRADED && r.score != null -> OnDeviceBoxResultDto(
                    answerBoxId = r.answerBoxId, outcome = OnDeviceBoxResultDto.SCORED, score = r.score,
                    feedback = feedback, confidence = confidence, rawResponse = raw
                )
                r.status == BoxStatus.BLANK -> OnDeviceBoxResultDto(
                    answerBoxId = r.answerBoxId, outcome = OnDeviceBoxResultDto.BLANK, score = 0.0,
                    feedback = feedback
                )
                else -> OnDeviceBoxResultDto(
                    answerBoxId = r.answerBoxId, outcome = OnDeviceBoxResultDto.NEEDS_REVIEW,
                    feedback = feedback, confidence = confidence, rawResponse = raw,
                    reviewReason = reason ?: "The phone could not mark this answer"
                )
            }
        }
    }
}
