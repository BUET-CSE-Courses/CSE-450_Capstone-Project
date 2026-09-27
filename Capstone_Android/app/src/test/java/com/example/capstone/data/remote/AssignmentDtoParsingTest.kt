package com.example.capstone.data.remote

import com.example.capstone.data.local.PackStore
import com.example.capstone.data.repository.AssignmentRepository
import com.example.capstone.data.repository.PackImageRef
import com.example.capstone.data.repository.toExtractorLayout
import com.example.capstone.data.repository.toWorksheet
import com.example.capstone.extractor.Bbox
import com.example.capstone.extractor.Segment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * The assignment pack (`GET /api/student/assignments/{question_id}/pack`,
 * Script-Checker-Web-End feature/on-device-grading) against a fixture built
 * from `AssignmentPack` / `PackMarkers` / `PackBox` in backend/schemas.py.
 * The fixture is synthetic: its model answers are placeholders, not a key.
 */
class AssignmentDtoParsingTest {

    private val packJson = resourceText("webend/pack_v1.json")
    private val questionId = "5b0f6c1e-2d7a-4a3e-9c51-0f3a2b7d9e10"

    private lateinit var server: MockWebServer
    private lateinit var cacheDir: File
    private lateinit var repo: AssignmentRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        cacheDir = createTempDirectory("packs").toFile()
        repo = AssignmentRepository(apiFor(server), PackStore(cacheDir))
    }

    @After
    fun tearDown() {
        server.shutdown()
        cacheDir.deleteRecursively()
    }

    private fun pack() = repo.parsePack(packJson, questionId)

    @Test
    fun `pack parses the paper fields`() {
        val pack = pack()

        assertThat(pack.packVersion).isEqualTo(1)
        assertThat(pack.courseId).isEqualTo("c7d1a9e4-8b2f-4f60-a1d3-6e5b4c3a2f19")
        assertThat(pack.pageWidthPx).isEqualTo(1240)
        assertThat(pack.pageHeightPx).isEqualTo(1754)
        assertThat(pack.pageCount).isEqualTo(2)
        assertThat(pack.dpi).isEqualTo(150)
    }

    @Test
    fun `pack parses the served marker contract in row major order`() {
        val markers = pack().markers

        assertThat(markers.arucoDict).isEqualTo("DICT_4X4_50")
        assertThat(markers.markerSizePx).isEqualTo(60)
        assertThat(markers.markerMarginPx).isEqualTo(40)
        // 0 top-left, 1 top-right, 2 BOTTOM-left, 3 bottom-right. Not clockwise.
        assertThat(markers.centres["0"]).containsExactly(70, 70).inOrder()
        assertThat(markers.centres["1"]).containsExactly(1170, 70).inOrder()
        assertThat(markers.centres["2"]).containsExactly(70, 1684).inOrder()
        assertThat(markers.centres["3"]).containsExactly(1170, 1684).inOrder()
    }

    @Test
    fun `pack parses every box field, including segments and nulls`() {
        val boxes = pack().boxes

        assertThat(boxes.map { it.id })
            .containsExactly("ab_fixture_one", "ab_fixture_two", "ab_fixture_three").inOrder()
        val one = boxes[0]
        assertThat(one.label).isEqualTo("Q1(a)")
        assertThat(one.points).isEqualTo(2)
        assertThat(one.bbox).containsExactly(186, 334, 930, 90).inOrder()
        assertThat(one.segments).containsExactly(listOf(0, 186, 334, 930, 90))
        assertThat(one.questionText).isEqualTo("State Newton's second law.")
        assertThat(one.modelAnswerText).isNotEmpty()
        assertThat(one.modelAnswerImages).containsExactly("pack/images/model-answer/9a1f0c2e-7b3d-4e5f-8a6b-1c2d3e4f5a6b")
        assertThat(one.questionImages).containsExactly("pack/images/question/3e4f5a6b-1c2d-4e5f-8a6b-9a1f0c2e7b3d")
        assertThat(one.blockedReason).isNull()

        assertThat(boxes[1].segments!!.map { it[0] }).containsExactly(0, 1).inOrder()

        val three = boxes[2]
        assertThat(three.points).isNull()
        assertThat(three.segments).containsExactly(listOf(1, 186, 500, 930, 200))
        assertThat(three.blockedReason).startsWith("No marks set for this part")
    }

    @Test
    fun `an unknown pack version is refused`() {
        val future = packJson.replace("\"pack_version\": 1", "\"pack_version\": 2")

        val error = assertThrows(IllegalStateException::class.java) { repo.parsePack(future, questionId) }
        assertThat(error.message).contains("version")
    }

    @Test
    fun `a pack for another paper is refused`() {
        assertThrows(IllegalStateException::class.java) { repo.parsePack(packJson, "some-other-question") }
    }

    @Test
    fun `image refs accept only the two served shapes`() {
        assertThat(PackImageRef.parse("pack/images/model-answer/abc-123"))
            .isEqualTo(PackImageRef("model-answer", "abc-123"))
        assertThat(PackImageRef.parse("pack/images/question/x_1")).isEqualTo(PackImageRef("question", "x_1"))
        assertThat(PackImageRef.parse("https://evil.example/steal")).isNull()
        assertThat(PackImageRef.parse("pack/images/other/abc")).isNull()
        assertThat(PackImageRef.parse("pack/images/question/../../me")).isNull()
    }

    @Test
    fun `the served geometry reaches the extractor layout unchanged`() {
        val layout = pack().toExtractorLayout()!!

        assertThat(layout.externalQuestionId).isEqualTo(questionId)
        assertThat(layout.pageWidthPx).isEqualTo(1240)
        assertThat(layout.markers.map { it.id }).containsExactly(0, 1, 2, 3)
        assertThat(layout.arucoDictionary).isEqualTo("DICT_4X4_50")
        assertThat(layout.answerBoxes.map { it.externalAnswerBoxId })
            .containsExactly("ab_fixture_one", "ab_fixture_two", "ab_fixture_three").inOrder()
        assertThat(layout.answerBoxes.map { it.orderIndex }).containsExactly(0, 1, 2).inOrder()
        assertThat(layout.answerBoxes[0].segments.single().bbox.y).isEqualTo(334)
        assertThat(layout.answerBoxes[2].segments.single().pageIndex).isEqualTo(1)
    }

    @Test
    fun `a box over a page break keeps every segment, one part per page`() {
        // Finalize sets bbox/page_index to the FIRST segment only; the layout must
        // take segments, or part 2 would never be cropped.
        val split = pack().toExtractorLayout()!!.answerBoxes[1]

        assertThat(split.segments.map { it.pageIndex }).containsExactly(0, 1).inOrder()
        assertThat(split.segments[0].bbox).isEqualTo(Bbox(186, 1500, 930, 150))
        assertThat(split.segments[1].bbox).isEqualTo(Bbox(186, 124, 930, 250))
    }

    @Test
    fun `without segments, page_index and bbox are the only part, as get_page_segments does`() {
        val first = pack().boxes[0]
        val layout = pack().copy(boxes = listOf(first.copy(segments = null))).toExtractorLayout()!!

        assertThat(layout.answerBoxes.single().segments)
            .containsExactly(Segment(0, Bbox(186, 334, 930, 90)))
    }

    @Test
    fun `a malformed segment is passed on for the extractor to refuse, not repaired`() {
        val first = pack().boxes[0]
        val layout = pack().copy(boxes = listOf(first.copy(segments = listOf(listOf(0, 186, 334))))).toExtractorLayout()!!

        assertThat(layout.answerBoxes.single().segments.single().pageIndex).isEqualTo(-1)
    }

    @Test
    fun `worksheet keys each question by its answer box id`() {
        val worksheet = pack().toWorksheet()

        assertThat(worksheet.id).isEqualTo(questionId)
        assertThat(worksheet.externalQuestionId).isEqualTo(questionId)
        assertThat(worksheet.questions.map { it.externalAnswerBoxId })
            .containsExactly("ab_fixture_one", "ab_fixture_two", "ab_fixture_three").inOrder()
        assertThat(worksheet.questions[0].marks).isEqualTo(2)
        assertThat(worksheet.questions[2].isGradeable).isFalse()
    }

    @Test
    fun `download caches the pack and its images, and the cache survives the server`() = runBlocking {
        val imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/student/assignments/$questionId/pack" -> MockResponse().setBody(packJson)
                "/api/student/assignments/$questionId/pack/images/model-answer/9a1f0c2e-7b3d-4e5f-8a6b-1c2d3e4f5a6b",
                "/api/student/assignments/$questionId/pack/images/question/3e4f5a6b-1c2d-4e5f-8a6b-9a1f0c2e7b3d" ->
                    MockResponse().setBody(Buffer().write(imageBytes)).setHeader("Content-Type", "image/png")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val downloaded = repo.downloadPack(questionId).getOrThrow()

        assertThat(downloaded.missingImages).isEmpty()
        assertThat(server.requestCount).isEqualTo(3)
        repeat(3) { assertThat(server.takeRequest().getHeader("Authorization")).startsWith("Bearer ") }
        server.shutdown()

        val cached = repo.cachedPack(questionId)!!
        assertThat(cached.pack).isEqualTo(downloaded.pack)
        assertThat(cached.missingImages).isEmpty()
        assertThat(repo.packImage(questionId, "pack/images/model-answer/9a1f0c2e-7b3d-4e5f-8a6b-1c2d3e4f5a6b"))
            .isEqualTo(imageBytes)
        assertThat(File(cacheDir, "$questionId/pack.json").readText()).isEqualTo(packJson)
        // Two pages and a box over the page break: the scan screen takes it now.
        val worksheet = repo.worksheetFor(questionId).getOrThrow()
        assertThat(worksheet.pageCount).isEqualTo(2)
        assertThat(worksheet.layout!!.pagesWithAnswers).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `a failed image is reported, not fatal`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.endsWith("/pack")) MockResponse().setBody(packJson)
                else MockResponse().setResponseCode(404).setBody("""{"detail":"Image not found"}""")
        }

        val downloaded = repo.downloadPack(questionId).getOrThrow()

        assertThat(downloaded.missingImages).hasSize(2)
        assertThat(repo.cachedPack(questionId)!!.missingImages).hasSize(2)
    }

    @Test
    fun `a failed download leaves no pack behind`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Assignment not found"}"""))

        val result = repo.downloadPack(questionId)

        assertThat(result.exceptionOrNull()!!.userMessage("x")).isEqualTo("404: Assignment not found")
        assertThat(repo.cachedPack(questionId)).isNull()
        assertThat(repo.worksheetFor(questionId).isFailure).isTrue()
    }
}
