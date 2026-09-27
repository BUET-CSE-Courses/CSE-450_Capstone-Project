package com.example.capstone.data.remote

import com.google.gson.annotations.SerializedName

/**
 * `GET /api/me`: the signed-in person as the web end knows them.
 *
 * Field names are `UserOut` in Script-Checker-Web-End `backend/schemas.py`.
 * The web end creates the row on first sign-in; [role] is "student" unless
 * the email is in its `TEACHER_EMAILS` (`backend/security.py`).
 */
data class UserDto(
    val id: String,
    val email: String,
    @SerializedName("display_name")
    val displayName: String,
    val role: String,
    @SerializedName("can_create_courses")
    val canCreateCourses: Boolean = true,
    val institution: String? = null,
    @SerializedName("has_avatar")
    val hasAvatar: Boolean = false
)
