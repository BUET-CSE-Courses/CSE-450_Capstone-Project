package com.example.capstone.ui.screens

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.capstone.CapstoneApplication
import com.example.capstone.data.local.EngineState
import com.example.capstone.data.local.GradingRunStore
import com.example.capstone.data.local.LocalGradingService
import com.example.capstone.data.local.LocalModelProvider
import com.example.capstone.data.local.ModelSpec
import com.example.capstone.data.local.SpecAvailability
import com.example.capstone.data.repository.GradingBenchmark
import com.example.capstone.data.repository.PaperPreparer
import com.example.capstone.data.repository.PrepareResult
import com.example.capstone.domain.grading.AnswerToGrade
import com.example.capstone.domain.grading.BoxGrader
import com.example.capstone.domain.grading.BoxGradeResult
import com.example.capstone.domain.grading.GRADING_SYSTEM_PROMPT
import com.example.capstone.domain.grading.GradingService
import com.example.capstone.domain.grading.buildUserMessage
import com.example.capstone.util.ImagePrep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TEMPORARY debug view model backing [ModelTestScreen].
 *
 * Talks to [LocalModelProvider] directly for the raw engine probes, which is a
 * deliberate exception for this throwaway screen. Real grading goes through the
 * [GradingService] interface.
 */
/** What produced the text currently in the raw output pane. */
enum class OutputSource {
    /** No probe has completed since the last one was started. */
    NONE,

    /** Verbatim bytes returned by the model. Nothing was added or removed. */
    MODEL,

    /** Text the app composed - a file report, a formatted BoxGradeResult. */
    HARNESS,

    /** A stack trace from a failed probe. */
    ERROR
}

