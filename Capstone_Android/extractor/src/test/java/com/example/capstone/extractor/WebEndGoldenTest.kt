package com.example.capstone.extractor

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * The phone's crops against the web end's, on the same images.
 *
 * Each folder under `src/test/resources/golden/` holds page images and a `golden.json` written
 * by `golden/make_golden.py`, which runs the web end's own `extract_page`
 * (Script-Checker-Web-End `backend/services/extractor.py`) over those exact bytes. For every
 * image this test checks, with the geometry the pack would serve:
 *
 * 1. **Same pieces.** The phone cuts the same (box, part) pairs off that page as the web end.
 *    That is segment routing: a box that spans pages gives part 0 on one page and part 1 on
 *    the next, on both sides.
 * 2. **Same region.** The phone's quad, reduced to its bounds the way the web end computes
 *    `warped_bbox`, matches the web end's `warped_bbox`: IoU at least [MIN_IOU] and every edge
 *    within [MAX_EDGE_DRIFT_PX].
 * 3. **Same pixels.** For a tilted image, the phone's rectified crop looks like the web end's
 *    crop of the same box on the flat page: mean absolute grey difference over ink pixels at
 *    most [MAX_MEAN_GREY_DIFF] once both are the same size.
 *
 * Decision 9 of the plan: the phone grades its own crops, the teacher sees the server's. This
 * is what keeps those the same regions.
 */
class WebEndGoldenTest {

    @Before
    fun loadNative() = OpenCvTestNative.load()

    @Test
    fun `every golden fixture is found`() {
        // The repo always carries golden/sample. A missing folder means the test is looking
        // in the wrong place and would pass by checking nothing.
        assertThat(fixtures().map { it.name }).contains("sample")
    }

    @Test
    fun `the phone cuts the same pieces off each page as the web end`() {
        forEachImage { fixture, image, registered ->
            val phone = registered.map { it.box.externalAnswerBoxId to it.part }
            val server = image.serverCrops.map { it.boxId to it.part }
            assertWithMessage(fixture.name + "/" + image.file + ": (box, part) pairs")
                .that(phone).containsExactlyElementsIn(server)
        }
    }

    @Test
    fun `the phone's regions match the web end's warped bboxes`() {
        forEachImage { fixture, image, registered ->
            val byKey = registered.associateBy { it.box.externalAnswerBoxId to it.part }
            for (crop in image.serverCrops) {
                val mine = byKey.getValue(crop.boxId to crop.part).warpedBounds()
                val where = fixture.name + "/" + image.file + " " + crop.boxId + " part " + crop.part +
                    ": phone " + mine.toList() + ", web end " + crop.warpedBbox.toList()
                assertWithMessage(where).that(iou(mine, crop.warpedBbox)).isAtLeast(MIN_IOU)
                for (i in 0 until 4) {
                    assertWithMessage(where).that(Math.abs(mine[i] - crop.warpedBbox[i]))
                        .isAtMost(MAX_EDGE_DRIFT_PX)
                }
            }
        }
    }

    @Test
    fun `a tilted photo's rectified crops look like the web end's flat crops`() {
        var compared = 0
        for (fixture in fixtures()) {
            val layout = fixture.layout
            for (tilted in fixture.images.filter { it.variant == "tilted" }) {
                val flat = fixture.images.single { it.variant == "flat" && it.pageIndex == tilted.pageIndex }
                val flatImg = requireNotNull(Registration.decode(fixture.bytes(flat.file)))
                val result = PageExtractor().extractPage(layout, tilted.pageIndex, fixture.bytes(tilted.file))
                try {
                    val crops = (result as? ExtractionResult.Success)?.crops
                    assertWithMessage(fixture.name + "/" + tilted.file + ": " + result).that(crops).isNotNull()
                    for (crop in crops!!) {
                        val server = flat.serverCrops.single {
                            it.boxId == crop.externalAnswerBoxId && it.part == crop.part
                        }
                        val diff = meanGreyDiff(crop.png, flatImg, server.warpedBbox)
                        assertWithMessage(
                            fixture.name + "/" + tilted.file + " " + crop.externalAnswerBoxId +
                                " part " + crop.part + ": mean grey difference " + diff,
                        ).that(diff).isAtMost(MAX_MEAN_GREY_DIFF)
                        compared++
                    }
                } finally {
                    flatImg.release()
                }
            }
        }
        assertThat(compared).isGreaterThan(0)
    }

