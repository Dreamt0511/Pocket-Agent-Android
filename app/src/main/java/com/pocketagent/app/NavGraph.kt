package com.pocketagent.app

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
    object Execute : Screen("execute")
    object History : Screen("history")
    object Skills : Screen("skills")
    object Terminal : Screen("terminal")
    object Config : Screen("config")
    object Overlay : Screen("overlay")
}

@Composable
fun AppNavGraph(navController: NavHostController) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context.settingsDataStore) }
    val settings by settingsRepository.settingsFlow.collectAsState(initial = null)
    val modelConfigured = settings != null &&
            settings!!.llmApiKey != "dummy" &&
            settings!!.llmApiKey.isNotBlank()

    val taskQueueManager = remember { TaskQueueManager() }

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(navController = navController, modelConfigured = modelConfigured)
        }
        composable(Screen.Execute.route) {
            ChatScreen(navController = navController)
        }
        composable(Screen.History.route) {
            HistoryScreen(navController = navController, taskQueueManager = taskQueueManager)
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
