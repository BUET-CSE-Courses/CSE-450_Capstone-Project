package com.example.capstone.domain.grading

import kotlin.math.ceil
import kotlin.math.min

/**
 * A region of a crop, as fractions (0..1) of its width and height, so it does not depend on
 * the crop's pixel size.
 */
data class IgnoreRegion(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(left in 0.0..1.0 && right in 0.0..1.0 && top in 0.0..1.0 && bottom in 0.0..1.0) {
            "IgnoreRegion needs fractions in 0..1: $this"
        }
    }

    fun toPixels(width: Int, height: Int) = BlankDetector.PixelRect(
        left = (left * width).toInt(),
        top = (top * height).toInt(),
        right = ceil(right * width).toInt(),
        bottom = ceil(bottom * height).toInt()
    )
}

/**
 * Where the web end prints an answer box's label ("☐ answer", "☐ answer — part 2 of 3") inside
 * the box, so [BlankDetector] can leave it out.
 *
 * Why: on a real phone photo of a blank box the label was the only "ink" the server's rule
 * found: 219 px, 0.035 %, against a 0.02 % threshold (session 7). The dashed border is
 * trimmed by the 4 % edge and shading stays under paper − 60, but the label sits inside the
 * trim, so a blank box went to the model, which then recited the model answer as the
 * student's work.
 *
 * Geometry, from `Script-Checker-Web-End/backend/services/doc_renderer.py` (`answer-box-node`
 * and `ab-label` styles, and the segment loop that writes `label_text`):
 * - every segment is a box with a 2 px border and 8 px padding; its first child is the label,
 *   11 px bold, `☐ {label or "answer"}` on part 0, and
 *   `☐ {label} — part {n+1} of {N}` on later parts;
 * - these are editor (CSS, 96 dpi) pixels, zoomed by `dpi / 96` onto the canonical page, whose
 *   pixels are the pack's `bbox` / `segments` units. A crop covers exactly its segment.
 *
 * The rectangle is deliberately generous (0.75 em a character, 1.5 line heights, plus slack):
 * measured on the session 7 photo, the label ended at 103 × 42 px of a 934 × 781 crop, and this
 * gives 128 × 51. The cost is that ink **only** in that corner would not count, which a real
 * answer does not do.
 */
object PrintedLabel {
    private const val CSS_DPI = 96.0
    private const val BORDER_PLUS_PADDING = 2.0 + 8.0
    private const val FONT_PX = 11.0
    private const val EM_PER_CHAR = 0.75
    private const val LINE_HEIGHTS = 1.5
    private const val SLACK_PX = 6.0

    /** The text the web end prints on [part] (0-based) of a box with [parts] parts. */
    fun text(label: String?, part: Int, parts: Int): String {
        val name = label?.takeIf { it.isNotEmpty() } ?: "answer"
        return if (part == 0) "☐ $name" else "☐ $name — part ${part + 1} of $parts"
    }

    /**
     * The label's region in a crop of one segment [segmentWidthPx] × [segmentHeightPx]
     * canonical pixels, on a paper rendered at [dpi]. Null when the segment has no size.
     */
    fun region(
        label: String?,
        part: Int,
        parts: Int,
        segmentWidthPx: Int,
        segmentHeightPx: Int,
        dpi: Int
    ): IgnoreRegion? {
        if (segmentWidthPx <= 0 || segmentHeightPx <= 0 || dpi <= 0) return null
        val zoom = dpi / CSS_DPI
        val chars = text(label, part, parts).length
        val widthCss = BORDER_PLUS_PADDING + chars * EM_PER_CHAR * FONT_PX + SLACK_PX
        val heightCss = BORDER_PLUS_PADDING + LINE_HEIGHTS * FONT_PX + SLACK_PX
        return IgnoreRegion(
            left = 0.0,
            top = 0.0,
            right = min(1.0, widthCss * zoom / segmentWidthPx),
            bottom = min(1.0, heightCss * zoom / segmentHeightPx)
        )
    }
}
