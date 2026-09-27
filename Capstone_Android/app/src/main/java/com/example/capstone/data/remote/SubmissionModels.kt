package com.example.capstone.data.remote

import com.google.gson.annotations.SerializedName

/**
 * Wire shapes for uploading pages and handing in, named as in Script-Checker-Web-End
 * `backend/schemas.py` (`ExtractionResult`, `PageExtractionResult`, `CropInfo`) and
 * `backend/routers/submissions.py` (`hand_in_submission`).
 *
 * These describe the SERVER's own extraction of each page: its crops are for the teacher and
 * for fallback (plan decision 9). The phone never downloads them; it crops its own photo.
 */

/**
 * `ExtractionResult`: the reply to `POST /api/submissions`, and the manifest
 * `GET /api/submissions/{id}` and `DELETE /api/submissions/{id}/pages/{page_index}` return.
 * [pages] is every page of the submission so far, sorted by page index, not just the one
 * just uploaded.
 */
data class ExtractionResultDto(
    @SerializedName("submission_id")
    val submissionId: String,
    @SerializedName("question_id")
    val questionId: String,
    val modality: String,
    val pages: List<PageExtractionResultDto> = emptyList()
)

/** `PageExtractionResult`: what the server's extractor made of one page. */
data class PageExtractionResultDto(
    @SerializedName("page_index")
    val pageIndex: Int,
    /** e.g. "4/4", or "N/A". */
    @SerializedName("markers_detected")
    val markersDetected: String,
    /** "homography", "affine", "identity" or "none". */
    @SerializedName("transform_type")
    val transformType: String,
    val crops: List<CropInfoDto> = emptyList(),
    @SerializedName("image_resolution")
    val imageResolution: String? = null,
    @SerializedName("image_dpi")
    val imageDpi: Int? = null,
    /** Set when the server could not crop the page, e.g. "Only 3/4 ArUco markers detected ...". */
    val error: String? = null
)

/** `CropInfo`: one server crop, without its pixels. */
data class CropInfoDto(
    @SerializedName("answer_box_id")
    val answerBoxId: String,
    /** "pass", "fail" or "absent": whether the box's printed QR code read, and matched. */
    @SerializedName("qr_check")
    val qrCheck: String,
    /** `[x1, y1, x2, y2]` in the uploaded image's pixels. */
    @SerializedName("warped_bbox")
    val warpedBbox: List<Int>,
    val part: Int = 0,
    /** "local" or "global". */
    val registration: String = "global"
)

/** `POST /api/submissions/{id}/submit`'s reply. */
data class HandInResponseDto(
    @SerializedName("submission_id")
    val submissionId: String,
    @SerializedName("submitted_at")
    val submittedAt: String
)
