package com.example.capstone.extractor

/**
 * The outcome of extracting one page.
 *
 * There is no success-with-an-error-string case, and that is the point. The web end's
 * `extract_page` (Script-Checker-Web-End `backend/services/extractor.py`) returns an empty
 * crop list beside an `error` field when fewer than four markers were found, which reads as
 * success to anything that does not think to look. Here a caller cannot get at crops without
 * having handled every way in which there are none.
 */
sealed interface ExtractionResult {

    /**
     * One crop per segment printed on the requested page, in (order index, part) order.
     * A box that spans pages contributes only its parts on this page.
     */
    data class Success(val crops: List<AnswerCrop>) : ExtractionResult

    /**
     * Fewer than all of the layout's markers were detected. [found] counts the layout's own
     * marker ids that were seen; markers carrying ids the layout does not name are ignored.
     */
    data class MarkersNotFound(val found: Int, val missingIds: List<Int>) : ExtractionResult

    /** The layout could not be used. [reason] names the box or marker and the numbers. */
    data class InvalidLayout(val reason: String) : ExtractionResult

    /** The bytes were not a decodable image. */
    data class Undecodable(val cause: Throwable) : ExtractionResult

    /**
     * Every marker was found, but the canonical-to-image solve degenerated - an empty
     * homography, or detected centres that are collinear or coincident.
     *
     * The web end has no such case: a degenerate solve there still reports 4/4 and
     * "homography" and produces crops from a garbage matrix.
     */
    data class RegistrationFailed(val reason: String) : ExtractionResult
}

/**
 * A point in image pixels.
 *
 * Deliberately not `org.opencv.core.Point`. OpenCV is an `implementation` dependency of this
 * module, so it is absent from the compile classpath of anything that depends on it; a result
 * type carrying an OpenCV class would be unusable by the app that asked for it.
 */
data class PointPx(val x: Double, val y: Double)

/**
 * One rectified crop: one [part] of one answer box.
 *
 * [orderIndex] is the box's served `order_index`. [part] is the segment's position in the
 * box's `segments`, which is the web end's `part` (`CropImage.part`), so a box's crops put
 * back in part order are its whole answer. [externalQuestionId] and [externalAnswerBoxId]
 * are kept as a pair: a bare answer-box id is only meaningful within its paper.
 *
 * Note that [png] is a ByteArray, so the generated equals/hashCode compare it by identity, not
 * by content. Two crops holding the same bytes are not equal. Compare the ids.
 */
data class AnswerCrop(
    val externalQuestionId: String,
    val externalAnswerBoxId: String,
    val part: Int,
    val pageIndex: Int,
    val orderIndex: Int,
    val png: ByteArray,
    /**
     * Where this crop was cut from, in the pixels of the image that was passed in: the
     * segment's four corners as TL, TR, BR, BL. Not axis aligned - a photo taken at an angle
     * gives a genuine quadrilateral, which is the point of rectifying rather than cropping.
     *
     * It is here so a UI can show the student what was read off their photo. Nothing in the
     * extraction path consumes it.
     */
    val imageQuad: List<PointPx>,
)
