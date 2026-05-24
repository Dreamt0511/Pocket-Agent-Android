package com.pocketagent.app.ui.overlay

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.pocketagent.app.overlay.OverlayManager
import com.pocketagent.app.overlay.OverlayService
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
    val streamText by OverlayService.streamText.collectAsState()
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
            )
            Text(
                text = "控制悬浮窗的显示状态与模式，方便在后台实时追踪 Agent 执行进度",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )

            // 显示/隐藏
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onToggleVisibility,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isVisible) Color(0xFFF44336) else Color(0xFF4CAF50)
                        )
                    ) {
                        Text(if (isVisible) "隐藏悬浮窗" else "显示悬浮窗")
                    }

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
                Text(
                    text = if (isVisible) "悬浮窗已显示${if (mode == "MINI") "，迷你模式仅显示状态图标" else "，展开模式可查看完整输出"}" else "打开悬浮窗，在桌面显示 Agent 运行状态",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }

            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // 快捷操作
            Text(
                text = "快捷操作",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "用于调试和验证流式输出是否正常工作",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { OverlayService.streamText.value = "" },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("清空输出")
                }

                OutlinedButton(
                    onClick = {
                        StreamBridge.out("[测试] 悬浮窗输出测试\n")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("测试输出")
                }
            }
            Text(
                text = "「清空输出」清除所有流式内容，「测试输出」发送一条测试消息验证悬浮窗响应",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
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

// ─── 权限管理 ───────────────────────────────────

@Composable
private fun OverlayPermissionHint() {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(OverlayManager.hasOverlayPermission(context)) }

    // 从设置页面返回后刷新权限状态
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasPermission = OverlayManager.hasOverlayPermission(context)
    }

    // 从后台切换回前台时刷新权限状态（用户可能在系统设置中更改）
    val lifecycleOwner = (LocalContext.current as? ComponentActivity)
    val lifecycle = lifecycleOwner?.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = OverlayManager.hasOverlayPermission(context)
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    if (hasPermission) {
        // ─── 已授权 ───
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFD4EDDA))
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("✓", fontSize = 20.sp, color = Color(0xFF155724))
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "悬浮窗权限已授予",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF155724)
                    )
                    Text(
                        text = "可在其他应用上层正常显示",
                        fontSize = 12.sp,
                        color = Color(0xFF155724).copy(alpha = 0.8f)
                    )
                }
                OutlinedButton(
                    onClick = {
                        permissionLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                ) {
                    Text("管理", fontSize = 12.sp)
                }
            }
        }
    } else {
        // ─── 未授权 → 直接引导授权 ───
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD))
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
                        text = "需要悬浮窗权限",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF856404)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "开启后方可在其他应用上层显示悬浮窗，实时查看 Agent 执行状态。",
                    fontSize = 12.sp,
                    color = Color(0xFF856404)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        permissionLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF856404)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("去授权")
                }

                Text(
                    text = "点击「去授权」将直接跳转到系统设置页，开启「在其他应用上层显示」后返回即可",
                    fontSize = 11.sp,
                    color = Color(0xFF856404).copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                )
            }
        }
    }
}