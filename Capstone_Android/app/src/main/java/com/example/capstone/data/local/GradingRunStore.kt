package com.example.capstone.data.local

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.example.capstone.data.remote.OnDeviceGradesDto
import com.example.capstone.domain.grading.BoxGradeResult
import com.example.capstone.domain.grading.BoxGrader
import com.example.capstone.domain.grading.BoxStatus
import com.google.gson.Gson
import com.google.gson.JsonParseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Where a grading run is. Saved, so a screen (or the next process) can pick it up. */
enum class RunPhase {
    PREPARING,
    STARTING,
    GRADING,
    POSTING,

    /** A call failed on the network; the run retries with backoff. */
    WAITING_FOR_NETWORK,

    /** The server acknowledged this phone's results post (2xx). The only "synced" state. */
    POSTED,

    /**
     * The server already had marks this phone did not post: a teacher graded on the website,
     * or another run finished. Nothing more to post.
     */
    SERVER_HAS_MARKS,

    /** Stopped for a reason retrying will not fix by itself; [GradingRunRecord.message] says which. */
    BLOCKED
}

/** One box's phone result as saved. Mirrors [BoxGradeResult]. */
data class StoredBoxResult(
    val answerBoxId: String,
    val score: Double?,
    val maxScore: Int,
    val transcript: String?,
    val feedback: String?,
    val confidence: Double?,
    val status: BoxStatus,
    val reason: String?,
    val rawReply: String?,
    val modelId: String?,
    val durationMs: Long
) {
    /**
     * The model call itself failed on the phone (an engine error, not a reply), so grading
     * this box again is worth trying. Other results without a mark (no crop, over budget, a
     * missing image) would come out the same.
     */
    val callFailed: Boolean
        get() = status == BoxStatus.NEEDS_FALLBACK && rawReply == null &&
            (reason?.startsWith(BoxGrader.REQUEST_FAILED) == true || reason?.startsWith(BoxGrader.PHONE_FAILED) == true)

    fun toResult() = BoxGradeResult(
        answerBoxId = answerBoxId,
        score = score,
        maxScore = maxScore,
        transcript = transcript,
        feedback = feedback,
        confidence = confidence,
        status = status,
        reason = reason,
        rawReply = rawReply,
        modelId = modelId,
        durationMs = durationMs
    )

    companion object {
        fun from(r: BoxGradeResult) = StoredBoxResult(
            answerBoxId = r.answerBoxId,
            score = r.score,
            maxScore = r.maxScore,
            transcript = r.transcript,
            feedback = r.feedback,
            confidence = r.confidence,
            status = r.status,
            reason = r.reason,
            rawReply = r.rawReply,
            modelId = r.modelId,
            durationMs = r.durationMs
        )
    }
}

/**
 * Everything a grading run needs to resume after the app is killed.
 *
 * The phone's per-box results do not depend on the run token (they come from the crops and
 * the pack), so they are kept across a restart: a new token re-grades nothing that is saved.
 *
 * Lists are read through the `*OrEmpty` accessors: Gson fills a missing field with null
 * whatever the Kotlin type says.
 */
data class GradingRunRecord(
    val submissionId: String,
    val questionId: String,
    /** The live token from `start`, or null when the next step must start again. */
    val runToken: String? = null,
    val eligibleBoxIds: List<String>? = null,
    val protectedBoxIds: List<String>? = null,
    val results: List<StoredBoxResult>? = null,
    val phase: RunPhase? = RunPhase.PREPARING,
    /** "Grading box [current] of [total]"; 0 when not grading. */
    val current: Int = 0,
    val total: Int = 0,
    val message: String? = null,
    val fallbackEnabled: Boolean = false,
    /** The token of the post the server acknowledged. Set only together with [RunPhase.POSTED]. */
    val postedToken: String? = null,
    /** The last marks the server sent back (results reply or my grades). */
    val serverGrades: OnDeviceGradesDto? = null,
    val updatedAtMillis: Long = 0L,
    /**
     * The box being graded right now and how many times grading it has **started**. Set before
     * the model call and cleared with its result, so a value found on resume means the app died
     * during that box (session 7: a library crash after every reply restarted the run forever).
     */
    val gradingBoxId: String? = null,
    val gradingBoxStarts: Int = 0
) {
    val eligibleOrEmpty: List<String> get() = eligibleBoxIds.orEmpty()
    val resultsOrEmpty: List<StoredBoxResult> get() = results.orEmpty()

    fun resultFor(boxId: String): StoredBoxResult? = resultsOrEmpty.firstOrNull { it.answerBoxId == boxId }

    /** Every eligible box has a saved result. */
    val hasAllResults: Boolean
        get() = eligibleBoxIds != null && eligibleOrEmpty.all { resultFor(it) != null }

    /** Nothing left to post: the server has this paper's marks. */
    val isFinished: Boolean
        get() = phase == RunPhase.POSTED || phase == RunPhase.SERVER_HAS_MARKS

    /**
     * The saved results a new run should keep. After a teacher's **Reset marks**
     * ([everything]) none: the teacher asked for the paper to be marked afresh. Otherwise
     * (the run expired or failed) every result except those whose model call failed on the
     * phone ([StoredBoxResult.callFailed]), which may well work now.
     */
    fun forGradingAgain(everything: Boolean): GradingRunRecord =
        copy(results = if (everything) emptyList() else resultsOrEmpty.filterNot { it.callFailed })

    /** Replaces (never duplicates) the result for its box. */
    fun withResult(result: BoxGradeResult): GradingRunRecord =
        copy(results = resultsOrEmpty.filter { it.answerBoxId != result.answerBoxId } + StoredBoxResult.from(result))
}

