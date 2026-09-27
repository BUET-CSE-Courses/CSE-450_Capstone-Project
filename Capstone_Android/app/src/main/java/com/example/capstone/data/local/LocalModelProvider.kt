package com.example.capstone.data.local

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.capstone.BuildConfig
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Owns the single, application-scoped LiteRT-LM [Engine].
 *
 * The engine costs ~10s to initialize and holds a large native allocation, so
 * there is exactly one for the whole process, created lazily on first use.
 * Conversation objects are cheap and are created fresh per inference by callers.
 *
 * The model file is NOT bundled in the APK (too large). It is adb-pushed to
 * [MODEL_DIR]; see [modelPath].
 *
 * ## Locking
 *
 * Two mutexes, always acquired in the order **[inferenceMutex] then
 * [initMutex]**. Every path that needs both takes them in that order, so there
 * is no lock-order inversion and no deadlock:
 *
 * - [initialize] takes [initMutex] alone, so only one engine is ever built.
 * - [withInference] takes [inferenceMutex] for the whole decode, and calls
 *   [initialize] inside it. Holding [inferenceMutex] across the decode is what
 *   stops [useSpec] from closing the engine out from under a running
 *   conversation.
 * - [useSpec] and [release] take [inferenceMutex] first - which waits for any
 *   in-flight decode to finish - and only then [initMutex] to close and rebuild.
 *
 * ## One engine at a time
 *
 * [useSpec] closes the current engine and waits for that close to return before
 * constructing the replacement. Two loaded models will not fit in this device's
 * RAM, so the two never overlap, not even briefly.
 */
