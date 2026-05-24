package com.pocketagent.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
    val taskQueueManager = remember { TaskQueueManager() }

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(navController = navController)
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
