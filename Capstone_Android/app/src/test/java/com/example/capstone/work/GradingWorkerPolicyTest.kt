package com.example.capstone.work

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Session 7: a run left waiting in retry backoff must start when the screen asks for it. */
class GradingWorkerPolicyTest {

    @Test
    fun `a running run is kept, so never two posts`() {
        assertThat(GradingWorker.policyFor(listOf(WorkInfo.State.RUNNING))).isEqualTo(ExistingWorkPolicy.KEEP)
    }

    @Test
    fun `a run only waiting, or finished, is replaced and starts now`() {
        for (state in listOf(
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.SUCCEEDED,
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED
        )) {
            assertThat(GradingWorker.policyFor(listOf(state))).isEqualTo(ExistingWorkPolicy.REPLACE)
        }
        assertThat(GradingWorker.policyFor(emptyList())).isEqualTo(ExistingWorkPolicy.REPLACE)
    }

    @Test
    fun `state unknown keeps whatever is there`() {
        assertThat(GradingWorker.policyFor(null)).isEqualTo(ExistingWorkPolicy.KEEP)
    }
}
