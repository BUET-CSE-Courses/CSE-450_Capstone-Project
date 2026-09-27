package com.example.capstone.extractor

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Test

/**
 * The recorded-output test.
 *
 * The web end's `extract_page` (Script-Checker-Web-End `backend/services/extractor.py`) over
 * `sample/sample_page.png` and `sample/layout.json` gives 4/4 markers,
 * `transform_type: "homography"`, and warped bboxes `[168, 600, 1128, 850]` and
 * `[168, 1283, 1128, 1573]` (recorded in `golden/sample/golden.json`, page_0.png).
 *
 * The inset is **disabled here**, because the web end has none. [WebEndGoldenTest] runs the
 * same comparison over every golden fixture, multi-page and tilted ones included.
 */
class GoldenSampleTest {

    @Before
    fun loadNative() = OpenCvTestNative.load()

    /** Per-component slack. The web end and this port agree far more closely than this. */
    private val tolerancePx = 3

    @Test
    fun `warped bboxes match the recorded python output`() {
        val layout = SampleFixture.layout()
        val img = requireNotNull(Registration.decode(SampleFixture.pageBytes()))
        try {
            val detected = Registration.detect(img)
            val registered = Registration.registerPage(
                layout = layout,
                pageIndex = 0,
                modality = Modality.PHOTO,
                inset = Inset.NONE,
                detected = detected,
            )

            assertThat(registered.map { it.box.externalAnswerBoxId })
                .containsExactly(SampleFixture.FIRST_BOX_ID, SampleFixture.SECOND_BOX_ID)
                .inOrder()

            assertBoundsNear(registered[0].warpedBounds(), intArrayOf(168, 600, 1128, 850))
            assertBoundsNear(registered[1].warpedBounds(), intArrayOf(168, 1283, 1128, 1573))
        } finally {
            img.release()
        }
    }

    @Test
    fun `all four markers are detected on the sample page`() {
        val layout = SampleFixture.layout()
        val img = requireNotNull(Registration.decode(SampleFixture.pageBytes()))
        try {
            val detected = Registration.detect(img)
            assertThat(detected.keys).containsAtLeast(0, 1, 2, 3)
        } finally {
            img.release()
        }
    }

    @Test
    fun `detected centres land within a couple of pixels of canonical`() {
        // The sample is a rendered page at 1242x1756 against a canonical 1240x1754, so the
        // homography here is very nearly the identity. Pinning it is what tells us, if this
        // test ever fails, whether the fixture changed or the detector did.
        val layout = SampleFixture.layout()
        val img = requireNotNull(Registration.decode(SampleFixture.pageBytes()))
        try {
            val detected = Registration.detect(img)
            for (marker in layout.markers) {
                val point = requireNotNull(detected[marker.id])
                assertThat(Math.hypot(point.x - marker.x, point.y - marker.y)).isLessThan(4.0)
            }
        } finally {
            img.release()
        }
    }

    private fun assertBoundsNear(actual: IntArray, expected: IntArray) {
        val names = listOf("xMin", "yMin", "xMax", "yMax")
        for (i in expected.indices) {
            val drift = Math.abs(actual[i] - expected[i])
            assertWithMessage(
                names[i] + " drifted " + drift + " px: got " + actual.toList() +
                    ", recorded " + expected.toList(),
            ).that(drift).isAtMost(tolerancePx)
        }
    }
}
