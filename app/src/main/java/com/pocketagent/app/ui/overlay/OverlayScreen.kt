package com.pocketagent.app.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.pocketagent.app.overlay.OverlayManager
import com.pocketagent.app.overlay.StreamBridge
import com.pocketagent.app.ui.theme.GlassCard
import kotlinx.coroutines.launch

/**
 * 悬浮窗控制页面
 * 
 * 功能:
 * 1. 悬浮窗显示/隐藏控制
 * 2. 模式切换 (MINI/EXPANDED)
 * 3. 实时状态监控
 * 4. 流式输出查看
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    var isOverlayVisible by remember { mutableStateOf(false) }
    var overlayMode by remember { mutableStateOf("MINI") }
    var streamText by remember { mutableStateOf("") }
    var taskStatus by remember { mutableStateOf("空闲") }

    // 监听悬浮窗状态
    LaunchedEffect(Unit) {
        // TODO: 监听 OverlayService 状态
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("悬浮窗控制") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 状态卡片
            OverlayStatusCard(
                isVisible = isOverlayVisible,
                mode = overlayMode,
                status = taskStatus
            )

            // 控制面板
            OverlayControlPanel(
                isVisible = isOverlayVisible,
                mode = overlayMode,
                onToggleVisibility = {
                    scope.launch {
                        if (isOverlayVisible) {
                            OverlayManager.hide()
                        } else {
                            OverlayManager.showMini()
                        }
                        isOverlayVisible = !isOverlayVisible
                    }
                },
                onSwitchMode = {
                    scope.launch {
                        if (overlayMode == "MINI") {
                            OverlayManager.showExpanded()
                            overlayMode = "EXPANDED"
                        } else {
                            OverlayManager.minimize()
                            overlayMode = "MINI"
                        }
                    }
                }
            )

            // 流式输出预览
            OverlayStreamPreview(streamText)

            // 权限提示
            OverlayPermissionHint()
        }
    }
}

// ─── 状态卡片 ───────────────────────────────────

@Composable
private fun OverlayStatusCard(
    isVisible: Boolean,
    mode: String,
    status: String
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = "悬浮窗状态",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isVisible) "正在显示" else "已隐藏",
                        fontSize = 14.sp,
                        color = if (isVisible) Color.Green else Color.Gray
                    )
                }

                // 状态指示灯
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(
                            color = if (isVisible) Color.Green else Color.Gray,
                            shape = RoundedCornerShape(50)
                        )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 详细信息
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column {
                    Text(
                        text = "模式",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = mode,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Column {
                    Text(
                        text = "任务状态",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = status,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = when (status) {
                            "空闲" -> Color.Green
                            "执行中" -> Color.Blue
                            "错误" -> Color.Red
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}

// ─── 控制面板 ───────────────────────────────────

@Composable
private fun OverlayControlPanel(
    isVisible: Boolean,
    mode: String,
    onToggleVisibility: () -> Unit,
    onSwitchMode: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "控制面板",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 显示/隐藏按钮
                Button(
                    onClick = onToggleVisibility,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isVisible) Color(0xFFF44336) else Color(0xFF4CAF50)
                    )
                ) {
                    Text(if (isVisible) "隐藏悬浮窗" else "显示悬浮窗")
                }

                // 模式切换按钮
                Button(
                    onClick = onSwitchMode,
                    modifier = Modifier.weight(1f),
                    enabled = isVisible,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2196F3)
                    )
                ) {
                    Text(if (mode == "MINI") "展开视图" else "折叠视图")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 快捷操作
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        // 清空输出
                        // TODO: 清空 StreamBridge
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("清空输出")
                }

                OutlinedButton(
                    onClick = {
                        // 测试输出
                        StreamBridge.out("[测试] 悬浮窗输出测试\n")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("测试输出")
                }
            }
        }
    }
}

// ─── 流式输出预览 ───────────────────────────────

@Composable
private fun OverlayStreamPreview(streamText: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A2E)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Text(
                text = "实时输出预览",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 输出内容
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F0F1A), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = if (streamText.isNotBlank()) streamText else "暂无输出",
                    fontSize = 11.sp,
                    color = Color(0xFFCCCCCC),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

// ─── 权限提示 ───────────────────────────────────

@Composable
private fun OverlayPermissionHint() {
    var showPermissionDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFF3CD)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("⚠️", fontSize = 20.sp)
                Text(
                    text = "悬浮窗权限",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF856404)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "如果悬浮窗无法显示，请检查是否已授予「在其他应用上层显示」权限。",
                fontSize = 12.sp,
                color = Color(0xFF856404)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { showPermissionDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF856404)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("检查权限")
            }
        }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("悬浮窗权限") },
            text = {
                Text("""
                    悬浮窗需要「在其他应用上层显示」权限。
                    
                    1. 打开「系统设置」
                    2. 进入「应用管理」
                    3. 找到「Pocket Agent」
                    4. 开启「在其他应用上层显示」
                    
                    开启后返回应用即可使用悬浮窗功能。
                """.trimIndent())
            },
            confirmButton = {
                Button(onClick = { showPermissionDialog = false }) {
                    Text("知道了")
                }
            }
        )
    }
}