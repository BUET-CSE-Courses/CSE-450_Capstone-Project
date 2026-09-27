package com.example.capstone.data.repository

import com.example.capstone.data.remote.AssignmentPackDto
import com.example.capstone.data.remote.resourceText
import com.example.capstone.domain.grading.PrintedLabel
import com.example.capstone.extractor.AnswerCrop
import com.example.capstone.extractor.PointPx
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import com.google.gson.Gson

/** Grading items from the pack fixture (`webend/pack_v1.json`) and the phone's crops. */
class PaperPreparerTest {

    private val pack: AssignmentPackDto =
        Gson().fromJson(resourceText("webend/pack_v1.json"), AssignmentPackDto::class.java)

    private fun crop(box: String, part: Int, page: Int, order: Int) = AnswerCrop(
        externalQuestionId = QID, externalAnswerBoxId = box, part = part, pageIndex = page, orderIndex = order,
        png = byteArrayOf(order.toByte(), part.toByte()),
        imageQuad = listOf(PointPx(0.0, 0.0), PointPx(1.0, 0.0), PointPx(1.0, 1.0), PointPx(0.0, 1.0))
    )

    private val allCrops = listOf(
        crop("ab_fixture_one", 0, 0, 0),
        crop("ab_fixture_two", 0, 0, 1),
        crop("ab_fixture_two", 1, 1, 1),
        crop("ab_fixture_three", 0, 1, 2)
    )

    private val images = { ref: String -> ref.toByteArray() }

    @Test
    fun usesThePacksTextLabelBlockedReasonAndImages() {
        val paper = (PaperPreparer.buildPaper(pack, allCrops, images) as PrepareResult.Ready).paper
        val one = paper.boxes.getValue("ab_fixture_one")
        assertThat(one.item.label).isEqualTo("Q1(a)")
        assertThat(one.item.maxScore).isEqualTo(2)
        assertThat(one.item.groundTruthImages).hasSize(1)
        assertThat(one.item.questionImages).hasSize(1)
        assertThat(one.problem).isNull()

        val two = paper.boxes.getValue("ab_fixture_two")
        assertThat(two.item.crops.map { it.toList() }).containsExactly(listOf<Byte>(1, 0), listOf<Byte>(1, 1)).inOrder()

        val three = paper.boxes.getValue("ab_fixture_three")
        assertThat(three.item.blockedReason).startsWith("No marks set for this part")
        assertThat(three.item.maxScore).isEqualTo(0)
    }

    @Test
    fun aBoxWithAMissingPartGetsNoCropsAtAllRatherThanHalf() {
        val paper = (PaperPreparer.buildPaper(pack, allCrops.filterNot { it.part == 1 }, images) as PrepareResult.Ready).paper
        assertThat(paper.boxes.getValue("ab_fixture_two").item.crops).isEmpty()
        assertThat(paper.boxes.getValue("ab_fixture_one").item.crops).hasSize(1)
    }

    @Test
    fun anImageThatNeverDownloadedIsAProblemForThatBox() {
        val paper = (PaperPreparer.buildPaper(pack, allCrops) { null } as PrepareResult.Ready).paper
        assertThat(paper.boxes.getValue("ab_fixture_one").problem).contains("didn't download")
        assertThat(paper.boxes.getValue("ab_fixture_two").problem).isNull()
    }

    @Test
    fun refusesWithNoCropsOrCropsFromAnotherPaper() {
        assertThat(PaperPreparer.buildPaper(pack, emptyList(), images)).isInstanceOf(PrepareResult.Refused::class.java)
        val foreign = allCrops + crop("ab_fixture_one", 0, 0, 0).copy(externalQuestionId = "other-paper")
        assertThat(PaperPreparer.buildPaper(pack, foreign, images)).isInstanceOf(PrepareResult.Refused::class.java)
    }

    @Test
    fun eachCropGetsItsPartsPrintedLabelRegion() {
        val paper = (PaperPreparer.buildPaper(pack, allCrops, images) as PrepareResult.Ready).paper
        val two = paper.boxes.getValue("ab_fixture_two").item
        // Segments [0, 186, 1500, 930, 150] and [1, 186, 124, 930, 250] at 150 dpi.
        assertThat(two.cropLabelRegions).containsExactly(
            PrintedLabel.region("Q1(b)", 0, 2, 930, 150, 150),
            PrintedLabel.region("Q1(b)", 1, 2, 930, 250, 150)
        ).inOrder()
    }

    @Test
    fun noLabelRegionsWhenThePartsDoNotMatchTheCrops() {
        val box = pack.boxes.first { it.id == "ab_fixture_two" }
        assertThat(PaperPreparer.labelRegions(box, 150, 1)).isEmpty()
        assertThat(PaperPreparer.labelRegions(box.copy(segments = null, bbox = listOf(1, 2, 300, 100)), 150, 1))
            .containsExactly(PrintedLabel.region("Q1(b)", 0, 1, 300, 100, 150))
    }

    private companion object {
        const val QID = "5b0f6c1e-2d7a-4a3e-9c51-0f3a2b7d9e10"
    }
}
