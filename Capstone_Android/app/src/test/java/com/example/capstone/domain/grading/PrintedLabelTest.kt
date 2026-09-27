package com.example.capstone.domain.grading

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Where the web end's `doc_renderer.py` prints a box's label, in crop fractions. */
class PrintedLabelTest {

    @Test
    fun `label text is the renderer's`() {
        assertThat(PrintedLabel.text("", 0, 1)).isEqualTo("☐ answer")
        assertThat(PrintedLabel.text(null, 0, 1)).isEqualTo("☐ answer")
        assertThat(PrintedLabel.text("Q1(b)", 0, 2)).isEqualTo("☐ Q1(b)")
        assertThat(PrintedLabel.text("Q1(b)", 1, 2)).isEqualTo("☐ Q1(b) — part 2 of 2")
    }

    @Test
    fun `covers the label measured on the session 7 photo`() {
        // 934 x 781 crop at 150 dpi; the label's ink ended at x = 103, y = 42.
        val r = checkNotNull(PrintedLabel.region("", 0, 1, 934, 781, 150)).toPixels(934, 781)
        assertThat(r.left).isEqualTo(0)
        assertThat(r.top).isEqualTo(0)
        assertThat(r.right).isAtLeast(104)
        assertThat(r.bottom).isAtLeast(43)
        // And stays a corner: well short of the box's writing space.
        assertThat(r.right).isAtMost(140)
        assertThat(r.bottom).isAtMost(60)
    }

    @Test
    fun `a later part's longer label gets a wider region`() {
        val first = checkNotNull(PrintedLabel.region("a", 0, 2, 930, 250, 150))
        val second = checkNotNull(PrintedLabel.region("a", 1, 2, 930, 250, 150))
        assertThat(second.right).isGreaterThan(first.right)
        assertThat(second.bottom).isEqualTo(first.bottom)
    }

    @Test
    fun `scales with the dpi and never leaves the crop`() {
        val at150 = checkNotNull(PrintedLabel.region("a", 0, 1, 900, 300, 150))
        val at300 = checkNotNull(PrintedLabel.region("a", 0, 1, 1800, 600, 300))
        assertThat(at300.right).isWithin(1e-9).of(at150.right)
        assertThat(at300.bottom).isWithin(1e-9).of(at150.bottom)

        val tiny = checkNotNull(PrintedLabel.region("a very long label indeed", 0, 1, 40, 20, 150))
        assertThat(tiny.right).isEqualTo(1.0)
        assertThat(tiny.bottom).isEqualTo(1.0)
    }

    @Test
    fun `no size, no region`() {
        assertThat(PrintedLabel.region("a", 0, 1, 0, 100, 150)).isNull()
        assertThat(PrintedLabel.region("a", 0, 1, 100, 100, 0)).isNull()
    }
}
