package com.example.capstone.extractor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * One case per rejection reason. No OpenCV: validation is pure arithmetic on the layout, which
 * is why it runs before a photo is decoded.
 */
class LayoutValidatorTest {

    private val markers = listOf(
        MarkerRef(0, 70.0, 70.0),
        MarkerRef(1, 1170.0, 70.0),
        MarkerRef(2, 70.0, 1684.0),
        MarkerRef(3, 1170.0, 1684.0),
    )

    private fun layout(
        markers: List<MarkerRef> = this.markers,
        boxes: List<AnswerBoxRef> = listOf(box("ab_a", 0, Bbox(186, 334, 930, 90))),
        pageW: Int = 1240,
        pageH: Int = 1754,
        dictionary: String = Layout.SUPPORTED_ARUCO_DICTIONARY,
    ) = Layout("q1", pageW, pageH, markers, boxes, dictionary)

    private var nextOrder = 0

    /** A one-piece box; order indices count up in the order the test builds boxes. */
    private fun box(id: String, page: Int, bbox: Bbox, order: Int = nextOrder++) =
        AnswerBoxRef.single(id, order, page, bbox)

    /** The border, padding and caption of the old printed box: an inset with real numbers. */
    private val someInset = Inset(left = 10, top = 23, right = 10, bottom = 10)

    private fun validate(layout: Layout, inset: Inset = someInset) =
        LayoutValidator.validate(layout, inset)

    @Test
    fun `a well formed layout is accepted`() {
        assertThat(validate(layout())).isNull()
    }

    @Test
    fun `three markers are rejected`() {
        assertThat(validate(layout(markers = markers.take(3))))
            .isEqualTo("expected exactly 4 markers, got 3")
    }

    @Test
    fun `a marker id outside zero to three is rejected`() {
        val wrong = markers.dropLast(1) + MarkerRef(5, 1170.0, 1684.0)

        assertThat(validate(layout(markers = wrong)))
            .isEqualTo("marker ids must be exactly [0, 1, 2, 3], got [0, 1, 2, 5]")
    }

    @Test
    fun `a duplicated marker id is rejected`() {
        val wrong = markers.dropLast(1) + MarkerRef(2, 1170.0, 1684.0)

        assertThat(validate(layout(markers = wrong)))
            .isEqualTo("marker ids must be exactly [0, 1, 2, 3], got [0, 1, 2, 2]")
    }

    @Test
    fun `collinear canonical marker centres are rejected`() {
        // Every centre on one horizontal line: a degenerate four-point solve that
        // findHomography would still answer.
        val flat = markers.mapIndexed { i, m -> MarkerRef(m.id, 70.0 + i * 300.0, 70.0) }

        assertThat(validate(layout(markers = flat)))
            .isEqualTo("canonical marker centres 0, 1, 2 are collinear (triangle area 0.0 px^2)")
    }

    @Test
    fun `a zero height bbox is rejected`() {
        assertThat(validate(layout(boxes = listOf(box("ab_a", 0, Bbox(186, 334, 930, 0))))))
            .isEqualTo("answer box ab_a: bbox 930x0 has non-positive area")
    }

    @Test
    fun `a bbox running past the page bottom is rejected`() {
        assertThat(validate(layout(boxes = listOf(box("ab_a", 0, Bbox(186, 1700, 930, 90))))))
            .isEqualTo(
                "answer box ab_a: bbox [186, 1700, 930, 90] extends past page height 1754",
            )
    }

    @Test
    fun `a bbox running past the page right edge is rejected`() {
        assertThat(validate(layout(boxes = listOf(box("ab_a", 0, Bbox(1000, 334, 930, 90))))))
            .isEqualTo(
                "answer box ab_a: bbox [1000, 334, 930, 90] extends past page width 1240",
            )
    }

    @Test
    fun `a negative bbox origin is rejected`() {
        assertThat(validate(layout(boxes = listOf(box("ab_a", 0, Bbox(-10, 334, 930, 90))))))
            .isEqualTo("answer box ab_a: bbox [-10, 334, 930, 90] starts outside the page")
    }

    @Test
    fun `overlapping boxes on one page are rejected`() {
        val boxes = listOf(
            box("ab_a", 0, Bbox(186, 334, 930, 90)),
            box("ab_b", 0, Bbox(186, 400, 930, 90)),
        )

        assertThat(validate(layout(boxes = boxes)))
            .isEqualTo("answer boxes ab_a and ab_b overlap on page 0")
    }

    @Test
    fun `boxes that only touch edge to edge are accepted`() {
        val boxes = listOf(
            box("ab_a", 0, Bbox(186, 334, 930, 90)),
            box("ab_b", 0, Bbox(186, 424, 930, 90)),
        )

        assertThat(validate(layout(boxes = boxes))).isNull()
    }

