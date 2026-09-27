package com.example.capstone.data.repository

import com.example.capstone.data.local.LocalGradingService
import com.example.capstone.data.local.ModelSpec
import com.example.capstone.data.local.TurnStats
import com.example.capstone.domain.grading.AnswerToGrade
import com.example.capstone.domain.grading.BoxGradeResult
import com.example.capstone.domain.grading.BoxStatus
import com.example.capstone.domain.grading.GradingConfig
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GradingBenchmarkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun item(id: String, crops: List<ByteArray> = listOf(byteArrayOf(1))) =
        AnswerToGrade(answerBoxId = id, label = "", maxScore = 15, questionText = "q", groundTruthText = "m", crops = crops)

    private fun items() = listOf(
        GradingBenchmark.Item("box1_full", "5/5", item("b1")),
        GradingBenchmark.Item("box2_partial", "10/15", item("b2")),
        GradingBenchmark.Item("blank", "0", item("b2", listOf(byteArrayOf(0))))
    )

    /** Grades "blank" without a call, everything else with one, recording the config seen. */
    private class Fake {
        val loaded = mutableListOf<ModelSpec>()
        val configs = mutableListOf<GradingConfig>()
        val log = mutableListOf<LocalGradingService.CallRecord>()
        var active: ModelSpec? = null

        suspend fun use(spec: ModelSpec) {
            if (spec == ModelSpec.GEMMA4_E2B && failGemma) error("engine init failed")
            loaded += spec
            active = spec
        }

        var failGemma = false

        suspend fun grade(config: GradingConfig, item: AnswerToGrade): BoxGradeResult {
            configs += config
            val blank = item.crops.single()[0] == 0.toByte()
            if (!blank) {
                log += LocalGradingService.CallRecord(
                    active!!.name,
                    listOf(byteArrayOf(7, 7)),
                    listOf(TurnStats("call#1", 1234, 900, 850, 50, 1.5, 40.0, 10.0))
                )
            }
            return BoxGradeResult(
                answerBoxId = item.answerBoxId, score = if (blank) 0.0 else 5.0, maxScore = item.maxScore,
                transcript = null, feedback = null, confidence = if (blank) null else 90.0,
                status = if (blank) BoxStatus.BLANK else BoxStatus.GRADED, reason = null,
                rawReply = if (blank) null else "SCORE: 5", modelId = if (blank) null else active!!.name,
                durationMs = 2000
            )
        }
    }

    private fun bench(fake: Fake, dir: File) = GradingBenchmark(
        useModel = fake::use,
        grade = fake::grade,
        callCount = { fake.log.size },
        callsSince = { n -> fake.log.drop(n) },
        outDir = dir,
        clock = { 0L }
    )

    @Test
    fun `every model x variant x item, in order, with the variant's config`() {
        val fake = Fake()
        val dir = tmp.newFolder("bench")
        val report = runBlocking {
            bench(fake, dir).run(listOf(ModelSpec.QWEN2_VL_2B, ModelSpec.GEMMA4_E2B), GradingBenchmark.Variant.entries, items())
        }
        assertThat(report.cases).hasSize(2 * GradingBenchmark.Variant.entries.size * 3)
        assertThat(report.finished).isTrue()
        assertThat(fake.loaded).containsExactly(ModelSpec.QWEN2_VL_2B, ModelSpec.GEMMA4_E2B).inOrder()
        assertThat(report.cases.map { "${it.model} ${it.variant} ${it.item}" }.take(4)).containsExactly(
            "QWEN2_VL_2B PLAIN box1_full",
            "QWEN2_VL_2B PLAIN box2_partial",
            "QWEN2_VL_2B PLAIN blank",
            "QWEN2_VL_2B PARTS_WORDING box1_full"
        ).inOrder()
        assertThat(fake.configs.map { Triple(it.formatReminder, it.trimCrops, it.twoTurn) }.distinct()).containsExactly(
            Triple(false, false, false), Triple(true, false, false), Triple(false, true, false), Triple(false, false, true)
        ).inOrder()
    }

    @Test
    fun `a model call saves its images and turns, a blank box records no call`() {
        val fake = Fake()
        val dir = tmp.newFolder("bench")
        val report = runBlocking {
            bench(fake, dir).run(listOf(ModelSpec.QWEN2_VL_2B), listOf(GradingBenchmark.Variant.PLAIN), items())
        }
        val (box1, _, blank) = report.cases
        assertThat(box1.modelCalled).isTrue()
        assertThat(box1.images).containsExactly("case01_img1.png")
        assertThat(File(dir, "case01_img1.png").readBytes().toList()).containsExactly(7.toByte(), 7.toByte())
        assertThat(box1.turns.single().promptTokens).isEqualTo(850)
        assertThat(box1.seconds).isEqualTo(2.0)
        assertThat(blank.modelCalled).isFalse()
        assertThat(blank.images).isEmpty()
        assertThat(blank.status).isEqualTo("BLANK")
    }

    @Test
    fun `a model that fails to load is recorded for its cases and the run goes on`() {
        val fake = Fake().apply { failGemma = true }
        val dir = tmp.newFolder("bench")
        val report = runBlocking {
            bench(fake, dir).run(listOf(ModelSpec.QWEN2_VL_2B, ModelSpec.GEMMA4_E2B), GradingBenchmark.Variant.entries, items())
        }
        assertThat(report.loads.map { it.error != null }).containsExactly(false, true).inOrder()
        val gemma = report.cases.filter { it.model == ModelSpec.GEMMA4_E2B.name }
        assertThat(gemma).hasSize(GradingBenchmark.Variant.entries.size * 3)
        assertThat(gemma.all { it.error!!.startsWith("model did not load") && it.status == null }).isTrue()
    }

    @Test
    fun `a case with two calls keeps both calls' turns and images`() {
        val fake = Fake()
        val dir = tmp.newFolder("bench")
        val twoCalls = GradingBenchmark(
            useModel = fake::use,
            grade = { config, item ->
                fake.grade(config, item).also {
                    fake.log += LocalGradingService.CallRecord("GEMMA4_E2B", emptyList(), listOf(TurnStats("call#2", 10, 1, 1, 1, 0.1, 1.0, 1.0)))
                }
            },
            callCount = { fake.log.size },
            callsSince = { n -> fake.log.drop(n) },
            outDir = dir,
            clock = { 0L }
        )
        val report = runBlocking {
            twoCalls.run(listOf(ModelSpec.GEMMA4_E2B), listOf(GradingBenchmark.Variant.TWO_TURN), items().take(1))
        }
        val case = report.cases.single()
        assertThat(case.turns.map { it.label }).containsExactly("call#1", "call#2").inOrder()
        assertThat(case.images).containsExactly("case01_call1_img1.png")
    }

    @Test
    fun `the screen's run is Gemma 4 E2B, plain against two-turn`() {
        assertThat(GradingBenchmark.MODELS).containsExactly(ModelSpec.GEMMA4_E2B)
        assertThat(GradingBenchmark.VARIANTS).containsExactly(GradingBenchmark.Variant.PLAIN, GradingBenchmark.Variant.TWO_TURN).inOrder()
    }

    @Test
    fun `results json is written and parses`() {
        val dir = tmp.newFolder("bench")
        runBlocking {
            bench(Fake(), dir).run(listOf(ModelSpec.QWEN2_VL_2B), listOf(GradingBenchmark.Variant.TRIM), items())
        }
        val json = JsonParser.parseString(File(dir, GradingBenchmark.RESULTS).readText()).asJsonObject
        assertThat(json["finished"].asBoolean).isTrue()
        assertThat(json["cases"].asJsonArray.size()).isEqualTo(3)
        assertThat(json["cases"].asJsonArray[0].asJsonObject["variant"].asString).isEqualTo("TRIM")
    }

    @Test
    fun `items are box 1 and box 2 by paper order, and the blank fixture graded as box 2`() {
        val b1 = item("b1")
        val b2 = item("b2")
        val paper = PreparedPaper(
            mapOf(
                "b2" to PreparedBox(b2, orderIndex = 1, label = ""),
                "b1" to PreparedBox(b1, orderIndex = 0, label = "")
            )
        )
        val blank = byteArrayOf(0)
        val list = GradingBenchmark.items(paper, blank)
        assertThat(list.map { it.name }).containsExactly("box1_full", "box2_partial", "blank").inOrder()
        assertThat(list[0].item).isSameInstanceAs(b1)
        assertThat(list[2].item.answerBoxId).isEqualTo("b2")
        assertThat(list[2].item.crops.single()).isSameInstanceAs(blank)
    }
}
