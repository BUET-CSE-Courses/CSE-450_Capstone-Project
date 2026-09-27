package com.example.capstone.data.repository

import android.util.Log
import com.example.capstone.data.local.PagePhotoStore
import com.example.capstone.data.remote.AssignmentPackDto
import com.example.capstone.data.remote.PackBoxDto
import com.example.capstone.domain.grading.AnswerToGrade
import com.example.capstone.domain.grading.IgnoreRegion
import com.example.capstone.domain.grading.PrintedLabel
import com.example.capstone.domain.worksheet.QuestionResolver
import com.example.capstone.domain.worksheet.Resolution
import com.example.capstone.domain.worksheet.ResolverCreation
import com.example.capstone.extractor.AnswerCrop

/**
 * One answer box ready for [com.example.capstone.domain.grading.GradingService].
 *
 * @param problem set when the phone cannot grade this box as the server would (a pack
 *   image that never downloaded). The box then goes to fallback without a model call.
 */
data class PreparedBox(
    val item: AnswerToGrade,
    val orderIndex: Int,
    val label: String,
    val problem: String? = null
)

/** Every box of one paper, by answer box id. */
class PreparedPaper(val boxes: Map<String, PreparedBox>)

sealed interface PrepareResult {
    data class Ready(val paper: PreparedPaper) : PrepareResult

    /** Nothing can be graded. [message] is for the student, [detail] names the cause. */
    data class Refused(val message: String, val detail: String? = null) : PrepareResult
}

/**
 * Builds the grading items from the **cached pack** and the **phone's own crops** (plan
 * decision 9): text, points, label and blocked reason exactly as the pack serves them
 * (they come from the server's `build_grading_items`), plus the pack's model-answer images
 * and question figures, in the pack's order. [com.example.capstone.domain.grading.TokenBudget]
 * later drops images that do not fit.
 */
class PaperPreparer(
    private val assignmentRepository: AssignmentRepository,
    private val pagePhotoStore: PagePhotoStore
) {
    /** Reads files; call off the main thread. */
    fun prepare(questionId: String): PrepareResult {
        val cached = assignmentRepository.cachedPack(questionId)
            ?: return PrepareResult.Refused(
                "This assignment isn't saved on this phone.",
                "Open it from the assignment list to download it, then try again."
            )
        return buildPaper(
            pack = cached.pack,
            crops = pagePhotoStore.allCrops(questionId),
            image = { ref -> assignmentRepository.packImage(questionId, ref) }
        )
    }

    companion object {
        private const val TAG = "PaperPreparer"

        /**
         * The pure part of [prepare].
         *
         * [QuestionResolver]'s all-or-nothing rule still decides whether the crops belong to
         * this paper: crops from another paper, unknown boxes or duplicates refuse the whole
         * run. The one exception is a box with a part **missing**. After hand-in every
         * eligible box must be posted, so such a box is not skipped: it gets no crops at
         * all (never half of them), which the grader turns into "No extracted answer image
         * for this box" and fallback (plan §D.3 step 2). The server has its own crops.
         */
        fun buildPaper(
            pack: AssignmentPackDto,
            crops: List<AnswerCrop>,
            image: (String) -> ByteArray?
        ): PrepareResult {
            if (crops.isEmpty()) {
                return PrepareResult.Refused(
                    "No pages of this paper were photographed on this phone.",
                    "Photograph every page from the scan screen first."
                )
            }
            val resolver = when (val creation = QuestionResolver.forAssignment(pack.toWorksheet())) {
                is ResolverCreation.Available -> creation.resolver
                is ResolverCreation.Unavailable -> return PrepareResult.Refused(
                    "This paper can't be marked on the phone.",
                    creation.reason
                )
            }

            val cropsByBox: Map<String, List<ByteArray>> = when (val resolution = resolver.resolve(crops)) {
                is Resolution.Resolved -> resolution.answers.associate { a -> a.answerBoxId to a.crops.map { it.png } }
                is Resolution.Failed -> {
                    val onlyMissing = resolution.cropsFromOtherQuestion.isEmpty() &&
                        resolution.cropsWithoutQuestion.isEmpty() &&
                        resolution.duplicateCropIds.isEmpty()
                    if (!onlyMissing) {
                        Log.w(TAG, resolution.message)
                        return PrepareResult.Refused(
                            "The photos on this phone don't match this paper.",
                            resolution.message
                        )
                    }
                    Log.w(TAG, "${resolution.message}; those boxes go to fallback")
                    completeBoxesOnly(pack, crops)
                }
            }

            val boxes = pack.boxes.associate { box ->
                val missing = mutableListOf<String>()
                fun images(refs: List<String>): List<ByteArray> = refs.mapNotNull { ref ->
                    image(ref) ?: run { missing += ref; null }
                }
                val item = AnswerToGrade(
                    answerBoxId = box.id,
                    label = box.label.orEmpty(),
                    maxScore = box.points ?: 0,
                    questionText = box.questionText,
                    groundTruthText = box.modelAnswerText,
                    questionImages = images(box.questionImages),
                    groundTruthImages = images(box.modelAnswerImages),
                    crops = cropsByBox[box.id].orEmpty(),
                    blockedReason = box.blockedReason,
                    cropLabelRegions = labelRegions(box, pack.dpi, cropsByBox[box.id].orEmpty().size)
                )
                val problem = if (missing.isEmpty()) null
                else "${missing.size} image(s) for this answer didn't download to the phone"
                box.id to PreparedBox(item, box.orderIndex, box.label.orEmpty(), problem)
            }
            return PrepareResult.Ready(PreparedPaper(boxes))
        }

        /**
         * Where the printed label sits on each of the box's [cropCount] crops (one per part, in
         * part order). Segments are `[page, x, y, w, h]`; a box without them is one part the
         * size of its `bbox` `[x, y, w, h]`, as in `toExtractorLayout`. Empty when the parts
         * don't match the crops, so blank detection falls back to the server's rule.
         */
        fun labelRegions(box: PackBoxDto, dpi: Int, cropCount: Int): List<IgnoreRegion?> {
            val sizes: List<Pair<Int, Int>> = box.segments?.takeIf { it.isNotEmpty() }
                ?.map { seg -> if (seg.size >= 5) seg[3] to seg[4] else 0 to 0 }
                ?: box.bbox?.takeIf { it.size >= 4 }?.let { listOf(it[2] to it[3]) }
                ?: return emptyList()
            if (sizes.size != cropCount) return emptyList()
            return sizes.mapIndexed { part, (w, h) ->
                PrintedLabel.region(box.label, part, sizes.size, w, h, dpi)
            }
        }

        /** Crops per box, in part order, only for boxes that have every part. */
        private fun completeBoxesOnly(pack: AssignmentPackDto, crops: List<AnswerCrop>): Map<String, List<ByteArray>> {
            val parts = pack.toExtractorLayout()?.answerBoxes
                ?.associate { it.externalAnswerBoxId to it.segments.size }.orEmpty()
            return crops.filter { it.externalQuestionId == pack.questionId }
                .groupBy { it.externalAnswerBoxId }
                .filter { (boxId, list) ->
                    val n = parts[boxId] ?: return@filter false
                    list.map { it.part }.toSet() == (0 until n).toSet()
                }
                .mapValues { (_, list) -> list.sortedBy { it.part }.map { it.png } }
        }
    }
}
