package com.example.capstone.domain.grading

/**
 * Phone only, debug toggle, off by default (session 9): what the model is sent instead of a
 * whole crop. The blank check and everything posted still use the crop as it is.
 *
 * Rebuilt after session 7, where the first trim (the bare [BlankDetector.writtenArea] rect)
 * gave box 2 a 789 × 276 strip that still held the printed "☐ answer" label, and the model
 * answered "[no handwriting]" (not separable from the prompt change in the same run). Now:
 * 1. the handwriting's bounding box plus a margin ([BlankDetector.writtenArea], which already
 *    leaves the label out when finding ink);
 * 2. widened within the crop to at most [MAX_ASPECT] : 1, so a single written line is not
 *    handed over as a thin strip;
 * 3. the printed label, where it falls inside the cut, painted over in the tone of the paper
 *    around it ([Plan.paint], [toneAround]), so the model sees only the student's writing.
 */
object CropTrim {

    /** The cut keeps its long side at most this many times its short side. */
    const val MAX_ASPECT = 2.0

    /** Width of the band around the label whose median grey is the paint (the paper there). */
    const val TONE_BAND_PX = 16

    /**
     * How to cut one crop.
     *
     * @param cut the region kept, in the crop's pixels.
     * @param paint the printed label, in the **cut's** pixels, or null when none of it is kept.
     * @param paperTone the grey value (0..255) to paint it with: the median of a
     *   [TONE_BAND_PX] band around the label, so the patch matches the paper next to it (a
     *   photo's paper is not one tone; the whole crop's tone left a visible rectangle).
     */
    data class Plan(
        val cut: BlankDetector.PixelRect,
        val paint: BlankDetector.PixelRect?,
        val paperTone: Int
    )

    /**
     * The plan for [image] with its printed [label], or null when the crop should go as it is:
     * no ink, or nothing to cut and no label to paint.
     */
    fun plan(image: GrayImage, label: IgnoreRegion?): Plan? {
        val full = BlankDetector.PixelRect(0, 0, image.width, image.height)
        val written = BlankDetector.writtenArea(image, label)
        if (written == null && !hasInk(image, label)) return null
        val cut = written?.let { widened(it, image.width, image.height) } ?: full

        val labelPx = label?.toPixels(image.width, image.height)
        val paint = labelPx?.let { intersect(it, cut) }
            ?.let { BlankDetector.PixelRect(it.left - cut.left, it.top - cut.top, it.right - cut.left, it.bottom - cut.top) }
        if (cut == full && paint == null) return null
        val tone = labelPx?.let { toneAround(image, it) } ?: BlankDetector.paperTone(image, label)
        return Plan(cut, paint, tone)
    }

    /** Median grey of the band [TONE_BAND_PX] wide around [rect] (inside the image), or null if empty. */
    fun toneAround(image: GrayImage, rect: BlankDetector.PixelRect): Int? {
        val histogram = IntArray(256)
        var count = 0
        val left = (rect.left - TONE_BAND_PX).coerceAtLeast(0)
        val top = (rect.top - TONE_BAND_PX).coerceAtLeast(0)
        val right = (rect.right + TONE_BAND_PX).coerceAtMost(image.width)
        val bottom = (rect.bottom + TONE_BAND_PX).coerceAtMost(image.height)
        for (y in top until bottom) for (x in left until right) {
            if (x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom) continue
            histogram[image[x, y].coerceIn(0, 255)]++
            count++
        }
        if (count == 0) return null
        var seen = 0
        for (v in histogram.indices) {
            seen += histogram[v]
            if (seen > count / 2) return v
        }
        return null
    }

    /** [rect] grown on its short side, centred and kept inside the image, to at most [MAX_ASPECT] : 1. */
    fun widened(rect: BlankDetector.PixelRect, width: Int, height: Int): BlankDetector.PixelRect {
        val w = rect.width
        val h = rect.height
        return when {
            w > h * MAX_ASPECT -> {
                val (top, bottom) = grow(rect.top, rect.bottom, minOf(height, Math.ceil(w / MAX_ASPECT).toInt()), height)
                rect.copy(top = top, bottom = bottom)
            }
            h > w * MAX_ASPECT -> {
                val (left, right) = grow(rect.left, rect.right, minOf(width, Math.ceil(h / MAX_ASPECT).toInt()), width)
                rect.copy(left = left, right = right)
            }
            else -> rect
        }
    }

    /** [start, end) grown to [want] around its centre, shifted back inside [0, limit). */
    private fun grow(start: Int, end: Int, want: Int, limit: Int): Pair<Int, Int> {
        val extra = want - (end - start)
        if (extra <= 0) return start to end
        var s = start - extra / 2
        var e = end + (extra - extra / 2)
        if (s < 0) { e -= s; s = 0 }
        if (e > limit) { s -= e - limit; e = limit }
        return s.coerceAtLeast(0) to e
    }

    private fun intersect(a: BlankDetector.PixelRect, b: BlankDetector.PixelRect): BlankDetector.PixelRect? {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        return if (right > left && bottom > top) BlankDetector.PixelRect(left, top, right, bottom) else null
    }

    private fun hasInk(image: GrayImage, label: IgnoreRegion?): Boolean = !BlankDetector.looksBlank(image, label)
}
