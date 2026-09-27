package com.example.capstone.ui.screens

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.capstone.CapstoneApplication
import com.example.capstone.data.local.GradingRunRecord
import com.example.capstone.data.local.GradingRunStore
import com.example.capstone.data.local.RunPhase
import com.example.capstone.data.remote.OnDeviceGradesDto
import com.example.capstone.data.remote.userMessage
import com.example.capstone.data.repository.AssignmentRepository
import com.example.capstone.data.repository.OnDeviceGradingRunner
import com.example.capstone.data.repository.SubmissionRepository
import com.example.capstone.domain.grading.BoxMarkView
import com.example.capstone.domain.grading.GradesPoller
import com.example.capstone.domain.grading.MarkDisplay
import com.example.capstone.work.GradingWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Who a re-evaluation request is about: one box, or the whole paper ([answerBoxId] null). */
data class ReevalTarget(val answerBoxId: String?, val label: String)

data class GradingScreenState(
    val loading: Boolean = true,
    val submissionId: String? = null,
    /** Cannot go on at all (no submission, not handed in). */
    val error: String? = null,
    /** The saved run on this phone (progress, phone results). */
    val run: GradingRunRecord? = null,
    /** The newest marks from the server (results reply or own grades). */
    val grades: OnDeviceGradesDto? = null,
    val boxes: List<BoxMarkView> = emptyList(),
    val polling: Boolean = false,
    val pollNote: String? = null,
    val reevalTarget: ReevalTarget? = null,
    val reevalMessage: String = "",
    val reevalSending: Boolean = false,
    val reevalError: String? = null,
    val reevalConfirmation: String? = null
) {
    /** Marks can be asked about only once the paper is graded (the route's rule). */
    val canAskReevaluation: Boolean get() = grades?.gradingStatus == OnDeviceGradesDto.GRADED

    /** The server failed or reset the paper after this phone's run: offer to grade again. */
    val canGradeAgain: Boolean
        get() = run?.isFinished == true &&
            (grades?.gradingStatus == OnDeviceGradesDto.FAILED || grades?.gradingStatus == OnDeviceGradesDto.UNGRADED)
}

/**
 * The grading and results screen for one handed-in paper.
 *
 * The run itself is [GradingWorker] (so screen-off does not stop it); this screen starts it
 * (unique work, so reopening never starts a second one), shows its saved progress, and
 * once the server has the marks shows them at once and polls the own-grades route every
 * 5 s until "graded" or "failed" (10 min at most), so fallback boxes fill in.
 */
