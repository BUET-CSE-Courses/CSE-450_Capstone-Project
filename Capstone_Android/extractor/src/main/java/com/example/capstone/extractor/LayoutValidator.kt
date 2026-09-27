package com.example.capstone.extractor

import kotlin.math.abs

/**
 * Checks a [Layout] before any of it is trusted.
 *
 * Everything here is cheap and none of it needs OpenCV. It exists because the failure it
 * guards against is silent: audit section 2.3 measured a wrong marker margin producing an
 * 11 px systematic displacement and a swapped id pair producing 990 px, both of which still
 * detect 4/4 markers and report success. A layout that cannot be right is worth refusing
 * before a photo is even decoded.
 */
internal object LayoutValidator {

    /** The ids the ArUco contract requires (`CORNER_MARKER_IDS`, `services/doc_renderer.py`). */
    private val REQUIRED_MARKER_IDS = setOf(0, 1, 2, 3)

    /** Half a square pixel. Below this, three centres are a line as far as a solver cares. */
    private const val COLLINEAR_AREA_EPSILON = 0.5

    /**
     * Returns a reason naming what is wrong, or null when the layout is usable.
     *
     * [inset] participates because "the inset leaves positive area" is a property of the pair,
     * not of the layout alone.
     */
    fun validate(layout: Layout, inset: Inset): String? =
        validateDictionary(layout) ?: validateMarkers(layout) ?: validateBoxes(layout, inset)

    private fun validateDictionary(layout: Layout): String? =
        if (layout.arucoDictionary == Layout.SUPPORTED_ARUCO_DICTIONARY) {
            null
        } else {
            "marker dictionary " + layout.arucoDictionary + " is not supported; this app reads " +
                Layout.SUPPORTED_ARUCO_DICTIONARY + " only"
        }

    private fun validateMarkers(layout: Layout): String? {
        if (layout.pageWidthPx <= 0 || layout.pageHeightPx <= 0) {
            return "page size " + layout.pageWidthPx + "x" + layout.pageHeightPx +
                " is not positive"
        }
        if (layout.markers.size != 4) {
            return "expected exactly 4 markers, got " + layout.markers.size
        }
        val ids = layout.markers.map { it.id }
        if (ids.toSet() != REQUIRED_MARKER_IDS) {
            return "marker ids must be exactly " + REQUIRED_MARKER_IDS.sorted() +
                ", got " + ids.sorted()
        }
        // Three collinear centres make the four-point solve rank deficient. findHomography
        // would still return a matrix, and it would be meaningless.
        for (i in 0..1) {
            for (j in i + 1..2) {
                for (k in j + 1..3) {
                    val a = layout.markers[i]
                    val b = layout.markers[j]
                    val c = layout.markers[k]
                    val area = triangleArea(a, b, c)
                    if (area < COLLINEAR_AREA_EPSILON) {
                        return "canonical marker centres " + a.id + ", " + b.id + ", " + c.id +
                            " are collinear (triangle area " + formatArea(area) + " px^2)"
                    }
                }
            }
        }
        return null
    }

    private fun validateBoxes(layout: Layout, inset: Inset): String? {
        val seenIds = mutableSetOf<String>()
        val seenOrder = mutableMapOf<Int, String>()
        for (box in layout.answerBoxes) {
            val id = box.externalAnswerBoxId
            if (!seenIds.add(id)) return "duplicate answer box id " + id
            // The served order_index is the only ordering there is, so two boxes sharing one
            // would make "document order" ambiguous.
            seenOrder.put(box.orderIndex, id)?.let { other ->
                return "answer boxes " + other + " and " + id + " share order_index " +
                    box.orderIndex
            }
            if (box.segments.isEmpty()) {
                return "answer box " + id + " has no segments"
            }
            for ((part, segment) in box.segments.withIndex()) {
                validateSegment(layout, inset, id, part, segment)?.let { return it }
            }
        }

        // Every printed piece against every other on the same page, whichever box it is from.
        val pieces = layout.answerBoxes.flatMap { box ->
            box.segments.mapIndexed { part, segment -> Triple(box.externalAnswerBoxId, part, segment) }
        }
        for (i in pieces.indices) {
            for (j in i + 1 until pieces.size) {
                val (idA, partA, a) = pieces[i]
                val (idB, partB, b) = pieces[j]
                if (a.pageIndex == b.pageIndex && overlaps(a.bbox, b.bbox)) {
                    return "answer boxes " + name(idA, partA) + " and " + name(idB, partB) +
                        " overlap on page " + a.pageIndex
                }
            }
        }
        return null
    }

    private fun validateSegment(layout: Layout, inset: Inset, id: String, part: Int, segment: Segment): String? {
        val where = "answer box " + name(id, part)
        if (segment.pageIndex < 0) {
            return where + ": page_index " + segment.pageIndex + " is negative"
        }
        val bbox = segment.bbox
        if (bbox.w <= 0 || bbox.h <= 0) {
            return where + ": bbox " + bbox.w + "x" + bbox.h + " has non-positive area"
        }
        if (bbox.x < 0 || bbox.y < 0) {
            return where + ": bbox " + bbox + " starts outside the page"
        }
        if (bbox.right > layout.pageWidthPx) {
            return where + ": bbox " + bbox + " extends past page width " + layout.pageWidthPx
        }
        if (bbox.bottom > layout.pageHeightPx) {
            return where + ": bbox " + bbox + " extends past page height " + layout.pageHeightPx
        }
        if (bbox.w <= inset.horizontal || bbox.h <= inset.vertical) {
            return where + ": bbox " + bbox.w + "x" + bbox.h + " leaves no area after inset " + inset
        }
        return null
    }

    /** "ab_x" for a box's first part, "ab_x part 2" for the rest (1-based, as printed). */
    private fun name(id: String, part: Int): String = if (part == 0) id else id + " part " + (part + 1)

    /** Strict interior intersection: boxes that meet edge to edge touch, they do not overlap. */
    private fun overlaps(a: Bbox, b: Bbox): Boolean =
        a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom

    private fun triangleArea(a: MarkerRef, b: MarkerRef, c: MarkerRef): Double =
        abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) / 2.0

    private fun formatArea(area: Double): String =
        (kotlin.math.round(area * 100.0) / 100.0).toString()
}
