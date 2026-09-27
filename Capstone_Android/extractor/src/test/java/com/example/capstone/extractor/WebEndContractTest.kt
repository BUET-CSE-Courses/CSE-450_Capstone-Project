package com.example.capstone.extractor

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Fails when the web end's page geometry drifts from what this module assumes.
 *
 * The phone and the web end crop the same paper with two separate pieces of code (plan
 * decision 9). They agree only while both follow one contract, and most of it lives in the
 * web end's source, not in anything the pack serves. So this test reads that source -
 * Script-Checker-Web-End `backend/config.py`, `services/doc_renderer.py`,
 * `services/extractor.py` and `routers/on_device.py` - and checks each rule this module
 * relies on, line by line.
 *
 * A failure names the file and the rule. It does not mean the web end is wrong: it means
 * something the phone depends on changed. Re-check `:extractor` (and `WebEndGoldenTest`'s
 * fixtures) against the new code, then update the pattern here.
 *
 * The web end is found at `../Script-Checker-Web-End/backend` next to this project (the
 * `webend.backend` system property, set in this module's build.gradle.kts). Where it is not
 * checked out, the tests are skipped, not passed.
 */
class WebEndContractTest {

    private lateinit var backend: File

    @Before
    fun findWebEnd() {
        val path = System.getProperty("webend.backend").orEmpty()
        backend = File(path)
        assumeTrue(
            "Script-Checker-Web-End/backend not found at '" + path + "'; contract not checked",
            File(backend, "services/extractor.py").isFile,
        )
    }

    // ---- markers ---------------------------------------------------------------------------

    @Test
    fun `the marker dictionary is the one this module reads`() {
        val dict = setting("ARUCO_DICT", "str")
        assertWithMessage("config.py ARUCO_DICT").that(dict.trim('"'))
            .isEqualTo(Layout.SUPPORTED_ARUCO_DICTIONARY)
        // doc_renderer draws with it and extractor detects with it; both must go through
        // settings, so the one value above is the whole story.
        requireIn(
            "services/doc_renderer.py",
            """ARUCO_DICT_ID = getattr(cv2.aruco, settings.ARUCO_DICT, cv2.aruco.DICT_4X4_50)""",
            "the printed markers must come from settings.ARUCO_DICT",
        )
        requireIn(
            "services/extractor.py",
            """ARUCO_PARAMS = cv2.aruco.DetectorParameters()""",
            "the server detects with default DetectorParameters, as Registration does",
        )
    }

    @Test
    fun `marker size and margin are the ones the fixtures were built with`() {
        assertWithMessage("config.py MARKER_SIZE_PX").that(setting("MARKER_SIZE_PX", "int").toInt())
            .isEqualTo(SampleFixture.MARKER_SIZE_PX)
        assertWithMessage("config.py MARKER_MARGIN_PX").that(setting("MARKER_MARGIN_PX", "int").toInt())
            .isEqualTo(SampleFixture.MARKER_MARGIN_PX)
    }

    @Test
    fun `marker ids are 0 to 3, row-major`() {
        requireIn(
            "services/doc_renderer.py",
            """CORNER_MARKER_IDS = [0, 1, 2, 3]  # TL, TR, BL, BR""",
            "LayoutValidator requires ids {0, 1, 2, 3}; MarkerCorners names them row-major",
        )
    }

    @Test
    fun `marker centres follow the formula the phone's fixtures use`() {
        // SampleFixture.markerCentres ports these four lines.
        val file = "services/doc_renderer.py"
        requireIn(file, """half = s // 2""", "centres use the integer half of the marker size")
        requireIn(file, """0: (m + half, m + half),""", "marker 0 is top-left")
        requireIn(file, """1: (canvas_w - m - half, m + half),""", "marker 1 is top-right")
        requireIn(file, """2: (m + half, canvas_h - m - half),""", "marker 2 is bottom-left")
        requireIn(file, """3: (canvas_w - m - half, canvas_h - m - half),""", "marker 3 is bottom-right")
    }

    @Test
    fun `the pack serves the server's own marker geometry`() {
        val file = "routers/on_device.py"
        requireIn(file, """get_marker_positions(q.page_w_px, q.page_h_px)""", "pack centres come from the renderer's formula")
        requireIn(file, """aruco_dict=settings.ARUCO_DICT,""", "pack dictionary is settings.ARUCO_DICT")
        requireIn(file, """segments=box.segments_json,""", "pack segments are the finalized segments_json")
        requireIn(file, """order_index=box.order_index,""", "pack order_index is the box's own")
    }

    // ---- page and boxes -------------------------------------------------------------------

    @Test
    fun `the canonical page is A4 at the paper's dpi`() {
        assertThat(setting("DEFAULT_DPI", "int").toInt()).isEqualTo(150)
        val file = "services/doc_renderer.py"
        requireIn(file, """canvas_w = round(8.27 * dpi)""", "page width is served as page_w_px")
        requireIn(file, """canvas_h = round(11.69 * dpi)""", "page height is served as page_h_px")
    }

    @Test
    fun `segments are page, x, y, w, h measured from the page's top-left corner`() {
        val file = "services/doc_renderer.py"
        requireIn(file, """boxes[bid].push([pageIdx, relX, relY, relW, relH]);""", "Segment is (page, Bbox(x, y, w, h))")
        requireIn(file, """const relX = Math.round(elRect.left - pageRect.left);""", "x is page-relative")
        requireIn(file, """const relY = Math.round(elRect.top - pageRect.top);""", "y is page-relative")
    }

    @Test
    fun `a page's pieces are chosen as get_page_segments chooses them`() {
        val file = "services/extractor.py"
        requireIn(file, """for part_idx, seg in enumerate(segs):""", "part is the segment's position")
        requireIn(file, """if seg[0] == page_index:""", "a segment belongs to the page it names")
        requireIn(file, """page_segments.append((box, part_idx, seg[1:]))""", "the rest of the segment is the bbox")
        requireIn(
            file,
            """elif box.get("page_index") == page_index and box.get("bbox"):""",
            "without segments, page_index + bbox is the only part (AssignmentRepository.toExtractorLayout)",
        )
    }

    // ---- registration and crop ------------------------------------------------------------

    @Test
    fun `detected centres are the mean of the four corners`() {
        requireIn(
            "services/extractor.py",
            """return {int(mid): corners[i][0].mean(axis=0) for i, mid in enumerate(ids.flatten())}""",
            "Registration.centreOf averages the four corners",
        )
    }

    @Test
    fun `the transform is the same solve with the same threshold`() {
        val file = "services/extractor.py"
        requireIn(file, """src_pts = np.array([canonical_pos[i] for i in range(4)], dtype=np.float64)""", "canonical centres by id")
        requireIn(file, """dst_pts = np.array([detected_markers[i] for i in range(4)], dtype=np.float64)""", "detected centres by id")
        val homography = Regex("""cv2\.findHomography\(canonical_pts, detected_pts, cv2\.RANSAC, ([0-9.]+)\)""")
            .find(read(file))
        assertWithMessage("$file: photo transform is cv2.findHomography(..., RANSAC, t)").that(homography).isNotNull()
        assertThat(homography!!.groupValues[1].toDouble()).isEqualTo(Registration.RANSAC_REPROJECTION_THRESHOLD)
        requireIn(file, """M, _ = cv2.estimateAffine2D(canonical_pts, detected_pts)""", "scanner transform is an affine")
        requireIn(file, """return np.vstack([M, [0, 0, 1]]), "affine"""", "the affine is lifted to 3x3")
    }

    @Test
    fun `the crop is the full bbox, corners in the order the phone uses`() {
        val file = "services/extractor.py"
        requireIn(
            file,
            """corners = np.array([[x, y], [x + w, y], [x + w, y + h], [x, y + h]], dtype=np.float64)""",
            "corners are TL, TR, BR, BL, as Registration.transformBbox",
        )
        // No inset on the server: the phone grades with Inset.NONE to see the same region.
        requireIn(file, """warped = _transform_bbox(bbox, transform)""", "the global crop warps the bbox as served")
        requireIn(file, """crop_img = _crop_region(img, warped)""", "the crop is the warped bbox's bounds")
        requireIn(file, """x_min, y_min = int(warped[:, 0].min()), int(warped[:, 1].min())""", "warped_bbox truncates, as warpedBounds")
    }

    @Test
    fun `the server still registers each page globally`() {
        // With local QR registration on, the server's crops come from a per-box transform the
        // phone does not compute, and WebEndGoldenTest's fixtures no longer describe it.
        assertThat(setting("USE_LOCAL_QR_REGISTRATION", "bool")).isEqualTo("False")
    }

    // ---- helpers --------------------------------------------------------------------------

    private fun read(relative: String): String {
        val file = File(backend, relative)
        assertWithMessage("web end file " + file.path + " is missing").that(file.isFile).isTrue()
        return file.readText().replace("\r\n", "\n")
    }

    private fun requireIn(relative: String, line: String, rule: String) {
        assertWithMessage(
            "Web end drift in " + relative + ": no longer contains\n    " + line +
                "\nThe phone relies on: " + rule + ".\nRe-check :extractor against the new code, " +
                "then update WebEndContractTest.",
        ).that(read(relative).contains(line)).isTrue()
    }

    /** The default of a pydantic setting in config.py, e.g. `MARKER_SIZE_PX: int = 60`. */
    private fun setting(name: String, type: String): String {
        val match = Regex("""(?m)^\s*""" + name + """:\s*""" + type + """\s*=\s*([^#\n]+)""")
            .find(read("config.py"))
        assertWithMessage("config.py no longer declares $name: $type").that(match).isNotNull()
        return match!!.groupValues[1].trim()
    }
}
