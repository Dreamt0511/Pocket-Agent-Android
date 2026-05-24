package com.pocketagent.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController

/**
 * 首页 — Ollama 风格极简文字导航
 *
 * 入口基于主仓库核心能力设计：
 *  执行任务 → 主入口，操控手机完成任务
 *  历史记录 → 过往任务回顾
 *  技能库   → Agent 已掌握的技能
 *  终端     → Shell 调试
 *  设置     → 模型配置
 *  悬浮窗   → 后台执行视图
 */
@Composable
fun HomeScreen(navController: NavController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(56.dp))
        Text(
            text = "Pocket Agent",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.3).sp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(48.dp))

        NavItem("执行任务", "用 AI 操控手机，一步到位", onClick = { navController.navigate("execute") })
        NavItem("历史记录", "查看过往任务与执行结果", onClick = { navController.navigate("history") })
        NavItem("技能库", "Agent 已掌握的技能与工具", onClick = { navController.navigate("skills") })
        NavItem("终端", "命令行与调试控制台", onClick = { navController.navigate("terminal") })
        NavItem("设置", "模型 / MCP / 高级参数", onClick = { navController.navigate("config") })
        NavItem("悬浮窗", "后台执行与状态监控", onClick = { navController.navigate("overlay") })

        Spacer(Modifier.weight(1f))
        Text(
            text = "Agent 就绪",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            modifier = Modifier.padding(bottom = 24.dp)
        )
    }
}

@Composable
private fun NavItem(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp)
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = subtitle,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            letterSpacing = 0.1.sp
        )
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        )
    }
}

@Preview(showBackground = true, showSystemUi = true, name = "首页亮色")
@Composable
private fun HomeScreenLightPreview() {
    com.pocketagent.app.ui.theme.PocketAgentTheme(darkTheme = false) {
        HomeScreen(navController = rememberNavController())
    }
}

@Preview(showBackground = true, showSystemUi = true, name = "首页暗色")
@Composable
private fun HomeScreenDarkPreview() {
    com.pocketagent.app.ui.theme.PocketAgentTheme(darkTheme = true) {
        HomeScreen(navController = rememberNavController())
    }
}
