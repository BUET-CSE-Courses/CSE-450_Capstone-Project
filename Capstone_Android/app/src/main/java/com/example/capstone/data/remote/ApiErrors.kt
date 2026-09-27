package com.example.capstone.data.remote

import com.example.capstone.data.auth.SignInRequiredException
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import retrofit2.HttpException
import java.io.IOException

/**
 * FastAPI's error body is `{"detail": ...}` where detail is one of:
 * - a string (`HTTPException(status, "No course found with that join code")`);
 * - a list of `{loc, msg, type}` (request validation, 422);
 * - an object with a `message` (the on-device routes' 400s).
 *
 * Returns the human part, or null when the body is none of these.
 */
fun parseErrorDetail(body: String?): String? {
    if (body.isNullOrBlank()) return null
    val detail = try {
        JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject?.get("detail")
    } catch (e: Exception) {
        null
    } ?: return null
    return describeDetail(detail)
}

private fun describeDetail(detail: JsonElement): String? = when {
    detail.isJsonNull -> null
    detail.isJsonPrimitive -> detail.asString
    detail.isJsonArray -> detail.asJsonArray.mapNotNull { item ->
        if (!item.isJsonObject) return@mapNotNull item.toString()
        val obj = item.asJsonObject
        val msg = obj.get("msg")?.takeIf { it.isJsonPrimitive }?.asString
        // loc is ["body", "join_code"]; the first element only says where.
        val field = obj.get("loc")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.drop(1)?.joinToString(".") { it.asString }
        when {
            msg == null -> null
            field.isNullOrBlank() -> msg
            else -> "$field: $msg"
        }
    }.joinToString("; ").ifBlank { null }
    detail.isJsonObject ->
        detail.asJsonObject.get("message")?.takeIf { it.isJsonPrimitive }?.asString
            ?: detail.toString()
    else -> null
}

/**
 * One line a student can act on. Always names the HTTP status, so a failure
 * can be told apart without a debugger.
 */
fun Throwable.userMessage(baseUrl: String): String = when (this) {
    is SignInRequiredException -> message ?: "Sign in again."
    is HttpException -> {
        val body = try {
            response()?.errorBody()?.string()
        } catch (e: Exception) {
            null
        }
        val detail = parseErrorDetail(body)
        if (detail != null) "${code()}: $detail" else "HTTP ${code()}"
    }
    is IOException ->
        "Can't reach the web end at $baseUrl (${javaClass.simpleName}: ${message ?: "no detail"}). " +
            "Is it running? For the local build, run: adb reverse tcp:8000 tcp:8000"
    else -> "${javaClass.simpleName}: ${message ?: "no detail"}"
}
