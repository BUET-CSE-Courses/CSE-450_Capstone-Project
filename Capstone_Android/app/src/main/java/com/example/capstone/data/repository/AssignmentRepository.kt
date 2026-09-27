package com.example.capstone.data.repository

import android.util.Log
import com.example.capstone.data.local.PackStore
import com.example.capstone.data.remote.ApiService
import com.example.capstone.data.remote.AssignmentPackDto
import com.example.capstone.data.remote.StudentAssignmentDto
import com.example.capstone.domain.model.Assignment
import com.example.capstone.domain.model.Question
import com.example.capstone.domain.model.StudentAssignment
import com.example.capstone.extractor.AnswerBoxRef
import com.example.capstone.extractor.Bbox
import com.example.capstone.extractor.Layout
import com.example.capstone.extractor.MarkerRef
import com.example.capstone.extractor.Segment
import com.google.gson.Gson
import com.google.gson.JsonParseException

/** A pack as held on the device. */
data class CachedPack(
    val pack: AssignmentPackDto,
    /** When it was saved (epoch ms). */
    val savedAtMillis: Long,
    /** Image refs the download could not fetch. Empty when the pack is complete. */
    val missingImages: List<String> = emptyList()
)

/** A pack image reference, e.g. "pack/images/model-answer/<id>". */
data class PackImageRef(val kind: String, val imageId: String) {
    companion object {
        private val REF = Regex("^pack/images/(model-answer|question)/([A-Za-z0-9_-]{1,128})$")

        /**
         * Refs are relative to the pack URL (routers/on_device.py). Only the two
         * served shapes are accepted: the app never follows an arbitrary URL
         * with the student's bearer token on it.
         */
        fun parse(ref: String): PackImageRef? =
            REF.matchEntire(ref)?.let { PackImageRef(it.groupValues[1], it.groupValues[2]) }
    }
}

class AssignmentRepository(
    private val api: ApiService,
    private val store: PackStore,
    private val gson: Gson = Gson()
) {

    /** `GET /api/student/assignments?course_id=`. */
    suspend fun assignments(courseId: String): Result<List<StudentAssignment>> = runCatching {
        api.assignments(courseId).map { it.toDomain() }
    }

    /**
     * Downloads the pack and every image it names, and caches them. The pack
     * is saved before the images, so a failed image leaves a usable pack and
     * is reported in [CachedPack.missingImages] rather than thrown.
     */
    suspend fun downloadPack(questionId: String): Result<CachedPack> = runCatching {
        val raw = api.pack(questionId).string()
        val pack = parsePack(raw, questionId)
        store.writePack(questionId, raw)

        val missing = mutableListOf<String>()
        val refs = pack.boxes.flatMap { it.modelAnswerImages + it.questionImages }.distinct()
        for (ref in refs) {
            val parsed = PackImageRef.parse(ref)
            if (parsed == null) {
                Log.w(TAG, "unrecognised image ref, not fetched: $ref")
                missing += ref
                continue
            }
            try {
                val bytes = api.packImage(questionId, parsed.kind, parsed.imageId).bytes()
                store.writeImage(questionId, parsed.kind, parsed.imageId, bytes)
            } catch (e: Exception) {
                Log.w(TAG, "image $ref not fetched", e)
                missing += ref
            }
        }
        CachedPack(pack, store.savedAt(questionId) ?: System.currentTimeMillis(), missing)
    }

    /** The cached pack, or null when there is none (or it no longer parses). */
    fun cachedPack(questionId: String): CachedPack? {
        val raw = store.readPack(questionId) ?: return null
        val pack = try {
            parsePack(raw, questionId)
        } catch (e: Exception) {
            Log.w(TAG, "cached pack for $questionId is unusable", e)
            return null
        }
        val missing = pack.boxes.flatMap { it.modelAnswerImages + it.questionImages }.distinct()
            .filter { ref ->
                val parsed = PackImageRef.parse(ref)
                parsed == null || !store.hasImage(questionId, parsed.kind, parsed.imageId)
            }
        return CachedPack(pack, store.savedAt(questionId) ?: 0L, missing)
    }

    /** A cached image's bytes, by the ref the pack gave. */
    fun packImage(questionId: String, ref: String): ByteArray? {
        val parsed = PackImageRef.parse(ref) ?: return null
        return store.readImage(questionId, parsed.kind, parsed.imageId)
    }

    /**
     * The cached pack as the scan and grading screens' [Assignment], any number of pages.
     * Fails when the pack has not been downloaded yet.
     */
    fun worksheetFor(questionId: String): Result<Assignment> {
        val cached = cachedPack(questionId)
            ?: return Result.failure(
                IllegalStateException("Download this assignment first (open it from the list).")
            )
        return Result.success(cached.pack.toWorksheet())
    }

    /**
     * Parses and checks a pack. Refuses a version this app does not know and
     * a pack for a different paper than the one asked for.
     */
    fun parsePack(raw: String, questionId: String): AssignmentPackDto {
        val pack = try {
            gson.fromJson(raw, AssignmentPackDto::class.java)
        } catch (e: JsonParseException) {
            throw IllegalStateException("The assignment pack is not valid JSON: ${e.message}", e)
        } ?: throw IllegalStateException("The assignment pack is empty.")
        check(pack.packVersion == AssignmentPackDto.SUPPORTED_PACK_VERSION) {
            "This app reads pack version ${AssignmentPackDto.SUPPORTED_PACK_VERSION}; " +
                "the web end sent ${pack.packVersion}. Update the app."
        }
        check(pack.questionId == questionId) {
            "Asked for the pack of $questionId, got ${pack.questionId}."
        }
        return pack
    }

    private companion object {
        const val TAG = "AssignmentRepository"
    }
}

