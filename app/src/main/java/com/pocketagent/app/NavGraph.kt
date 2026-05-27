package com.pocketagent.app

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.pocketagent.app.data.SettingsRepository
import com.pocketagent.app.data.settingsDataStore
import com.pocketagent.app.service.TaskQueueManager
import com.pocketagent.app.ui.home.HomeScreen
import com.pocketagent.app.ui.chat.ChatScreen
import com.pocketagent.app.ui.config.ConfigScreen
import com.pocketagent.app.ui.terminal.TerminalScreen
import com.pocketagent.app.ui.overlay.OverlayScreen
import com.pocketagent.app.ui.screens.history.HistoryScreen
import com.pocketagent.app.ui.screens.skills.SkillsScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Execute : Screen("execute/{conversationId}") {
        fun createRoute(conversationId: String = "") = "execute/$conversationId"
    }
    object History : Screen("history")
    object Skills : Screen("skills")
    object Terminal : Screen("terminal")
    object Config : Screen("config")
    object Overlay : Screen("overlay")
}

@Composable
fun AppNavGraph(navController: NavHostController) {
    val context = LocalContext.current
    val taskQueueManager = remember { TaskQueueManager() }

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            val settingsRepo = remember { SettingsRepository(context.settingsDataStore) }
            val settings by settingsRepo.settingsFlow.collectAsState(initial = null)
            val modelConfigured = settings?.let {
                    it.llmApiKey.isNotBlank() && it.llmApiKey != "dummy"
                } ?: false
            HomeScreen(
                navController = navController,
                modelConfigured = modelConfigured,
                settingsRepo = settingsRepo
            )
        }
        composable(
            route = Screen.Execute.route,
            arguments = listOf(
                navArgument("conversationId") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val convId = backStackEntry.arguments?.getString("conversationId") ?: ""
            ChatScreen(
                navController = navController,
                conversationId = if (convId.isBlank() || convId == "new") null else convId
            )
        }
        composable(Screen.History.route) {
            HistoryScreen(navController = navController)
        }
        composable(Screen.Skills.route) {
            SkillsScreen(navController = navController)
        }
        composable(Screen.Terminal.route) {
            TerminalScreen(navController = navController)
        }
        composable(Screen.Config.route) {
            ConfigScreen(navController = navController)
        }
        composable(Screen.Overlay.route) {
            OverlayScreen(navController = navController)
        }
    }
}
