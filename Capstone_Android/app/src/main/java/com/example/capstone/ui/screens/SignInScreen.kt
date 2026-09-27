package com.example.capstone.ui.screens

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.capstone.BuildConfig
import com.example.capstone.domain.model.Me

@Composable
fun SignInScreen(
    onSignedIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SignInViewModel = viewModel(factory = SignInViewModel.Factory)
) {
    val state = viewModel.uiState
    val activity = LocalActivity.current

    LaunchedEffect(state) {
        if (state is SignInUiState.Ready) onSignedIn()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Script Checker", style = MaterialTheme.typography.headlineMedium)
        Text("For students", style = MaterialTheme.typography.titleMedium)

        when (state) {
            SignInUiState.Checking, SignInUiState.Working, is SignInUiState.Ready ->
                CircularProgressIndicator()

            is SignInUiState.NotConfigured -> {
                Text("This build can't sign in yet.", color = MaterialTheme.colorScheme.error)
                state.problems.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }

            is SignInUiState.SignedOut -> {
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = { activity?.let(viewModel::signIn) },
                    enabled = activity != null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Sign in with Microsoft") }
            }

            is SignInUiState.NotAStudent -> {
                WhoAmI(state.me)
                Text(
                    "This app is for students; teachers use the website.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign out")
                }
            }

            is SignInUiState.Error -> {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = viewModel::retry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                OutlinedButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign out")
                }
            }
        }

        Text(
            "Web end: ${BuildConfig.BASE_URL.ifBlank { "(not set)" }} · ${BuildConfig.FLAVOR}",
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun WhoAmI(me: Me, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(me.displayName, style = MaterialTheme.typography.titleLarge)
        Text(me.email, style = MaterialTheme.typography.bodyMedium)
        Text("Role: ${me.role}", style = MaterialTheme.typography.bodyMedium)
    }
}
