package com.example.capstone.data.remote

import com.google.gson.annotations.SerializedName

/**
 * The assignment pack: `GET /api/student/assignments/{question_id}/pack`,
 * from Script-Checker-Web-End branch `feature/on-device-grading`
 * (`backend/routers/on_device.py`; shapes `AssignmentPack`, `PackMarkers` and
 * `PackBox` in `backend/schemas.py`).
 *
 * **It carries the answer key** ([PackBoxDto.modelAnswerText] and the
 * model-answer images). That is an accepted demo risk (plan decision 3). The
 * app caches it outside backups and never shows it on screen.
 *
 * The app computes none of the geometry. The marker centres in particular
 * come from the server's constants; a mismatch does not fail loudly, it
 * silently displaces every crop while still reporting four markers found.
 */
data class AssignmentPackDto(
    /** Shape version. The app refuses one it does not know ([SUPPORTED_PACK_VERSION]). */
    @SerializedName("pack_version")
    val packVersion: Int,
    @SerializedName("question_id")
    val questionId: String,
    @SerializedName("course_id")
    val courseId: String,
    val title: String?,
    @SerializedName("page_w_px")
    val pageWidthPx: Int?,
    @SerializedName("page_h_px")
    val pageHeightPx: Int?,
    @SerializedName("page_count")
    val pageCount: Int?,
    val dpi: Int,
    val markers: PackMarkersDto,
    /** Sorted by [PackBoxDto.orderIndex] on the server. */
    val boxes: List<PackBoxDto>
) {
    companion object {
        const val SUPPORTED_PACK_VERSION = 1
    }
}

/**
 * Where the four registration markers sit on the canonical page.
 *
 * [centres] is keyed by marker id as a string. Ids are row-major:
 * 0 top-left, 1 top-right, 2 BOTTOM-left, 3 bottom-right - not clockwise.
 * Empty when the paper has no page size.
 */
data class PackMarkersDto(
    @SerializedName("aruco_dict")
    val arucoDict: String,
    @SerializedName("marker_size_px")
    val markerSizePx: Int,
    @SerializedName("marker_margin_px")
    val markerMarginPx: Int,
    val centres: Map<String, List<Int>>
)

/**
 * One answer box. [bbox] is `[x, y, w, h]` on page [pageIndex], the full
 * border box; [segments] is `[[page, x, y, w, h], ...]`, one crop ("part")
 * per segment, for a box that runs over a page break.
 */
data class PackBoxDto(
    val id: String,
    val label: String?,
    val points: Int?,
    @SerializedName("order_index")
    val orderIndex: Int,
    @SerializedName("page_index")
    val pageIndex: Int?,
    val bbox: List<Int>?,
    val segments: List<List<Int>>?,
    @SerializedName("question_text")
    val questionText: String,
    /** THE ANSWER KEY. Never displayed. */
    @SerializedName("model_answer_text")
    val modelAnswerText: String,
    /** Relative to the pack URL: "pack/images/model-answer/<id>". */
    @SerializedName("model_answer_images")
    val modelAnswerImages: List<String>,
    /** Relative to the pack URL: "pack/images/question/<id>". */
    @SerializedName("question_images")
    val questionImages: List<String>,
    /** The server's `build_grading_items` reason, or null when the box can be marked. */
    @SerializedName("blocked_reason")
    val blockedReason: String?
)
