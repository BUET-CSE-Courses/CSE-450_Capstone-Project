package com.example.capstone.domain.grading

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.awt.Color

/**
 * test_blank_detection from Script-Checker-Web-End/backend/tests/test_grading.py
 * (c69eea2), plus the thresholds of looks_blank one by one. The server's own
 * images are checked against the server's own answers in [ServerParityTest];
 * here the same four cases are drawn again with AWT's font.
 */
class BlankDetectorTest {

    private fun blank(png: ByteArray) = BlankDetector.looksBlank(Fixtures.decodeGray(png))

    // ---- Ported from test_grading.py::test_blank_detection -------------------

    @Test
    fun `pure white is blank`() {
        assertThat(blank(Fixtures.png())).isTrue()
    }

    @Test
    fun `photographed grey is blank`() {
        assertThat(blank(Fixtures.png(background = Color(0xD8, 0xD8, 0xD8)))).isTrue()
    }

    @Test
    fun `faint short answer is not blank`() {
        assertThat(blank(Fixtures.png(listOf("x = 3")))).isFalse()
    }

    @Test
    fun `full working is not blank`() {
        assertThat(blank(Fixtures.png(listOf("2x + 4 = 10", "2x = 6", "x = 3")))).isFalse()
    }

    @Test
    fun `the server's own images give the expected answers`() {
        assertThat(BlankDetector.looksBlank(Fixtures.gray("pure_white.png"))).isTrue()
        assertThat(BlankDetector.looksBlank(Fixtures.gray("photographed_grey.png"))).isTrue()
        assertThat(BlankDetector.looksBlank(Fixtures.gray("faint_short_answer.png"))).isFalse()
        assertThat(BlankDetector.looksBlank(Fixtures.gray("full_working.png"))).isFalse()
    }

    // ---- The printed label (phone only) -------------------------------------

    /**
     * Session 7, a real phone photo of a blank box (halved): the printed "☐ answer" label was
     * the only ink the server's rule found, so the box went to the model.
     */
    @Test
    fun `a photographed blank box is blank once its printed label is left out`() {
        val photo = Fixtures.gray("phone_blank_box_with_label.png")
        val label = PrintedLabel.region(label = "", part = 0, parts = 1, segmentWidthPx = 934, segmentHeightPx = 781, dpi = 150)

        assertThat(BlankDetector.looksBlank(photo)).isFalse()
        assertThat(BlankDetector.looksBlank(photo, label)).isTrue()
    }

    @Test
    fun `writing outside the label still counts`() {
        val written = Fixtures.gray("faint_short_answer.png")
        val label = PrintedLabel.region("a", 0, 1, written.width, written.height, dpi = 150)
        assertThat(BlankDetector.looksBlank(written, label)).isFalse()
    }

    @Test
    fun `ignored pixels are neither ink nor paper`() {
        // A dark block covering the whole ignored corner: without it the crop is plain paper.
        val w = 200
        val h = 100
        val pixels = IntArray(w * h) { 200 }
        for (y in 0 until 30) for (x in 0 until 60) pixels[y * w + x] = 0
        val image = GrayImage(w, h, pixels)
        val corner = IgnoreRegion(0.0, 0.0, 0.3, 0.3)

        assertThat(BlankDetector.looksBlank(image)).isFalse()
        assertThat(BlankDetector.looksBlank(image, corner)).isTrue()
    }

    @Test
    fun `no region is the server's rule exactly`() {
        for (name in listOf("pure_white.png", "photographed_grey.png", "faint_short_answer.png", "full_working.png")) {
            val image = Fixtures.gray(name)
            assertThat(BlankDetector.looksBlank(image, null)).isEqualTo(BlankDetector.looksBlank(image))
        }
    }

    // ---- The written area (phone only, model input) -----------------------------

    /** Session 7's box 2 shape: 934 x 781, writing only in rows ~51-235. */
    private fun tallBoxWrittenAtTop(): GrayImage {
        val w = 934
        val h = 781
        val pixels = IntArray(w * h) { 170 }
        for (y in 60 until 230) for (x in 100 until 700) if ((x + y) % 7 == 0) pixels[y * w + x] = 40
        return GrayImage(w, h, pixels)
    }

    @Test
    fun `the written area of a tall box written at the top is the top, plus a margin`() {
        val rect = checkNotNull(BlankDetector.writtenArea(tallBoxWrittenAtTop()))
        val margin = (934 * BlankDetector.WRITTEN_MARGIN).toInt()
        assertThat(rect.top).isEqualTo(60 - margin)
        assertThat(rect.bottom).isEqualTo(230 + margin)
        assertThat(rect.left).isEqualTo(100 - margin)
        assertThat(rect.right).isEqualTo(700 + margin)
        assertThat(rect.height).isLessThan(781 / 2)
    }