    @Test
    fun `every golden page registers on the phone as it did on the web end`() {
        for (fixture in fixtures()) {
            for (image in fixture.images) {
                val result = PageExtractor().extractPage(fixture.layout, image.pageIndex, fixture.bytes(image.file))
                val where = fixture.name + "/" + image.file
                if (image.serverMarkers == "4/4") {
                    assertWithMessage(where + ": " + result).that(result)
                        .isInstanceOf(ExtractionResult.Success::class.java)
                } else {
                    assertWithMessage(where + ": " + result).that(result)
                        .isInstanceOf(ExtractionResult.MarkersNotFound::class.java)
                }
            }
        }
    }

    // ---- fixtures ------------------------------------------------------------------------

    private class ServerCrop(val boxId: String, val part: Int, val warpedBbox: IntArray)

    private class GoldenImage(
        val file: String,
        val pageIndex: Int,
        val variant: String,
        val serverMarkers: String,
        val serverCrops: List<ServerCrop>,
    )

    private class Fixture(val dir: File, val layout: Layout, val images: List<GoldenImage>) {
        val name: String get() = dir.name
        fun bytes(file: String): ByteArray = File(dir, file).readBytes()
    }

    private fun forEachImage(check: (Fixture, GoldenImage, List<RegisteredBox>) -> Unit) {
        var images = 0
        for (fixture in fixtures()) {
            for (image in fixture.images.filter { it.serverMarkers == "4/4" }) {
                val img = requireNotNull(Registration.decode(fixture.bytes(image.file))) { image.file }
                try {
                    val registered = Registration.registerPage(
                        layout = fixture.layout,
                        pageIndex = image.pageIndex,
                        modality = Modality.PHOTO,
                        inset = Inset.NONE,
                        detected = Registration.detect(img),
                    )
                    check(fixture, image, registered)
                    images++
                } finally {
                    img.release()
                }
            }
        }
        assertThat(images).isGreaterThan(0)
    }

    private fun fixtures(): List<Fixture> {
        val root = File(checkNotNull(System.getProperty("golden.dir")) { "golden.dir not set" })
        return root.listFiles().orEmpty()
            .filter { File(it, "golden.json").isFile }
            .sortedBy { it.name }
            .map { load(it) }
    }

    private fun load(dir: File): Fixture {
        val json = JSONObject(File(dir, "golden.json").readText())
        val images = json.getJSONArray("images").objects().map { img ->
            val server = img.getJSONObject("server")
            GoldenImage(
                file = img.getString("file"),
                pageIndex = img.getInt("page_index"),
                variant = img.getString("variant"),
                serverMarkers = server.optString("markers_detected"),
                serverCrops = server.getJSONArray("crops").objects().map { c ->
                    ServerCrop(c.getString("answer_box_id"), c.optInt("part", 0), c.getJSONArray("warped_bbox").ints())
                },
            )
        }
        return Fixture(dir, packLayout(json.getJSONObject("layout")), images)
    }

