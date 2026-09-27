package com.example.capstone.data.local

import com.example.capstone.extractor.AnswerCrop
import com.example.capstone.extractor.PointPx
import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.io.File

/**
 * The phone's own crops of each photographed page, on disk, one folder per page:
 *
 * ```
 * <root>/<question_id>/page_<n>/crops.json
 * <root>/<question_id>/page_<n>/<k>.png
 * ```
 *
 * These are what the phone grades (plan decision 9): cut by `:extractor` from the photo the
 * student took, never downloaded from the server. A page's crops are saved only once the
 * web end has accepted the same photo, so what is here matches what was uploaded from this
 * phone. Files, not memory, so marking can resume after Android kills the app.
 *
 * A page is replaced as a whole: written to a temp folder, then swapped in, so a crash
 * mid-save leaves the old page or the new one, never a mix.
 */
class PagePhotoStore(private val root: File, private val gson: Gson = Gson()) {

    /** Replaces every crop held for [pageIndex] with [crops]. */
    @Synchronized
    fun savePage(questionId: String, pageIndex: Int, crops: List<AnswerCrop>) {
        require(crops.all { it.pageIndex == pageIndex && it.externalQuestionId == questionId }) {
            "crops for another page or paper"
        }
        val target = pageDir(questionId, pageIndex)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.deleteRecursively()
        check(tmp.mkdirs()) { "could not create ${tmp.name}" }

        val entries = crops.mapIndexed { k, crop ->
            File(tmp, "$k.png").writeBytes(crop.png)
            StoredCrop(
                box = crop.externalAnswerBoxId,
                part = crop.part,
                order = crop.orderIndex,
                file = "$k.png",
                quad = crop.imageQuad.map { listOf(it.x, it.y) }
            )
        }
        File(tmp, INDEX).writeText(gson.toJson(StoredPage(questionId, pageIndex, entries)))

        target.deleteRecursively()
        check(tmp.renameTo(target)) { "could not save page ${pageIndex + 1}" }
    }

    /** The crops held for [pageIndex], or null when that page has none (or they are unreadable). */
    @Synchronized
    fun page(questionId: String, pageIndex: Int): List<AnswerCrop>? {
        val dir = pageDir(questionId, pageIndex)
        val index = File(dir, INDEX).takeIf { it.isFile } ?: return null
        val stored = try {
            gson.fromJson(index.readText(), StoredPage::class.java)
        } catch (e: JsonParseException) {
            return null
        } ?: return null
        if (stored.questionId != questionId || stored.pageIndex != pageIndex) return null
        return stored.crops.map { entry ->
            val png = File(dir, PackStore.safe(entry.file.removeSuffix(".png")) + ".png")
                .takeIf { it.isFile }?.readBytes() ?: return null
            AnswerCrop(
                externalQuestionId = questionId,
                externalAnswerBoxId = entry.box,
                part = entry.part,
                pageIndex = pageIndex,
                orderIndex = entry.order,
                png = png,
                imageQuad = entry.quad.map { PointPx(it[0], it[1]) }
            )
        }
    }

    /** Page indices that have crops, ascending. */
    @Synchronized
    fun pages(questionId: String): List<Int> =
        paperDir(questionId).listFiles().orEmpty()
            .mapNotNull { PAGE_DIR.matchEntire(it.name)?.groupValues?.get(1)?.toInt() }
            .filter { page(questionId, it) != null }
            .sorted()

    /** Every crop held for the paper, page by page. */
    @Synchronized
    fun allCrops(questionId: String): List<AnswerCrop> =
        pages(questionId).flatMap { page(questionId, it).orEmpty() }

    @Synchronized
    fun removePage(questionId: String, pageIndex: Int) {
        pageDir(questionId, pageIndex).deleteRecursively()
    }

    private fun paperDir(questionId: String) = File(root, PackStore.safe(questionId))

    private fun pageDir(questionId: String, pageIndex: Int): File {
        require(pageIndex >= 0) { "page index $pageIndex is negative" }
        return File(paperDir(questionId), "page_$pageIndex")
    }

    private data class StoredPage(
        val questionId: String,
        val pageIndex: Int,
        val crops: List<StoredCrop>
    )

    private data class StoredCrop(
        val box: String,
        val part: Int,
        val order: Int,
        val file: String,
        val quad: List<List<Double>>
    )

    private companion object {
        const val INDEX = "crops.json"
        val PAGE_DIR = Regex("page_(\\d{1,4})")
    }
}
