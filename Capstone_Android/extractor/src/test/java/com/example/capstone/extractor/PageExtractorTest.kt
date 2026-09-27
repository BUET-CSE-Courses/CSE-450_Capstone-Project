package com.example.capstone.extractor

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs

/**
 * The public surface: every [ExtractionResult] case, the crop geometry, and the ordering.
 */
class PageExtractorTest {

    @Before
    fun loadNative() = OpenCvTestNative.load()

    // ---- Success, geometry and ordering ------------------------------------------------

    @Test
    fun `a crop is rectified to exactly its inset canonical size`() {
        val result = PageExtractor(Inset(left = 10, top = 23, right = 10, bottom = 10))
            .extractPage(SampleFixture.layout(), 0, SampleFixture.pageBytes())

        val crops = (result as ExtractionResult.Success).crops
        // Sample bboxes are 960x250 and 960x290; the inset takes 20 off the width and 33 off
        // the height, in canonical space, before the transform.
        assertThat(sizeOf(crops[0].png)).isEqualTo(940 to 217)
        assertThat(sizeOf(crops[1].png)).isEqualTo(940 to 257)
    }

    @Test
    fun `no inset means the crop is the bbox as served`() {
        val result = PageExtractor(Inset.NONE)
            .extractPage(SampleFixture.layout(), 0, SampleFixture.pageBytes())

        val crops = (result as ExtractionResult.Success).crops
        assertThat(sizeOf(crops[0].png)).isEqualTo(960 to 250)
        assertThat(sizeOf(crops[1].png)).isEqualTo(960 to 290)
    }

    @Test
    fun `the default extractor crops the full bbox, as the web end does`() {
        val result = PageExtractor()
            .extractPage(SampleFixture.layout(), 0, SampleFixture.pageBytes())

        val crops = (result as ExtractionResult.Success).crops
        assertThat(sizeOf(crops[0].png)).isEqualTo(960 to 250)
        assertThat(sizeOf(crops[1].png)).isEqualTo(960 to 290)
    }

    @Test
    fun `crops carry the served order index and come back in that order`() {
        val base = SampleFixture.layout()
        // Array order reversed against order_index: the served index wins.
        val layout = base.copy(
            answerBoxes = listOf(
                AnswerBoxRef.single("ab_second", 7, 0, Bbox(168, 1282, 960, 290)),
                AnswerBoxRef.single("ab_first", 2, 0, Bbox(168, 600, 960, 250)),
            ),
        )
        val result = PageExtractor().extractPage(layout, 0, SampleFixture.pageBytes())

        val crops = (result as ExtractionResult.Success).crops
        assertThat(crops.map { it.externalAnswerBoxId }).containsExactly("ab_first", "ab_second")
            .inOrder()
        assertThat(crops.map { it.orderIndex }).containsExactly(2, 7).inOrder()
        assertThat(crops.map { it.part }).containsExactly(0, 0)
    }

    @Test
    fun `every crop carries the question id and its own page index`() {
        val layout = SampleFixture.layout()
        val result = PageExtractor().extractPage(layout, 0, SampleFixture.pageBytes())

        val crops = (result as ExtractionResult.Success).crops
        assertThat(crops.map { it.externalQuestionId }.toSet())
            .containsExactly(layout.externalQuestionId)
        assertThat(crops.map { it.pageIndex }.toSet()).containsExactly(0)
    }

    @Test
    fun `a box that spans two pages gives one part from each page`() {
        // The sample page stands in for both pages. The split box's part 1 sits where the
        // first box sits on page 0.
        val base = SampleFixture.layout()
        val split = AnswerBoxRef(
            "ab_split",
            1,
            listOf(Segment(0, Bbox(168, 1282, 960, 290)), Segment(1, Bbox(168, 600, 960, 250))),
        )
        val layout = base.copy(
            answerBoxes = listOf(
                AnswerBoxRef.single("ab_p0", 0, 0, Bbox(168, 600, 960, 250)),
                split,
                AnswerBoxRef.single("ab_p1", 2, 1, Bbox(168, 1282, 960, 290)),
            ),
        )

        val page0 = (PageExtractor().extractPage(layout, 0, SampleFixture.pageBytes())
            as ExtractionResult.Success).crops
        val page1 = (PageExtractor().extractPage(layout, 1, SampleFixture.pageBytes())
            as ExtractionResult.Success).crops

        assertThat(page0.map { it.externalAnswerBoxId to it.part })
            .containsExactly("ab_p0" to 0, "ab_split" to 0).inOrder()
        assertThat(page1.map { it.externalAnswerBoxId to it.part })
            .containsExactly("ab_split" to 1, "ab_p1" to 0).inOrder()
        assertThat(page1.map { it.pageIndex }.toSet()).containsExactly(1)
        assertThat(page1.map { it.orderIndex }).containsExactly(1, 2).inOrder()
        // Each part is rectified to its own segment's size.
        assertThat(sizeOf(page0[1].png)).isEqualTo(960 to 290)
        assertThat(sizeOf(page1[0].png)).isEqualTo(960 to 250)
    }

