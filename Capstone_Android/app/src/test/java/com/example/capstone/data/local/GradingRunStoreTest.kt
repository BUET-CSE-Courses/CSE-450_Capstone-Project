package com.example.capstone.data.local

import com.example.capstone.domain.grading.BoxGradeResult
import com.example.capstone.domain.grading.BoxStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GradingRunStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun result(id: String, score: Double) =
        BoxGradeResult(id, score, 5, "t", "f", 90.0, BoxStatus.GRADED, null, "raw", "m", 3)

    @Test
    fun progressSurvivesANewProcess() {
        val file = File(tmp.root, GradingRunStore.FILE_NAME)
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runBlocking {
            val store = GradingRunStore.create(file, scope1)
            store.put(
                GradingRunRecord("s1", "q1", runToken = "t1", eligibleBoxIds = listOf("a", "b"))
                    .withResult(result("a", 2.0))
            )
        }
        scope1.cancel()
        Thread.sleep(50)

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val back = runBlocking { GradingRunStore.create(file, scope2).get("s1") }!!
            assertThat(back.runToken).isEqualTo("t1")
            assertThat(back.resultFor("a")!!.toResult()).isEqualTo(result("a", 2.0))
            assertThat(back.hasAllResults).isFalse()
            assertThat(back.withResult(result("b", 1.0)).hasAllResults).isTrue()
        } finally {
            scope2.cancel()
        }
    }

    @Test
    fun forgetOtherRunsDropsOnlyThisPapersOtherSubmissions() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            runBlocking {
                val store = GradingRunStore.create(File(tmp.root, GradingRunStore.FILE_NAME), scope)
                store.put(GradingRunRecord("deleted", "q1").withResult(result("a", 2.0)))
                store.put(GradingRunRecord("current", "q1"))
                store.put(GradingRunRecord("other-paper", "q2"))

                assertThat(store.forgetOtherRuns("q1", keepSubmissionId = "current")).containsExactly("deleted")
                assertThat(store.get("deleted")).isNull()
                assertThat(store.get("current")).isNotNull()
                assertThat(store.get("other-paper")).isNotNull()

                // No submission on the server any more: every run of the paper goes.
                assertThat(store.forgetOtherRuns("q1", keepSubmissionId = null)).containsExactly("current")
                assertThat(store.get("other-paper")).isNotNull()
                assertThat(store.forgetOtherRuns("q1", keepSubmissionId = null)).isEmpty()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun gradingAgainKeepsGoodResultsUnlessTheTeacherResetTheMarks() {
        // Session 7: both calls failed with the engine's logits error.
        val failedCall = BoxGradeResult(
            "b", null, 15, null, null, null, BoxStatus.NEEDS_FALLBACK,
            "Grading request failed: Status Code: 3. Message: Logits dimensions must be [batch_size, 1, vocab_size].",
            null, "QWEN2_VL_2B", 60_000
        )
        val phoneFailed = failedCall.copy(answerBoxId = "c", reason = "Grading on the phone failed: boom", modelId = null)
        val noCrop = failedCall.copy(answerBoxId = "d", reason = "No extracted answer image for this box", modelId = null)
        val rec = GradingRunRecord("s", "q")
            .withResult(result("a", 2.0)).withResult(failedCall).withResult(phoneFailed).withResult(noCrop)

        // Expired or failed run: failed calls go again, the rest stays.
        assertThat(rec.forGradingAgain(everything = false).resultsOrEmpty.map { it.answerBoxId })
            .containsExactly("a", "d")
        // The teacher pressed Reset marks: everything is graded afresh.
        assertThat(rec.forGradingAgain(everything = true).resultsOrEmpty).isEmpty()
    }

    @Test
    fun withResultReplacesRatherThanDuplicates() {
        val rec = GradingRunRecord("s", "q").withResult(result("a", 1.0)).withResult(result("a", 3.0))
        assertThat(rec.resultsOrEmpty).hasSize(1)
        assertThat(rec.resultFor("a")!!.score).isEqualTo(3.0)
    }

    @Test
    fun aFileThatDoesNotParseIsReplacedWithAnEmptyOne() {
        val file = File(tmp.root, GradingRunStore.FILE_NAME).apply { writeText("{not json") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            assertThat(runBlocking { GradingRunStore.create(file, scope).get("s") }).isNull()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun fallbackSettingDefaultsToTheBuildAndOnlyDebugOverrides() {
        assertThat(FallbackSetting.resolve(buildDefault = false, debugBuild = true, override = null)).isFalse()
        assertThat(FallbackSetting.resolve(buildDefault = false, debugBuild = true, override = true)).isTrue()
        assertThat(FallbackSetting.resolve(buildDefault = true, debugBuild = false, override = false)).isTrue()
        assertThat(FallbackSetting.resolve(buildDefault = true, debugBuild = true, override = false)).isFalse()
    }
}