/** The file's contents: every run on this phone, by submission id. */
data class GradingRunsFile(
    val version: Int = VERSION,
    val runs: Map<String, GradingRunRecord>? = null
) {
    companion object {
        const val VERSION = 1
    }
}

/**
 * Grading-run progress in a DataStore (`noBackupFilesDir/grading_runs.json`: the phone's
 * raw replies can quote the answer key). One instance per process and file: DataStore
 * refuses two active stores on one file.
 */
class GradingRunStore(private val dataStore: DataStore<GradingRunsFile>) {

    fun observe(submissionId: String): Flow<GradingRunRecord?> =
        dataStore.data.map { it.runs?.get(submissionId) }.distinctUntilChanged()

    suspend fun get(submissionId: String): GradingRunRecord? = dataStore.data.first().runs?.get(submissionId)

    /** Applies [change] atomically and returns the saved record (null removes it). */
    suspend fun update(
        submissionId: String,
        change: (GradingRunRecord?) -> GradingRunRecord?
    ): GradingRunRecord? {
        var saved: GradingRunRecord? = null
        dataStore.updateData { file ->
            val runs = file.runs.orEmpty().toMutableMap()
            val next = change(runs[submissionId])?.copy(updatedAtMillis = System.currentTimeMillis())
            if (next == null) runs.remove(submissionId) else runs[submissionId] = next
            saved = next
            file.copy(version = GradingRunsFile.VERSION, runs = runs)
        }
        return saved
    }

    suspend fun put(record: GradingRunRecord): GradingRunRecord = update(record.submissionId) { record }!!

    /**
     * Drops every saved run for [questionId] except the one for [keepSubmissionId] (null
     * drops them all). For a submission the server no longer has, e.g. deleted by the
     * teacher: its run must not be resumed or shown. Returns the submission ids removed.
     */
    suspend fun forgetOtherRuns(questionId: String, keepSubmissionId: String?): List<String> {
        var removed: List<String> = emptyList()
        dataStore.updateData { file ->
            val runs = file.runs.orEmpty()
            removed = runs.values
                .filter { it.questionId == questionId && it.submissionId != keepSubmissionId }
                .map { it.submissionId }
            if (removed.isEmpty()) file
            else file.copy(version = GradingRunsFile.VERSION, runs = runs - removed.toSet())
        }
        return removed
    }

    companion object {
        const val FILE_NAME = "grading_runs.json"

        fun create(file: File, scope: CoroutineScope, gson: Gson = Gson()): GradingRunStore =
            GradingRunStore(
                DataStoreFactory.create(
                    serializer = JsonSerializer(gson),
                    // A file that no longer parses is replaced with an empty one rather than
                    // wedging every run; the server still has whatever was posted.
                    corruptionHandler = ReplaceFileCorruptionHandler { GradingRunsFile() },
                    scope = scope,
                    produceFile = { file }
                )
            )
    }

    private class JsonSerializer(private val gson: Gson) : Serializer<GradingRunsFile> {
        override val defaultValue = GradingRunsFile()

        override suspend fun readFrom(input: InputStream): GradingRunsFile {
            val text = input.readBytes().toString(Charsets.UTF_8)
            if (text.isBlank()) return defaultValue
            return try {
                gson.fromJson(text, GradingRunsFile::class.java) ?: defaultValue
            } catch (e: JsonParseException) {
                throw CorruptionException("grading runs file does not parse", e)
            }
        }

        override suspend fun writeTo(t: GradingRunsFile, output: OutputStream) {
            output.write(gson.toJson(t).toByteArray(Charsets.UTF_8))
        }
    }
}
