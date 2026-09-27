package com.example.capstone.data.remote

import com.example.capstone.data.local.PackStore
import com.example.capstone.data.repository.AssignmentRepository
import com.example.capstone.data.repository.CourseRepository
import com.example.capstone.data.repository.toDomain
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import kotlin.io.path.createTempDirectory

/**
 * Routes, bodies and parsing against the web end's own shapes
 * (Script-Checker-Web-End backend/schemas.py: UserOut, CourseOut,
 * JoinRequest, StudentAssignment).
 */
class WebEndApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ApiService

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = apiFor(server)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun course(id: String, role: String) = """
        {"id":"$id","title":"Course $id","join_code":"ABC$id","teacher_id":"t1",
         "teacher_name":"Dr Teacher","archived":false,"created_at":"2026-09-01T10:00:00",
         "student_count":12,"my_role":"$role"}
    """.trimIndent()

    @Test
    fun `me parses UserOut`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"u1","email":"s@example.com","display_name":"Sam Student","role":"teacher",
                   "can_create_courses":true,"institution":null,"has_avatar":false}"""
            )
        )

        val me = api.me().toDomain()

        assertThat(me.displayName).isEqualTo("Sam Student")
        assertThat(me.role).isEqualTo("teacher")
        assertThat(me.isStudent).isFalse()
    }

    @Test
    fun `my courses keeps only the ones I take`() = runBlocking {
        server.enqueue(MockResponse().setBody("[${course("1", "teacher")},${course("2", "student")}]"))

        val courses = CourseRepository(api).myCourses().getOrThrow()

        assertThat(server.takeRequest().path).isEqualTo("/api/courses")
        assertThat(courses.map { it.id }).containsExactly("2")
        assertThat(courses[0].teacherName).isEqualTo("Dr Teacher")
        assertThat(courses[0].studentCount).isEqualTo(12)
    }

    @Test
    fun `join posts JoinRequest with the trimmed code`() = runBlocking {
        server.enqueue(MockResponse().setBody(course("9", "student")))

        val joined = CourseRepository(api).join("  abc123 ").getOrThrow()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/api/courses/join")
        assertThat(request.body.readUtf8()).isEqualTo("""{"join_code":"abc123"}""")
        assertThat(joined.id).isEqualTo("9")
    }

    @Test
    fun `a bad join code reports the server's detail`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(404).setBody("""{"detail":"No course found with that join code"}""")
        )

        val error = CourseRepository(api).join("NOPE").exceptionOrNull()

        assertThat(error).isInstanceOf(HttpException::class.java)
        assertThat(error!!.userMessage("http://x/api/")).isEqualTo("404: No course found with that join code")
    }

    @Test
    fun `a blank join code is refused before any request`() = runBlocking {
        val result = CourseRepository(api).join("   ")

        assertThat(result.isFailure).isTrue()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `assignments parse StudentAssignment with status`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[
                  {"question_id":"q1","course_id":"c1","title":"Quiz 1","total_marks":10,"page_count":2,
                   "finalized_at":"2026-09-20T08:00:00","submission_id":null,"submitted_pages":0,
                   "handed_in":false,"submission_status":null,"released":false,"earned":null,"max_score":null},
                  {"question_id":"q2","course_id":"c1","title":null,"total_marks":5,"page_count":1,
                   "finalized_at":"2026-09-21T08:00:00","submission_id":"s2","submitted_pages":1,
                   "handed_in":true,"submission_status":"grading","released":false,"earned":null,"max_score":null},
                  {"question_id":"q3","course_id":"c1","title":"Quiz 3","total_marks":8,"page_count":1,
                   "finalized_at":null,"submission_id":"s3","submitted_pages":1,
                   "handed_in":true,"submission_status":"graded","released":true,"earned":6.5,"max_score":8},
                  {"question_id":"q4","course_id":"c1","title":"Quiz 4","total_marks":4,"page_count":3,
                   "finalized_at":null,"submission_id":"s4","submitted_pages":2,
                   "handed_in":false,"submission_status":"ungraded","released":false,"earned":null,"max_score":null}
                ]"""
            )
        )
        val repo = AssignmentRepository(api, PackStore(createTempDirectory().toFile()))

        val list = repo.assignments("c1").getOrThrow()

        assertThat(server.takeRequest().path).isEqualTo("/api/student/assignments?course_id=c1")
        assertThat(list.map { it.questionId }).containsExactly("q1", "q2", "q3", "q4").inOrder()
        assertThat(list[0].status).isEqualTo("Not started")
        assertThat(list[1].title).isEqualTo("Untitled paper")
        assertThat(list[1].status).isEqualTo("Handed in, being marked")
        assertThat(list[2].status).isEqualTo("Marks released: 6.5 / 8")
        assertThat(list[3].status).isEqualTo("2 of 3 page(s) uploaded, not handed in")
    }

    @Test
    fun `error detail handles all three FastAPI shapes`() {
        assertThat(parseErrorDetail("""{"detail":"That course is archived"}""")).isEqualTo("That course is archived")
        assertThat(
            parseErrorDetail(
                """{"detail":[{"loc":["body","join_code"],"msg":"String should have at most 32 characters","type":"x"}]}"""
            )
        ).isEqualTo("join_code: String should have at most 32 characters")
        assertThat(parseErrorDetail("""{"detail":{"message":"Some boxes are missing","missing_box_ids":["a"]}}"""))
            .isEqualTo("Some boxes are missing")
        assertThat(parseErrorDetail("<html>502</html>")).isNull()
        assertThat(parseErrorDetail(null)).isNull()
    }
}