class ModelTestViewModel(
    private val context: Context,
    private val modelProvider: LocalModelProvider,
    private val gradingService: GradingService,
    private val localGradingModel: LocalGradingService,
    private val runStore: GradingRunStore,
    private val paperPreparer: PaperPreparer
) : ViewModel() {

    var status by mutableStateOf("Idle. Pick an image, then load the engine.")
        private set

    /**
     * Whatever the last completed probe produced, byte for byte.
     *
     * `null` means no probe has completed - it is NOT the same as a probe
     * that completed and returned an empty string, and the two must stay
     * distinguishable or an empty model response looks like an idle screen.
     */
    var rawOutput by mutableStateOf<String?>(null)
        private set

    /**
     * Where [rawOutput] came from. The pane renders harness-composed text and
     * verbatim model text identically, so the source has to be stated rather
     * than left to be inferred.
     */
    var outputSource by mutableStateOf(OutputSource.NONE)
        private set

    var elapsedMs by mutableStateOf<Long?>(null)
        private set

    /**
     * The model that was active when the last probe finished.
     *
     * Recorded at completion rather than read live, so a result stays labelled
     * with the model that actually produced it even after a later switch.
     */
    var resultSpec by mutableStateOf<ModelSpec?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    /** The prepared PNG (EXIF-corrected, <= 1024px) that the raw probes send to the model. */
    var imagePng by mutableStateOf<ByteArray?>(null)
        private set

    /**
     * The picked file's bytes, untouched. "Grade it" hands these to the engine
     * as the crop, as a real run would: the blank check reads them as they
     * are, and the model adapter prepares them itself.
     */
    private var pickedBytes: ByteArray? = null

    /** The active model. The provider owns this; the screen observes it. */
    val activeSpec: StateFlow<ModelSpec> = modelProvider.activeSpec

    /** Engine load state, so the screen can spin and lock out probes during a swap. */
    val engineState: StateFlow<EngineState> = modelProvider.engineState

    /**
     * Every registry entry with the result of a filesystem check, so entries
     * whose file was never pushed can be shown disabled with a reason instead
     * of silently missing. Empty until the first refresh completes.
     */
    var modelOptions by mutableStateOf<List<SpecAvailability>>(emptyList())
        private set

    init {
        refreshModelOptions()
    }

    /** Re-runs the per-entry file check. Disk I/O, so it hops to IO itself. */
    fun refreshModelOptions() {
        viewModelScope.launch {
            modelOptions = modelProvider.availability()
        }
    }

    /**
     * Switches models.
     *
     * Selecting the model already in use is a no-op in the provider - no
     * release, no reload - and is reported as such. Otherwise the current
     * engine is closed before the replacement is built; the replacement is only
     * built when one was loaded beforehand.
     */
    fun onSpecSelected(spec: ModelSpec) {
        val previous = modelProvider.spec
        val wasLoaded = modelProvider.isEngineReady()
        launchOperation("Switch to ${spec.name}", OutputSource.HARNESS) {
            modelProvider.useSpec(spec)
            refreshModelOptions()
            buildString {
                if (spec == previous) {
                    appendLine("${spec.displayName} was already active. Nothing was reloaded.")
                } else {
                    appendLine("switched: ${previous.displayName} -> ${spec.displayName}")
                    appendLine(
                        if (wasLoaded) {
                            "Previous engine closed, then the replacement was loaded."
                        } else {
                            "No engine was loaded. The next probe loads this one."
                        }
                    )
                }
                appendLine("file: ${spec.fileName}")
                appendLine("supportsVision: ${spec.supportsVision}")
                appendLine("minRamGb: ${spec.minRamGb ?: "unspecified"}")
                appendLine("maxNumTokens: ${spec.maxNumTokens}  imageTokens: ${spec.imageTokens}")
                appendLine("approxSizeMb: ${spec.approxSizeMb}")
                append("engine loaded: ${modelProvider.isEngineReady()}")
            }
        }
    }

    fun onImagePicked(uri: Uri) = launchOperation("Prepare image", OutputSource.HARNESS) {
        val prepared = withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Could not open the selected image")
            ImagePrep.toGradingPng(bytes) to bytes
        }
        val (png, original) = prepared
        val originalSize = original.size
        if (png == null) {
            imagePng = null
            pickedBytes = null
            error("ImagePrep could not decode the selected image ($originalSize bytes)")
        }
        imagePng = png
        pickedBytes = original
        "Image ready.\noriginal: $originalSize bytes\nprepared PNG: ${png.size} bytes"
    }

    fun checkModelFile() = launchOperation("Check model file", OutputSource.HARNESS) {
        // stat() and File.length() are disk I/O: keep them off the main thread.
        withContext(Dispatchers.IO) {
            val status = modelProvider.inspectModel()
            buildString {
                appendLine("model: ${modelProvider.spec.displayName}")
                appendLine("usable: ${status.usable}")
                appendLine("path: ${status.path}")
                appendLine("exists: ${status.exists}  isFile: ${status.isFile}  readable: ${status.readable}")
                appendLine("dir exists: ${status.parentExists}  dir traversable: ${status.parentTraversable}")
                appendLine("sizeBytes: ${status.sizeBytes}")
                appendLine(
                    "expectedBytes: ${status.expectedBytes ?: "unrecorded (exact-size check skipped)"}" +
                        "  match: ${status.sizeMatchesExpected}"
                )
                appendLine(
                    "sizeMB: ${String.format(Locale.US, "%.1f", status.sizeBytes / 1024.0 / 1024.0)}"
                )
                appendLine("engine already loaded: ${modelProvider.isEngineReady()}")
                append("diagnosis: ${status.diagnosis}")
            }
        }
    }

    fun loadEngine() = launchOperation("Load engine", OutputSource.HARNESS) {
        modelProvider.initialize()
        "Engine initialized. See Logcat tag LocalModelProvider for the init duration."
    }

    fun textOnlyTest() = launchOperation("Text only test", OutputSource.MODEL) {
        modelProvider.runRawPrompt("Reply with exactly the two characters: OK")
    }

    fun describeImage() = launchOperation("Describe image", OutputSource.MODEL) {
        modelProvider.runRawPrompt(
            prompt = DESCRIBE_PROMPT,
            imagePng = requireImage()
        )
    }

    /**
     * Pure OCR probe: can the model read the handwriting at all?
     *
     * This deliberately does NOT go through [GradingService]. Every grading
     * entry point builds a prompt containing the expected answer and the
     * marks available, and a model handed the answer key can reproduce it
     * without reading a single pen stroke - which makes the result worthless
     * as evidence about transcription. Nothing about the question, the
     * expected answer, marks or correctness may enter [TRANSCRIBE_PROMPT].
     */
    fun transcribeHandwriting() =
        launchOperation("Transcribe handwriting (OCR only)", OutputSource.MODEL) {
            modelProvider.runRawPrompt(
                prompt = TRANSCRIBE_PROMPT,
                imagePng = requireImage()
            )
        }

    /**
     * Marks the picked image as one answer box through the real engine,
     * [GradingService.grade]: blank check, token budget, the web end's prompt,
     * one model call, the ported parser and the CONFIDENCE threshold. Question,
     * model answer and max marks come from the fields on the screen. Unlike the
     * transcribe probe this one SHOULD see the model answer.
     */
    fun gradeIt() = launchOperation("Grade it", OutputSource.HARNESS) {
        val item = testItem(listOf(pickedBytes ?: error("Pick an image first")), "manual-1")
        val result = gradingService.grade(item)
        buildString {
            appendLine("---- user message sent ----")
            appendLine(buildUserMessage(item))
            appendLine("---- BoxGradeResult ----")
            append(describe(result))
        }
    }

    /** One answer box from the screen's fields. */
    private fun testItem(crops: List<ByteArray>, boxId: String) = AnswerToGrade(
        answerBoxId = boxId,
        label = "",
        maxScore = requireMaxMarks(),
        questionText = expQuestion,
        groundTruthText = expModelAnswer,
        crops = crops
    )

    // ---- Phase 1 experiments (debug only) --------------------------------

    /**
     * Up to three prepared PNGs for the experiments, in pick order. For the
     * multi-image probe the order means: 1 = student crop, 2 = model-answer
     * image, 3 = question figure.
     */
    var experimentPngs by mutableStateOf<List<ByteArray>>(emptyList())
        private set

    /** Grading context for experiments A and C; editable on the screen. */
    var expQuestion by mutableStateOf(TEST_QUESTION)
    var expModelAnswer by mutableStateOf(TEST_MODEL_ANSWER)
    var expMaxMarks by mutableStateOf(TEST_MAX_MARKS.toString())

    fun onExperimentImagesPicked(uris: List<Uri>) =
        launchOperation("Prepare experiment images", OutputSource.HARNESS) {
            val prepared = withContext(Dispatchers.IO) {
                uris.take(MAX_EXPERIMENT_IMAGES).map { uri ->
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Could not open $uri")
                    ImagePrep.toGradingPng(bytes) ?: error("ImagePrep could not decode $uri")
                }
            }
            experimentPngs = prepared
            buildString {
                appendLine("${prepared.size} experiment image(s) ready:")
                prepared.forEachIndexed { i, png ->
                    appendLine("  ${i + 1}. ${EXPERIMENT_ROLES[i]}: ${png.size} bytes")
                }
            }
        }

    /**
     * Experiment A: three boxes in a row through the real engine,
     * [GradingService.grade]. Picked images are reused in turn when fewer than
     * three were picked, so picking ONE image tests "same crop, second call"
     * and picking three tests "different crops".
     */
    fun experimentGradeThree() = launchOperation("Exp A: grade 3 in a row", OutputSource.HARNESS) {
        val pngs = requireExperimentImages()
        buildString {
            appendLine("question: $expQuestion  expected: $expModelAnswer  max: ${requireMaxMarks()}")
            for (box in 0 until 3) {
                val index = box % pngs.size
                val result = gradingService.grade(testItem(listOf(pngs[index]), "expA-${box + 1}"))
                appendLine("---- box ${box + 1} (image ${index + 1}) ----")
                appendLine(describe(result))
            }
            append("(token counts and errors: Logcat tag LocalGradingService)")
        }
    }

    /**
     * Experiment B: one message with 2 images, then one with 3. Each run is
     * caught on its own so a failure at 2 still lets 3 be tried. The prompt
     * asks for one line per image and carries no grading context, so a reply
     * that describes each picture is evidence the model saw each one.
     */
    fun experimentMultiImage() = launchOperation("Exp B: multi-image", OutputSource.HARNESS) {
        val pngs = requireExperimentImages()
        buildString {
            for (count in 2..3) {
                appendLine("==== $count images in one message ====")
                val images = List(count) { pngs[it % pngs.size] }
                if (pngs.size < count) {
                    appendLine("(only ${pngs.size} picked: images repeat)")
                }
                val startedAt = SystemClock.elapsedRealtime()
                val reply = runCatching {
                    modelProvider.runRawPrompt(
                        prompt = multiImagePrompt(count),
                        images = images,
                        label = "expB-$count"
                    )
                }
                val ms = SystemClock.elapsedRealtime() - startedAt
                reply.onSuccess {
                    appendLine("ACCEPTED in $ms ms. Reply, verbatim:")
                    appendLine(it)
                }.onFailure {
                    if (it is CancellationException) throw it
                    appendLine("FAILED in $ms ms: ${it.javaClass.name}: ${it.message}")
                }
            }
            append("(Logcat tag LocalModelProvider has token counts per run)")
        }
    }

    /**
     * Experiment C: the engine's prompt ([GRADING_SYSTEM_PROMPT] and
     * [buildUserMessage]) on each picked crop, raw, bypassing the engine.
     * Tries it as a real system turn first; if the engine rejects a system
     * instruction, falls back to prepending it to the user text, and says which
     * one ran. The engine itself always uses the system turn.
     */
    fun experimentWebPrompt() = launchOperation("Exp C: web end prompt", OutputSource.HARNESS) {
        val pngs = requireExperimentImages()
        val user = buildUserMessage(testItem(listOf(pngs.first()), "expC"))
        buildString {
            pngs.forEachIndexed { i, png ->
                appendLine("==== crop ${i + 1} ====")
                val startedAt = SystemClock.elapsedRealtime()
                val asSystem = runCatching {
                    modelProvider.runRawPrompt(
                        prompt = user,
                        images = listOf(png),
                        systemInstruction = GRADING_SYSTEM_PROMPT,
                        label = "expC-${i + 1}-system"
                    )
                }
                asSystem.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                val (mode, reply) = if (asSystem.isSuccess) {
                    "system turn" to asSystem.getOrThrow()
                } else {
                    appendLine("system turn FAILED: ${asSystem.exceptionOrNull()?.message}")
                    "inline" to modelProvider.runRawPrompt(
                        prompt = GRADING_SYSTEM_PROMPT + "\n\n" + user,
                        images = listOf(png),
                        label = "expC-${i + 1}-inline"
                    )
                }
                val ms = SystemClock.elapsedRealtime() - startedAt
                appendLine("mode=$mode  ${ms} ms. Reply, verbatim:")
                appendLine(reply)
            }
        }
    }

    private fun requireExperimentImages(): List<ByteArray> =
        experimentPngs.ifEmpty { error("Pick 1-3 experiment images first") }

    private fun requireMaxMarks(): Int =
        expMaxMarks.trim().toIntOrNull()?.takeIf { it > 0 } ?: error("Max marks must be a positive number")

    private fun describe(r: BoxGradeResult): String = buildString {
        appendLine("answerBoxId: ${r.answerBoxId}")
        appendLine("status: ${r.status}")
        appendLine("score: ${r.score ?: "none"} / ${r.maxScore}")
        appendLine("confidence: ${r.confidence ?: "none"}")
        appendLine("reason: ${r.reason ?: "none"}")
        appendLine("transcript: ${r.transcript ?: "none"}")
        appendLine("feedback: ${r.feedback ?: "none"}")
        appendLine("modelId: ${r.modelId ?: "not called"}  durationMs: ${r.durationMs}")
        appendLine("---- rawReply ----")
        append(r.rawReply ?: "(the model was not called)")
    }

    private fun requireImage(): ByteArray =
        imagePng ?: error("Pick an image first")

    /**
     * Runs one probe, timing it and routing any failure into the output pane
     * instead of crashing the screen. Ignores taps while another probe runs.
     */
    /**
     * Session 9 benchmark, debug only, never posts: the saved crops of
     * [GradingBenchmark.SUBMISSION_ID] (box 1, box 2) and the blank fixture, through
     * [GradingBenchmark.MODELS] x [GradingBenchmark.VARIANTS]. Writes
     * `no_backup/bench/<time>/results.json` and the images each call was given.
     */
    fun runBenchmark() = launchOperation("Benchmark", OutputSource.HARNESS) {
        val record = runStore.get(GradingBenchmark.SUBMISSION_ID)
            ?: error("No saved run for ${GradingBenchmark.SUBMISSION_ID} on this phone")
        val paper = when (val prepared = withContext(Dispatchers.IO) { paperPreparer.prepare(record.questionId) }) {
            is PrepareResult.Ready -> prepared.paper
            is PrepareResult.Refused -> error("${prepared.message} ${prepared.detail.orEmpty()}")
        }
        val blank = withContext(Dispatchers.IO) {
            File(GradingBenchmark.BLANK_FIXTURE).takeIf { it.isFile }?.readBytes()
        } ?: error("Blank fixture missing: adb push it to ${GradingBenchmark.BLANK_FIXTURE}")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(context.noBackupFilesDir, "bench/$stamp")
        val benchmark = GradingBenchmark(
            useModel = { spec ->
                modelProvider.useSpec(spec)
                modelProvider.initialize()
            },
            grade = { config, item ->
                BoxGrader(localGradingModel, ImagePrep::toGray, config, cropTo = ImagePrep::trimmedPng).grade(item)
            },
            callCount = { localGradingModel.callCount },
            callsSince = localGradingModel::callsSince,
            outDir = dir
        )
        val report = withContext(Dispatchers.IO) {
            benchmark.run(
                GradingBenchmark.MODELS,
                GradingBenchmark.VARIANTS,
                GradingBenchmark.items(paper, blank)
            ) { line -> viewModelScope.launch { status = "Benchmark: $line" } }
        }
        buildString {
            appendLine("Benchmark written to ${dir.path}")
            report.loads.forEach { appendLine("load ${it.model}: ${"%.1f".format(Locale.US, it.seconds)} s ${it.error ?: ""}") }
            report.cases.forEach { c ->
                appendLine(
                    "#${c.index} ${c.model} ${c.variant} ${c.item}: ${c.status ?: "ERROR"} " +
                        "score=${c.score ?: "-"}/${c.maxScore} conf=${c.confidence ?: "-"} " +
                        "${c.seconds?.let { "%.1f s".format(Locale.US, it) } ?: ""} ${c.reason ?: c.error ?: ""}"
                )
            }
        }
    }

    private fun launchOperation(
        label: String,
        source: OutputSource,
        block: suspend () -> String
    ) {
        if (busy) return
        busy = true
        status = "$label: running..."
        rawOutput = null
        outputSource = OutputSource.NONE
        elapsedMs = null
        resultSpec = null

        viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val output = block()
                elapsedMs = SystemClock.elapsedRealtime() - startedAt
                // Stored exactly as returned: no trim, no default, no fallback.
                rawOutput = output
                outputSource = source
                status = "$label: done"
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                elapsedMs = SystemClock.elapsedRealtime() - startedAt
                rawOutput = t.stackTraceToString()
                outputSource = OutputSource.ERROR
                status = "$label: FAILED - ${t.message ?: t.javaClass.simpleName}"
            } finally {
                // Whichever model was active when this finished is the one that
                // produced the result above.
                resultSpec = modelProvider.spec
                busy = false
            }
        }
    }

    companion object {
        // Defaults for the grading fields. Never used by the OCR probe.
        const val TEST_QUESTION = "What is 7 x 8?"
        const val TEST_MODEL_ANSWER = "56"
        const val TEST_MAX_MARKS = 5

        /**
         * Pure transcription. Carries no question, no expected answer, no
         * marks and no notion of correctness, so whatever comes back can only
         * have come from the pixels.
         */
        const val TRANSCRIBE_PROMPT =
            "Read this image. Write out exactly what is handwritten on it, " +
                "character for character. Do not solve anything. Do not judge " +
                "correctness. Do not explain.\n" +
                "If you cannot read it, write exactly: ILLEGIBLE\n" +
                "Output only the transcribed text."

        /** Plain description. No grading context. */
        const val DESCRIBE_PROMPT = "Describe what you see in this image."

        const val MAX_EXPERIMENT_IMAGES = 3

        /** What each experiment image stands for, in pick order. */
        val EXPERIMENT_ROLES = listOf("student crop", "model-answer image", "question figure")

        /** Experiment B: one line per image, no grading context. */
        fun multiImagePrompt(count: Int): String = buildString {
            appendLine("You are given $count images.")
            appendLine("For each image, in order, write exactly one line:")
            for (k in 1..count) appendLine("IMAGE $k: <what it shows, including any text you can read>")
            append("Write nothing else.")
        }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as CapstoneApplication)
                ModelTestViewModel(
                    context = application,
                    modelProvider = application.container.localModelProvider,
                    // The screen's own picker decides the model here, not the debug
                    // settings the real runs use.
                    gradingService = BoxGrader(
                        application.container.localGradingModel,
                        ImagePrep::toGray,
                        cropTo = ImagePrep::trimmedPng
                    ),
                    localGradingModel = application.container.localGradingModel,
                    runStore = application.container.gradingRunStore,
                    paperPreparer = application.container.paperPreparer
                )
            }
        }
    }
}
