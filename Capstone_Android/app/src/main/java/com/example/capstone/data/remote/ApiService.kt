package com.example.capstone.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The web end's routes, relative to `BuildConfig.BASE_URL` (".../api/").
 * Every route needs the Entra bearer token, which [com.example.capstone.data.auth.AuthInterceptor]
 * adds. Paths are taken from Script-Checker-Web-End `backend/routers/`.
 */
interface ApiService {
    /** `routers/me.py` get_me. */
    @GET("me")
    suspend fun me(): UserDto

    /** `routers/courses.py` list_my_courses: courses taught and courses taken. */
    @GET("courses")
    suspend fun courses(): List<CourseDto>

    /** `routers/courses.py` join_course. 404 bad code; 409 archived or you teach it. */
    @POST("courses/join")
    suspend fun joinCourse(@Body body: JoinRequest): CourseDto

    /** `routers/student.py` list_assignments. 404 if not enrolled. */
    @GET("student/assignments")
    suspend fun assignments(@Query("course_id") courseId: String): List<StudentAssignmentDto>

    /**
     * `routers/on_device.py` get_assignment_pack (feature/on-device-grading).
     * Raw, so the exact bytes the server sent are what gets cached.
     */
    @GET("student/assignments/{question_id}/pack")
    suspend fun pack(@Path("question_id") questionId: String): ResponseBody

    /** `routers/on_device.py` get_pack_image. [kind] is "model-answer" or "question". */
    @GET("student/assignments/{question_id}/pack/images/{kind}/{image_id}")
    suspend fun packImage(
        @Path("question_id") questionId: String,
        @Path("kind") kind: String,
        @Path("image_id") imageId: String
    ): ResponseBody

    /**
     * `routers/submissions.py` create_submission: one photographed page, as multipart form
     * fields. A null part is left out of the form.
     *
     * - [pageIndex] explicit: this page, replacing an earlier upload of it; the server skips
     *   reading the page number off the QR codes but still refuses a different paper.
     * - [pageIndexHint]: a page number the server's QR reading overrides.
     * - [submissionId]: the open submission; omitted for the first page (the server also
     *   finds a student's open one by itself).
     *
     * 422 for a blurred or dark photo, a different paper, or unreadable codes with no page
     * number; 409 once handed in or marked; 400 for a paper that is not finalized.
     */
    @Multipart
    @POST("submissions")
    suspend fun uploadPage(
        @Part("question_id") questionId: RequestBody,
        @Part("modality") modality: RequestBody,
        @Part("page_index") pageIndex: RequestBody?,
        @Part("page_index_hint") pageIndexHint: RequestBody?,
        @Part("submission_id") submissionId: RequestBody?,
        @Part image: MultipartBody.Part
    ): ExtractionResultDto

    /**
     * `routers/submissions.py` get_submission: the stored manifest. Every committed
     * submission has one: the upload route writes it in the same transaction as the row.
     */
    @GET("submissions/{submission_id}")
    suspend fun submission(@Path("submission_id") submissionId: String): ExtractionResultDto

    /** `routers/submissions.py` delete_submission_page. Before hand-in only; 404 for no such page. */
    @DELETE("submissions/{submission_id}/pages/{page_index}")
    suspend fun deletePage(
        @Path("submission_id") submissionId: String,
        @Path("page_index") pageIndex: Int
    ): ExtractionResultDto

    /**
     * `routers/submissions.py` hand_in_submission. After this the pages are fixed.
     * 409 with no pages, or once handed in or marked. Notifies the teacher.
     */
    @POST("submissions/{submission_id}/submit")
    suspend fun handIn(@Path("submission_id") submissionId: String): HandInResponseDto

    /**
     * `routers/on_device.py` start_on_device_run: a new run token (replacing the student's
     * own unposted run, if any) and the boxes to post. 409 when not handed in, released,
     * already graded, a teacher's run is going, or the server is re-marking a posted run.
     * Rate-limited (`LLM_LIMIT`).
     */
    @POST("student/submissions/{submission_id}/on-device/start")
    suspend fun startOnDeviceRun(@Path("submission_id") submissionId: String): OnDeviceRunStartedDto

    /**
     * `routers/on_device.py` post_on_device_results: every eligible box in one call. 201 on
     * the first save, 200 (nothing changed) on a repeat with the same token. 409 for a
     * superseded, expired or no-longer-active run; 400 (`detail.message` plus
     * `missing_box_ids` / `unknown_box_ids` / `duplicate_box_ids` / `answer_box_id`) for a
     * post that does not fit the paper.
     */
    @POST("student/submissions/{submission_id}/on-device/results")
    suspend fun postOnDeviceResults(
        @Path("submission_id") submissionId: String,
        @Body body: OnDeviceResultsInDto
    ): OnDeviceGradesDto

    /** `routers/on_device.py` get_my_grades: the student's own marks, provisional before release. */
    @GET("student/submissions/{submission_id}/grades")
    suspend fun myGrades(@Path("submission_id") submissionId: String): OnDeviceGradesDto

    /**
     * `routers/on_device.py` request_reevaluation: tells the teacher through the bell.
     * 409 until the paper is graded. Rate-limited to 5 an hour.
     */
    @POST("student/submissions/{submission_id}/re-evaluation")
    suspend fun requestReevaluation(
        @Path("submission_id") submissionId: String,
        @Body body: ReevaluationRequestDto
    ): ReevaluationResponseDto
}
