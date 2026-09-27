package com.example.capstone.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.capstone.domain.model.StudentAssignment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignmentsScreen(
    onNavigateBack: () -> Unit,
    onAssignmentClick: (questionId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AssignmentsViewModel = viewModel(factory = AssignmentsViewModel.Factory)
) {
    val uiState = viewModel.uiState

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.courseTitle.ifBlank { "Assignments" }) },
                navigationIcon = { TextButton(onClick = onNavigateBack) { Text("Back") } },
                actions = { TextButton(onClick = viewModel::load) { Text("Refresh") } }
            )
        }
    ) { innerPadding ->
        Box(modifier = modifier.padding(innerPadding).fillMaxSize()) {
            when (uiState) {
                is AssignmentsUiState.Loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is AssignmentsUiState.Error -> Column(
                    Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(uiState.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = viewModel::load) { Text("Retry") }
                }
                is AssignmentsUiState.Success ->
                    if (uiState.assignments.isEmpty()) {
                        Text(
                            "No assignments in this course yet.",
                            Modifier.align(Alignment.Center).padding(16.dp)
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(uiState.assignments, key = { it.questionId }) { assignment ->
                                AssignmentCard(assignment) { onAssignmentClick(assignment.questionId) }
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun AssignmentCard(assignment: StudentAssignment, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(assignment.title, style = MaterialTheme.typography.titleLarge)
            val pages = assignment.pageCount?.let { " · $it page(s)" } ?: ""
            Text("${assignment.totalMarks} mark(s)$pages", style = MaterialTheme.typography.bodyMedium)
            Text(assignment.status, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
