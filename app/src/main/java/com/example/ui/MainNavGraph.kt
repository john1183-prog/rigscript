package com.example.ui

import androidx.compose.runtime.*
import androidx.navigation.*
import androidx.navigation.compose.*
import com.example.ui.editor.EditorScreen
import com.example.ui.home.HomeScreen
import com.example.ui.poses.PoseEditorScreen
import com.example.ui.settings.SettingsScreen
import com.example.viewmodel.MainViewModel

private const val HOME     = "home"
private const val EDITOR   = "editor/{projectId}"
private const val POSES    = "poses"
private const val SETTINGS = "settings"

@Composable
fun MainNavGraph(vm: MainViewModel) {
    val navController = rememberNavController()

    // Navigate into a project right after it's created. Driven by an explicit
    // one-shot signal from the ViewModel rather than "projects.size increased",
    // which would ALSO fire on cold start once Room's Flow emits an existing
    // project list (size goes 0 -> N) and force-navigate away from Home.
    LaunchedEffect(Unit) {
        vm.navigateToProject.collect { id ->
            navController.navigate("editor/$id")
        }
    }

    NavHost(navController = navController, startDestination = HOME) {

        composable(HOME) {
            HomeScreen(
                vm             = vm,
                onOpenProject  = { id -> navController.navigate("editor/$id") },
                onOpenPoses    = { navController.navigate(POSES) },
                onOpenSettings = { navController.navigate(SETTINGS) }
            )
        }

        composable(
            route = EDITOR,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { backStack ->
            val projectId = backStack.arguments?.getString("projectId") ?: return@composable
            EditorScreen(
                projectId         = projectId,
                vm                = vm,
                onBack            = { navController.popBackStack() },
                onOpenPoseLibrary = { navController.navigate(POSES) }
            )
        }

        composable(POSES) {
            val parentEntry = navController.previousBackStackEntry
            val fromEditor  = parentEntry?.destination?.route?.startsWith("editor") == true

            PoseEditorScreen(
                vm   = vm,
                onBack = { navController.popBackStack() },
                onPoseSelected = if (fromEditor) { poseId ->
                    val projectId = parentEntry?.arguments?.getString("projectId")
                    if (projectId != null) {
                        // Position the new event after the LATEST timestamp, not
                        // the last list element — the script may have been
                        // hand-edited with out-of-order timestamps.
                        val atSec = vm.activeProject.value
                            ?.script?.events?.maxByOrNull { it.timeSec }
                            ?.let { it.timeSec + it.duration + 0.5f } ?: 0f
                        vm.insertScriptEvent(poseId, atSec)
                    }
                    navController.popBackStack()
                } else null
            )
        }

        composable(SETTINGS) {
            SettingsScreen(vm = vm, onBack = { navController.popBackStack() })
        }
    }
}
