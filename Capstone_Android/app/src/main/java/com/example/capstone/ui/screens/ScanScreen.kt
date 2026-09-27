package com.example.capstone.ui.screens

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.capstone.domain.worksheet.ServerVerdict
import kotlin.math.min

/**
 * Photograph every printed page, upload it, then hand in.
 *
 * One card per page of the paper. The camera is the system camera
 * (`TakePicture`, full resolution); choosing an existing photo from the gallery
 * is the second path. Each photo is cropped on the phone first, then uploaded,
 * and the card shows what the web end made of it. A page the web end could not
 * read asks for a retake.
 *
 * Hand in appears only when every page with answer boxes is ready, and asks
 * before it goes: after it the answers cannot change.
 */
@Composable
fun ScanScreen(
    /** (question id, submission id). Called once, right after a successful hand-in, and from "See marks". */
    onHandedIn: (String, String?) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScanViewModel = viewModel(factory = ScanViewModel.Factory)
) {
    val uiState = viewModel.uiState
    var cameraMissing by remember { mutableStateOf(false) }

    // Hand-in done: grading starts on the next screen (plan decision 3, steps 7-10).
    val handedIn = viewModel.justHandedIn
    LaunchedEffect(handedIn) {
        if (handedIn != null) {
            viewModel.onHandInShown()
            onHandedIn(viewModel.assignmentId, handedIn)
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        viewModel.onCaptured(saved)
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.onPicked(uri)
    }

    fun capture(page: Int) {
        val target = viewModel.prepareCapture(page)
        try {
            takePicture.launch(target)
        } catch (e: ActivityNotFoundException) {
            viewModel.onCaptured(false)
            cameraMissing = true
        }
    }

    fun pick(page: Int) {
        viewModel.preparePick(page)
        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("Photograph and hand in") },
                navigationIcon = { TextButton(onClick = onNavigateBack) { Text("Back") } }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            when (uiState) {
                is ScanUiState.Loading -> Centered { CircularProgressIndicator() }

                is ScanUiState.Blocked -> Centered {
                    Text(
                        uiState.message,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onNavigateBack) { Text("Back") }
                }

                is ScanUiState.Ready -> Pages(
                    state = uiState,
                    onCapture = ::capture,
                    onPick = ::pick,
                    onRemove = viewModel::removePage,
                    onRefresh = viewModel::refreshServer,
                    onHandIn = viewModel::handIn,
                    onMark = { onHandedIn(viewModel.assignmentId, uiState.submissionId) }
                )
            }
        }
    }

    if (cameraMissing) {
        AlertDialog(
            onDismissRequest = { cameraMissing = false },
            confirmButton = { TextButton(onClick = { cameraMissing = false }) { Text("OK") } },
            title = { Text("No camera app") },
            text = { Text("This phone has no camera app to take the photo. Choose a photo from the gallery instead.") }
        )
    }
}