    @Test
    fun `no trim for a blank crop, or writing that fills most of it`() {
        assertThat(BlankDetector.writtenArea(Fixtures.flat(400, 300, 200))).isNull()
        val full = IntArray(400 * 300) { i -> if (i % 5 == 0) 30 else 200 }
        assertThat(BlankDetector.writtenArea(GrayImage(400, 300, full))).isNull()
    }

    @Test
    fun `the printed label alone is no written area`() {
        val photo = Fixtures.gray("phone_blank_box_with_label.png")
        val label = PrintedLabel.region("", 0, 1, 934, 781, 150)
        assertThat(BlankDetector.writtenArea(photo, label)).isNull()
    }

    // ---- The constants -------------------------------------------------------

    @Test
    fun `constants are the server's`() {
        assertThat(BlankDetector.BLANK_INK_FRACTION).isEqualTo(0.0002)
        assertThat(BlankDetector.EDGE_TRIM).isEqualTo(0.04)
        assertThat(BlankDetector.PAPER_PERCENTILE).isEqualTo(0.9)
        assertThat(BlankDetector.INK_DARKER_THAN_PAPER).isEqualTo(60)
    }

    @Test
    fun `ink fraction threshold is strict less-than`() {
        // 1000 x 1000 trims to 920 x 920 = 846,400 pixels; 0.02% of that is 169.28.
        assertThat(BlankDetector.looksBlank(Fixtures.flat(1000, 1000, 255, ink = 169))).isTrue()
        assertThat(BlankDetector.looksBlank(Fixtures.flat(1000, 1000, 255, ink = 170))).isFalse()
    }

    @Test
    fun `ink is darker than paper minus 60, not equal to it`() {
        fun oneShade(value: Int): Boolean {
            val img = Fixtures.flat(100, 100, 200)
            // 92 x 92 after trim; 5% of it well past the ink fraction.
            for (i in 0 until 500) img.pixels[(30 + i / 50) * 100 + 25 + i % 50] = value
            return BlankDetector.looksBlank(img)
        }
        assertThat(oneShade(140)).isTrue()   // 200 - 60: not ink
        assertThat(oneShade(139)).isFalse()  // one darker: ink
    }

    @Test
    fun `paper tone is the 90th percentile, so writing on up to 10 percent does not move it`() {
        // A mid-grey page with 9% black: the paper still reads 128, and the
        // black is ink against it.
        val img = Fixtures.flat(100, 100, 128)
        val trimmed = (4 until 96).flatMap { y -> (4 until 96).map { x -> y * 100 + x } }
        trimmed.take(trimmed.size * 9 / 100).forEach { img.pixels[it] = 0 }
        assertThat(BlankDetector.looksBlank(img)).isFalse()
    }

    @Test
    fun `the 4 percent edge is ignored, so a printed border is not ink`() {
        val img = Fixtures.flat(200, 100, 255)
        for (x in 0 until 200) {
            for (y in 0 until 4) img.pixels[y * 200 + x] = 0          // top 4 rows
            for (y in 96 until 100) img.pixels[y * 200 + x] = 0       // bottom 4 rows
        }
        for (y in 0 until 100) {
            for (x in 0 until 8) img.pixels[y * 200 + x] = 0          // left 8 columns
            for (x in 192 until 200) img.pixels[y * 200 + x] = 0      // right 8 columns
        }
        assertThat(BlankDetector.looksBlank(img)).isTrue()
    }

    @Test
    fun `images of 20 px or less are not trimmed`() {
        val img = Fixtures.flat(20, 20, 255)
        img.pixels[0] = 0 // a corner pixel: trimmed on a larger image, counted here
        assertThat(BlankDetector.looksBlank(img)).isFalse()
    }

    @Test
    fun `an undecodable crop is treated as written on`() {
        assertThat(BlankDetector.looksBlank(null)).isFalse()
        assertThat(Fixtures.decodeGray("PNG".toByteArray())).isNull()
    }

    @Test
    fun `an empty image is blank`() {
        assertThat(BlankDetector.looksBlank(GrayImage(0, 0, IntArray(0)))).isTrue()
    }

    @Test
    fun `luma is PIL's L conversion`() {
        assertThat(GrayImage.luma(255, 255, 255)).isEqualTo(255)
        assertThat(GrayImage.luma(0, 0, 0)).isEqualTo(0)
        assertThat(GrayImage.luma(0xD8, 0xD8, 0xD8)).isEqualTo(0xD8)
        // (255*19595 + 0x8000) >> 16 = 76; PIL gives 76 for pure red.
        assertThat(GrayImage.luma(255, 0, 0)).isEqualTo(76)
        assertThat(GrayImage.luma(0, 255, 0)).isEqualTo(150)
        assertThat(GrayImage.luma(0, 0, 255)).isEqualTo(29)
    }
}
