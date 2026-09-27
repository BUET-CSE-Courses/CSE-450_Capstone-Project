package com.example.capstone.data.local

import com.example.capstone.extractor.AnswerCrop
import com.example.capstone.extractor.PointPx
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** The phone's crops per page, on disk: what the grading screen reads after a restart. */
class PagePhotoStoreTest {

    private val root: File = createTempDirectory("scans").toFile()
    private val store = PagePhotoStore(root)
    private val questionId = "q-1"

    private fun crop(box: String, page: Int, part: Int = 0, order: Int = 0, byte: Byte = 1) = AnswerCrop(
        externalQuestionId = questionId,
        externalAnswerBoxId = box,
        part = part,
        pageIndex = page,
        orderIndex = order,
        png = byteArrayOf(byte, 2, 3),
        imageQuad = listOf(PointPx(1.0, 2.0), PointPx(3.0, 2.0), PointPx(3.0, 4.0), PointPx(1.0, 4.0))
    )

    @Test
    fun `a saved page reads back field for field, from a new store`() {
        store.savePage(questionId, 1, listOf(crop("ab_split", 1, part = 1, order = 4)))

        val back = PagePhotoStore(root).page(questionId, 1)!!.single()

        assertThat(back.externalAnswerBoxId).isEqualTo("ab_split")
        assertThat(back.part).isEqualTo(1)
        assertThat(back.pageIndex).isEqualTo(1)
        assertThat(back.orderIndex).isEqualTo(4)
        assertThat(back.png).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(back.imageQuad[2]).isEqualTo(PointPx(3.0, 4.0))
    }

    @Test
    fun `saving a page again replaces it whole`() {
        store.savePage(questionId, 0, listOf(crop("ab_1", 0), crop("ab_2", 0, order = 1)))
        store.savePage(questionId, 0, listOf(crop("ab_1", 0, byte = 9)))

        val back = store.page(questionId, 0)!!
        assertThat(back.map { it.externalAnswerBoxId }).containsExactly("ab_1")
        assertThat(back.single().png[0]).isEqualTo(9.toByte())
    }

    @Test
    fun `all crops come page by page, and a removed page is gone`() {
        store.savePage(questionId, 1, listOf(crop("ab_split", 1, part = 1)))
        store.savePage(questionId, 0, listOf(crop("ab_1", 0), crop("ab_split", 0)))

        assertThat(store.pages(questionId)).containsExactly(0, 1).inOrder()
        assertThat(store.allCrops(questionId).map { it.externalAnswerBoxId to it.part })
            .containsExactly("ab_1" to 0, "ab_split" to 0, "ab_split" to 1).inOrder()

        store.removePage(questionId, 0)
        assertThat(store.page(questionId, 0)).isNull()
        assertThat(store.pages(questionId)).containsExactly(1)
    }

    @Test
    fun `a page with no crops at all is still a saved page`() {
        store.savePage(questionId, 2, emptyList())

        assertThat(store.page(questionId, 2)).isEmpty()
    }

    @Test
    fun `crops for another page are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            store.savePage(questionId, 0, listOf(crop("ab_1", page = 1)))
        }
    }

    @Test
    fun `an id that is not a plain id never becomes a path`() {
        assertThrows(IllegalArgumentException::class.java) { store.page("../x", 0) }
    }
}
