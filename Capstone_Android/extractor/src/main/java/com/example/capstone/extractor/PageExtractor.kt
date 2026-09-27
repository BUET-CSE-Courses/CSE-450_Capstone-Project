package com.example.capstone.extractor

import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * Cuts one rectified PNG per answer-box segment out of a photographed page.
 *
 * The caller must have loaded the OpenCV native library first - [OpenCvNative] on Android,
 * `nu.pattern.OpenCV.loadLocally()` on the JVM.
 *
 * ### How this differs from the web end
 *
 * The web end (Script-Checker-Web-End `backend/services/extractor.py`) crops the same region -
 * the full segment bbox, registered by the same homography - but cuts it differently:
 * `_crop_region` slices the axis-aligned bounding rectangle of the warped quad out of the
 * image, after `scan_photograph` has flattened and relit a photo (`routers/submissions.py`).
 * Here the canonical rectangle is warped straight onto its own `[w, h]`, so the crop comes out
 * rectified, the same size for every photo of the same box, and without whatever surrounds a
 * skewed box. Same region, different pixels: `WebEndGoldenTest` checks that the regions agree.
 *
 * The [inset] is applied to the canonical rectangle **before** the transform, never to the
 * finished crop, because a photo scales differently across the page and insetting afterwards
 * would take a different amount off each edge. Grading uses [Inset.NONE], as the web end does.
 */
class PageExtractor(
    /** How much of each segment's bbox to leave out. [Inset.NONE] matches the web end. */
    private val inset: Inset = Inset.NONE,
) {

    /**
     * Extracts every segment printed on page [pageIndex]: one crop per (box, part), so a box
     * that runs over a page break gives one crop from each of its pages.
     *
     * A page with no segments is a [ExtractionResult.Success] carrying an empty list: there
     * was nothing to extract and nothing went wrong. The web end reports that as an `error`
     * string inside a successful response, which is the shape this module exists to avoid.
     */
    fun extractPage(
        layout: Layout,
        pageIndex: Int,
        imageBytes: ByteArray,
        modality: Modality = Modality.PHOTO,
    ): ExtractionResult {
        LayoutValidator.validate(layout, inset)?.let {
            return ExtractionResult.InvalidLayout(it)
        }

        val img = try {
            Registration.decode(imageBytes)
                ?: return ExtractionResult.Undecodable(
                    IllegalArgumentException(
                        "imdecode rejected " + imageBytes.size +
                            " bytes: unsupported format or corrupt file",
                    ),
                )
        } catch (t: Throwable) {
            return ExtractionResult.Undecodable(t)
        }

        try {
            val detected = Registration.detect(img)
            val missing = layout.markers.map { it.id }.filterNot { detected.containsKey(it) }
            if (missing.isNotEmpty()) {
                return ExtractionResult.MarkersNotFound(
                    found = layout.markers.size - missing.size,
                    missingIds = missing.sorted(),
                )
            }

            val registered = try {
                Registration.registerPage(layout, pageIndex, modality, inset, detected)
            } catch (e: RegistrationException) {
                return ExtractionResult.RegistrationFailed(e.message.orEmpty())
            }

            return ExtractionResult.Success(registered.map { cut(img, layout, it) })
        } finally {
            img.release()
        }
    }

    /**
     * Rectifies one segment onto its canonical size.
     *
     * A box that runs off the edge of the frame comes back padded with black rather than
     * truncated - `warpPerspective` fills outside the source with BORDER_CONSTANT. That is a
     * real behavioural difference from the web end's `_crop_region`, which clamps to the image
     * bounds and returns a smaller crop. Black padding is the better of the two: the crop keeps its
     * declared size, so nothing downstream has to guess whether a short crop means a short
     * answer or a clipped photo.
     */
    private fun cut(img: Mat, layout: Layout, registered: RegisteredBox): AnswerCrop {
        val w = registered.canonicalRect.w.toDouble()
        val h = registered.canonicalRect.h.toDouble()

        val src = MatOfPoint2f(*registered.imageQuad.toTypedArray())
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(w, 0.0),
            Point(w, h),
            Point(0.0, h),
        )
        val transform = Imgproc.getPerspectiveTransform(src, dst)
        val rectified = Mat()
        val png = MatOfByte()
        try {
            Imgproc.warpPerspective(img, rectified, transform, Size(w, h))
            check(Imgcodecs.imencode(".png", rectified, png)) {
                "imencode failed for answer box " + registered.box.externalAnswerBoxId +
                    " part " + registered.part
            }
            return AnswerCrop(
                externalQuestionId = layout.externalQuestionId,
                externalAnswerBoxId = registered.box.externalAnswerBoxId,
                part = registered.part,
                pageIndex = registered.pageIndex,
                orderIndex = registered.box.orderIndex,
                png = png.toArray(),
                imageQuad = registered.imageQuad.map { PointPx(it.x, it.y) },
            )
        } finally {
            src.release()
            dst.release()
            transform.release()
            rectified.release()
            png.release()
        }
    }
}
