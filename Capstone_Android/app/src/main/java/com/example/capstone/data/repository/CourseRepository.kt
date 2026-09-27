package com.example.capstone.data.repository

import com.example.capstone.data.remote.ApiService
import com.example.capstone.data.remote.CourseDto
import com.example.capstone.data.remote.JoinRequest
import com.example.capstone.domain.model.Course

class CourseRepository(private val api: ApiService) {

    /**
     * The courses this person takes. `GET /api/courses` also lists the ones
     * they teach (`my_role == "teacher"`); a student app has no use for those.
     */
    suspend fun myCourses(): Result<List<Course>> = runCatching {
        api.courses().filter { it.myRole == "student" }.map { it.toDomain() }
    }

    /**
     * `POST /api/courses/join` with `{"join_code": ...}`. Joining a course
     * you are already in succeeds and changes nothing (routers/courses.py).
     */
    suspend fun join(code: String): Result<Course> = runCatching {
        val trimmed = code.trim()
        require(trimmed.length in 1..MAX_JOIN_CODE) { "A join code is 1 to $MAX_JOIN_CODE characters." }
        api.joinCourse(JoinRequest(trimmed)).toDomain()
    }

    private companion object {
        /** `JoinRequest.join_code` max_length in backend/schemas.py. */
        const val MAX_JOIN_CODE = 32
    }
}

internal fun CourseDto.toDomain() = Course(
    id = id,
    title = title,
    teacherName = teacherName,
    archived = archived,
    studentCount = studentCount
)
