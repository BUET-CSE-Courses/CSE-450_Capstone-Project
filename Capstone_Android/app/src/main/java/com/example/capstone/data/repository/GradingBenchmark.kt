package com.example.capstone.data.repository

import com.example.capstone.data.local.LocalGradingService
import com.example.capstone.data.local.ModelSpec
import com.example.capstone.data.local.TurnStats
import com.example.capstone.domain.grading.AnswerToGrade
import com.example.capstone.domain.grading.BoxGradeResult
import com.example.capstone.domain.grading.GradingConfig
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Debug only (session 9): grades fixed answer boxes with every model and prompt/crop variant
 * and writes what happened to [outDir]. Nothing is posted; the web end is never called.
 *
 * Per case: the parsed result (status, score, confidence, reason), the raw reply, seconds,
 * the engine's token counts per turn, and the exact PNGs the engine was given
 * (`case<NN>_img<k>.png`). `results.json` is rewritten after every case, so a crash keeps
 * everything before it. `outDir` is under `noBackupFilesDir`: raw replies can quote the
 * answer key.
 *
 * @param useModel makes a model active (loads it); may throw, which is recorded.
 * @param grade grades one item with one config on the active model.
 * @param callCount how many model calls have finished ([LocalGradingService.callCount]).
 * @param callsSince the calls after a given count ([LocalGradingService.callsSince]); a
 *   two-turn case makes two.
 */
