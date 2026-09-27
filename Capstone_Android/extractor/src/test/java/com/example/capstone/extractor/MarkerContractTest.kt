package com.example.capstone.extractor

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Proves the module holds no page geometry of its own.
 *
 * `INTEGRATION_AUDIT.md` section 2.3 measured, against real markers and a real perspective
 * warp, what a wrong marker convention costs:
 *
 * | assumption            | corner error |
 * |-----------------------|--------------|
 * | m=40, s=60 (correct)  | 0.0 px       |
 * | m=50, s=60            | max 11.0 px  |
 * | ids 2 and 3 swapped   | max 990.6 px |
 *
 * and the finding that matters more than the numbers: **every one of those still detects 4/4
 * markers and reports success.** The web end's extractor has no residual check either.
 *
 * Each test below changes only what the *caller* supplies. If this module carried a built-in
 * margin or a built-in TL/TR/BL/BR ordering, these results could not move - so the fact that
 * they move by the recorded amounts is the assertion.
 */
class MarkerContractTest {

    @Before
    fun loadNative() = OpenCvTestNative.load()

    @Test
    fun `swapping marker ids 2 and 3 displaces every crop by hundreds of pixels`() {
        // Clockwise (TL, TR, BR, BL) instead of the generator's row-major (TL, TR, BL, BR).
        val drift = maxCornerDrift(SampleFixture.layout(markerIdOrder = listOf(0, 1, 3, 2)))

        assertThat(drift).isGreaterThan(900.0)
    }

    @Test
    fun `a marker margin off by ten pixels displaces every crop by about ten`() {
        val drift = maxCornerDrift(SampleFixture.layout(markerMarginPx = 50))

        assertThat(drift).isGreaterThan(8.0)
        assertThat(drift).isLessThan(14.0)
    }

    @Test
    fun `a marker size off by twenty pixels displaces every crop by about ten`() {
        // m=40,s=80 and m=50,s=60 both shift the centre by 10 px, so the audit measured the
        // same 11.0 px for both. Two different constants, one indistinguishable failure.
        val drift = maxCornerDrift(SampleFixture.layout(markerSizePx = 80))

        assertThat(drift).isGreaterThan(8.0)
        assertThat(drift).isLessThan(14.0)
    }

    @Test
    fun `the correct convention drifts by nothing`() {
        assertThat(maxCornerDrift(SampleFixture.layout())).isLessThan(0.001)
    }

    /**
     * The worst per-corner distance between the quads [layout] produces and the quads the
     * generator's own convention produces, over every box on page 0.
     */
    private fun maxCornerDrift(layout: Layout): Double {
        val truth = quads(SampleFixture.layout())
        val actual = quads(layout)

        var worst = 0.0
        for (b in truth.indices) {
            for (c in truth[b].indices) {
                val a = actual[b][c]
                val t = truth[b][c]
                worst = maxOf(worst, Math.hypot(a.x - t.x, a.y - t.y))
            }
        }
        return worst
    }

    private fun quads(layout: Layout): List<List<org.opencv.core.Point>> {
        val img = requireNotNull(Registration.decode(SampleFixture.pageBytes()))
        return try {
            Registration.registerPage(
                layout = layout,
                pageIndex = 0,
                modality = Modality.PHOTO,
                inset = Inset.NONE,
                detected = Registration.detect(img),
            ).map { it.imageQuad }
        } finally {
            img.release()
        }
    }
}
