package com.example.capstone.domain.worksheet

import com.example.capstone.extractor.AnswerBoxRef
import com.example.capstone.extractor.Bbox
import com.example.capstone.extractor.Layout
import com.example.capstone.extractor.MarkerRef
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [MarkerCorners].
 *
 * The property being pinned is that corner names come from the layout's own
 * marker centres and from nothing else. `INTEGRATION_AUDIT.md` §2.3 measured a
 * hardcoded id-to-corner assumption costing 990 px of mirrored displacement
 * while still reporting four markers found, so the last test here deliberately
 * hands over a layout with the ids assigned the other way round and asserts the
 * names follow the geometry rather than the id.
 */
class MarkerCornersTest {

    private val pageW = 1240
    private val pageH = 1754

    /** The generator's row-major convention: 0 TL, 1 TR, 2 BL, 3 BR. */
    private fun layout(
        markers: List<MarkerRef> = listOf(
            MarkerRef(0, 70.0, 70.0),
            MarkerRef(1, 1170.0, 70.0),
            MarkerRef(2, 70.0, 1684.0),
            MarkerRef(3, 1170.0, 1684.0)
        )
    ) = Layout(
        externalQuestionId = "q_worksheet_3",
        pageWidthPx = pageW,
        pageHeightPx = pageH,
        markers = markers,
        answerBoxes = listOf(
            AnswerBoxRef.single("ab_1", orderIndex = 0, pageIndex = 0, bbox = Bbox(186, 334, 930, 90))
        ),
        arucoDictionary = Layout.SUPPORTED_ARUCO_DICTIONARY
    )

    @Test
    fun `each marker id is named by where its centre sits on the page`() {
        val named = MarkerCorners.describe(layout(), listOf(0, 1, 2, 3))

        assertThat(named)
            .containsExactly("top-left", "top-right", "bottom-left", "bottom-right")
            .inOrder()
    }

    @Test
    fun `names follow the requested order, not the layout order`() {
        val named = MarkerCorners.describe(layout(), listOf(3, 0))

        assertThat(named).containsExactly("bottom-right", "top-left").inOrder()
    }

    @Test
    fun `an id the layout does not name is not given a corner`() {
        val named = MarkerCorners.describe(layout(), listOf(7))

        // Placing it would be a guess, so it says what it knows and no more.
        assertThat(named).containsExactly("marker 7")
    }

    @Test
    fun `names come from geometry, not from the id`() {
        // The same four centres, with the ids assigned clockwise instead of
        // row-major. Nothing here should notice the id at all.
        val clockwise = layout(
            markers = listOf(
                MarkerRef(0, 70.0, 70.0),
                MarkerRef(1, 1170.0, 70.0),
                MarkerRef(2, 1170.0, 1684.0),
                MarkerRef(3, 70.0, 1684.0)
            )
        )

        assertThat(MarkerCorners.describe(clockwise, listOf(2)))
            .containsExactly("bottom-right")
        assertThat(MarkerCorners.describe(clockwise, listOf(3)))
            .containsExactly("bottom-left")
    }

    @Test
    fun `one missing corner reads as one sentence`() {
        assertThat(MarkerCorners.sentence(layout(), listOf(0)))
            .isEqualTo("The top-left corner marker is not visible.")
    }

    @Test
    fun `two missing corners are joined with and`() {
        assertThat(MarkerCorners.sentence(layout(), listOf(0, 3)))
            .isEqualTo("The top-left and bottom-right corner markers are not visible.")
    }

    @Test
    fun `three missing corners are comma separated then and`() {
        assertThat(MarkerCorners.sentence(layout(), listOf(0, 1, 2)))
            .isEqualTo("The top-left, top-right and bottom-left corner markers are not visible.")
    }

    @Test
    fun `no ids at all still produces a sentence rather than an empty string`() {
        assertThat(MarkerCorners.sentence(layout(), emptyList()))
            .isEqualTo("No corner markers were found on this photo.")
    }
}
