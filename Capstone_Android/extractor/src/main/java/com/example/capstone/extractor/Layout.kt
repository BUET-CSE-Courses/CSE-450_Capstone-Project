package com.example.capstone.extractor

/**
 * The geometry this module extracts against.
 *
 * Every number here is supplied by the caller. **No page geometry is hardcoded anywhere in
 * this module** - not the marker margin, not the marker size, not an id-to-corner ordering.
 * That is deliberate, and it is the single most important property of this file.
 *
 * `INTEGRATION_AUDIT.md` §2.3 measured the cost of getting it wrong, against real markers and
 * a real perspective warp: a marker margin off by 10 px displaces every crop by ~11 px, and
 * assuming a clockwise id order instead of the generator's row-major one displaces them by
 * 990 px with a mirrored homography. **Both still detect 4/4 markers and report success.**
 * So the numbers are arguments, and a mismatch is the caller's to state and to check.
 *
 * The contract they follow is the web end's (Script-Checker-Web-End `backend/`):
 * - page size `round(8.27 * dpi) x round(11.69 * dpi)` (`services/doc_renderer.py`,
 *   `render_finalized_question`);
 * - marker centres `get_marker_positions` in `services/doc_renderer.py`, ids 0..3 row-major
 *   (TL, TR, BL, BR), dictionary `settings.ARUCO_DICT` (`config.py`);
 * - per box, `segments = [[page, x, y, w, h], ...]`, measured in the browser against the
 *   page's own top-left corner (`doc_renderer.py`, the `getBoundingClientRect` pass).
 *
 * The app reads them from the assignment pack (`routers/on_device.py`), which serves the
 * server's own values. `WebEndContractTest` fails when the web end's source moves away
 * from what this module assumes.
 */

/** A rectangle in canonical page pixels: the `[x, y, w, h]` form the web end serves. */
data class Bbox(val x: Int, val y: Int, val w: Int, val h: Int) {
    internal val right: Int get() = x + w
    internal val bottom: Int get() = y + h
    override fun toString(): String = "[$x, $y, $w, $h]"
}

/**
 * The canonical centre of one ArUco marker, in page pixels.
 *
 * Correspondence with a detected marker is by [id] and only by [id]. Position in
 * [Layout.markers] carries no meaning.
 */
data class MarkerRef(val id: Int, val x: Double, val y: Double)

/**
 * One printed piece of an answer box: a `[page, x, y, w, h]` entry of `segments_json`.
 *
 * A box taller than the room left on a page is split across pages by the web end's
 * `_paginate` (`services/doc_renderer.py`), one segment per page. Its position in
 * [AnswerBoxRef.segments] is the server's `part` (`services/extractor.py`,
 * `get_page_segments`), so part numbers mean the same thing on both sides.
 */
data class Segment(val pageIndex: Int, val bbox: Bbox)

/**
 * One answer box, as the pack serves it.
 *
 * [orderIndex] is the served `order_index`, the teacher's document order. It is carried into
 * every crop verbatim and never re-derived from geometry.
 *
 * [segments] is never empty for a usable box. A box on one page has one segment. The
 * adapter that builds this from the pack follows `get_page_segments`: `segments` when the
 * server has them, otherwise `page_index` + `bbox` as the only part.
 */
data class AnswerBoxRef(
    val externalAnswerBoxId: String,
    val orderIndex: Int,
    val segments: List<Segment>,
) {
    companion object {
        /** A box printed in one piece. */
        fun single(id: String, orderIndex: Int, pageIndex: Int, bbox: Bbox) =
            AnswerBoxRef(id, orderIndex, listOf(Segment(pageIndex, bbox)))
    }
}

/**
 * One finalized question's page geometry.
 *
 * [arucoDictionary] is the served dictionary name (`settings.ARUCO_DICT`). Only
 * [SUPPORTED_ARUCO_DICTIONARY] is accepted: a marker drawn from another dictionary does not
 * decode here at all, so a layout naming one is refused up front instead of reported as
 * "markers not found" on every photo.
 */
data class Layout(
    val externalQuestionId: String,
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val markers: List<MarkerRef>,
    val answerBoxes: List<AnswerBoxRef>,
    val arucoDictionary: String,
) {
    /** Every page that has at least one segment on it, ascending. */
    val pagesWithAnswers: List<Int>
        get() = answerBoxes.flatMap { box -> box.segments.map { it.pageIndex } }.distinct().sorted()

    /** The (box, part) pairs printed on [pageIndex], in (order index, part) order. */
    fun partsOnPage(pageIndex: Int): List<Pair<AnswerBoxRef, Int>> =
        answerBoxes.sortedBy { it.orderIndex }.flatMap { box ->
            box.segments.withIndex()
                .filter { it.value.pageIndex == pageIndex }
                .map { box to it.index }
        }

    companion object {
        /** The only dictionary the detector here is built for. */
        const val SUPPORTED_ARUCO_DICTIONARY = "DICT_4X4_50"
    }
}

/**
 * How much of each segment's `bbox` to leave out of the crop.
 *
 * Grading uses [NONE]: the web end crops the full bbox with no inset
 * (`services/extractor.py`, `_crop_region` over `_transform_bbox`), so the phone grades the
 * same region the server's fallback and the teacher look at. Any other inset is applied to
 * the canonical rectangle **before** the homography - see [PageExtractor]. Insetting the
 * warped quad afterwards would take a different amount off each edge, because a photo's
 * scale varies across the page.
 */
data class Inset(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    internal val horizontal: Int get() = left + right
    internal val vertical: Int get() = top + bottom

    internal fun applyTo(bbox: Bbox): Bbox = Bbox(
        x = bbox.x + left,
        y = bbox.y + top,
        w = bbox.w - horizontal,
        h = bbox.h - vertical,
    )

    override fun toString(): String = "($left, $top, $right, $bottom)"

    companion object {
        /** No inset: the crop is the `bbox` as served, the region the web end crops. */
        val NONE = Inset(0, 0, 0, 0)
    }
}

/**
 * Which transform maps canonical page space onto the image.
 *
 * Ports `_compute_transform` (Script-Checker-Web-End `backend/services/extractor.py`): photo
 * is a RANSAC homography, scanner an affine. The web end's third modality, `tablet`, is not
 * here: it has no image and no markers, so it is not extraction.
 */
enum class Modality { PHOTO, SCANNER }
