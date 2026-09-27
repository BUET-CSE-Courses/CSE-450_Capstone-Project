package com.example.capstone.data.remote

import com.example.capstone.data.repository.SubmissionRepository
import com.example.capstone.data.repository.UploadImage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Page upload and hand-in against the web end's own shapes: the form fields of
 * `create_submission` and the `ExtractionResult` / `PageExtractionResult` / `CropInfo`
 * models (Script-Checker-Web-End `backend/routers/submissions.py`, `backend/schemas.py`).
 */
class SubmissionApiTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: SubmissionRepository

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02)

    /** One page as the server's ExtractionResult carries it. */
    private val manifest = """
        {"submission_id":"sub-1","question_id":"q-1","modality":"photo","pages":[
          {"page_index":0,"markers_detected":"4/4","transform_type":"homography",
           "crops":[{"answer_box_id":"ab_1","qr_check":"pass","warped_bbox":[10,20,300,400],"part":0,"registration":"global"},
                    {"answer_box_id":"ab_2","qr_check":"absent","warped_bbox":[10,500,300,900],"part":0,"registration":"global"}],
           "image_resolution":"3000x4000","image_dpi":null},
          {"page_index":1,"markers_detected":"3/4","transform_type":"none","crops":[],
           "image_resolution":"3000x4000","image_dpi":null,
           "error":"Only 3/4 ArUco markers detected on page 1. Flag for manual review."}
        ]}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        repo = SubmissionRepository(apiFor(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** The multipart form as (field name -> value) for text parts. */
    private fun formFields(request: RecordedRequest): Map<String, String> {
        val body = request.body.readUtf8()
        return Regex("""name="([a-z_]+)"(?:; filename="[^"]*")?\r\n(?:[^\r\n]+\r\n)*\r\n([^\r\n]*)""")
            .findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `the first page is posted with question, modality photo and an explicit page index`() = runBlocking {
        server.enqueue(MockResponse().setBody(manifest))

        val result = repo.uploadPage("q-1", pageIndex = 0, submissionId = null, image = UploadImage(jpeg, "image/jpeg"))
            .getOrThrow()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/api/submissions")
        assertThat(request.getHeader("Content-Type")).startsWith("multipart/form-data")
        val fields = formFields(request)
        assertThat(fields["question_id"]).isEqualTo("q-1")
        assertThat(fields["modality"]).isEqualTo("photo")
        assertThat(fields["page_index"]).isEqualTo("0")
        // No submission yet, and the page number is explicit rather than a hint.
        assertThat(fields).doesNotContainKey("submission_id")
        assertThat(fields).doesNotContainKey("page_index_hint")
        assertThat(result.submissionId).isEqualTo("sub-1")
    }

    @Test
    fun `later pages carry the submission id, and the image part is a named jpeg`() = runBlocking {
        server.enqueue(MockResponse().setBody(manifest))

        repo.uploadPage("q-1", pageIndex = 1, submissionId = "sub-1", image = UploadImage(jpeg, "image/jpeg")).getOrThrow()

        val request = server.takeRequest()
        val raw = request.body.clone().readUtf8()
        val fields = formFields(request)
        assertThat(fields["submission_id"]).isEqualTo("sub-1")
        assertThat(fields["page_index"]).isEqualTo("1")
        // The server sniffs PDF/TIFF from the file name and type, so both say JPEG.
        assertThat(raw).contains("name=\"image\"; filename=\"page.jpg\"")
        assertThat(raw).contains("Content-Type: image/jpeg")
        assertThat(request.getHeader("Authorization")).startsWith("Bearer ")
    }

    @Test
    fun `the extraction result parses every page, crop and error`() = runBlocking {
        server.enqueue(MockResponse().setBody(manifest))

        val result = repo.uploadPage("q-1", 0, null, UploadImage(jpeg, "image/jpeg")).getOrThrow()

        assertThat(result.pages.map { it.pageIndex }).containsExactly(0, 1).inOrder()
        val first = result.pages[0]
        assertThat(first.markersDetected).isEqualTo("4/4")
        assertThat(first.transformType).isEqualTo("homography")
        assertThat(first.crops.map { it.answerBoxId to it.qrCheck })
            .containsExactly("ab_1" to "pass", "ab_2" to "absent").inOrder()
        assertThat(first.crops[0].warpedBbox).containsExactly(10, 20, 300, 400).inOrder()
        assertThat(first.error).isNull()
        assertThat(result.pages[1].error).startsWith("Only 3/4 ArUco markers")
    }

    @Test
    fun `a blurred photo's 422 reads as the server's own sentence`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(422)
                .setBody("""{"detail":"This photo looks blurred. Hold the phone steady and take it again."}""")
        )

        val error = repo.uploadPage("q-1", 0, null, UploadImage(jpeg, "image/jpeg")).exceptionOrNull()!!

        assertThat(error.userMessage("x"))
            .isEqualTo("422: This photo looks blurred. Hold the phone steady and take it again.")
    }

    @Test
    fun `hand in posts to submit and reads the time`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"submission_id":"sub-1","submitted_at":"2026-09-25T10:00:00Z"}"""))

        val handedIn = repo.handIn("sub-1").getOrThrow()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/api/submissions/sub-1/submit")
        assertThat(handedIn.submittedAt).isEqualTo("2026-09-25T10:00:00Z")
    }

    @Test
    fun `hand in with no pages is the server's 409`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"Add at least one page before handing this in"}"""))

        val error = repo.handIn("sub-1").exceptionOrNull()!!

        assertThat(error.userMessage("x")).isEqualTo("409: Add at least one page before handing this in")
    }

    @Test
    fun `manifest and page removal use the submission routes`() = runBlocking {
        server.enqueue(MockResponse().setBody(manifest))
        server.enqueue(MockResponse().setBody(manifest))

        repo.manifest("sub-1").getOrThrow()
        repo.deletePage("sub-1", 1).getOrThrow()

        assertThat(server.takeRequest().path).isEqualTo("/api/submissions/sub-1")
        val delete = server.takeRequest()
        assertThat(delete.method).isEqualTo("DELETE")
        assertThat(delete.path).isEqualTo("/api/submissions/sub-1/pages/1")
    }

    @Test
    fun `my assignment finds this paper's row in the course list`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"question_id":"q-other","course_id":"c-1","title":"Other","total_marks":5,"page_count":1,
                     "finalized_at":null,"submission_id":null,"submitted_pages":0,"handed_in":false,
                     "submission_status":null,"released":false,"earned":null,"max_score":null},
                    {"question_id":"q-1","course_id":"c-1","title":"Quiz","total_marks":10,"page_count":2,
                     "finalized_at":null,"submission_id":"sub-1","submitted_pages":1,"handed_in":false,
                     "submission_status":"ungraded","released":false,"earned":null,"max_score":null}]"""
            )
        )

        val mine = repo.myAssignment("c-1", "q-1").getOrThrow()!!

        assertThat(server.takeRequest().path).isEqualTo("/api/student/assignments?course_id=c-1")
        assertThat(mine.submissionId).isEqualTo("sub-1")
        assertThat(mine.submittedPages).isEqualTo(1)
        assertThat(mine.handedIn).isFalse()
    }
}
