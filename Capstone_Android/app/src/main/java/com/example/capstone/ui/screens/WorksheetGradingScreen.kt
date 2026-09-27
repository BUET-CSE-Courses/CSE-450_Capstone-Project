package com.example.capstone.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.capstone.data.local.GradingRunRecord
import com.example.capstone.data.local.RunPhase
import com.example.capstone.data.remote.OnDeviceGradesDto
import com.example.capstone.data.repository.RunProgress
import com.example.capstone.domain.grading.BoxMarkView
import com.example.capstone.domain.grading.MarkDisplay
import com.example.capstone.domain.grading.MarkState
import com.example.capstone.work.GradingWorker

/**
 * Grading and marks for one handed-in paper.
 *
 * While the phone grades: "Grading box 2 of 5". Once the server has the marks: the
 * provisional banner, the total from the own-grades route, and one card per box with its
 * score, feedback, the phone's transcript (collapsed) and who marked it. A box that needs
 * review shows "– / 5" in amber with its reason, never a 0; a box the server is re-marking
 * shows that it is still coming.
 */
@Composable
fun WorksheetGradingScreen(
    onDone: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorksheetGradingViewModel = viewModel(factory = WorksheetGradingViewModel.Factory)
) {
    val state = viewModel.state
    AskForNotifications()

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("Marks") },
                navigationIcon = { TextButton(onClick = onNavigateBack) { Text("Back") } }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            val grades = state.grades
            when {
                state.loading -> Centered {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Loading…")
                }
                state.error != null -> Centered {
                    Text(
                        state.error,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onNavigateBack) { Text("Back") }
                }
                grades == null -> RunProgressView(state.run, viewModel::retryRun)
                else -> Results(state, grades, viewModel, onDone, Modifier.weight(1f))
            }
        }
    }

    state.reevalTarget?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::dismissReevaluation,
            title = { Text("Ask for re-evaluation") },
            text = {
                Column {
                    Text("Your teacher will be asked to look at ${target.label} again.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.reevalMessage,
                        onValueChange = viewModel::onReevalMessage,
                        label = { Text("Message (optional)") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    state.reevalError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = viewModel::sendReevaluation, enabled = !state.reevalSending) {
                    Text(if (state.reevalSending) "Sending…" else "Send")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissReevaluation, enabled = !state.reevalSending) { Text("Cancel") }
            }
        )
    }

    state.reevalConfirmation?.let { text ->
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirmation,
            title = { Text("Request sent") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = viewModel::dismissConfirmation) { Text("OK") } }
        )
    }
}

/** The run before the server has marks: "Grading box 2 of 5", or why it stopped. */
@Composable
private fun ColumnScope.RunProgressView(run: GradingRunRecord?, onRetry: () -> Unit) {
    val phase = run?.phase ?: RunPhase.PREPARING
    Centered {
        val text = GradingWorker.progressText(RunProgress(phase, run?.current ?: 0, run?.total ?: 0, run?.message))
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        when (phase) {
            RunPhase.GRADING -> {
                val total = run?.total ?: 0
                val done = ((run?.current ?: 1) - 1).coerceAtLeast(0)
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "You can turn the screen off; grading carries on.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
            RunPhase.BLOCKED, RunPhase.WAITING_FOR_NETWORK -> {
                run?.message?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                }
                Text(
                    "What the phone has marked so far is saved.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text("Try again") }
            }
            else -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun Results(
    state: GradingScreenState,
    grades: OnDeviceGradesDto,
    viewModel: WorksheetGradingViewModel,
    onDone: () -> Unit,
    modifier: Modifier
) {
    Column(modifier.fillMaxWidth()) {
        Surface(
            color = if (grades.released) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(MarkDisplay.banner(grades), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(12.dp))
        Text("Total ${MarkDisplay.totalText(grades)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        MarkDisplay.totalNote(grades)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = REVIEW_COLOR) }

        val statusLine = when {
            grades.gradingStatus == OnDeviceGradesDto.FAILED ->
                "Marking failed: ${grades.gradingError?.takeIf { it.isNotBlank() } ?: "no reason given"}"
            state.polling && grades.pendingFallbackCount > 0 -> "The server is marking some answers again…"
            state.polling -> "Checking for updates…"
            else -> null
        }
        statusLine?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        (state.run?.message.takeIf { state.run?.isFinished == true })?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        state.pollNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(state.boxes, key = { it.answerBoxId }) { box ->
                BoxCard(
                    box,
                    canAsk = state.canAskReevaluation,
                    onAsk = { viewModel.openReevaluation(ReevalTarget(box.answerBoxId, box.label)) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        if (state.canGradeAgain) {
            Button(onClick = viewModel::gradeAgain, modifier = Modifier.fillMaxWidth()) { Text("Grade again on this phone") }
        }
        OutlinedButton(
            onClick = { viewModel.openReevaluation(ReevalTarget(null, "the whole paper")) },
            enabled = state.canAskReevaluation,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Ask for re-evaluation of the whole paper") }
        if (!state.canAskReevaluation) {
            Text(
                "You can ask for re-evaluation once marking has finished.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::refresh, enabled = !state.polling, modifier = Modifier.weight(1f)) {
                Text("Check again")
            }
            Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Done") }
        }
    }
}

@Composable
private fun BoxCard(box: BoxMarkView, canAsk: Boolean, onAsk: () -> Unit) {
    var showTranscript by rememberSaveable(box.answerBoxId) { mutableStateOf(false) }
    val attention = box.state == MarkState.NEEDS_REVIEW || box.state == MarkState.PENDING_FALLBACK
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = if (box.state == MarkState.NEEDS_REVIEW) {
            CardDefaults.cardColors(containerColor = REVIEW_BACKGROUND)
        } else CardDefaults.cardColors()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(box.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text(
                    box.scoreText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (attention) REVIEW_COLOR else MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(4.dp))
            when (box.state) {
                MarkState.NEEDS_REVIEW -> Text("Needs review", color = REVIEW_COLOR, style = MaterialTheme.typography.labelLarge)
                MarkState.PENDING_FALLBACK -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Being marked by the server", color = REVIEW_COLOR, style = MaterialTheme.typography.labelLarge)
                }
                MarkState.NOT_MARKED -> Text("Not marked yet", style = MaterialTheme.typography.labelLarge)
                MarkState.SCORED -> {}
            }
            Text(box.markedByText, style = MaterialTheme.typography.labelMedium, fontStyle = FontStyle.Italic)
            box.reason?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            box.feedback?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (box.transcript != null) {
                    TextButton(onClick = { showTranscript = !showTranscript }) {
                        Text(if (showTranscript) "Hide what the phone read" else "Show what the phone read")
                    }
                } else Spacer(Modifier.width(1.dp))
                TextButton(onClick = onAsk, enabled = canAsk) { Text("Re-evaluate") }
            }
            if (showTranscript && box.transcript != null) {
                Text(box.transcript, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
            }
        }
    }
}

/** Android 13+: the grading notification needs this. Asked once; grading works without it. */
@Composable
private fun AskForNotifications() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { asked = true }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Amber, distinct from both the scored text colour and the error red. */
private val REVIEW_COLOR = Color(0xFFB26A00)
private val REVIEW_BACKGROUND = Color(0x1FB26A00)

@Composable
private fun ColumnScope.Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content
    )
}