    /**
     * The pack's geometry fields onto [Layout], by the web end's `get_page_segments` rule:
     * `segments` when present, else `page_index` + `bbox`. The same rule the app's
     * `AssignmentRepository.toExtractorLayout` follows.
     */
    private fun packLayout(json: JSONObject): Layout {
        val markers = json.getJSONObject("markers")
        val centres = markers.getJSONObject("centres")
        return Layout(
            externalQuestionId = json.getString("question_id"),
            pageWidthPx = json.getInt("page_w_px"),
            pageHeightPx = json.getInt("page_h_px"),
            markers = centres.keys().asSequence().map { id ->
                val c = centres.getJSONArray(id)
                MarkerRef(id.toInt(), c.getDouble(0), c.getDouble(1))
            }.toList(),
            answerBoxes = json.getJSONArray("boxes").objects().map { box ->
                val segments = box.optJSONArray("segments")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val s = arr.getJSONArray(i).ints()
                        Segment(s[0], Bbox(s[1], s[2], s[3], s[4]))
                    }
                }.orEmpty().ifEmpty {
                    val b = box.getJSONArray("bbox").ints()
                    listOf(Segment(box.getInt("page_index"), Bbox(b[0], b[1], b[2], b[3])))
                }
                AnswerBoxRef(box.getString("id"), box.getInt("order_index"), segments)
            },
            arucoDictionary = markers.getString("aruco_dict"),
        )
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun JSONArray.ints(): IntArray = IntArray(length()) { getInt(it) }

    // ---- measures ------------------------------------------------------------------------

    /** Intersection over union of two `[x1, y1, x2, y2]` rectangles. */
    private fun iou(a: IntArray, b: IntArray): Double {
        val w = minOf(a[2], b[2]) - maxOf(a[0], b[0])
        val h = minOf(a[3], b[3]) - maxOf(a[1], b[1])
        if (w <= 0 || h <= 0) return 0.0
        val inter = w.toDouble() * h
        val areaA = (a[2] - a[0]).toDouble() * (a[3] - a[1])
        val areaB = (b[2] - b[0]).toDouble() * (b[3] - b[1])
        return inter / (areaA + areaB - inter)
    }

    /**
     * The phone's crop against the web end's crop of the flat page (`warped_bbox` sliced out
     * of it, as `_crop_region` does), resized to the phone crop's size, both grey and lightly
     * blurred. 0..255.
     *
     * Averaged over ink only - pixels darker than [INK_BELOW] in either crop - because an
     * answer box is mostly blank paper, and blank paper agrees whatever the alignment: over
     * the whole crop a 10 px misregistration measured under 2 on the sample. Over ink, a
     * shifted crop puts the border and the writing where the other has paper.
     */
    private fun meanGreyDiff(phonePng: ByteArray, flat: Mat, warpedBbox: IntArray): Double {
        val phone = requireNotNull(Registration.decode(phonePng))
        val region = flat.submat(Rect(warpedBbox[0], warpedBbox[1], warpedBbox[2] - warpedBbox[0], warpedBbox[3] - warpedBbox[1]))
        val server = Mat()
        val a = Mat()
        val b = Mat()
        val diff = Mat()
        val inkA = Mat()
        val inkB = Mat()
        val ink = Mat()
        try {
            Imgproc.resize(region, server, Size(phone.cols().toDouble(), phone.rows().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(phone, a, Imgproc.COLOR_BGR2GRAY)
            Imgproc.cvtColor(server, b, Imgproc.COLOR_BGR2GRAY)
            Imgproc.GaussianBlur(a, a, Size(3.0, 3.0), 0.0)
            Imgproc.GaussianBlur(b, b, Size(3.0, 3.0), 0.0)
            Core.absdiff(a, b, diff)
            Imgproc.threshold(a, inkA, INK_BELOW, 255.0, Imgproc.THRESH_BINARY_INV)
            Imgproc.threshold(b, inkB, INK_BELOW, 255.0, Imgproc.THRESH_BINARY_INV)
            Core.bitwise_or(inkA, inkB, ink)
            check(Core.countNonZero(ink) > 0) { "no ink in either crop" }
            return Core.mean(diff, ink).`val`[0]
        } finally {
            listOf(phone, region, server, a, b, diff, inkA, inkB, ink).forEach { it.release() }
        }
    }

    private companion object {
        const val MIN_IOU = 0.98
        const val MAX_EDGE_DRIFT_PX = 2
        /** Measured on golden/sample: about 30 aligned (JPEG and resampling blur thin lines), about 70 when shifted by 4 px. */
        const val MAX_MEAN_GREY_DIFF = 45.0
        const val INK_BELOW = 200.0
    }
}