class GradingBenchmark(
    private val useModel: suspend (ModelSpec) -> Unit,
    private val grade: suspend (GradingConfig, AnswerToGrade) -> BoxGradeResult,
    private val callCount: () -> Int,
    private val callsSince: (Int) -> List<LocalGradingService.CallRecord>,
    private val outDir: File,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    /** One answer box to grade, with what a teacher would expect. */
    class Item(val name: String, val expected: String, val item: AnswerToGrade)

    /** The prompt/crop variants. PLAIN is what the phone grades with today. */
    enum class Variant(val config: GradingConfig) {
        PLAIN(GradingConfig()),

        /** Change (c): the format again at the end, "add the parts up, write ONE total". */
        PARTS_WORDING(GradingConfig(formatReminder = true)),

        /** [com.example.capstone.domain.grading.CropTrim], plain wording. */
        TRIM(GradingConfig(trimCrops = true)),

        /** Two conversations: transcript without the model answer, then marks (session 9). */
        TWO_TURN(GradingConfig(twoTurn = true))
    }

    data class Case(
        val index: Int,
        val model: String,
        val variant: String,
        val item: String,
        val expected: String,
        val status: String?,
        val score: Double?,
        val maxScore: Int?,
        val confidence: Double?,
        val reason: String?,
        val rawReply: String?,
        val seconds: Double?,
        val modelCalled: Boolean,
        val turns: List<TurnStats>,
        val images: List<String>,
        val error: String?
    )

    data class ModelLoad(val model: String, val seconds: Double, val error: String?)

    data class Report(
        val startedAtMillis: Long,
        var finished: Boolean,
        val loads: MutableList<ModelLoad>,
        val cases: MutableList<Case>
    )

    /**
     * Every model x variant x item, in that order. [onProgress] gets "n/total: ...".
     * Returns the report as written.
     */
    suspend fun run(
        models: List<ModelSpec>,
        variants: List<Variant>,
        items: List<Item>,
        onProgress: (String) -> Unit = {}
    ): Report {
        outDir.mkdirs()
        val report = Report(System.currentTimeMillis(), false, mutableListOf(), mutableListOf())
        val total = models.size * variants.size * items.size
        var n = 0
        for (model in models) {
            onProgress("loading ${model.name}")
            val loadStart = clock()
            val loadError = try {
                useModel(model)
                null
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                "${t.javaClass.simpleName}: ${t.message}"
            }
            report.loads += ModelLoad(model.name, seconds(clock() - loadStart), loadError)
            write(report)

            for (variant in variants) for (bench in items) {
                n++
                onProgress("$n/$total: ${model.name} ${variant.name} ${bench.name}")
                report.cases += if (loadError != null) {
                    failed(n, model, variant, bench, "model did not load: $loadError")
                } else {
                    one(n, model, variant, bench)
                }
                write(report)
            }
        }
        report.finished = true
        write(report)
        onProgress("done: $total cases in ${outDir.path}")
        return report
    }

    private suspend fun one(n: Int, model: ModelSpec, variant: Variant, bench: Item): Case {
        val before = callCount()
        val started = clock()
        val result = try {
            grade(variant.config, bench.item)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return failed(n, model, variant, bench, "${t.javaClass.simpleName}: ${t.message}", seconds(clock() - started))
        }
        val calls = callsSince(before)
        val images = calls.flatMapIndexed { c, call ->
            call.images.mapIndexed { k, png ->
                val name = if (calls.size == 1) "case%02d_img%d.png".format(n, k + 1)
                else "case%02d_call%d_img%d.png".format(n, c + 1, k + 1)
                File(outDir, name).writeBytes(png)
                name
            }
        }
        return Case(
            index = n,
            model = model.name,
            variant = variant.name,
            item = bench.name,
            expected = bench.expected,
            status = result.status.name,
            score = result.score,
            maxScore = result.maxScore,
            confidence = result.confidence,
            reason = result.reason,
            rawReply = result.rawReply,
            seconds = seconds(result.durationMs),
            modelCalled = calls.isNotEmpty(),
            turns = calls.flatMap { it.turns },
            images = images,
            error = null
        )
    }

    private fun failed(n: Int, model: ModelSpec, variant: Variant, bench: Item, error: String, secs: Double? = null) = Case(
        index = n, model = model.name, variant = variant.name, item = bench.name, expected = bench.expected,
        status = null, score = null, maxScore = bench.item.maxScore, confidence = null, reason = null,
        rawReply = null, seconds = secs, modelCalled = false, turns = emptyList(), images = emptyList(),
        error = error
    )

    private fun write(report: Report) {
        val tmp = File(outDir, "$RESULTS.tmp")
        tmp.writeText(gson.toJson(report))
        val target = File(outDir, RESULTS)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun seconds(ms: Long) = ms / 1000.0

    companion object {
        const val RESULTS = "results.json"

        /**
         * What the Model Test screen's button runs. Second benchmark (session 9, the user's
         * decision "Gemma 4 E2B only"): plain against two-turn. The first run was
         * [ModelSpec.GRADING_CHOICES] x PLAIN / PARTS_WORDING / TRIM.
         */
        val MODELS = listOf(ModelSpec.GEMMA4_E2B)
        val VARIANTS = listOf(Variant.PLAIN, Variant.TWO_TURN)
        private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

        /** Session 9's fixed input: the run store's submission, whose crops are on the phone. */
        const val SUBMISSION_ID = "5a46d218-84db-4d79-9e2b-6ec8e34e4c84"

        /**
         * The blank-box fixture (`src/test/resources/grading/phone_blank_box_with_label.png`),
         * adb-pushed here: the app reads model files from the same folder.
         */
        const val BLANK_FIXTURE = "/data/local/tmp/llm/bench/phone_blank_box_with_label.png"

        /**
         * Box 1 and box 2 of [paper] (first two by paper order) and the blank fixture graded
         * as box 2 (same question, marks and printed label).
         */
        fun items(paper: PreparedPaper, blankPng: ByteArray): List<Item> {
            val ordered = paper.boxes.values.sortedBy { it.orderIndex }
            require(ordered.size >= 2) { "the paper has ${ordered.size} boxes; the benchmark needs 2" }
            val (box1, box2) = ordered
            return listOf(
                Item("box1_full", "about ${box1.item.maxScore}/${box1.item.maxScore}", box1.item),
                Item("box2_partial", "about 10/${box2.item.maxScore}", box2.item),
                Item("blank", "0/${box2.item.maxScore}, no model call", box2.item.withCrops(listOf(blankPng)))
            )
        }
    }
}
