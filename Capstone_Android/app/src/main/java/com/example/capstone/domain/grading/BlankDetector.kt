package com.example.capstone.domain.grading

/**
 * A greyscale image: one 0..255 value per pixel, row-major. What PIL's
 * `Image.convert("L")` gives the server.
 */
class GrayImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width >= 0 && height >= 0 && pixels.size == width * height) {
            "GrayImage $width x $height needs ${width * height} pixels, got ${pixels.size}"
        }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    companion object {
        /**
         * PIL's RGB to L conversion (Pillow Convert.c, `L24`): ITU-R 601-2 luma
         * in 16.16 fixed point, rounded. Alpha is ignored, as PIL ignores it.
         */
        fun luma(r: Int, g: Int, b: Int): Int = (r * 19595 + g * 38470 + b * 7471 + 0x8000) shr 16

        /** From Android/AWT packed ARGB pixels. */
        fun fromArgb(width: Int, height: Int, argb: IntArray): GrayImage = GrayImage(
            width,
            height,
            IntArray(argb.size) { i ->
                val p = argb[i]
                luma((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            }
        )
    }
}

/*
 * Port of looks_blank and BLANK_INK_FRACTION from
 * Script-Checker-Web-End/backend/services/grading.py:74-125 at commit c69eea2
 * (main).
 */
object BlankDetector {

    /**
     * Fraction of a crop that must be markedly darker than the paper before we
     * accept there is handwriting on it. The server measured 0.0000% for blank
     * crops and 0.20% for a short pencil "x = 3", so this sits far below the
     * faintest writing.
     */
    const val BLANK_INK_FRACTION = 0.0002

    /** Share of each edge trimmed off: usually the printed box border. */
    const val EDGE_TRIM = 0.04

    /** The paper's tone is the value this far up the sorted pixels. */
    const val PAPER_PERCENTILE = 0.9

    /** Ink is anything darker than the paper by more than this. */
    const val INK_DARKER_THAN_PAPER = 60

    /**
     * Whether a crop has essentially no ink on it.
     *
     * Null means the crop could not be decoded. The server treats that as
     * written on (returns false), so a real answer is never zeroed because a
     * file was odd; the model then gets to see it.
     *
     * [ignore] leaves out a printed region that is not the student's (the
     * box's "☐ answer" label, [PrintedLabel]). Its pixels count as neither ink
     * nor paper; every other rule is the server's. With no region this is
     * `looks_blank` exactly.
     */
    fun looksBlank(image: GrayImage?, ignore: IgnoreRegion? = null): Boolean {
        if (image == null) return false

        var left = 0
        var top = 0
        var right = image.width
        var bottom = image.height
        if (image.width > 20 && image.height > 20) {
            val mX = (image.width * EDGE_TRIM).toInt()
            val mY = (image.height * EDGE_TRIM).toInt()
            left = mX
            top = mY
            right = image.width - mX
            bottom = image.height - mY
        }

        val skip = ignore?.toPixels(image.width, image.height)

        // A histogram gives the same order statistic as sorting the pixels.
        val histogram = IntArray(256)
        var count = 0L
        for (y in top until bottom) {
            for (x in left until right) {
                if (skip != null && x < skip.right && x >= skip.left && y < skip.bottom && y >= skip.top) continue
                histogram[image[x, y].coerceIn(0, 255)]++
                count++
            }
        }
        if (count <= 0L) return true

        val paper = valueAtRank(histogram, (count * PAPER_PERCENTILE).toLong())
        val threshold = paper - INK_DARKER_THAN_PAPER
        var ink = 0L
        for (v in 0 until threshold.coerceIn(0, 256)) ink += histogram[v]

        return ink.toDouble() / count < BLANK_INK_FRACTION
    }

    /** A pixel rectangle, right and bottom exclusive. */
    data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** Rows and columns with fewer ink pixels than this are specks, not writing. */
    const val MIN_INK_PER_LINE = 2

    /** Margin kept around the writing: this share of the crop's longer side, at least [MIN_MARGIN_PX]. */
    const val WRITTEN_MARGIN = 0.05
    const val MIN_MARGIN_PX = 16

    /** A trim that keeps more than this share of the crop's area is not worth it. */
    const val MAX_KEPT_AREA = 0.85

    /**
     * Phone only, for the model's input (session 7): the part of the crop the student wrote
     * on, plus a margin, so a tall box with writing only at the top is not shrunk to
     * unreadable pixels. Ink is found as in [looksBlank] (edge trim, paper tone, [ignore]).
     * Null when there is no ink, or the writing already fills most of the crop.
     */
    fun writtenArea(image: GrayImage, ignore: IgnoreRegion? = null): PixelRect? {
        if (image.width <= 20 || image.height <= 20) return null
        val mX = (image.width * EDGE_TRIM).toInt()
        val mY = (image.height * EDGE_TRIM).toInt()
        val skip = ignore?.toPixels(image.width, image.height)
        fun counted(x: Int, y: Int) =
            !(skip != null && x < skip.right && x >= skip.left && y < skip.bottom && y >= skip.top)

        val histogram = IntArray(256)
        var count = 0L
        for (y in mY until image.height - mY) for (x in mX until image.width - mX) {
            if (!counted(x, y)) continue
            histogram[image[x, y].coerceIn(0, 255)]++
            count++
        }
        if (count <= 0L) return null
        val threshold = valueAtRank(histogram, (count * PAPER_PERCENTILE).toLong()) - INK_DARKER_THAN_PAPER

        val rows = IntArray(image.height)
        val cols = IntArray(image.width)
        for (y in mY until image.height - mY) for (x in mX until image.width - mX) {
            if (counted(x, y) && image[x, y] < threshold) {
                rows[y]++
                cols[x]++
            }
        }
        val top = rows.indexOfFirst { it >= MIN_INK_PER_LINE }
        val left = cols.indexOfFirst { it >= MIN_INK_PER_LINE }
        if (top < 0 || left < 0) return null
        val bottom = rows.indexOfLast { it >= MIN_INK_PER_LINE } + 1
        val right = cols.indexOfLast { it >= MIN_INK_PER_LINE } + 1

        val margin = maxOf(MIN_MARGIN_PX, (maxOf(image.width, image.height) * WRITTEN_MARGIN).toInt())
        val rect = PixelRect(
            left = (left - margin).coerceAtLeast(0),
            top = (top - margin).coerceAtLeast(0),
            right = (right + margin).coerceAtMost(image.width),
            bottom = (bottom + margin).coerceAtMost(image.height)
        )
        val kept = rect.width.toDouble() * rect.height / (image.width.toDouble() * image.height)
        return if (kept > MAX_KEPT_AREA) null else rect
    }

    /**
     * The paper's grey value as [looksBlank] measures it (90th percentile inside the 4 % edge,
     * [ignore] left out). Phone only: the tone [CropTrim] paints the printed label with.
     */
    fun paperTone(image: GrayImage, ignore: IgnoreRegion? = null): Int {
        val trim = image.width > 20 && image.height > 20
        val mX = if (trim) (image.width * EDGE_TRIM).toInt() else 0
        val mY = if (trim) (image.height * EDGE_TRIM).toInt() else 0
        val skip = ignore?.toPixels(image.width, image.height)
        val histogram = IntArray(256)
        var count = 0L
        for (y in mY until image.height - mY) for (x in mX until image.width - mX) {
            if (skip != null && x < skip.right && x >= skip.left && y < skip.bottom && y >= skip.top) continue
            histogram[image[x, y].coerceIn(0, 255)]++
            count++
        }
        return if (count <= 0L) 255 else valueAtRank(histogram, (count * PAPER_PERCENTILE).toLong())
    }

    /** The value at 0-based position [rank] of the sorted pixels. */
    private fun valueAtRank(histogram: IntArray, rank: Long): Int {
        var seen = 0L
        for (v in histogram.indices) {
            seen += histogram[v]
            if (seen > rank) return v
        }
        return histogram.indices.last
    }
}
