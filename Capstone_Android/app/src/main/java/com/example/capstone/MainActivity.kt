package com.example.capstone

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.capstone.data.auth.AuthState
import com.example.capstone.ui.screens.*
import com.example.capstone.ui.theme.CapstoneTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CapstoneTheme {
                CapstoneApp()
            }
        }
    }
}

private const val SIGN_IN = "signin"

@Composable
fun CapstoneApp() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val application = context.applicationContext as CapstoneApplication
    val authState by application.container.authRepository.state.collectAsState()

    // Sign-out, or a 401 the interceptor could not recover from: back to sign-in,
    // with nothing left on the back stack.
    LaunchedEffect(authState) {
        val route = navController.currentDestination?.route
        if (authState is AuthState.SignedOut && route != null && route != SIGN_IN) {
            navController.navigate(SIGN_IN) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = SIGN_IN,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(SIGN_IN) {
                SignInScreen(
                    onSignedIn = {
                        navController.navigate("courses") {
                            popUpTo(SIGN_IN) { inclusive = true }
                        }
                    }
                )
            }
            composable("courses") {
                HomeScreen(
                    onCourseClick = { course ->
                        navController.navigate("assignments/${course.id}?title=${Uri.encode(course.title)}")
                    },
                    // TEMPORARY: debug entry point for the on-device model.
                    onOpenModelTest = {
                        navController.navigate("model_test")
                    }
                )
            }
            // TEMPORARY debug route, debug builds only. Remove with ModelTestScreen.
            if (BuildConfig.DEBUG) {
                composable("model_test") {
                    ModelTestScreen(
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }
            composable(
                "assignments/{courseId}?title={title}",
                arguments = listOf(
                    navArgument("courseId") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" }
                )
            ) {
                AssignmentsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onAssignmentClick = { questionId ->
                        navController.navigate("assignment_detail/$questionId")
                    }
                )
            }
            composable("assignment_detail/{questionId}") {
                AssignmentDetailScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onStartScan = { questionId ->
                        navController.navigate("scan/$questionId")
                    },
                    onOpenMarks = { questionId ->
                        navController.navigate("grade/$questionId")
                    }
                )
            }
            composable("scan/{assignmentId}") {
                ScanScreen(
                    onNavigateBack = { navController.popBackStack() },
                    // The crops do not travel through the back stack: they are
                    // megabytes of PNG, saved per page in PagePhotoStore, and
                    // this hop carries only the id.
                    onHandedIn = { assignmentId, submissionId ->
                        navController.navigate("grade/$assignmentId?submissionId=${Uri.encode(submissionId.orEmpty())}") {
                            // Back from the marks goes to the paper, not to the finished scan.
                            popUpTo("scan/{assignmentId}") { inclusive = true }
                        }
                    }
                )
            }
            // After hand-in: the grading run (work/GradingWorker), then the marks. The
            // submission id is optional; without it the screen finds it from the list.
            composable(
                "grade/{assignmentId}?submissionId={submissionId}",
                arguments = listOf(
                    navArgument("assignmentId") { type = NavType.StringType },
                    navArgument("submissionId") { type = NavType.StringType; defaultValue = "" }
                )
            ) {
                WorksheetGradingScreen(
                    onDone = {
                        navController.navigate("courses") {
                            popUpTo("courses") { inclusive = true }
                        }
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
