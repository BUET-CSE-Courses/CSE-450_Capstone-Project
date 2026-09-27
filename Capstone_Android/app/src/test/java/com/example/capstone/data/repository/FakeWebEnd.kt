package com.example.capstone.data.repository

import com.example.capstone.data.auth.AuthInterceptor
import com.example.capstone.data.remote.ApiService
import com.example.capstone.data.remote.FakeTokens
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * The web end's on-device routes, reduced to the rules the app depends on. Every rule is
 * taken from Script-Checker-Web-End `backend/routers/on_device.py` and
 * `backend/services/on_device.py` (feature/on-device-grading):
 *
 * - start: 409 when graded, queued, a teacher's run (grading without a phone start), or a
 *   posted run still re-marking; otherwise a new token, status "grading".
 * - results: 409 on a token mismatch; **200 with the current marks when this token already
 *   posted**; 409 when no longer active or expired; 400 `missing_box_ids`; then one save,
 *   pending rows when fallback is available, "Fallback unavailable" when not.
 * - grades: the student's own marks.
 */
class FakeWebEnd(
    val submissionId: String,
    /** (box id, label, points) in order_index order. */
    val boxes: List<Triple<String, String, Int>>
) : Dispatcher() {

    data class Row(
        var score: Double?,
        var feedback: String?,
        var provider: String,
        var needsReview: Boolean,
        var reason: String?
    )

    enum class Fault {
        /** Drop the connection before the server does anything. */
        OFFLINE,

        /** 503, nothing done. */
        UNAVAILABLE,

        /** Do the work, then drop the connection: the reply is lost. */
        LOSE_REPLY
    }

    var status = "ungraded"
    var token: String? = null
    var startedAt: String? = null
    var postedAt: String? = null
    var released = false
    var selfHostedLlm = false
    var protectedIds = emptySet<String>()
    val rows = linkedMapOf<String, Row>()

    val tokensIssued = mutableListOf<String>()
    val postBodies = mutableListOf<JsonObject>()
    var saves = 0
    val startFaults = ArrayDeque<Fault>()
    val postFaults = ArrayDeque<Fault>()
    val gradesFaults = ArrayDeque<Fault>()

    /** The lease runs out before the next results post arrives. */
    var expireBeforeNextPost = false

    private val base = "/api/student/submissions/$submissionId"

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        return when {
            request.method == "POST" && path == "$base/on-device/start" -> fault(startFaults) { start() }
            request.method == "POST" && path == "$base/on-device/results" -> {
                val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
                fault(postFaults) {
                    postBodies += body
                    results(body)
                }
            }
            request.method == "GET" && path == "$base/grades" -> fault(gradesFaults) { json(200, payload()) }
            else -> MockResponse().setResponseCode(404).setBody("""{"detail":"Not Found"}""")
        }
    }

    private fun fault(queue: ArrayDeque<Fault>, handle: () -> MockResponse): MockResponse =
        when (queue.removeFirstOrNull()) {
            Fault.OFFLINE -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            Fault.UNAVAILABLE -> MockResponse().setResponseCode(503).setBody("""{"detail":"Service Unavailable"}""")
            Fault.LOSE_REPLY -> handle().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            null -> handle()
        }

    private fun start(): MockResponse {
        if (released) return conflict("These marks have already been released")
        if (status == "graded") return conflict("This has already been graded. Ask for re-evaluation instead")
        if (status == "queued" || (status == "grading" && startedAt == null)) {
            return conflict("Your teacher is grading this on the website")
        }
        if (status == "grading" && postedAt != null) {
            return conflict("The server is still re-marking some answers from this run")
        }
        val t = "token-${tokensIssued.size + 1}"
        tokensIssued += t
        token = t
        startedAt = "2026-09-25T10:00:0${tokensIssued.size}"
        postedAt = null
        status = "grading"
        val eligible = boxes.map { it.first }.filter { it !in protectedIds }
        return json(
            200,
            mapOf(
                "run_token" to t,
                "started_at" to startedAt,
                "lease_expires_at" to "2026-09-25T10:15:00",
                "eligible_box_ids" to eligible,
                "protected_box_ids" to protectedIds.toList()
            )
        )
    }

    private fun results(body: JsonObject): MockResponse {
        if (expireBeforeNextPost) {
            // expire_stale_runs: an expired run becomes failed and loses its token.
            expireBeforeNextPost = false
            status = "failed"
            token = null
            startedAt = null
        }
        val posted = body.get("run_token").asString
        if (token == null || posted != token) return conflict("This grading run was superseded or has expired")
        if (postedAt != null) return json(200, payload() + ("fallback" to fallbackState()))
        if (!(status == "grading" && startedAt != null)) return conflict("This grading run is no longer active")

        val eligible = boxes.map { it.first }.filter { it !in protectedIds }
        val results = body.getAsJsonArray("results").map { it.asJsonObject }
        val byId = results.associateBy { it.get("answer_box_id").asString }
        val missing = eligible.filter { it !in byId }
        if (missing.isNotEmpty()) {
            return json(400, mapOf("detail" to mapOf("message" to "Every eligible answer box needs a result", "missing_box_ids" to missing)))
        }
        val useFallback = body.get("use_fallback").asBoolean
        val flagged = body.getAsJsonArray("fallback_box_ids").map { it.asString }.toSet()
        val available = useFallback && selfHostedLlm
        saves++
        var pending = 0
        for (id in eligible) {
            val r = byId.getValue(id)
            val outcome = r.get("outcome").asString
            val feedback = r.get("feedback")?.takeIf { !it.isJsonNull }?.asString
            val row = when {
                id in flagged && available -> Row(null, null, "on_device", false, "Awaiting server re-mark").also { pending++ }
                id in flagged -> Row(null, feedback, "on_device", true, "Fallback unavailable")
                outcome == "blank" -> Row(0.0, "Nothing was written in this answer box.", "on_device", false, null)
                outcome == "scored" -> Row(r.get("score").asDouble, feedback, "on_device", false, null)
                else -> Row(
                    null, feedback, "on_device", true,
                    r.get("review_reason")?.takeIf { !it.isJsonNull }?.asString ?: "The phone could not mark this answer"
                )
            }
            rows[id] = row
        }
        postedAt = "2026-09-25T10:05:00"
        if (pending == 0) {
            status = "graded"
            startedAt = null
        }
        return json(201, payload() + ("fallback" to fallbackState()))
    }

    /** The background self-hosted re-mark finishing. */
    @Synchronized
    fun finishFallback(score: Double) {
        rows.values.filter { it.reason == "Awaiting server re-mark" }.forEach {
            it.score = score
            it.feedback = "Re-marked on the server."
            it.provider = "self_hosted"
            it.reason = null
        }
        status = "graded"
        startedAt = null
    }

    /** A teacher pressing Grade on the website: every unprotected row re-marked by the server's provider. */
    @Synchronized
    fun teacherGrades(score: Double) {
        for ((id, _, _) in boxes) rows[id] = Row(score, "Marked from the website.", "gemini", false, null)
        status = "graded"
        startedAt = null
    }

    private fun isPending(row: Row) =
        status == "grading" && postedAt != null && row.score == null && !row.needsReview &&
            row.reason == "Awaiting server re-mark"

    private fun fallbackState(): String = when {
        rows.values.any(::isPending) -> "scheduled"
        rows.values.any { it.reason == "Fallback unavailable" } -> "unavailable"
        rows.values.any { it.provider == "self_hosted" } -> "completed"
        else -> "none"
    }

    fun payload(): Map<String, Any?> {
        val scored = rows.values.filter { it.score != null }
        return mapOf(
            "submission_id" to submissionId,
            "question_id" to "q-1",
            "grading_status" to status,
            "grading_error" to if (status == "failed") "On-device grading didn't finish in time." else null,
            "released" to released,
            "provisional" to !released,
            "earned" to scored.sumOf { it.score!! },
            "max_score" to if (rows.isEmpty()) 0 else boxes.filter { it.first in rows }.sumOf { it.third },
            "graded_count" to scored.size,
            "needs_review_count" to rows.values.count { it.needsReview },
            "pending_fallback_count" to rows.values.count(::isPending),
            "run" to mapOf("started_at" to startedAt, "posted_at" to postedAt, "lease_expires_at" to null),
            "boxes" to boxes.mapIndexed { i, (id, label, points) ->
                val row = rows[id]
                mapOf(
                    "answer_box_id" to id,
                    "label" to label,
                    "order_index" to i,
                    "max_score" to points,
                    "score" to row?.score,
                    "feedback" to row?.feedback,
                    "provider" to row?.provider,
                    "needs_manual_review" to (row?.needsReview ?: false),
                    "review_reason" to row?.reason,
                    "pending_fallback" to (row != null && isPending(row))
                )
            }
        )
    }

    private fun conflict(detail: String) = json(409, mapOf("detail" to detail))

    private fun json(code: Int, body: Any): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json")
            .setBody(Gson().newBuilder().serializeNulls().create().toJson(body))

    companion object {
        /**
         * The app's real ApiService and AuthInterceptor on [server], without OkHttp's silent
         * retry on a dropped connection, so each test sees every attempt the runner makes.
         */
        fun api(server: MockWebServer): ApiService =
            Retrofit.Builder()
                .baseUrl(server.url("/api/"))
                .client(
                    OkHttpClient.Builder()
                        .retryOnConnectionFailure(false)
                        .addInterceptor(AuthInterceptor(FakeTokens()))
                        .build()
                )
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
    }
}