class LocalModelProvider(
    private val context: Context,
    initialSpec: ModelSpec = ModelSpec.DEFAULT
) {

    private val _activeSpec = MutableStateFlow(initialSpec)

    /** The model currently backing the engine. Change it with [useSpec]. */
    val activeSpec: StateFlow<ModelSpec> = _activeSpec.asStateFlow()

    /** Current value of [activeSpec], for callers that do not observe. */
    val spec: ModelSpec get() = _activeSpec.value

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Idle(initialSpec))

    /** Observable load state of the engine, for the UI. */
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    /** Guards engine creation so two callers cannot build two engines. */
    private val initMutex = Mutex()

    /**
     * Serializes inference. One native engine must not run two decodes at once,
     * and the debug screen makes double-taps easy. Also held across a model
     * swap, so a swap can never close an engine mid-decode.
     */
    private val inferenceMutex = Mutex()

    @Volatile
    private var engine: Engine? = null

    /** The location the model must be pushed to. */
    fun modelFile(): File = File(MODEL_DIR, spec.fileName)

    fun modelPath(): String = modelFile().absolutePath

    /** True when a non-empty, readable model file is present at [modelPath]. */
    fun isModelPresent(): Boolean = inspectModel().usable

    /**
     * Everything that can be learned about the model file without opening it.
     *
     * `File.isFile` returns false both when a file is absent and when the app's
     * uid cannot stat it, which are opposite problems with opposite fixes. They
     * are reported separately here so the on-device output can be compared
     * directly against `adb shell ls -la`.
     */
    fun inspectModel(target: ModelSpec = spec): ModelFileStatus {
        val file = File(MODEL_DIR, target.fileName)
        val dir = file.parentFile
        val status = ModelFileStatus(
            path = file.absolutePath,
            expectedBytes = target.expectedBytes,
            parentExists = dir?.exists() == true,
            // Directories need +x, not +r, to be traversed by absolute path.
            parentTraversable = dir?.canExecute() == true,
            exists = file.exists(),
            isFile = file.isFile,
            readable = file.canRead(),
            sizeBytes = if (file.isFile) file.length() else 0L
        )
        if (status.usable) {
            Log.i(TAG, "model OK: $status")
        } else {
            // Log the exact absolute path so it can be diffed against `adb shell ls`.
            Log.w(TAG, "model NOT usable: $status -- ${status.diagnosis}")
        }
        return status
    }

    /**
     * Which registry entries actually have a file on this device, and why not
     * when they do not.
     *
     * Hits the filesystem once per [ModelSpec], so it runs on [Dispatchers.IO].
     */
    suspend fun availability(): List<SpecAvailability> = withContext(Dispatchers.IO) {
        ModelSpec.entries.map { availabilityOf(it) }
    }

    /** Filesystem check for one entry. Call from [Dispatchers.IO]. */
    fun availabilityOf(target: ModelSpec): SpecAvailability {
        val file = File(MODEL_DIR, target.fileName)
        val reason = when {
            !file.exists() -> "Not on device: no file at $MODEL_DIR/${target.fileName}"
            !file.isFile -> "$MODEL_DIR/${target.fileName} is not a regular file"
            !file.canRead() -> "Present but this app cannot read it (permissions or SELinux)"
            file.length() == 0L -> "Present but zero bytes"
            else -> null
        }
        return SpecAvailability(
            spec = target,
            present = reason == null,
            reason = reason,
            sizeBytes = if (file.isFile) file.length() else 0L
        )
    }

    /** Size of the model file in bytes, or 0 when it is missing. */
    fun modelSizeBytes(): Long {
        val file = modelFile()
        return if (file.isFile) file.length() else 0L
    }

    /** True once the engine has been built. Does not trigger initialization. */
    fun isEngineReady(): Boolean = engine != null

    /**
     * Returns the shared engine, building it on first call.
     *
     * Safe to call from any dispatcher: the expensive work happens on
     * [Dispatchers.IO]. Concurrent callers block on [initMutex] and all receive
     * the same instance.
     *
     * @throws IllegalStateException if the model file is missing, empty, or the
     *   wrong size for the active spec.
     */
    suspend fun initialize(): Engine {
        engine?.let { return it }
        return initMutex.withLock {
            // Re-check inside the lock: another caller may have won the race.
            engine ?: createEngineLocked().also { engine = it }
        }
    }

    /** Alias for [initialize], for call sites that only want the engine. */
    suspend fun engine(): Engine = initialize()

    /**
     * Builds the engine for the active spec.
     *
     * Caller MUST hold [initMutex]. Publishes [EngineState.Loading] before the
     * native work and [EngineState.Ready] / [EngineState.Failed] after it.
     */
    private suspend fun createEngineLocked(): Engine = withContext(Dispatchers.IO) {
        val target = spec
        val file = File(MODEL_DIR, target.fileName)
        val status = inspectModel(target)
        val sizeMb = String.format(Locale.US, "%.1f", status.sizeBytes / 1024.0 / 1024.0)
        Log.i(TAG, "loading ${target.name} from ${status.path} ($sizeMb MB)")

        _engineState.value = EngineState.Loading(target)

        try {
            // Fail here, in Kotlin, where the message survives. Handing a missing
            // or truncated file to the native loader risks an abort that no catch
            // block can see.
            check(status.usable) { status.diagnosis }
            check(status.sizeMatchesExpected) {
                "Model file at ${status.path} is ${status.sizeBytes} bytes but " +
                    "${target.name} should be ${target.expectedBytes}. The push was " +
                    "truncated or the wrong file is in place. Re-push it: " +
                    "adb push ${target.fileName} $MODEL_DIR/"
            }

            val config = EngineConfig(
                modelPath = file.absolutePath,
                backend = Backend.CPU(),
                visionBackend = Backend.GPU(),
                maxNumTokens = target.maxNumTokens,
                cacheDir = context.cacheDir.path
            )

            // Debug only: read at engine creation, it makes
            // Conversation.getBenchmarkInfo() report prefill/decode token counts.
            if (BuildConfig.DEBUG) enableBenchmarkFlag()
            Log.i(
                TAG,
                "EngineConfig maxNumTokens=${config.maxNumTokens} " +
                    "maxNumImages=${config.maxNumImages ?: "unset (native default)"} " +
                    "benchmark=${BuildConfig.DEBUG}"
            )

            val startedAt = SystemClock.elapsedRealtime()
            val created = Engine(config)
            try {
                created.initialize()
            } catch (t: Throwable) {
                // Do not leak the half-built native engine.
                runCatching { created.close() }
                Log.e(
                    TAG,
                    "engine init FAILED after ${SystemClock.elapsedRealtime() - startedAt} ms",
                    t
                )
                throw t
            }
            Log.i(TAG, "engine initialized in ${SystemClock.elapsedRealtime() - startedAt} ms")
            _engineState.value = EngineState.Ready(target)
            created
        } catch (t: Throwable) {
            _engineState.value = EngineState.Failed(
                spec = target,
                message = t.message ?: t.javaClass.simpleName
            )
            throw t
        }
    }

    /**
     * Runs [block] against the shared engine with inference serialized and off
     * the main thread. Initializes the engine first if needed.
     *
     * [inferenceMutex] is held for the whole decode, so a concurrent [useSpec]
     * waits here rather than closing the engine mid-conversation.
     */
    suspend fun <T> withInference(block: suspend (Engine) -> T): T =
        inferenceMutex.withLock {
            // Resolved INSIDE the lock. Resolving it outside would let a swap
            // close this engine between the lookup and the decode.
            val ready = initialize()
            withContext(Dispatchers.IO) { block(ready) }
        }

    /**
     * Sends one throwaway prompt and returns the model's raw text. Debug helper
     * for ModelTestScreen only — real grading goes through
     * [com.example.capstone.domain.grading.GradingService].
     */
    suspend fun runRawPrompt(prompt: String, imagePng: ByteArray? = null): String =
        runRawPrompt(prompt, listOfNotNull(imagePng))

    /**
     * Debug variant of [runRawPrompt] that attaches every image in [images], in
     * order, before the text. Used by the multi-image probe on ModelTestScreen.
     *
     * @param systemInstruction optional system turn, passed through
     *   [ConversationConfig.systemInstruction].
     */
    suspend fun runRawPrompt(
        prompt: String,
        images: List<ByteArray>,
        systemInstruction: String? = null,
        label: String = "runRawPrompt"
    ): String = withInference { engine ->
        val startedAt = SystemClock.elapsedRealtime()
        val conversationConfig = ConversationConfig(
            systemInstruction = systemInstruction?.let { Contents.of(Content.Text(it)) },
            samplerConfig = SamplerConfig(topK = 5, topP = 0.95, temperature = 0.1)
        )
        engine.createConversation(conversationConfig).use { conversation ->
            // Images first, text last - required content order for Gemma 3n.
            val parts = images.map { Content.ImageBytes(it) } + Content.Text(prompt)
            val raw = try {
                conversation.sendMessage(Contents.of(parts)).textOrEmpty()
            } catch (t: Throwable) {
                Log.e(TAG, "$label FAILED images=${images.size} ${t.javaClass.name}: ${t.message}", t)
                throw t
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            Log.i(
                TAG,
                "$label images=${images.size} system=${systemInstruction != null} " +
                    "elapsedMs=$elapsedMs ${conversation.statsLine()}"
            )
            logVerbatim(TAG, "$label rawResponse", raw)
            raw
        }
    }

    /**
     * Closes the engine and frees its native memory. Idempotent: calling it
     * without an engine, or twice, is a no-op.
     */
    suspend fun release() {
        // NonCancellable so a cancelled caller cannot abandon the native engine.
        withContext(NonCancellable) {
            // Wait for any in-flight inference: closing the engine underneath a
            // running decode would crash in native code.
            inferenceMutex.withLock {
                initMutex.withLock {
                    closeEngineLocked()
                    _engineState.value = EngineState.Idle(spec)
                }
            }
        }
    }

    /**
     * Closes and clears the engine. Caller MUST hold both mutexes.
     *
     * Returns true when there was an engine to close. `close()` is a blocking
     * native call, so by the time it returns the allocation is gone - which is
     * what makes it safe for [useSpec] to build the replacement immediately
     * afterwards.
     */
    private suspend fun closeEngineLocked(): Boolean = withContext(Dispatchers.IO) {
        val current = engine
        engine = null
        if (current == null) {
            Log.i(TAG, "no engine to release")
            false
        } else {
            runCatching { current.close() }
                .onSuccess { Log.i(TAG, "engine released") }
                .onFailure { Log.w(TAG, "engine close failed", it) }
            true
        }
    }

    /**
     * Switches the active model.
     *
     * Selecting the model that is already active is a no-op: no release, no
     * reload.
     *
     * Otherwise, under both mutexes and in this order:
     * 1. wait for any in-flight decode to finish,
     * 2. close the current engine and wait for that close to return,
     * 3. publish the new spec,
     * 4. build the replacement **only if an engine was loaded before the
     *    switch**, so a swap preserves whether a model is loaded without ever
     *    holding two at once. When nothing was loaded, the replacement is left
     *    for the next [initialize] exactly as before.
     *
     * @throws Throwable whatever loading the replacement threw. The old engine
     *   is closed and gone by then, and [engineState] is [EngineState.Failed].
     */
    suspend fun useSpec(newSpec: ModelSpec) {
        if (newSpec == spec) {
            Log.i(TAG, "useSpec(${newSpec.name}) ignored: already the active spec")
            return
        }

        withContext(NonCancellable) {
            inferenceMutex.withLock {
                initMutex.withLock {
                    // Re-check under the locks: another caller may have switched
                    // to this same spec while we queued.
                    if (newSpec == spec) {
                        Log.i(TAG, "useSpec(${newSpec.name}) ignored: already the active spec")
                        return@withLock
                    }

                    val hadEngine = closeEngineLocked()

                    _activeSpec.value = newSpec
                    _engineState.value = EngineState.Idle(newSpec)
                    Log.i(
                        TAG,
                        "active spec is now ${newSpec.name} file=${newSpec.fileName} " +
                            "maxNumTokens=${newSpec.maxNumTokens} reload=$hadEngine"
                    )

                    // Only now, with the previous engine closed, is it safe to
                    // allocate another one.
                    if (hadEngine) {
                        engine = createEngineLocked()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "LocalModelProvider"

        /**
         * Directory holding the adb-pushed model file. Single source of truth -
         * [modelFile], [isModelPresent] and [modelSizeBytes] all resolve through it.
         *
         * DEV-ONLY PATH. `/data/local/tmp/` is world-readable scratch space that
         * `adb push` can write to without root. It is used because Android 11+
         * scoped storage blocks the adb shell user from writing into
         * /sdcard/Android/data/<package>/files/, so the model cannot be pushed to
         * the app's external files dir. It is also what Google's LiteRT-LM
         * development docs use.
         *
         * A production build must NOT read from here: the path is outside the app
         * sandbox, is shared with every other app's debug tooling, and is wiped by
         * some OEM cleanup. Production needs a download flow that fetches the model
         * into app-private storage (filesDir / getExternalFilesDir) with a checksum.
         */
        const val MODEL_DIR = "/data/local/tmp/llm"

        /** Spec used when no caller specifies one. */
        val DEFAULT_SPEC = ModelSpec.DEFAULT
    }
}

/**
 * Load state of the single engine, published for the UI.
 *
 * [spec] is the model the state refers to, which during a swap is already the
 * new one.
 */
sealed interface EngineState {
    val spec: ModelSpec

    /** No engine allocated. Either never loaded, released, or just swapped. */
    data class Idle(override val spec: ModelSpec) : EngineState

    /** Native load in progress. Nothing may run inference. */
    data class Loading(override val spec: ModelSpec) : EngineState

    /** Engine allocated and initialized for [spec]. */
    data class Ready(override val spec: ModelSpec) : EngineState

    /** The last load attempt for [spec] failed. No engine is allocated. */
    data class Failed(override val spec: ModelSpec, val message: String) : EngineState

    /** True while the engine cannot be used because a load is running. */
    val isLoading: Boolean get() = this is Loading
}

/**
 * Whether one registry entry has a usable file on this device.
 *
 * A model whose file was never pushed is a normal state on a dev device, not an
 * error - the picker shows it disabled with [reason] rather than hiding it, so
 * "this model is missing" and "this model does not exist" stay distinguishable.
 */
data class SpecAvailability(
    val spec: ModelSpec,
    val present: Boolean,
    /** Why the entry is unusable, or null when it is usable. */
    val reason: String?,
    /** Actual size on disk, or 0 when there is no file. */
    val sizeBytes: Long
)

/**
 * What the filesystem says about the model file, captured in one pass.
 *
 * Split into independent facts on purpose: "not there" and "there but the app
 * cannot read it" need different fixes, and the second is invisible to a plain
 * existence check.
 */
data class ModelFileStatus(
    val path: String,
    /** Published size for this spec, or null when none is recorded. */
    val expectedBytes: Long?,
    val parentExists: Boolean,
    val parentTraversable: Boolean,
    val exists: Boolean,
    val isFile: Boolean,
    val readable: Boolean,
    val sizeBytes: Long
) {
    /** Loadable as far as the filesystem is concerned. Says nothing about the contents. */
    val usable: Boolean get() = isFile && readable && sizeBytes > 0L

    /** True when no published size is recorded: there is nothing to contradict. */
    val sizeMatchesExpected: Boolean
        get() = expectedBytes == null || sizeBytes == expectedBytes

    /** One sentence naming the most likely cause, and the command that fixes it. */
    val diagnosis: String
        get() = when {
            !parentExists ->
                "Directory ${LocalModelProvider.MODEL_DIR} does not exist. " +
                    "Run: adb shell mkdir -p ${LocalModelProvider.MODEL_DIR}"
            !parentTraversable ->
                "Directory ${LocalModelProvider.MODEL_DIR} is not traversable by this app. " +
                    "Run: adb shell chmod 755 /data/local/tmp ${LocalModelProvider.MODEL_DIR}"
            !exists ->
                "No file at $path. Push the model, then re-run this check."
            !isFile ->
                "$path exists but is not a regular file."
            !readable ->
                "$path exists but this app cannot read it. Run: adb shell chmod 644 $path " +
                    "(if that does not help, the block is SELinux, not permissions)."
            sizeBytes == 0L ->
                "$path is zero bytes. The push produced an empty file."
            !sizeMatchesExpected ->
                "$path is $sizeBytes bytes, expected $expectedBytes. Truncated or wrong file."
            else -> "OK"
        }

    override fun toString(): String =
        "path=$path exists=$exists isFile=$isFile readable=$readable " +
            "sizeBytes=$sizeBytes expectedBytes=${expectedBytes ?: "unrecorded"} " +
            "parentExists=$parentExists parentTraversable=$parentTraversable"
}

/**
 * Flattens a model [Message] to its text parts. Non-text contents are ignored.
 * The result is not trimmed or altered, so callers that need the model's
 * verbatim output get it.
 */
internal fun Message.textOrEmpty(): String =
    contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

/** Sets the LiteRT-LM benchmark flag. Only takes effect for engines built afterwards. */
@OptIn(ExperimentalApi::class)
private fun enableBenchmarkFlag() {
    ExperimentalFlags.enableBenchmark = true
}

/**
 * Token counts for the conversation's last turn, for the diagnostic log.
 *
 * `tokenCount` is the conversation's total (prompt plus reply) and is always
 * available. The prefill/decode split comes from benchmark info, which is only
 * filled when the engine was built with the benchmark flag (debug builds).
 * Either one failing is reported, not thrown: it is diagnostics only.
 */
@OptIn(ExperimentalApi::class)
internal fun Conversation.statsLine(): String {
    val total = runCatching { getTokenCount().toString() }
        .getOrElse { "n/a(${it.javaClass.simpleName})" }
    val bench = runCatching { getBenchmarkInfo() }.getOrNull()
    return if (bench != null) {
        "tokenCount=$total promptTokens=${bench.lastPrefillTokenCount} " +
            "responseTokens=${bench.lastDecodeTokenCount} " +
            "ttftS=${String.format(Locale.US, "%.2f", bench.timeToFirstTokenInSecond)} " +
            "prefillTps=${String.format(Locale.US, "%.1f", bench.lastPrefillTokensPerSecond)} " +
            "decodeTps=${String.format(Locale.US, "%.1f", bench.lastDecodeTokensPerSecond)}"
    } else {
        "tokenCount=$total promptTokens=n/a responseTokens=n/a"
    }
}

/**
 * One turn's numbers, kept for the debug benchmark (session 9: the phone's log throttling
 * dropped the app's logcat lines, so counts must not depend on logcat). Null when unknown;
 * the prefill/decode split needs the benchmark flag (debug builds).
 */
data class TurnStats(
    val label: String,
    val elapsedMs: Long,
    val tokenCount: Int?,
    val promptTokens: Int?,
    val responseTokens: Int?,
    val timeToFirstTokenS: Double?,
    val prefillTokensPerS: Double?,
    val decodeTokensPerS: Double?
)

@OptIn(ExperimentalApi::class)
internal fun Conversation.turnStats(label: String, elapsedMs: Long): TurnStats {
    val total = runCatching { getTokenCount() }.getOrNull()
    val bench = runCatching { getBenchmarkInfo() }.getOrNull()
    return TurnStats(
        label = label,
        elapsedMs = elapsedMs,
        tokenCount = total,
        promptTokens = bench?.lastPrefillTokenCount,
        responseTokens = bench?.lastDecodeTokenCount,
        timeToFirstTokenS = bench?.timeToFirstTokenInSecond,
        prefillTokensPerS = bench?.lastPrefillTokensPerSecond,
        decodeTokensPerS = bench?.lastDecodeTokensPerSecond
    )
}

/** Logcat drops everything past ~4 KB of one line, so long text goes out in pieces. */
private const val LOG_CHUNK = 3000

/**
 * Logs [text] exactly as given: no trim, no parse. The length is logged first
 * and the text is fenced with `<<<` / `>>>` so leading and trailing whitespace,
 * and an empty reply, stay visible.
 *
 * Debug builds only. A reply can recite the answer key, so a release build logs
 * the length and nothing else.
 */
internal fun logVerbatim(tag: String, header: String, text: String) {
    if (!BuildConfig.DEBUG) {
        Log.i(tag, "$header length=${text.length} (text logged in debug builds only)")
        return
    }
    val chunks = text.chunked(LOG_CHUNK).ifEmpty { listOf("") }
    Log.i(tag, "$header length=${text.length} parts=${chunks.size}")
    chunks.forEachIndexed { i, part ->
        val open = if (i == 0) "<<<" else ""
        val close = if (i == chunks.lastIndex) ">>>" else ""
        Log.i(tag, "$header [${i + 1}/${chunks.size}] $open$part$close")
    }
}
