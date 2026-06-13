package com.example.ui

import androidx.compose.runtime.*
import androidx.navigation.*
import androidx.navigation.compose.*
import com.example.ui.editor.EditorScreen
import com.example.ui.home.HomeScreen
import com.example.ui.poses.PoseEditorScreen
import com.example.ui.settings.SettingsScreen
import com.example.viewmodel.MainViewModel

private const val HOME    = "home"
private const val EDITOR  = "editor/{projectId}"
private const val POSES   = "poses"
private const val SETTINGS = "settings"

@Composable
fun MainNavGraph(vm: MainViewModel) {
    val navController = rememberNavController()

    // Navigate to newly created projects automatically
    val projects by vm.projects.collectAsState()
    var lastCount by remember { mutableIntStateOf(projects.size) }
    LaunchedEffect(projects) {
        if (projects.size > lastCount && projects.isNotEmpty()) {
            navController.navigate("editor/${projects.first().id}")
        }
        lastCount = projects.size
    }

    NavHost(navController = navController, startDestination = HOME) {

        composable(HOME) {
            HomeScreen(
                vm           = vm,
                onOpenProject = { id -> navController.navigate("editor/$id") },
                onOpenPoses  = { navController.navigate(POSES) },
                onOpenSettings = { navController.navigate(SETTINGS) }
            )
        }

        composable(
            route = EDITOR,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { backStack ->
            val projectId = backStack.arguments?.getString("projectId") ?: return@composable
            EditorScreen(
                projectId       = projectId,
                vm              = vm,
                onBack          = { navController.popBackStack() },
                onOpenPoseLibrary = { navController.navigate(POSES) }
            )
        }

        composable(POSES) {
            // Check whether we came from the editor (pick mode) or home
            val parentEntry = navController.previousBackStackEntry
            val fromEditor  = parentEntry?.destination?.route?.startsWith("editor") == true

            PoseEditorScreen(
                vm   = vm,
                onBack = { navController.popBackStack() },
                onPoseSelected = if (fromEditor) { poseId ->
                    // Pass selected pose back to editor via ViewModel
                    val projectId = parentEntry?.arguments?.getString("projectId")
                    if (projectId != null) {
                        vm.insertScriptEvent(poseId, vm.activeProject.value
                            ?.script?.events?.lastOrNull()?.let { it.timeSec + it.duration + 0.5f } ?: 0f)
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
