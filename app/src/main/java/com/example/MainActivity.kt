package com.example

import android.app.Application
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.data.local.ProjectStatus
import com.example.security.configureAppCheck
import com.example.ui.navigation.AudioSettingsRoute
import com.example.ui.navigation.DubbingSettingsRoute
import com.example.ui.navigation.ExportRoute
import com.example.ui.navigation.HomeRoute
import com.example.ui.navigation.ImportMovieRoute
import com.example.ui.navigation.ProcessingRoute
import com.example.ui.navigation.ReviewRoute
import com.example.ui.navigation.SettingsRoute
import com.example.ui.screens.AudioSettingsScreen
import com.example.ui.screens.DubbingSettingsScreen
import com.example.ui.screens.ExportScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ImportMovieScreen
import com.example.ui.screens.ProcessingScreen
import com.example.ui.screens.ReviewScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.HindiDubStudioTheme
import com.example.ui.viewmodel.ProjectStudioViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure Firebase App Check with launch intent before any UI or ViewModels
        configureAppCheck(this, intent)

        // STEP 2: Enable Edge-to-Edge with light system icons on our dark studio theme (Android 12–16+)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            HindiDubStudioTheme {
                DubbingStudioNavHost()
            }
        }
    }
}

@Composable
fun DubbingStudioNavHost() {
    val navController = rememberNavController()
    val application = LocalContext.current.applicationContext as Application

    NavHost(
        navController = navController,
        startDestination = HomeRoute
    ) {
        composable<HomeRoute> {
            HomeScreen(
                onSelectMovie = { navController.navigate(ImportMovieRoute) },
                onOpenProject = { project ->
                    when (project.status) {
                        ProjectStatus.COMPLETED -> navController.navigate(ExportRoute(project.id))
                        ProjectStatus.READY_FOR_REVIEW -> navController.navigate(ReviewRoute(project.id))
                        ProjectStatus.PROCESSING, ProjectStatus.PAUSED, ProjectStatus.ERROR ->
                            navController.navigate(ProcessingRoute(project.id))
                        else -> navController.navigate(DubbingSettingsRoute(project.id))
                    }
                },
                onOpenSettings = { navController.navigate(SettingsRoute) }
            )
        }

        composable<ImportMovieRoute> {
            ImportMovieScreen(
                onBack = { navController.popBackStack() },
                onProjectCreated = { projectId ->
                    navController.navigate(DubbingSettingsRoute(projectId)) {
                        popUpTo(ImportMovieRoute) { inclusive = true }
                    }
                }
            )
        }

        composable<DubbingSettingsRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<DubbingSettingsRoute>()
            val vm: ProjectStudioViewModel = viewModel(
                key = "project_vm_${route.projectId}",
                factory = ProjectStudioViewModel.factory(application, route.projectId)
            )
            DubbingSettingsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onNextAudioSettings = {
                    navController.navigate(AudioSettingsRoute(route.projectId))
                }
            )
        }

        composable<AudioSettingsRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<AudioSettingsRoute>()
            val vm: ProjectStudioViewModel = viewModel(
                key = "project_vm_${route.projectId}",
                factory = ProjectStudioViewModel.factory(application, route.projectId)
            )
            AudioSettingsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onGoToProcessing = {
                    navController.navigate(ProcessingRoute(route.projectId))
                }
            )
        }

        composable<ProcessingRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ProcessingRoute>()
            val vm: ProjectStudioViewModel = viewModel(
                key = "project_vm_${route.projectId}",
                factory = ProjectStudioViewModel.factory(application, route.projectId)
            )
            ProcessingScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenAudioSettings = {
                    navController.navigate(AudioSettingsRoute(route.projectId))
                },
                onOpenReview = {
                    navController.navigate(ReviewRoute(route.projectId))
                },
                onOpenExport = {
                    navController.navigate(ExportRoute(route.projectId))
                }
            )
        }

        composable<ReviewRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ReviewRoute>()
            val vm: ProjectStudioViewModel = viewModel(
                key = "project_vm_${route.projectId}",
                factory = ProjectStudioViewModel.factory(application, route.projectId)
            )
            ReviewScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onGoToExport = {
                    navController.navigate(ExportRoute(route.projectId))
                }
            )
        }

        composable<ExportRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ExportRoute>()
            val vm: ProjectStudioViewModel = viewModel(
                key = "project_vm_${route.projectId}",
                factory = ProjectStudioViewModel.factory(application, route.projectId)
            )
            ExportScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() }
            )
        }

        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