class WorksheetGradingViewModel(
    private val appContext: Context,
    private val assignmentRepository: AssignmentRepository,
    private val submissionRepository: SubmissionRepository,
    private val runStore: GradingRunStore,
    private val runner: OnDeviceGradingRunner,
    private val baseUrl: String,
    savedStateHandle: SavedStateHandle,
    private val poller: GradesPoller = GradesPoller()
) : ViewModel() {

    val questionId: String = checkNotNull(savedStateHandle.get<String>("assignmentId"))
    private val routeSubmissionId: String? = savedStateHandle.get<String>("submissionId")?.takeIf { it.isNotBlank() }

    var state by mutableStateOf(GradingScreenState())
        private set

    private var pollJob: Job? = null
    private var observing = false

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            state = GradingScreenState(loading = true)
            val sid = routeSubmissionId ?: findSubmission() ?: return@launch
            state = state.copy(submissionId = sid)

            val saved = runStore.get(sid)
            if (saved == null || !saved.isFinished) {
                // Before (re)starting, ask the server: a teacher may already have marked it,
                // and then there is nothing for this phone to do.
                val server = submissionRepository.myGrades(sid).getOrNull()
                if (server != null && OnDeviceGradingRunner.serverHasMarks(server) &&
                    saved?.runToken == null
                ) {
                    showGrades(server)
                } else if (saved?.phase != RunPhase.BLOCKED) {
                    enqueueRun(sid)
                }
            }
            state = state.copy(loading = false)
            observe(sid)
        }
    }

    /** The student's submission for this paper, from the assignment list; null after setting an error. */
    private suspend fun findSubmission(): String? {
        val pack = withContext(Dispatchers.IO) { assignmentRepository.cachedPack(questionId) }
        if (pack == null) {
            state = state.copy(loading = false, error = "Open this assignment from the list first.")
            return null
        }
        val row = submissionRepository.myAssignment(pack.pack.courseId, questionId).getOrElse { e ->
            state = state.copy(loading = false, error = "Couldn't load your submission. ${e.userMessage(baseUrl)}")
            return null
        }
        return when {
            row?.submissionId == null -> {
                state = state.copy(loading = false, error = "Nothing is uploaded for this paper yet.")
                null
            }
            !row.handedIn -> {
                state = state.copy(loading = false, error = "Hand this paper in first. Marking starts after hand-in.")
                null
            }
            else -> row.submissionId
        }
    }

    private fun observe(sid: String) {
        if (observing) return
        observing = true
        viewModelScope.launch {
            runStore.observe(sid).collect { rec ->
                state = state.copy(run = rec, boxes = state.grades?.let { MarkDisplay.boxes(it, rec) }.orEmpty())
                val grades = rec?.serverGrades
                if (rec != null && rec.isFinished && grades != null && pollJob == null) showGrades(grades)
            }
        }
    }

    /** Shows [grades] at once, then polls until they are final. */
    private fun showGrades(grades: OnDeviceGradesDto) {
        state = state.copy(grades = grades, boxes = MarkDisplay.boxes(grades, state.run))
        startPolling()
    }

    private fun startPolling() {
        val sid = state.submissionId ?: return
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            state = state.copy(polling = true, pollNote = null)
            val end = poller.poll(fetch = { submissionRepository.myGrades(sid) }) { update ->
                state = when (update) {
                    is GradesPoller.Update.Grades -> state.copy(
                        grades = update.grades,
                        boxes = MarkDisplay.boxes(update.grades, state.run),
                        pollNote = null
                    )
                    is GradesPoller.Update.FetchFailed -> state.copy(
                        pollNote = "Couldn't refresh the marks. ${update.error.userMessage(baseUrl)} Trying again…"
                    )
                }
            }
            state = state.copy(
                polling = false,
                pollNote = if (end is GradesPoller.End.TimedOut) {
                    "Still being marked. Check again later; the marks fill in when the server is done."
                } else state.pollNote
            )
        }
    }

    /** Pull to refresh / "Check again". */
    fun refresh() {
        pollJob?.cancel()
        pollJob = null
        startPolling()
    }

    /** The run stopped (blocked or out of retries): run it again from the saved progress. */
    fun retryRun() {
        val sid = state.submissionId ?: return
        viewModelScope.launch { enqueueRun(sid) }
    }

    private suspend fun enqueueRun(sid: String) =
        withContext(Dispatchers.IO) { GradingWorker.enqueue(appContext, sid, questionId) }

    /**
     * The server failed or reset the paper: grade again. After a teacher's reset ("ungraded")
     * every box is graded afresh; after a failure the phone's good results are re-used.
     */
    fun gradeAgain() {
        val sid = state.submissionId ?: return
        val teacherReset = state.grades?.gradingStatus == OnDeviceGradesDto.UNGRADED
        viewModelScope.launch {
            runner.reopen(sid, everything = teacherReset)
            pollJob?.cancel()
            pollJob = null
            state = state.copy(grades = null, boxes = emptyList(), pollNote = null)
            enqueueRun(sid)
        }
    }

    // ---- re-evaluation -------------------------------------------------------------------

    fun openReevaluation(target: ReevalTarget) {
        state = state.copy(reevalTarget = target, reevalMessage = "", reevalError = null, reevalConfirmation = null)
    }

    fun onReevalMessage(text: String) {
        state = state.copy(reevalMessage = text.take(com.example.capstone.data.remote.ReevaluationRequestDto.MAX_MESSAGE))
    }

    fun dismissReevaluation() {
        if (!state.reevalSending) state = state.copy(reevalTarget = null, reevalError = null)
    }

    fun dismissConfirmation() {
        state = state.copy(reevalConfirmation = null)
    }

    fun sendReevaluation() {
        val sid = state.submissionId ?: return
        val target = state.reevalTarget ?: return
        if (state.reevalSending) return
        viewModelScope.launch {
            state = state.copy(reevalSending = true, reevalError = null)
            submissionRepository.requestReevaluation(
                sid,
                target.answerBoxId?.let { listOf(it) },
                state.reevalMessage
            ).fold(
                onSuccess = { reply ->
                    state = state.copy(
                        reevalSending = false,
                        reevalTarget = null,
                        reevalConfirmation = if (reply.notified) {
                            "Sent. Your teacher has been asked to look at ${target.label} again."
                        } else {
                            "Sent, but this course has no teacher to notify."
                        }
                    )
                },
                onFailure = { e ->
                    state = state.copy(reevalSending = false, reevalError = "Couldn't send. ${e.userMessage(baseUrl)}")
                }
            )
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as CapstoneApplication
                val container = application.container
                WorksheetGradingViewModel(
                    appContext = application,
                    assignmentRepository = container.assignmentRepository,
                    submissionRepository = container.submissionRepository,
                    runStore = container.gradingRunStore,
                    runner = container.gradingRunner,
                    baseUrl = container.baseUrl,
                    savedStateHandle = createSavedStateHandle()
                )
            }
        }
    }
}
