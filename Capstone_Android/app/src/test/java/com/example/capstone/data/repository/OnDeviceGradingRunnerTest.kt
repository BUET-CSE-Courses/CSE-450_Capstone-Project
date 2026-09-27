package com.example.capstone.data.repository

import com.example.capstone.data.local.GradingRunRecord
import com.example.capstone.data.local.GradingRunStore
import com.example.capstone.data.local.RunPhase
import com.example.capstone.domain.grading.AnswerToGrade
import com.example.capstone.domain.grading.BoxGradeResult
import com.example.capstone.domain.grading.BoxStatus
import com.example.capstone.domain.grading.GradingService
import com.example.capstone.domain.grading.MarkDisplay
import com.example.capstone.domain.grading.MarkState
import com.example.capstone.domain.grading.MarkedBy
import com.example.capstone.work.GradingWorker
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The grading run against [FakeWebEnd] (the web end's on-device rules) through the app's
 * real Retrofit interface, with the run's progress in a real DataStore file.
 */
class OnDeviceGradingRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sid = "sub-1"
    private val qid = "q-1"
    private val boxes = listOf(
        Triple("ab_1", "Q1(a)", 2),
        Triple("ab_2", "Q1(b)", 3),
        Triple("ab_3", "Q2", 5)
    )

    private lateinit var server: MockWebServer
    private lateinit var web: FakeWebEnd
    private lateinit var storeFile: File
    private var storeScope = newScope()
    private lateinit var store: GradingRunStore
    private val sleeps = mutableListOf<Long>()

    @Before
    fun setUp() {
        web = FakeWebEnd(sid, boxes)
        server = MockWebServer().apply { dispatcher = web; start() }
        storeFile = File(tmp.root, "grading_runs.json")
        store = GradingRunStore.create(storeFile, storeScope)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        server.shutdown()
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A new process: the old DataStore is gone, a new one reads the same file. */
    private fun restartProcess() {
        storeScope.cancel()
        Thread.sleep(50)
        storeScope = newScope()
        store = GradingRunStore.create(storeFile, storeScope)
    }

    // ---- fakes -------------------------------------------------------------------------

    private fun result(id: String, status: BoxStatus, score: Double? = null, reason: String? = null, max: Int = 5) =
        BoxGradeResult(
            answerBoxId = id, score = score, maxScore = max,
            transcript = if (status == BoxStatus.GRADED) "v = u + at" else null,
            feedback = if (status == BoxStatus.GRADED) "Good." else null,
            confidence = if (status == BoxStatus.GRADED) 85.0 else null,
            status = status, reason = reason,
            rawReply = if (status == BoxStatus.GRADED) "TRANSCRIPT: v = u + at\nSCORE: $score\nFEEDBACK: Good.\nCONFIDENCE: 85" else null,
            modelId = "QWEN2_VL_2B", durationMs = 10
        )

    private inner class FakeGrader(val byId: Map<String, BoxGradeResult>) : GradingService {
        val calls = mutableListOf<String>()
        var beforeGrade: (String) -> Unit = {}
        override suspend fun grade(item: AnswerToGrade): BoxGradeResult {
            beforeGrade(item.answerBoxId)
            calls += item.answerBoxId
            return byId.getValue(item.answerBoxId)
        }
    }

    private fun paper(): PrepareResult.Ready = PrepareResult.Ready(
        PreparedPaper(
            boxes.mapIndexed { i, (id, label, points) ->
                id to PreparedBox(
                    AnswerToGrade(
                        answerBoxId = id, label = label, maxScore = points, questionText = "Question $i",
                        groundTruthText = "model answer", crops = listOf(byteArrayOf(1))
                    ),
                    orderIndex = i, label = label
                )
            }.toMap()
        )
    )

    private fun runner(grader: GradingService) = OnDeviceGradingRunner(
        api = FakeWebEnd.api(server),
        store = store,
        grader = grader,
        sleep = { sleeps += it },
        retry = RetryPolicy(listOf(2_000L, 5_000L))
    )

    private val progress = mutableListOf<RunProgress>()

    private fun run(grader: GradingService, fallback: Boolean = false, r: OnDeviceGradingRunner = runner(grader)) =
        runBlocking { r.run(sid, qid, fallback, prepare = { paper() }, onProgress = { progress += it }) }

    private fun allGraded() = FakeGrader(
        mapOf(
            "ab_1" to result("ab_1", BoxStatus.GRADED, 2.0, max = 2),
            "ab_2" to result("ab_2", BoxStatus.GRADED, 1.5, max = 3),
            "ab_3" to result("ab_3", BoxStatus.GRADED, 4.0)
        )
    )

    private fun posted() = web.postBodies.last()
    private fun postedOutcome(id: String) = posted().getAsJsonArray("results").map { it.asJsonObject }
        .first { it.get("answer_box_id").asString == id }

    // ---- 1. all graded ---------------------------------------------------------------

    @Test
    fun allGraded_startsGradesEachBoxOnceAndPostsOnce() {
        val grader = allGraded()
        val outcome = run(grader)

        assertThat(outcome).isInstanceOf(RunOutcome.Posted::class.java)
        val grades = (outcome as RunOutcome.Posted).grades
        assertThat(grades.gradingStatus).isEqualTo("graded")
        assertThat(MarkDisplay.totalText(grades)).isEqualTo("7.5 / 10")
        assertThat(grader.calls).containsExactly("ab_1", "ab_2", "ab_3").inOrder()
        assertThat(web.tokensIssued).hasSize(1)
        assertThat(web.postBodies).hasSize(1)
        assertThat(posted().get("run_token").asString).isEqualTo("token-1")
        assertThat(posted().getAsJsonArray("fallback_box_ids").size()).isEqualTo(0)
        assertThat(postedOutcome("ab_2").get("outcome").asString).isEqualTo("scored")
        assertThat(postedOutcome("ab_2").get("score").asDouble).isEqualTo(1.5)
        assertThat(postedOutcome("ab_2").get("confidence").asDouble).isEqualTo(85.0)

        // "Grading box 2 of 3", one box at a time, in paper order.
        val grading = progress.filter { it.phase == RunPhase.GRADING }
        assertThat(grading.map { GradingWorker.progressText(it) })
            .containsExactly("Grading box 1 of 3", "Grading box 2 of 3", "Grading box 3 of 3").inOrder()

        val saved = runBlocking { store.get(sid) }!!
        assertThat(saved.phase).isEqualTo(RunPhase.POSTED)
        assertThat(saved.postedToken).isEqualTo("token-1")
        // The transcript is shown from the phone's own result.
        val view = MarkDisplay.boxes(grades, saved).first { it.answerBoxId == "ab_1" }
        assertThat(view.transcript).isEqualTo("v = u + at")
        assertThat(view.markedBy).isEqualTo(MarkedBy.PHONE)
    }

    // ---- session 7: the app kept dying during one box ---------------------------------

    @Test
    fun aBoxTheAppDiedOnTwiceGoesToReviewInsteadOfLoopingForever() {
        // As left by two process deaths while grading ab_2 (ab_1 already done).
        runBlocking {
            store.put(
                GradingRunRecord(sid, qid, gradingBoxId = "ab_2", gradingBoxStarts = 2)
                    .withResult(result("ab_1", BoxStatus.GRADED, 2.0, max = 2))
            )
        }
        val grader = allGraded()
        val outcome = run(grader)

        assertThat(outcome).isInstanceOf(RunOutcome.Posted::class.java)
        assertThat(grader.calls).containsExactly("ab_3")
        assertThat(postedOutcome("ab_2").get("outcome").asString).isEqualTo("needs_review")
        assertThat(postedOutcome("ab_2").get("review_reason").asString).isEqualTo(OnDeviceGradingRunner.CRASHED)
        val saved = runBlocking { store.get(sid) }!!
        assertThat(saved.gradingBoxId).isNull()
        assertThat(saved.resultFor("ab_2")!!.callFailed).isTrue()
    }

    @Test
    fun oneDeathDuringABoxGetsOneMoreTry() {
        runBlocking { store.put(GradingRunRecord(sid, qid, gradingBoxId = "ab_1", gradingBoxStarts = 1)) }
        val grader = allGraded()
        run(grader)
        assertThat(grader.calls).containsExactly("ab_1", "ab_2", "ab_3").inOrder()
        assertThat(postedOutcome("ab_1").get("outcome").asString).isEqualTo("scored")
    }

    @Test
    fun theBoxBeingGradedIsRecordedBeforeTheCallAndClearedAfter() {
        val grader = allGraded()
        val seen = mutableListOf<Pair<String?, Int>>()
        grader.beforeGrade = { runBlocking { store.get(sid) }!!.let { seen += it.gradingBoxId to it.gradingBoxStarts } }
        run(grader)
        assertThat(seen).containsExactly("ab_1" to 1, "ab_2" to 1, "ab_3" to 1).inOrder()
        assertThat(runBlocking { store.get(sid) }!!.gradingBoxId).isNull()
    }

    // ---- 2. some blank ---------------------------------------------------------------

    @Test
    fun someBlank_postedAsBlankAndShownAsARealZero() {
        val grader = FakeGrader(
            mapOf(
                "ab_1" to result("ab_1", BoxStatus.BLANK, 0.0, max = 2),
                "ab_2" to result("ab_2", BoxStatus.GRADED, 3.0, max = 3),
                "ab_3" to result("ab_3", BoxStatus.BLANK, 0.0)
            )
        )
        val grades = (run(grader) as RunOutcome.Posted).grades

        assertThat(postedOutcome("ab_1").get("outcome").asString).isEqualTo("blank")
        assertThat(postedOutcome("ab_1").get("score").asDouble).isEqualTo(0.0)
        assertThat(grades.gradingStatus).isEqualTo("graded")
        val blank = MarkDisplay.boxes(grades, null).first { it.answerBoxId == "ab_3" }
        assertThat(blank.state).isEqualTo(MarkState.SCORED)
        assertThat(blank.scoreText).isEqualTo("0 / 5")
        assertThat(MarkDisplay.totalText(grades)).isEqualTo("3 / 10")
    }

    // ---- 3. some needs review --------------------------------------------------------

    @Test
    fun someNeedsReview_postedWithReasonAndShownDifferentlyFromZero() {
        val reason = "No marks set for this part — give it a marking scheme, or set them by hand"
        val grader = FakeGrader(
            mapOf(
                "ab_1" to result("ab_1", BoxStatus.GRADED, 0.0, max = 2),
                "ab_2" to result("ab_2", BoxStatus.GRADED, 2.0, max = 3),
                "ab_3" to result("ab_3", BoxStatus.NEEDS_REVIEW, reason = reason)
            )
        )
        val grades = (run(grader) as RunOutcome.Posted).grades

        val sent = postedOutcome("ab_3")
        assertThat(sent.get("outcome").asString).isEqualTo("needs_review")
        assertThat(sent.get("review_reason").asString).isEqualTo(reason)
        assertThat(sent.has("score")).isFalse()

        val views = MarkDisplay.boxes(grades, null)
        val zero = views.first { it.answerBoxId == "ab_1" }
        val review = views.first { it.answerBoxId == "ab_3" }
        assertThat(zero.state).isEqualTo(MarkState.SCORED)
        assertThat(zero.scoreText).isEqualTo("0 / 2")
        assertThat(review.state).isEqualTo(MarkState.NEEDS_REVIEW)
        assertThat(review.scoreText).isEqualTo("– / 5")
        assertThat(review.reason).contains(reason)
        assertThat(MarkDisplay.totalNote(grades)).isEqualTo("1 answer needs your teacher")
        views.forEach { v ->
            listOf(v.label, v.scoreText, v.markedByText, v.reason, v.feedback, v.transcript).forEach {
                assertThat(it ?: "").doesNotContain("null")
            }
        }
    }

    // ---- 4. fallback off -------------------------------------------------------------

    @Test
    fun fallbackOff_needsFallbackBoxesPostedAsReviewWithTheirReasonAndNotFlagged() {
        web.selfHostedLlm = true // even with the server able to re-mark
        val grader = FakeGrader(
            mapOf(
                "ab_1" to result("ab_1", BoxStatus.GRADED, 1.0, max = 2),
                "ab_2" to result("ab_2", BoxStatus.NEEDS_FALLBACK, reason = "The model's CONFIDENCE 40 is below 60", max = 3),
                "ab_3" to result("ab_3", BoxStatus.GRADED, 5.0)
            )
        )
        val grades = (run(grader, fallback = false) as RunOutcome.Posted).grades

        assertThat(posted().get("use_fallback").asBoolean).isFalse()
        assertThat(posted().getAsJsonArray("fallback_box_ids").size()).isEqualTo(0)
        assertThat(postedOutcome("ab_2").get("outcome").asString).isEqualTo("needs_review")
        assertThat(postedOutcome("ab_2").get("review_reason").asString).isEqualTo("The model's CONFIDENCE 40 is below 60")
        assertThat(grades.gradingStatus).isEqualTo("graded")
        assertThat(grades.pendingFallbackCount).isEqualTo(0)
        val box = grades.boxes.first { it.answerBoxId == "ab_2" }
        assertThat(box.reviewReason).isEqualTo("The model's CONFIDENCE 40 is below 60")
        assertThat(MarkDisplay.box(box, null).state).isEqualTo(MarkState.NEEDS_REVIEW)
    }

    // ---- 5. fallback on, unavailable on the server -----------------------------------

    @Test
    fun fallbackUnavailableOnServer_flaggedBoxesComeBackAsReview() {
        web.selfHostedLlm = false
        val grader = FakeGrader(
            mapOf(
                "ab_1" to result("ab_1", BoxStatus.GRADED, 2.0, max = 2),
                "ab_2" to result("ab_2", BoxStatus.NEEDS_FALLBACK, reason = "Model could not read the answer", max = 3),
                "ab_3" to result("ab_3", BoxStatus.GRADED, 5.0)
            )
        )
        val grades = (run(grader, fallback = true) as RunOutcome.Posted).grades

        assertThat(posted().get("use_fallback").asBoolean).isTrue()
        assertThat(posted().getAsJsonArray("fallback_box_ids").map { it.asString }).containsExactly("ab_2")
        assertThat(grades.fallback).isEqualTo("unavailable")
        assertThat(grades.gradingStatus).isEqualTo("graded")
        val view = MarkDisplay.box(grades.boxes.first { it.answerBoxId == "ab_2" }, null)
        assertThat(view.state).isEqualTo(MarkState.NEEDS_REVIEW)
        assertThat(view.reason).contains("server re-marking is unavailable")
        assertThat(view.scoreText).isEqualTo("– / 3")
    }

    // ---- 6. network failure, then retry ------------------------------------------------

    @Test
    fun networkFailure_retriesWithBackoffThenSucceeds() {
        web.startFaults += FakeWebEnd.Fault.OFFLINE
        web.postFaults += FakeWebEnd.Fault.UNAVAILABLE
        val outcome = run(allGraded())

        assertThat(outcome).isInstanceOf(RunOutcome.Posted::class.java)
        assertThat(sleeps).containsExactly(2_000L, 2_000L).inOrder()
        assertThat(web.tokensIssued).hasSize(1)
        assertThat(web.saves).isEqualTo(1)
    }

    @Test
    fun networkDownThroughEveryRetry_isNotMarkedSyncedAndPostsOnTheNextRun() {
        repeat(3) { web.postFaults += FakeWebEnd.Fault.OFFLINE }
        val grader = allGraded()
        val first = run(grader)

        assertThat(first).isInstanceOf(RunOutcome.RetryLater::class.java)
        assertThat(sleeps).containsExactly(2_000L, 5_000L).inOrder()
        val saved = runBlocking { store.get(sid) }!!
        assertThat(saved.phase).isEqualTo(RunPhase.WAITING_FOR_NETWORK)
        assertThat(saved.postedToken).isNull()
        assertThat(saved.isFinished).isFalse()

        // WorkManager runs it again later: same token, nothing re-graded.
        val second = run(grader)
        assertThat(second).isInstanceOf(RunOutcome.Posted::class.java)
        assertThat(grader.calls).hasSize(3)
        assertThat(web.tokensIssued).hasSize(1)
        assertThat(web.saves).isEqualTo(1)
    }

    // ---- 7. restart mid-grading --------------------------------------------------------

    @Test
    fun restartMidGrading_startsAgainWithANewTokenAndGradesOnlyWhatIsLeft() {
        val killed = allGraded().apply {
            beforeGrade = { id -> if (id == "ab_3") throw CancellationException("process killed") }
        }
        try {
            run(killed)
            error("expected the run to be killed")
        } catch (e: CancellationException) {
            // as Android would
        }
        assertThat(killed.calls).containsExactly("ab_1", "ab_2").inOrder()
        assertThat(web.postBodies).isEmpty()

        restartProcess()
        val saved = runBlocking { store.get(sid) }!!
        assertThat(saved.resultsOrEmpty.map { it.answerBoxId }).containsExactly("ab_1", "ab_2")

        val resumed = allGraded()
        val outcome = run(resumed)
        assertThat(outcome).isInstanceOf(RunOutcome.Posted::class.java)
        assertThat(resumed.calls).containsExactly("ab_3")
        assertThat(web.tokensIssued).containsExactly("token-1", "token-2").inOrder()
        assertThat(web.postBodies).hasSize(1)
        assertThat(posted().get("run_token").asString).isEqualTo("token-2")
        assertThat(posted().getAsJsonArray("results").size()).isEqualTo(3)
    }

    // ---- 8. expired token --------------------------------------------------------------

    @Test
    fun expiredToken_restartsWithANewTokenAndRepostsWithoutRegrading() {
        web.expireBeforeNextPost = true
        val grader = allGraded()
        val outcome = run(grader)

        assertThat(outcome).isInstanceOf(RunOutcome.Posted::class.java)
        assertThat(grader.calls).hasSize(3)
        assertThat(web.tokensIssued).containsExactly("token-1", "token-2").inOrder()
        assertThat(web.postBodies.map { it.get("run_token").asString })
            .containsExactly("token-1", "token-2").inOrder()
        assertThat(web.saves).isEqualTo(1)
        assertThat((outcome as RunOutcome.Posted).grades.gradingStatus).isEqualTo("graded")
    }

    // ---- 9. teacher graded meanwhile ---------------------------------------------------

    @Test
    fun teacherGradedWhileThePhoneWasGrading_showsTheTeachersMarksAndPostsNothingMore() {
        val grader = allGraded().apply {
            beforeGrade = { id -> if (id == "ab_2") web.teacherGrades(1.0) }
        }
        val outcome = run(grader)

        assertThat(outcome).isInstanceOf(RunOutcome.ServerHasMarks::class.java)
        val grades = (outcome as RunOutcome.ServerHasMarks).grades
        assertThat(grades.gradingStatus).isEqualTo("graded")
        assertThat(web.postBodies).hasSize(1) // refused with 409, not retried
        assertThat(web.saves).isEqualTo(0)
        assertThat(web.tokensIssued).hasSize(1)
        val saved = runBlocking { store.get(sid) }!!
        assertThat(saved.phase).isEqualTo(RunPhase.SERVER_HAS_MARKS)
        assertThat(saved.postedToken).isNull()
        assertThat(MarkDisplay.boxes(grades, saved).map { it.markedBy }.toSet()).containsExactly(MarkedBy.TEACHER)
    }

    @Test
    fun teacherGradedBeforeStart_startIsRefusedAndTheirMarksAreShown() {
        web.teacherGrades(2.0)
        val grader = allGraded()
        val outcome = run(grader)

        assertThat(outcome).isInstanceOf(RunOutcome.ServerHasMarks::class.java)
        assertThat(grader.calls).isEmpty()
        assertThat(web.postBodies).isEmpty()
        assertThat((outcome as RunOutcome.ServerHasMarks).message).contains("already marked")
    }

    // ---- 10. fallback success, boxes filling in ------------------------------------------

    @Test
    fun fallbackSuccess_postedPendingThenFilledInByPolling() {
        web.selfHostedLlm = true
        val grader = FakeGrader(
            mapOf(
                "ab_1" to result("ab_1", BoxStatus.GRADED, 2.0, max = 2),
                "ab_2" to result("ab_2", BoxStatus.NEEDS_FALLBACK, reason = "The model's reply has no CONFIDENCE line", max = 3),
                "ab_3" to result("ab_3", BoxStatus.GRADED, 5.0)
            )
        )
        val posted = (run(grader, fallback = true) as RunOutcome.Posted).grades

        // The phone's marks at once; the flagged box pending.
        assertThat(posted.gradingStatus).isEqualTo("grading")
        assertThat(posted.fallback).isEqualTo("scheduled")
        assertThat(posted.pendingFallbackCount).isEqualTo(1)
        assertThat(MarkDisplay.box(posted.boxes.first { it.answerBoxId == "ab_2" }, null).state)
            .isEqualTo(MarkState.PENDING_FALLBACK)
        assertThat(MarkDisplay.totalText(posted)).isEqualTo("7 / 10")

        // Polling: the server finishes on the second wait.
        var waits = 0
        val poller = com.example.capstone.domain.grading.GradesPoller(
            sleep = { if (++waits == 2) web.finishFallback(2.5) },
            clock = { 0L }
        )
        val api = FakeWebEnd.api(server)
        val seen = mutableListOf<String>()
        val end = runBlocking {
            poller.poll(fetch = { runCatching { api.myGrades(sid) } }, fetchFirst = false) { u ->
                if (u is com.example.capstone.domain.grading.GradesPoller.Update.Grades) seen += u.grades.gradingStatus
            }
        }
        assertThat(seen).containsExactly("grading", "graded").inOrder()
        val final = (end as com.example.capstone.domain.grading.GradesPoller.End.Terminal).grades
        val filled = MarkDisplay.box(final.boxes.first { it.answerBoxId == "ab_2" }, runBlocking { store.get(sid) })
        assertThat(filled.state).isEqualTo(MarkState.SCORED)
        assertThat(filled.scoreText).isEqualTo("2.5 / 3")
        assertThat(filled.markedBy).isEqualTo(MarkedBy.SERVER_MODEL)
        assertThat(MarkDisplay.totalText(final)).isEqualTo("9.5 / 10")
    }

    // ---- 11. duplicate-post prevention ---------------------------------------------------

    @Test
    fun lostReply_isRetriedWithTheSameTokenAndSavedOnce() {
        web.postFaults += FakeWebEnd.Fault.LOSE_REPLY
        val outcome = run(allGraded())

        assertThat(outcome).isInstanceOf(RunOutcome.Posted::class.java)
        assertThat(web.postBodies.map { it.get("run_token").asString }).containsExactly("token-1", "token-1")
        assertThat(web.saves).isEqualTo(1)
        assertThat(web.tokensIssued).hasSize(1)
    }

    @Test
    fun lostReplyThenProcessRestart_postsTheSameTokenAgainInsteadOfStarting() {
        repeat(3) { web.postFaults += FakeWebEnd.Fault.LOSE_REPLY }
        assertThat(run(allGraded())).isInstanceOf(RunOutcome.RetryLater::class.java)
        assertThat(web.saves).isEqualTo(1) // the server has it; the phone doesn't know yet

        restartProcess()
        val grader = allGraded()
        val outcome = run(grader)
        assertThat(outcome).isInstanceOf(RunOutcome.Posted::class.java)
        assertThat(grader.calls).isEmpty()
        assertThat(web.tokensIssued).hasSize(1)
        assertThat(web.saves).isEqualTo(1)
    }

    @Test
    fun afterPosting_runningAgainSendsNothing() {
        run(allGraded())
        val requests = server.requestCount

        val again = run(allGraded())
        assertThat(again).isInstanceOf(RunOutcome.Posted::class.java)
        assertThat(server.requestCount).isEqualTo(requests)
        assertThat(web.postBodies).hasSize(1)
    }

    @Test
    fun twoRunsAtOnce_postOnlyOnce() {
        val r = runner(allGraded())
        val outcomes = runBlocking(Dispatchers.IO) {
            (1..2).map { async { r.run(sid, qid, false, prepare = { paper() }) } }.awaitAll()
        }
        assertThat(outcomes.all { it is RunOutcome.Posted }).isTrue()
        assertThat(web.tokensIssued).hasSize(1)
        assertThat(web.postBodies).hasSize(1)
        assertThat(web.saves).isEqualTo(1)
    }

    // ---- body limits ----------------------------------------------------------------

    @Test
    fun resultsBody_cutsStringsToTheServersLimits() {
        val long = "x".repeat(30_000)
        val r = result("ab_1", BoxStatus.NEEDS_FALLBACK, reason = long).copy(feedback = long, rawReply = long)
        val dto = OnDeviceGradingRunner.toDto(com.example.capstone.data.local.StoredBoxResult.from(r))
        assertThat(dto.feedback!!.length).isEqualTo(5000)
        assertThat(dto.rawResponse!!.length).isEqualTo(20000)
        assertThat(dto.reviewReason!!.length).isEqualTo(500)
        assertThat(dto.score).isNull()
    }

    // ---- force server re-mark (debug, session 9) ---------------------------------------

    private fun postedRecord() = com.example.capstone.data.local.GradingRunRecord(
        submissionId = sid,
        questionId = qid,
        runToken = "tok",
        eligibleBoxIds = listOf("ab_1", "ab_2", "ab_3"),
        results = listOf(
            result("ab_1", BoxStatus.GRADED, score = 5.0),
            result("ab_2", BoxStatus.NEEDS_FALLBACK, reason = "x"),
            result("ab_3", BoxStatus.BLANK, score = 0.0)
        ).map { com.example.capstone.data.local.StoredBoxResult.from(it) }
    )

    @Test
    fun resultsBody_forceRemark_flagsEveryBoxAndAsksForFallback() {
        val body = OnDeviceGradingRunner.resultsBody(postedRecord(), fallbackEnabled = false, forceRemark = true)
        assertThat(body.useFallback).isTrue()
        assertThat(body.fallbackBoxIds).containsExactly("ab_1", "ab_2", "ab_3").inOrder()
        // The phone's own results still go up as they are.
        assertThat(body.results.map { it.outcome }).containsExactly("scored", "needs_review", "blank").inOrder()
    }

    @Test
    fun resultsBody_withoutForce_isUnchanged() {
        val on = OnDeviceGradingRunner.resultsBody(postedRecord(), fallbackEnabled = true)
        assertThat(on.fallbackBoxIds).containsExactly("ab_2")
        val off = OnDeviceGradingRunner.resultsBody(postedRecord(), fallbackEnabled = false)
        assertThat(off.useFallback).isFalse()
        assertThat(off.fallbackBoxIds).isEmpty()
    }
}
