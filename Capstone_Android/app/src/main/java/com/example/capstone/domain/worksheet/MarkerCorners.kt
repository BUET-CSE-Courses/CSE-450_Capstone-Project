package com.example.capstone.domain.worksheet

import com.example.capstone.extractor.Layout

/**
 * Turns undetected marker ids into words a student can act on.
 *
 * The extractor names no corners on purpose: it holds no id-to-corner
 * convention at all, because holding a wrong one costs 990 px of silent
 * displacement while still reporting four markers found (`INTEGRATION_AUDIT.md`
 * §2.3, run J). So the names are not looked up from a table here either. They
 * are read off the marker centres the layout itself carries: a marker in the
 * left half of the page is on the left, a marker in the top half is at the top.
 *
 * That keeps the property the extractor is built around - the geometry is
 * whatever the server said it is - while still letting the screen say "the
 * top-left corner is not visible" instead of "marker 0 was not detected".
 */
object MarkerCorners {

    /**
     * Names the corner each of [missingIds] sits in, in the order given.
     *
     * An id the layout does not name comes back as `"marker <id>"`: it cannot be
     * placed on the page, and inventing a corner for it would be a guess.
     */
    fun describe(layout: Layout, missingIds: List<Int>): List<String> {
        val centreX = layout.pageWidthPx / 2.0
        val centreY = layout.pageHeightPx / 2.0

        return missingIds.map { id ->
            val marker = layout.markers.firstOrNull { it.id == id }
                ?: return@map "marker $id"
            val vertical = if (marker.y < centreY) "top" else "bottom"
            val horizontal = if (marker.x < centreX) "left" else "right"
            "$vertical-$horizontal"
        }
    }

    /**
     * One sentence for a student: which corners were not visible.
     *
     * Written to be shown as-is. It says "not visible" rather than "not
     * detected" because from where the student stands the cause is almost
     * always framing, glare or a finger over the corner, not a detection
     * failure.
     */
    fun sentence(layout: Layout, missingIds: List<Int>): String {
        val names = describe(layout, missingIds)
        return when (names.size) {
            0 -> "No corner markers were found on this photo."
            1 -> "The ${names[0]} corner marker is not visible."
            else -> {
                val listed = names.dropLast(1).joinToString(", ") + " and " + names.last()
                "The $listed corner markers are not visible."
            }
        }
    }
}
