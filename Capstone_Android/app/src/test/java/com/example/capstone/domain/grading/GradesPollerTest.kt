package com.example.capstone.domain.grading

import com.example.capstone.data.remote.OnDeviceGradesDto
import com.example.capstone.data.remote.OnDeviceRunInfoDto
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException

class GradesPollerTest {

    private fun grades(status: String) = OnDeviceGradesDto(
        submissionId = "s", questionId = "q", gradingStatus = status, gradingError = null,
        released = false, provisional = true, earned = 0.0, maxScore = 5, gradedCount = 0,
        needsReviewCount = 0, pendingFallbackCount = 0,
        run = OnDeviceRunInfoDto(null, null, null), boxes = emptyList()
    )

    private class Clock {
        var now = 0L
    }

    private fun poller(clock: Clock, sleeps: MutableList<Long>) = GradesPoller(
        sleep = { sleeps += it; clock.now += it },
        clock = { clock.now }
    )

    @Test
    fun pollsEveryFiveSecondsUntilGraded() {
        val clock = Clock()
        val sleeps = mutableListOf<Long>()
        val replies = ArrayDeque(listOf("grading", "grading", "graded"))
        val end = runBlocking {
            poller(clock, sleeps).poll(fetch = { Result.success(grades(replies.removeFirst())) }) {}
        }
        assertThat(end).isInstanceOf(GradesPoller.End.Terminal::class.java)
        assertThat(sleeps).containsExactly(5_000L, 5_000L)
    }

    @Test
    fun stopsAtFailed() {
        val clock = Clock()
        val end = runBlocking {
            poller(clock, mutableListOf()).poll(fetch = { Result.success(grades("failed")) }) {}
        }
        assertThat((end as GradesPoller.End.Terminal).grades.gradingStatus).isEqualTo("failed")
    }

    @Test
    fun stopsAfterTenMinutes() {
        val clock = Clock()
        val sleeps = mutableListOf<Long>()
        val end = runBlocking {
            poller(clock, sleeps).poll(fetch = { Result.success(grades("grading")) }) {}
        }
        assertThat(end).isInstanceOf(GradesPoller.End.TimedOut::class.java)
        assertThat((end as GradesPoller.End.TimedOut).last?.gradingStatus).isEqualTo("grading")
        assertThat(clock.now).isEqualTo(10 * 60_000L)
        assertThat(sleeps).hasSize(120)
    }

    @Test
    fun aFailedFetchIsReportedAndPollingGoesOn() {
        val clock = Clock()
        val updates = mutableListOf<GradesPoller.Update>()
        val replies = ArrayDeque(
            listOf<Result<OnDeviceGradesDto>>(Result.failure(IOException("offline")), Result.success(grades("graded")))
        )
        val end = runBlocking {
            poller(clock, mutableListOf()).poll(fetch = { replies.removeFirst() }) { updates += it }
        }
        assertThat(updates.first()).isInstanceOf(GradesPoller.Update.FetchFailed::class.java)
        assertThat(end).isInstanceOf(GradesPoller.End.Terminal::class.java)
    }

    @Test
    fun withFreshMarksOnScreen_theFirstFetchWaitsOneInterval() {
        val clock = Clock()
        val sleeps = mutableListOf<Long>()
        runBlocking { poller(clock, sleeps).poll(fetch = { Result.success(grades("graded")) }, fetchFirst = false) {} }
        assertThat(sleeps).containsExactly(5_000L)
    }
}