internal fun StudentAssignmentDto.toDomain() = StudentAssignment(
    questionId = questionId,
    courseId = courseId,
    title = title?.takeIf { it.isNotBlank() } ?: "Untitled paper",
    totalMarks = totalMarks,
    pageCount = pageCount,
    submissionId = submissionId,
    submittedPages = submittedPages,
    handedIn = handedIn,
    gradingStatus = submissionStatus,
    released = released,
    earned = earned,
    maxScore = maxScore
)

/**
 * Bridges the pack onto the scan and grading screens. The box id is the join
 * key between crops and questions, so each box becomes one [Question] keyed by
 * it; its parts are in [Assignment.layout]. The grading run reads the pack's
 * own text, label, images and `blocked_reason` (PaperPreparer); this bridge
 * only feeds the scan screen and QuestionResolver.
 */
internal fun AssignmentPackDto.toWorksheet(): Assignment = Assignment(
    id = questionId,
    title = title?.takeIf { it.isNotBlank() } ?: "Untitled paper",
    description = null,
    questions = boxes.map { box ->
        Question(
            id = box.orderIndex,
            text = box.questionText,
            marks = box.points,
            modelAnswer = box.modelAnswerText,
            rubric = null,
            externalAnswerBoxId = box.id
        )
    },
    externalQuestionId = questionId,
    layout = toExtractorLayout(),
    pageCount = pageCount
)

/**
 * Adapts the pack's geometry onto the extractor's [Layout]. Every number
 * came off the wire. Nothing is repaired on the way through: a malformed
 * centre or bbox is passed along for LayoutValidator to refuse, because a
 * layout patched into plausibility still registers four markers and then
 * crops in the wrong place.
 *
 * A box's pieces follow the web end's `get_page_segments`
 * (`services/extractor.py`): its `segments` when it has any, one crop per
 * segment ("part"); otherwise `page_index` + `bbox` as its only part. At
 * finalize the server sets `bbox`/`page_index` to the FIRST segment only
 * (`routers/questions.py`), so reading `bbox` alone would grade half of a box
 * that runs onto the next page.
 */
internal fun AssignmentPackDto.toExtractorLayout(): Layout? {
    val width = pageWidthPx ?: return null
    val height = pageHeightPx ?: return null
    return Layout(
        externalQuestionId = questionId,
        pageWidthPx = width,
        pageHeightPx = height,
        // A key that is not an integer, or a centre that is not a pair, is
        // dropped so the validator sees a marker set that is short, not wrong.
        markers = markers.centres.mapNotNull { (id, centre) ->
            val markerId = id.toIntOrNull()
            if (markerId == null || centre.size != 2) null
            else MarkerRef(id = markerId, x = centre[0].toDouble(), y = centre[1].toDouble())
        },
        // Server order (order_index), mapped in place, never re-sorted.
        answerBoxes = boxes.map { box ->
            val served = box.segments.orEmpty()
            val segments = if (served.isNotEmpty()) {
                // A segment that is not five numbers becomes page -1 with a zero bbox,
                // which the validator refuses by name.
                served.map { seg ->
                    if (seg.size == 5) Segment(seg[0], Bbox(seg[1], seg[2], seg[3], seg[4]))
                    else Segment(-1, Bbox(0, 0, 0, 0))
                }
            } else {
                val bbox = box.bbox.orEmpty()
                listOf(
                    Segment(
                        pageIndex = box.pageIndex ?: -1,
                        bbox = Bbox(
                            x = bbox.getOrElse(0) { 0 },
                            y = bbox.getOrElse(1) { 0 },
                            w = bbox.getOrElse(2) { 0 },
                            h = bbox.getOrElse(3) { 0 }
                        )
                    )
                )
            }
            AnswerBoxRef(externalAnswerBoxId = box.id, orderIndex = box.orderIndex, segments = segments)
        },
        arucoDictionary = markers.arucoDict
    )
}
