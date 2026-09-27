package com.example.capstone.ui.screens

// ============================================================================
// TEMPORARY DEBUG SCREEN - validates the on-device LiteRT-LM grading path.
// Not part of the student flow. Delete once grading is wired into submission.
// ============================================================================

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.capstone.BuildConfig
import com.example.capstone.data.local.EngineState
import com.example.capstone.data.local.ModelSpec
import com.example.capstone.data.local.SpecAvailability
import com.example.capstone.data.repository.GradingBenchmark
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelTestScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ModelTestViewModel = viewModel(factory = ModelTestViewModel.Factory)
) {
    val imagePng = viewModel.imagePng
    val busy = viewModel.busy
    val activeSpec by viewModel.activeSpec.collectAsState()
    val engineState by viewModel.engineState.collectAsState()

    // A native model load is in progress: nothing may touch the engine.
    val swapping = engineState.isLoading
    val probesEnabled = !busy && !swapping
    // A text-only model has no image path at all, so the three probes that send
    // one are unavailable rather than merely likely to fail.
    val visionEnabled = probesEnabled && activeSpec.supportsVision

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.onImagePicked(uri)
    }

    val pickExperimentImages = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(
            maxItems = ModelTestViewModel.MAX_EXPERIMENT_IMAGES
        )
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.onExperimentImagesPicked(uris)
    }

    // Decode only when the prepared PNG actually changes. It is already <= 1024px.
    val preview = remember(imagePng) {
        imagePng?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Test (debug)") },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) { Text("Back") }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .padding(innerPadding)
                .fillMaxSize()
                // Single scroll container for the whole screen: no nested
                // same-axis scrollables, which Compose cannot measure.
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Model selection. Switching closes the current engine before it
            // builds the replacement, so it can take as long as a load.
            Text("Model", style = MaterialTheme.typography.titleSmall)
            ModelPicker(
                options = viewModel.modelOptions,
                activeSpec = activeSpec,
                enabled = !busy && !swapping,
                onSelect = viewModel::onSpecSelected
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (swapping) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text(
                    text = engineStateLabel(engineState),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (!activeSpec.supportsVision) {
                Text(
                    text = "Text-only model. Describe image, Transcribe handwriting " +
                        "and Grade it are disabled - it cannot accept an image.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            HorizontalDivider()

            Button(
                onClick = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = !busy && !swapping,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (imagePng == null) "Pick image from gallery" else "Pick a different image")
            }

            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = "Selected worksheet photo",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )
            }

            HorizontalDivider()

            // Used by "Grade it" and experiments A and C.
            Text(
                text = "Grade it marks the picked image as one answer box with the " +
                    "grading engine (web end prompt + CONFIDENCE).",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = viewModel.expQuestion,
                onValueChange = { viewModel.expQuestion = it },
                label = { Text("Question") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = viewModel.expModelAnswer,
                onValueChange = { viewModel.expModelAnswer = it },
                label = { Text("Model answer") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = viewModel.expMaxMarks,
                onValueChange = { viewModel.expMaxMarks = it },
                label = { Text("Max marks") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // The six probes, in the order they should be run.
            ProbeRow(
                left = "Check model file" to viewModel::checkModelFile,
                right = "Load engine" to viewModel::loadEngine,
                leftEnabled = probesEnabled,
                rightEnabled = probesEnabled
            )
            ProbeRow(
                left = "Text only test" to viewModel::textOnlyTest,
                right = "Describe image" to viewModel::describeImage,
                leftEnabled = probesEnabled,
                rightEnabled = visionEnabled
            )
            ProbeRow(
                left = "Transcribe handwriting" to viewModel::transcribeHandwriting,
                right = "Grade it" to viewModel::gradeIt,
                leftEnabled = visionEnabled,
                rightEnabled = visionEnabled
            )

            HorizontalDivider()

            // Phase 1 experiments. Debug builds only.
            if (BuildConfig.DEBUG) {
                Text("Phase 1 experiments", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Pick 1-3 images in this order: student crop, model-answer image, " +
                        "question figure. Fewer than 3 are reused in turn.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = {
                        pickExperimentImages.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = probesEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Pick experiment images (${viewModel.experimentPngs.size} ready)")
                }
                val experimentsEnabled = visionEnabled && viewModel.experimentPngs.isNotEmpty()
                ProbeRow(
                    left = "A: grade 3 in a row" to viewModel::experimentGradeThree,
                    right = "B: 2 then 3 images" to viewModel::experimentMultiImage,
                    leftEnabled = experimentsEnabled,
                    rightEnabled = experimentsEnabled
                )
                OutlinedButton(
                    onClick = viewModel::experimentWebPrompt,
                    enabled = experimentsEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("C: web end prompt + CONFIDENCE")
                }

                HorizontalDivider()

                // Session 9 benchmark. Never posts to the web end.
                Text("Benchmark (no posting)", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Saved crops of submission ${GradingBenchmark.SUBMISSION_ID.take(8)}… (box 1, box 2) " +
                        "and the blank fixture, through Gemma 4 E2B x plain / two-turn. " +
                        "Keep this screen open; it stays on while running.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = viewModel::runBenchmark,
                    enabled = probesEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Run benchmark")
                }

                HorizontalDivider()
            }

            // The benchmark takes many minutes; don't let the screen sleep under it.
            val view = LocalView.current
            DisposableEffect(busy) {
                view.keepScreenOn = busy
                onDispose { view.keepScreenOn = false }
            }

            Text(text = viewModel.status, style = MaterialTheme.typography.bodyMedium)

            viewModel.elapsedMs?.let { ms ->
                // The model that produced the result is part of the result: the
                // same probe means different things on different models.
                Text(
                    text = "${(viewModel.resultSpec ?: activeSpec).displayName} - elapsed: $ms ms",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            if (busy || swapping) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // The heading carries every piece of interpretation. The pane below
            // it holds the payload and nothing else, so an empty model response
            // shows as an empty pane rather than as harness text.
            val output = viewModel.rawOutput
            Text(
                text = when (viewModel.outputSource) {
                    OutputSource.NONE -> "Output - nothing run yet"
                    OutputSource.MODEL ->
                        "MODEL OUTPUT - verbatim, ${output?.length ?: 0} chars"
                    OutputSource.HARNESS ->
                        "HARNESS OUTPUT - composed by the app, not the model"
                    OutputSource.ERROR -> "HARNESS ERROR - stack trace"
                },
                style = MaterialTheme.typography.titleSmall
            )
            if (viewModel.outputSource == OutputSource.MODEL && output?.isEmpty() == true) {
                Text(
                    text = "The model returned an empty string.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            SelectionContainer {
                Text(
                    // No ifBlank, no orEmpty fallback text, no trim. When a probe
                    // has run this is exactly what it returned.
                    text = output.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** One line naming what the engine is doing, for the status row. */
private fun engineStateLabel(state: EngineState): String = when (state) {
    is EngineState.Idle -> "engine: not loaded (${state.spec.name})"
    is EngineState.Loading -> "engine: loading ${state.spec.name}..."
    is EngineState.Ready -> "engine: ready (${state.spec.name})"
    is EngineState.Failed -> "engine: FAILED (${state.spec.name}) - ${state.message}"
}

/**
 * Model selector over the whole registry.
 *
 * Entries whose file is not on the device stay listed but unselectable, with
 * the reason shown: a missing push is a fixable state, and hiding it would make
 * it look like the model does not exist.
 */
@Composable
private fun ModelPicker(
    options: List<SpecAvailability>,
    activeSpec: ModelSpec,
    enabled: Boolean,
    onSelect: (ModelSpec) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "${activeSpec.displayName}  (~${activeSpec.approxSizeMb} MB)",
                maxLines = 2
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (options.isEmpty()) {
                // The file check has not come back yet; it is disk I/O.
                DropdownMenuItem(
                    text = { Text("Checking which models are on the device...") },
                    onClick = {},
                    enabled = false
                )
            }

            options.forEach { option ->
                val spec = option.spec
                DropdownMenuItem(
                    enabled = option.present,
                    onClick = {
                        expanded = false
                        onSelect(spec)
                    },
                    text = {
                        Column {
                            Text(
                                text = spec.displayName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "~${spec.approxSizeMb} MB - ${spec.maxNumTokens} tokens" +
                                    if (spec.supportsVision) {
                                        " - ${spec.imageTokens} per image"
                                    } else {
                                        " - text only"
                                    },
                                style = MaterialTheme.typography.bodySmall
                            )
                            option.reason?.let { reason ->
                                Text(
                                    text = reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    },
                    trailingIcon = {
                        if (spec == activeSpec) {
                            Text(text = "active", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ProbeRow(
    left: Pair<String, () -> Unit>,
    right: Pair<String, () -> Unit>,
    leftEnabled: Boolean,
    rightEnabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = left.second,
            enabled = leftEnabled,
            modifier = Modifier.weight(1f)
        ) {
            Text(text = left.first, maxLines = 1)
        }
        OutlinedButton(
            onClick = right.second,
            enabled = rightEnabled,
            modifier = Modifier.weight(1f)
        ) {
            Text(text = right.first, maxLines = 1)
        }
    }
}