@Composable
private fun Pages(
    state: ScanUiState.Ready,
    onCapture: (Int) -> Unit,
    onPick: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onRefresh: () -> Unit,
    onHandIn: () -> Unit,
    onMark: () -> Unit
) {
    var confirmHandIn by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Text(state.title, style = MaterialTheme.typography.headlineSmall)
        Text(
            if (state.handedIn) "Handed in. Your answers can't be changed now."
            else "Take one photo of each page, with all four corner markers inside the frame. " +
                "The page number is printed at the bottom of each sheet.",
            style = MaterialTheme.typography.bodyMedium
        )
        state.message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRefresh, enabled = !state.busy) { Text("Try again") }
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.preview?.let { preview ->
                item(key = "preview") { PreviewCard(preview) }
            }
            items(state.pages, key = { it.pageIndex }) { row ->
                PageCard(
                    row = row,
                    pageCount = state.pages.size,
                    busyLabel = state.busyLabel.takeIf { state.busyPage == row.pageIndex },
                    enabled = !state.busy && !state.handedIn,
                    onCapture = { onCapture(row.pageIndex) },
                    onPick = { onPick(row.pageIndex) },
                    onRemove = { onRemove(row.pageIndex) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        if (state.handedIn) {
            Button(onClick = onMark, modifier = Modifier.fillMaxWidth()) { Text("See marks") }
        } else {
            val problem = state.handInProblem ?: if (state.submissionId == null) "Nothing is uploaded yet." else null
            problem?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = { confirmHandIn = true },
                enabled = problem == null && !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (state.handingIn) "Handing in…" else "Hand in") }
        }
    }

    if (confirmHandIn) {
        AlertDialog(
            onDismissRequest = { confirmHandIn = false },
            title = { Text("Hand in your answers?") },
            text = { Text("After this you can't change your answers.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmHandIn = false
                    onHandIn()
                }) { Text("Hand in") }
            },
            dismissButton = { TextButton(onClick = { confirmHandIn = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PageCard(
    row: PageRow,
    pageCount: Int,
    busyLabel: String?,
    enabled: Boolean,
    onCapture: () -> Unit,
    onPick: () -> Unit,
    onRemove: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Page ${row.pageIndex + 1} of $pageCount", style = MaterialTheme.typography.titleSmall)

            when {
                !row.hasAnswers -> Text("No answer boxes on this page. No photo needed.")
                busyLabel != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(busyLabel)
                }
                else -> PageStatus(row)
            }

            if (row.hasAnswers && enabled) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCapture) { Text(if (row.server == null && row.phoneCrops == null) "Take photo" else "Retake") }
                    OutlinedButton(onClick = onPick) { Text("Gallery") }
                    if (row.server != null) TextButton(onClick = onRemove) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun PageStatus(row: PageRow) {
    val error = MaterialTheme.colorScheme.error
    row.problem?.let {
        Text(it, color = error)
        row.problemDetail?.let { detail -> Text(detail, style = MaterialTheme.typography.bodySmall) }
    }
    when (val verdict = row.verdict) {
        null -> if (row.problem == null) Text("No photo yet.")
        is ServerVerdict.Accepted -> {
            Text("Uploaded. Web end: ${verdict.summary}.", style = MaterialTheme.typography.bodySmall)
            if (row.phoneCrops != null) {
                Text(
                    "Ready. This phone cut ${row.phoneCrops} of ${row.expected.size} answer box piece(s).",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    "This page wasn't photographed on this phone, so the phone can't mark it. Take it again here.",
                    color = error
                )
            }
        }
        is ServerVerdict.Refused -> {
            Text("Web end: ${verdict.summary}.", style = MaterialTheme.typography.bodySmall)
            if (row.problem == null) {
                Text("The web end couldn't read this page. Take it again.", color = error)
                Text(verdict.reason, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * The last photo with the boxes the phone read off it, drawn as the extractor's quads
 * (not rectangles: a photo taken at an angle gives genuinely skewed boxes).
 */
@Composable
private fun PreviewCard(preview: PagePreview) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Page ${preview.pageIndex + 1} as this phone read it: ${preview.crops.size} piece(s)",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))
            val image = remember(preview.bitmap) { preview.bitmap.asImageBitmap() }
            Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                Image(
                    bitmap = image,
                    contentDescription = "Photo of page ${preview.pageIndex + 1} with the answer boxes found",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                Canvas(Modifier.fillMaxSize()) {
                    if (image.width == 0 || image.height == 0) return@Canvas
                    val fit = min(size.width / image.width, size.height / image.height)
                    val offsetX = (size.width - image.width * fit) / 2f
                    val offsetY = (size.height - image.height * fit) / 2f
                    // Full-size photo pixels -> preview bitmap pixels -> canvas pixels.
                    val scale = preview.scale * fit
                    for (crop in preview.crops) {
                        if (crop.imageQuad.size < 4) continue
                        val path = Path().apply {
                            crop.imageQuad.forEachIndexed { index, point ->
                                val x = offsetX + point.x.toFloat() * scale
                                val y = offsetY + point.y.toFloat() * scale
                                if (index == 0) moveTo(x, y) else lineTo(x, y)
                            }
                            close()
                        }
                        drawPath(path, color = OVERLAY_COLOR, style = Stroke(width = OVERLAY_STROKE))
                        // A dot on the first corner, so a box drawn upside down by a mirrored
                        // solve is visible rather than merely plausible.
                        val first = crop.imageQuad.first()
                        drawCircle(
                            color = OVERLAY_COLOR,
                            radius = OVERLAY_STROKE * 2f,
                            center = Offset(offsetX + first.x.toFloat() * scale, offsetY + first.y.toFloat() * scale)
                        )
                    }
                }
            }
        }
    }
}

private val OVERLAY_COLOR = Color(0xFF00C853)
private const val OVERLAY_STROKE = 4f

@Composable
private fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content
    )
}