    @Test
    fun `boxes at the same position on different pages are accepted`() {
        val boxes = listOf(
            box("ab_a", 0, Bbox(186, 334, 930, 90)),
            box("ab_b", 1, Bbox(186, 334, 930, 90)),
        )

        assertThat(validate(layout(boxes = boxes))).isNull()
    }

    @Test
    fun `a duplicate answer box id is rejected`() {
        val boxes = listOf(
            box("ab_a", 0, Bbox(186, 334, 930, 90)),
            box("ab_a", 0, Bbox(186, 500, 930, 90)),
        )

        assertThat(validate(layout(boxes = boxes))).isEqualTo("duplicate answer box id ab_a")
    }

    @Test
    fun `a box too short for the inset is rejected`() {
        // 33 px of vertical inset against a 30 px box.
        assertThat(validate(layout(boxes = listOf(box("ab_a", 0, Bbox(186, 334, 930, 30))))))
            .isEqualTo("answer box ab_a: bbox 930x30 leaves no area after inset (10, 23, 10, 10)")
    }

    @Test
    fun `a box too short for the inset is accepted when there is no inset`() {
        val short = layout(boxes = listOf(box("ab_a", 0, Bbox(186, 334, 930, 30))))

        assertThat(validate(short, Inset.NONE)).isNull()
    }

    @Test
    fun `array order does not matter, the served order index does`() {
        // The reading-order check is gone: order_index is served, so geometry is not
        // consulted for order at all.
        val boxes = listOf(
            box("ab_lower", 0, Bbox(186, 900, 930, 90), order = 0),
            box("ab_upper", 0, Bbox(186, 334, 930, 90), order = 1),
        )

        assertThat(validate(layout(boxes = boxes))).isNull()
    }

    @Test
    fun `two boxes sharing an order index are rejected`() {
        val boxes = listOf(
            box("ab_a", 0, Bbox(186, 334, 930, 90), order = 3),
            box("ab_b", 0, Bbox(186, 900, 930, 90), order = 3),
        )

        assertThat(validate(layout(boxes = boxes)))
            .isEqualTo("answer boxes ab_a and ab_b share order_index 3")
    }

    @Test
    fun `a box with no segments is rejected`() {
        val boxes = listOf(AnswerBoxRef("ab_a", 0, emptyList()))

        assertThat(validate(layout(boxes = boxes))).isEqualTo("answer box ab_a has no segments")
    }

    @Test
    fun `a box split over two pages is accepted`() {
        val split = AnswerBoxRef(
            "ab_split",
            0,
            listOf(Segment(0, Bbox(186, 1400, 930, 250)), Segment(1, Bbox(186, 124, 930, 400))),
        )

        assertThat(validate(layout(boxes = listOf(split)))).isNull()
    }

    @Test
    fun `a later part that leaves the page is named by its part number`() {
        val split = AnswerBoxRef(
            "ab_split",
            0,
            listOf(Segment(0, Bbox(186, 1400, 930, 250)), Segment(1, Bbox(186, 1600, 930, 400))),
        )

        assertThat(validate(layout(boxes = listOf(split)))).isEqualTo(
            "answer box ab_split part 2: bbox [186, 1600, 930, 400] extends past page height 1754",
        )
    }

    @Test
    fun `a continuation that overlaps another box on its page is rejected`() {
        val split = AnswerBoxRef(
            "ab_split",
            0,
            listOf(Segment(0, Bbox(186, 1400, 930, 250)), Segment(1, Bbox(186, 124, 930, 400))),
        )
        val next = box("ab_next", 1, Bbox(186, 500, 930, 90), order = 1)

        assertThat(validate(layout(boxes = listOf(split, next))))
            .isEqualTo("answer boxes ab_split part 2 and ab_next overlap on page 1")
    }

    @Test
    fun `a marker dictionary other than DICT_4X4_50 is rejected`() {
        assertThat(validate(layout(dictionary = "DICT_5X5_100"))).isEqualTo(
            "marker dictionary DICT_5X5_100 is not supported; this app reads DICT_4X4_50 only",
        )
    }

    @Test
    fun `a negative page index is rejected`() {
        assertThat(validate(layout(boxes = listOf(box("ab_a", -1, Bbox(186, 334, 930, 90))))))
            .isEqualTo("answer box ab_a: page_index -1 is negative")
    }

    @Test
    fun `a non positive page size is rejected`() {
        assertThat(validate(layout(pageH = 0))).isEqualTo("page size 1240x0 is not positive")
    }

    @Test
    fun `the sample fixture passes validation`() {
        assertThat(validate(SampleFixture.layout())).isNull()
    }
}
