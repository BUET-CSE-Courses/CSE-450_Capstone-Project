package com.example.capstone.data.repository

import com.example.capstone.data.remote.ApiService
import com.example.capstone.data.remote.ExtractionResultDto
import com.example.capstone.data.remote.HandInResponseDto
import com.example.capstone.data.remote.OnDeviceGradesDto
import com.example.capstone.data.remote.ReevaluationRequestDto
import com.example.capstone.data.remote.ReevaluationResponseDto
import com.example.capstone.domain.model.StudentAssignment
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** A photo ready to upload: its bytes and their media type ("image/jpeg" or "image/png"). */
class UploadImage(val bytes: ByteArray, val mediaType: String) {
    /** The form part's file name. The server sniffs PDF and TIFF from it, so it names the type. */
    val fileName: String
        get() = if (mediaType == "image/png") "page.png" else "page.jpg"
}

/**
 * The student's script on the web end: pages up, then hand-in
 * (Script-Checker-Web-End `backend/routers/submissions.py`).
 *
 * What the server does with each page (its own crops, for the teacher and for fallback) is
 * reported back and shown, never downloaded: the phone grades crops it cuts itself.
 */
class SubmissionRepository(private val api: ApiService) {

    /**
     * `POST /api/submissions`, modality "photo", with an explicit `page_index`.
     *
     * The page number is the one the student picked, which the printed footer
     * "Page N of M" states. Sent as `page_index`, not `page_index_hint`: the phone crops
     * that page's segments from its own copy of the photo, so the server must file it under
     * the same page rather than one it reads off the QR codes. An explicit index replaces an
     * earlier upload of that page, which is how a retake works. The server still refuses a
     * photo of a different paper (`identify_page`'s "wrong_paper").
     */
    suspend fun uploadPage(
        questionId: String,
        pageIndex: Int,
        submissionId: String?,
        image: UploadImage
    ): Result<ExtractionResultDto> = runCatching {
        api.uploadPage(
            questionId = text(questionId),
            modality = text(MODALITY_PHOTO),
            pageIndex = text(pageIndex.toString()),
            pageIndexHint = null,
            submissionId = submissionId?.let(::text),
            image = MultipartBody.Part.createFormData(
                "image",
                image.fileName,
                image.bytes.toRequestBody(image.mediaType.toMediaType())
            )
        )
    }

    /** `GET /api/submissions/{id}`: the pages the server holds. */
    suspend fun manifest(submissionId: String): Result<ExtractionResultDto> = runCatching {
        api.submission(submissionId)
    }

    /** `DELETE /api/submissions/{id}/pages/{page_index}`. */
    suspend fun deletePage(submissionId: String, pageIndex: Int): Result<ExtractionResultDto> = runCatching {
        api.deletePage(submissionId, pageIndex)
    }

    /** `POST /api/submissions/{id}/submit`. */
    suspend fun handIn(submissionId: String): Result<HandInResponseDto> = runCatching {
        api.handIn(submissionId)
    }

    /**
     * This student's row for one paper from `GET /api/student/assignments?course_id=`:
     * whether a submission exists, how many pages it has, whether it is handed in.
     * Null when the list does not contain the paper.
     */
    suspend fun myAssignment(courseId: String, questionId: String): Result<StudentAssignment?> = runCatching {
        api.assignments(courseId).firstOrNull { it.questionId == questionId }?.toDomain()
    }

    /** `GET /api/student/submissions/{id}/grades`: this student's marks, provisional before release. */
    suspend fun myGrades(submissionId: String): Result<OnDeviceGradesDto> = runCatching {
        api.myGrades(submissionId)
    }

    /**
     * `POST /api/student/submissions/{id}/re-evaluation`. [answerBoxIds] null asks about the
     * whole paper. A blank message is left out; a long one is cut to the server's limit.
     */
    suspend fun requestReevaluation(
        submissionId: String,
        answerBoxIds: List<String>?,
        message: String
    ): Result<ReevaluationResponseDto> = runCatching {
        api.requestReevaluation(
            submissionId,
            ReevaluationRequestDto(
                answerBoxIds = answerBoxIds?.takeIf { it.isNotEmpty() },
                message = message.trim().take(ReevaluationRequestDto.MAX_MESSAGE).ifBlank { null }
            )
        )
    }

    private fun text(value: String): RequestBody = value.toRequestBody(TEXT)

    companion object {
        /** `create_submission` takes "photo" or "scanner". A phone takes photos. */
        const val MODALITY_PHOTO = "photo"
        private val TEXT = "text/plain".toMediaType()
    }
}
