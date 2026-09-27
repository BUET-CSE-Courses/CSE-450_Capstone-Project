package com.example.capstone.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.example.capstone.BuildConfig
import com.example.capstone.CapstoneApplication
import com.example.capstone.data.local.ModelSpec
import com.example.capstone.domain.model.Course
import kotlinx.coroutines.launch

/** Courses: who is signed in, join by code, and the courses they take. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onCourseClick: (Course) -> Unit,
    onOpenModelTest: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
) {
    val uiState = viewModel.uiState
    val me by viewModel.me.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My courses") },
                actions = {
                    // TEMPORARY debug button, debug builds only. Remove with ModelTestScreen.
                    if (BuildConfig.DEBUG) {
                        TextButton(onClick = onOpenModelTest) { Text("Model") }
                    }
                    TextButton(onClick = viewModel::signOut) { Text("Sign out") }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            me?.let {
                Text(
                    "${it.displayName} · ${it.role}",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = viewModel.joinCode,
                    onValueChange = { viewModel.joinCode = it.take(32) },
                    label = { Text("Join code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(onGo = { viewModel.join() }),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = viewModel::join,
                    enabled = !viewModel.joining && viewModel.joinCode.isNotBlank()
                ) { Text(if (viewModel.joining) "Joining…" else "Join") }
            }
            viewModel.joinMessage?.let {
                Text(
                    it,
                    color = if (viewModel.joinFailed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }

            if (BuildConfig.DEBUG) {
                FallbackDebugToggle()
                GradingDebugControls()
            }

            when (uiState) {
                is HomeUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is HomeUiState.Success -> CourseList(uiState.courses, onCourseClick)
                is HomeUiState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(uiState.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = viewModel::loadCourses) { Text("Retry") }
                }
            }
        }
    }
}

/**
 * Debug builds only: overrides `BuildConfig.FALLBACK_ENABLED` for the next grading run
 * (whether low-confidence boxes are sent to the server's model or straight to review).
 */
@Composable
private fun FallbackDebugToggle() {
    val setting = (LocalContext.current.applicationContext as CapstoneApplication).container.fallbackSetting
    val enabled by setting.enabled.collectAsState(initial = BuildConfig.FALLBACK_ENABLED)
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Server re-marking (debug)", style = MaterialTheme.typography.labelLarge)
            Text(
                "Build default: ${if (BuildConfig.FALLBACK_ENABLED) "on" else "off"}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(checked = enabled, onCheckedChange = { value -> scope.launch { setting.setOverride(value) } })
    }
}

/**
 * Debug builds only (session 9): which model grades the next boxes, crop trim, plain instead of
 * two-turn grading, and force server re-mark. Defaults: Gemma 4 E2B, two-turn, the rest off.
 */
@Composable
private fun GradingDebugControls() {
    val settings = (LocalContext.current.applicationContext as CapstoneApplication).container.gradingDebugSettings
    val model by settings.model.collectAsState(initial = ModelSpec.DEFAULT)
    val trim by settings.trimCrops.collectAsState(initial = false)
    val twoTurn by settings.twoTurn.collectAsState(initial = true)
    val forceRemark by settings.forceRemark.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    Column {
        Text("Grading model (debug)", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelSpec.GRADING_CHOICES.forEach { spec ->
                FilterChip(
                    selected = spec == model,
                    onClick = { scope.launch { settings.setModel(spec) } },
                    label = { Text(spec.displayName.substringBefore(" (")) }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Trim crops to the writing (debug)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Switch(checked = trim, onCheckedChange = { value -> scope.launch { settings.setTrimCrops(value) } })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Plain grading, one turn (debug)", style = MaterialTheme.typography.labelLarge)
                Text("Off = two-turn, the default.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = !twoTurn, onCheckedChange = { value -> scope.launch { settings.setPlainGrading(value) } })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Force server re-mark (debug)", style = MaterialTheme.typography.labelLarge)
                Text("Every box is sent for the server's re-mark.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = forceRemark, onCheckedChange = { value -> scope.launch { settings.setForceRemark(value) } })
        }
    }
}

@Composable
private fun CourseList(courses: List<Course>, onCourseClick: (Course) -> Unit) {
    if (courses.isEmpty()) {
        Text("You haven't joined a course yet. Ask your teacher for the join code.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(courses, key = { it.id }) { course ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCourseClick(course) }
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(course.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${course.teacherName} · ${course.studentCount} student(s)" +
                            if (course.archived) " · archived" else "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
