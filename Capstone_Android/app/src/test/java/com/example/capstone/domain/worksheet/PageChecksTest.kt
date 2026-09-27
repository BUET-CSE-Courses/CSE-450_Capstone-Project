package com.example.capstone.domain.worksheet

import com.example.capstone.data.remote.CropInfoDto
import com.example.capstone.data.remote.PageExtractionResultDto
import com.example.capstone.extractor.AnswerBoxRef
import com.example.capstone.extractor.Bbox
import com.example.capstone.extractor.Layout
import com.example.capstone.extractor.MarkerRef
import com.example.capstone.extractor.Segment
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The web end's page results judged against the pack, and hand-in readiness. */
class PageChecksTest {

    /** Page 0: ab_1 and part 1 of nothing; ab_split starts on page 0, ends on page 1; page 2 is empty. */
    private val layout = Layout(
        externalQuestionId = "q-1",
        pageWidthPx = 1240,
        pageHeightPx = 1754,
        markers = listOf(MarkerRef(0, 70.0, 70.0), MarkerRef(1, 1170.0, 70.0), MarkerRef(2, 70.0, 1684.0), MarkerRef(3, 1170.0, 1684.0)),
        answerBoxes = listOf(
            AnswerBoxRef("ab_split", 1, listOf(Segment(0, Bbox(186, 1300, 930, 300)), Segment(1, Bbox(186, 124, 930, 300)))),
            AnswerBoxRef.single("ab_1", 0, 0, Bbox(186, 334, 930, 300)),
        ),
        arucoDictionary = Layout.SUPPORTED_ARUCO_DICTIONARY
    )

    private fun crop(box: String, qr: String = "absent", part: Int = 0) =
        CropInfoDto(answerBoxId = box, qrCheck = qr, warpedBbox = listOf(0, 0, 10, 10), part = part)

    private fun page(
        index: Int,
        crops: List<CropInfoDto>,
        markers: String = "4/4",
        error: String? = null
    ) = PageExtractionResultDto(index, markers, if (markers == "4/4") "homography" else "none", crops, error = error)

    @Test
    fun `expected pieces follow order index, then part, and skip other pages`() {
        assertThat(PageChecks.expectedPieces(layout, 0))
            .containsExactly(PagePiece("ab_1", 0), PagePiece("ab_split", 0)).inOrder()
        assertThat(PageChecks.expectedPieces(layout, 1)).containsExactly(PagePiece("ab_split", 1))
        assertThat(PageChecks.expectedPieces(layout, 2)).isEmpty()
    }

    @Test
    fun `a page with every piece and four markers is accepted, even with no codes read`() {
        val verdict = PageChecks.serverVerdict(
            PageChecks.expectedPieces(layout, 0),
            page(0, listOf(crop("ab_1"), crop("ab_split")))
        )

        assertThat(verdict).isInstanceOf(ServerVerdict.Accepted::class.java)
        assertThat(verdict.summary).isEqualTo("4/4 corner markers, 2 answer boxes cut, 0 of 2 codes read")
    }

    @Test
    fun `the server's error refuses the page with its own words`() {
        val verdict = PageChecks.serverVerdict(
            PageChecks.expectedPieces(layout, 1),
            page(1, emptyList(), markers = "3/4", error = "Only 3/4 ArUco markers detected on page 1.")
        )

        assertThat((verdict as ServerVerdict.Refused).reason).isEqualTo("Only 3/4 ArUco markers detected on page 1.")
    }

    @Test
    fun `fewer than four markers refuses even without an error`() {
        val verdict = PageChecks.serverVerdict(PageChecks.expectedPieces(layout, 1), page(1, emptyList(), markers = "2/4"))

        assertThat((verdict as ServerVerdict.Refused).reason).contains("2/4")
    }

    @Test
    fun `a different set of pieces than the pack prints is refused`() {
        val verdict = PageChecks.serverVerdict(PageChecks.expectedPieces(layout, 0), page(0, listOf(crop("ab_1"))))

        assertThat(verdict).isInstanceOf(ServerVerdict.Refused::class.java)
    }

    @Test
    fun `codes that all belong elsewhere mean the wrong page`() {
        val verdict = PageChecks.serverVerdict(
            PageChecks.expectedPieces(layout, 0),
            page(0, listOf(crop("ab_1", qr = "fail"), crop("ab_split", qr = "absent")))
        )

        assertThat((verdict as ServerVerdict.Refused).reason).contains("right page")
    }

    @Test
    fun `one mismatched code among matches is not a refusal`() {
        // The server's search window can catch a neighbouring box's code.
        val verdict = PageChecks.serverVerdict(
            PageChecks.expectedPieces(layout, 0),
            page(0, listOf(crop("ab_1", qr = "fail"), crop("ab_split", qr = "pass")))
        )

        assertThat(verdict).isInstanceOf(ServerVerdict.Accepted::class.java)
    }

    @Test
    fun `hand in waits for every page with answers, and only those`() {
        assertThat(PageChecks.handInProblem(listOf(0, 1), setOf(0))).isEqualTo("Page 2 still needs a photo.")
        assertThat(PageChecks.handInProblem(listOf(0, 1), emptySet())).isEqualTo("Pages 1, 2 still need photos.")
        assertThat(PageChecks.handInProblem(listOf(0, 1), setOf(0, 1))).isNull()
    }

    @Test
    fun `phone pages the server no longer holds are stale`() {
        // Session 7: the teacher deleted the submission, so nothing on the server.
        assertThat(PageChecks.stalePhonePages(setOf(0, 1), serverPages = null)).containsExactly(0, 1)
        // One page deleted on the server.
        assertThat(PageChecks.stalePhonePages(setOf(0, 1), serverPages = setOf(0))).containsExactly(1)
        // Server pages the phone never cropped are not the phone's to drop.
        assertThat(PageChecks.stalePhonePages(setOf(0), serverPages = setOf(0, 1))).isEmpty()
        assertThat(PageChecks.stalePhonePages(emptySet(), serverPages = null)).isEmpty()
    }
}