    @Test
    fun `a page with no boxes succeeds with no crops`() {
        // Nothing to extract is not a failure. The web end's extract_page reports this as an
        // error string inside a successful result.
        val result = PageExtractor().extractPage(SampleFixture.layout(), 7, SampleFixture.pageBytes())

        assertThat(result).isEqualTo(ExtractionResult.Success(emptyList()))
    }

    @Test
    fun `the crop bytes are a real png`() {
        val result = PageExtractor().extractPage(SampleFixture.layout(), 0, SampleFixture.pageBytes())

        val png = (result as ExtractionResult.Success).crops[0].png
        val signature = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        )
        assertThat(png.copyOfRange(0, 4)).isEqualTo(signature)
    }

    // ---- Failure cases -----------------------------------------------------------------

    @Test
    fun `a layout that fails validation never reaches the decoder`() {
        val layout = SampleFixture.layout().let { it.copy(markers = it.markers.take(3)) }

        // Deliberately passing junk bytes: validation must reject before anything decodes.
        val result = PageExtractor().extractPage(layout, 0, "not an image".toByteArray())

        assertThat(result)
            .isEqualTo(ExtractionResult.InvalidLayout("expected exactly 4 markers, got 3"))
    }

    @Test
    fun `bytes that are not an image are Undecodable`() {
        val result = PageExtractor().extractPage(
            SampleFixture.layout(),
            0,
            "definitely not a png".toByteArray(),
        )

        assertThat(result).isInstanceOf(ExtractionResult.Undecodable::class.java)
    }

    @Test
    fun `empty bytes are Undecodable`() {
        val result = PageExtractor().extractPage(SampleFixture.layout(), 0, ByteArray(0))

        assertThat(result).isInstanceOf(ExtractionResult.Undecodable::class.java)
    }

    @Test
    fun `a blank page reports every marker missing`() {
        val result = PageExtractor().extractPage(SampleFixture.layout(), 0, blankPagePng())

        assertThat(result).isEqualTo(ExtractionResult.MarkersNotFound(0, listOf(0, 1, 2, 3)))
    }

    @Test
    fun `a layout naming a marker id outside the contract is refused`() {
        // Marker 3 replaced by id 7 - it is a valid DICT_4X4_50 id, it is simply not on this
        // page. Reported as missing rather than quietly registering off three points.
        val layout = SampleFixture.layout().let {
            it.copy(markers = it.markers.dropLast(1) + MarkerRef(7, 1170.0, 1684.0))
        }

        val result = PageExtractor().extractPage(layout, 0, SampleFixture.pageBytes())

        // The id set check fires first; both are refusals, and the layout one is more specific.
        assertThat(result).isInstanceOf(ExtractionResult.InvalidLayout::class.java)
    }

    @Test
    fun `coincident canonical marker centres are rejected before the solve`() {
        val layout = SampleFixture.layout().let {
            it.copy(markers = it.markers.map { m -> MarkerRef(m.id, 70.0, 70.0) })
        }

        val result = PageExtractor().extractPage(layout, 0, SampleFixture.pageBytes())

        assertThat(result).isInstanceOf(ExtractionResult.InvalidLayout::class.java)
        assertThat((result as ExtractionResult.InvalidLayout).reason).contains("collinear")
    }

    @Test
    fun `detected centres that collapse to a line are RegistrationFailed`() {
        // Canonical geometry is fine, so validation passes; it is the photo that is degenerate.
        // Registration.registerPage is reached directly because no fixture photograph can be
        // made to produce four collinear detections on demand.
        val layout = SampleFixture.layout()
        val detected = layout.markers.associate { it.id to org.opencv.core.Point(it.x, 70.0) }

        val thrown = runCatching {
            Registration.registerPage(layout, 0, Modality.PHOTO, Inset.NONE, detected)
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(RegistrationException::class.java)
        assertThat(thrown).hasMessageThat().contains("collinear or coincident")
    }

    // ---- Scanner modality ---------------------------------------------------------------

    @Test
    fun `the scanner affine path registers the sample too`() {
        // The sample is a rendered page, so affine and homography should agree closely on it.
        val layout = SampleFixture.layout()
        val photo = PageExtractor(Inset.NONE)
            .extractPage(layout, 0, SampleFixture.pageBytes(), Modality.PHOTO)
        val scan = PageExtractor(Inset.NONE)
            .extractPage(layout, 0, SampleFixture.pageBytes(), Modality.SCANNER)

        assertThat(scan).isInstanceOf(ExtractionResult.Success::class.java)
        assertThat(sizeOf((scan as ExtractionResult.Success).crops[0].png))
            .isEqualTo(sizeOf((photo as ExtractionResult.Success).crops[0].png))
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun sizeOf(png: ByteArray): Pair<Int, Int> {
        val decoded = requireNotNull(Registration.decode(png))
        return try {
            decoded.cols() to decoded.rows()
        } finally {
            decoded.release()
        }
    }

    private fun blankPagePng(): ByteArray {
        val white = Mat(1756, 1242, CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))
        val png = MatOfByte()
        return try {
            check(Imgcodecs.imencode(".png", white, png))
            png.toArray()
        } finally {
            white.release()
            png.release()
        }
    }
}
