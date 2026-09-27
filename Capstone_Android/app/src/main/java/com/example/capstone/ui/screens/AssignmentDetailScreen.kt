package com.example.capstone.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.capstone.data.remote.PackBoxDto
import java.text.DateFormat
import java.util.Date

/**
 * The downloaded pack: title, pages and answer boxes. The pack holds the
 * answer key; this screen never shows it (no model answer text or images).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignmentDetailScreen(
    onNavigateBack: () -> Unit,
    onStartScan: (questionId: String) -> Unit,
    /** Grading progress and marks, for a paper already handed in. */
    onOpenMarks: (questionId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AssignmentDetailViewModel = viewModel(factory = AssignmentDetailViewModel.Factory)
) {
    val uiState = viewModel.uiState

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assignment") },
                navigationIcon = { TextButton(onClick = onNavigateBack) { Text("Back") } }
            )
        }
    ) { innerPadding ->
        Box(modifier = modifier.padding(innerPadding).fillMaxSize()) {
            when (uiState) {
                is AssignmentDetailUiState.Loading -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Text("Downloading the assignment…")
                }
                is AssignmentDetailUiState.Error -> Column(
                    Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(uiState.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = viewModel::download) { Text("Retry") }
                }
                is AssignmentDetailUiState.Success -> {
                    val pack = uiState.cached.pack
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text(
                            pack.title?.takeIf { it.isNotBlank() } ?: "Untitled paper",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            "${pack.pageCount ?: "?"} page(s) · ${pack.boxes.size} answer box(es) · " +
                                "${pack.boxes.sumOf { it.points ?: 0 }} mark(s)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Saved on this phone " +
                                DateFormat.getDateTimeInstance().format(Date(uiState.cached.savedAtMillis)),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (uiState.cached.missingImages.isNotEmpty()) {
                            Text(
                                "${uiState.cached.missingImages.size} image(s) didn't download. " +
                                    "Download again before grading.",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        uiState.downloadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                        Spacer(Modifier.height(12.dp))
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(pack.boxes, key = { it.id }) { box -> BoxItem(box) }
                        }

                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = viewModel::download,
                            enabled = !uiState.downloading,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (uiState.downloading) "Downloading…" else "Download again") }
                        Button(
                            onClick = { onStartScan(viewModel.questionId) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Photograph and hand in") }
                        OutlinedButton(
                            onClick = { onOpenMarks(viewModel.questionId) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Grading and marks") }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxItem(box: PackBoxDto) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            val label = box.label?.takeIf { it.isNotBlank() } ?: "Box ${box.orderIndex + 1}"
            val pages = box.segments?.map { it.firstOrNull() }?.distinct()?.filterNotNull()
            val where = when {
                pages != null && pages.size > 1 -> "pages ${pages.joinToString { "${it + 1}" }}"
                box.pageIndex != null -> "page ${box.pageIndex + 1}"
                else -> "page ?"
            }
            Text("$label · ${box.points ?: "?"} mark(s) · $where", style = MaterialTheme.typography.titleSmall)
            if (box.questionText.isNotBlank()) {
                Text(box.questionText, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
            box.blockedReason?.let {
                Text("Can't be marked on the phone: $it", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
