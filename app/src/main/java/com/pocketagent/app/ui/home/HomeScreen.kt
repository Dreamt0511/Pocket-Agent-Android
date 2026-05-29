package com.pocketagent.app.ui.home

import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.res.painterResource
import com.pocketagent.app.R
import android.net.Uri
import androidx.compose.animation.core.*
import kotlinx.coroutines.delay


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector


import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.pocketagent.app.data.SettingsRepository
import com.pocketagent.app.ui.components.AnimatedBackground
import com.pocketagent.app.ui.theme.*
import com.pocketagent.app.core.AgentDaemon
import com.pocketagent.app.core.AppBootstrapper
import com.pocketagent.app.core.TermuxLauncher
import com.pocketagent.app.core.TermuxServiceClient
import com.pocketagent.app.core.ScriptProgress
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class NavEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: String
)

private val navEntries = listOf(
    NavEntry("智能对话", "懂你的 AI，替你行动", Icons.Default.PlayArrow, "execute/new"),
    NavEntry("设置", "模型 / MCP / 高级参数", Icons.Default.Settings, "config"),
    NavEntry("技能库", "Agent 已掌握的技能与工具", Icons.Default.Psychology, "skills"),
    NavEntry("悬浮窗", "后台执行与状态监控", Icons.Default.PictureInPicture, "overlay"),
    NavEntry("历史记录", "查看过往任务与执行结果", Icons.Default.History, "history"),

)

@Composable
fun HomeScreen(navController: NavController, modelConfigured: Boolean, settingsRepo: SettingsRepository) {
    val context = LocalContext.current
    val isPythonReady = remember { mutableStateOf(false) } // 不再使用内嵌 Python
    val settings by settingsRepo.settingsFlow.collectAsState(initial = null)
    val daemonStatus by AppBootstrapper.daemonStatus.collectAsState()
    var initialSetupDone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        initialSetupDone = settingsRepo.isInitialSetupDone()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ─── 液态玻璃动态背景 ───
        AnimatedBackground(
            orbColors = orbColors(),
            modifier = Modifier.fillMaxSize()
        )

        // ─── 前景内容 ───
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(48.dp))

            // 标题区域（渐入动画）
            AnimatedStaggeredItem(delayMs = 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column {
                        Text(
                            text = "Pocket Agent",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.5f).sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "口袋里的 AI 管家",
                            fontSize = 13.sp,
                            letterSpacing = (0.3f).sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        IconButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Dreamt0511"))
                                context.startActivity(intent)
                            }
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF24292F),
                                modifier = Modifier.size(28.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_github),
                                        contentDescription = "GitHub",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = "作者主页",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ─── Agent 状态总览 ───
            AnimatedStaggeredItem(delayMs = 60) {
                AgentOverviewCard(
                    daemonStatus = daemonStatus,
                    modelConfigured = modelConfigured,
                    initialSetupDone = initialSetupDone,
                    navController = navController
                )
            }
            Spacer(Modifier.height(12.dp))

            // ─── 环境配置卡片（常驻首页） ───
            if (true) {
                AnimatedStaggeredItem(delayMs = 80) {
                    TermuxStatusCard(
                        currentMirrorUrl = settings?.pypiMirrorUrl ?: "",
                        settingsRepo = settingsRepo,
                        initialSetupDone = initialSetupDone,
                        daemonStatus = daemonStatus,
                        onLaunch = { mirrorUrl ->
                            TermuxLauncher.launchFastAPI(context, mirrorUrl)
                        }
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            // ─── 导航项列表（错峰入场） ───
            navEntries.forEachIndexed { index, entry ->
                AnimatedStaggeredItem(delayMs = 100 + index * 80) {
                    PressBounceContainer(onClick = { navController.navigate(entry.route) }) {
                        NavCard(entry = entry)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ─── 导航卡片 ──────────────────────────────────

@Composable
private fun NavCard(entry: NavEntry) {
    GlassCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标圆形容器
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(navIconBackground()),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.width(14.dp))

            // 文字区域
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (0.2f).sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = entry.subtitle,
                    fontSize = 12.5.sp,
                    letterSpacing = (0.1f).sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }

            // 箭头指示
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
            )
        }
    }
}

// ─── Agent 状态总览卡片 ────────────────────────

@Composable
private fun AgentOverviewCard(
    daemonStatus: AgentDaemon.DaemonStatus,
    modelConfigured: Boolean,
    initialSetupDone: Boolean,
    navController: NavController
) {
    val serviceReady = daemonStatus is AgentDaemon.DaemonStatus.Ready
    // 服务已连通 = 环境依赖 OK（不需要强制重新 bootstrap）
    val envReady = serviceReady || initialSetupDone
    val allReady = envReady && serviceReady && modelConfigured

    val statusColor = if (allReady) Color(0xFF4CAF50) else Color(0xFFFF9800)
    val statusText = when {
        !envReady -> "环境未就绪"
        !serviceReady -> "服务未连接"
        !modelConfigured -> "模型未配置"
        else -> "Agent 已就绪"
    }

    GlassCard(
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            // 标题行 — 居中显示
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(8.dp))
            // 三项检查
            CheckItem(
                label = "环境依赖",
                ok = envReady,
                guidance = if (!envReady) "点击下方「启动服务」自动安装" else null
            )
            Spacer(Modifier.height(2.dp))
            CheckItem(
                label = "服务连接",
                ok = serviceReady,
                guidance = if (!serviceReady) "点击下方「启动服务」连接" else null
            )
            Spacer(Modifier.height(2.dp))
            CheckItem(
                label = "模型配置",
                ok = modelConfigured,
                guidance = if (!modelConfigured) "前往设置配置 LLM" else null,
                onClick = if (!modelConfigured) {
                    { navController.navigate("config") }
                } else null
            )
        }
    }
}

@Composable
private fun CheckItem(
    label: String,
    ok: Boolean,
    guidance: String? = null,
    onClick: (() -> Unit)? = null
) {
    val modifier = if (onClick != null) {
        Modifier.clickable { onClick() }
    } else {
        Modifier
    }
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (ok) "●" else "○",
                fontSize = 9.sp,
                color = if (ok) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (ok) 0.7f else 0.4f)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (ok) "完成" else "待完成",
                fontSize = 10.sp,
                color = if (ok) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        }
        if (guidance != null) {
            Text(
                text = guidance,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                modifier = Modifier.padding(start = 15.dp)
            )
        }
    }
}

private val pypiMirrors = listOf(
    "官方 PyPI" to "",
    "清华" to "https://mirrors.tuna.tsinghua.edu.cn/pypi/web/simple/",
    "阿里云" to "https://mirrors.aliyun.com/pypi/simple/",
    "中科大" to "https://pypi.mirrors.ustc.edu.cn/simple/",
    "豆瓣" to "https://pypi.doubanio.com/simple/",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TermuxStatusCard(
    currentMirrorUrl: String,
    settingsRepo: SettingsRepository,
    initialSetupDone: Boolean,
    daemonStatus: AgentDaemon.DaemonStatus,
    onLaunch: (mirrorUrl: String) -> Unit
) {
    val context = LocalContext.current
    var isChecking by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var embedResult by remember { mutableStateOf<String?>(null) }
    var showPermDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 全局状态，切页面回来不丢失
    val launchStatus by ScriptProgress.status.collectAsState()
    val isLaunching by ScriptProgress.isLaunching.collectAsState()
    val statusText = testResult ?: launchStatus ?: "点击测试连接 Termux 服务"

    // 服务运行时长（App 端累加，每秒更新）
    var startTimeMs by remember { mutableStateOf(0L) }
    var uptimeText by remember { mutableStateOf("") }

    // 服务状态变化时：就绪则获取启动时间，否则清零
    LaunchedEffect(daemonStatus) {
        if (daemonStatus is AgentDaemon.DaemonStatus.Ready) {
            try {
                val startedAt = TermuxServiceClient.fetchStartTime()
                if (startedAt > 0) {
                    startTimeMs = startedAt * 1000
                }
            } catch (_: Exception) {}
        } else {
            startTimeMs = 0
            uptimeText = ""
        }
    }

    // 每秒更新显示
    LaunchedEffect(startTimeMs) {
        while (startTimeMs > 0) {
            val elapsed = (System.currentTimeMillis() - startTimeMs) / 1000
            uptimeText = formatUptime(elapsed)
            delay(1000)
        }
        uptimeText = ""
    }

    val launchService: () -> Unit = {
        scope.launch { settingsRepo.setServiceStopRequested(false) }
        onLaunch(currentMirrorUrl)
        testResult = null
        embedResult = null
        ScriptProgress.startLaunch()
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchService()
        } else {
            testResult = "权限被拒绝，请到系统设置 → 应用 → Termux → 权限中手动授权"
            embedResult = null
        }
    }
    var mirrorCustom by remember { mutableStateOf(
        currentMirrorUrl.isNotEmpty() && pypiMirrors.none { it.second == currentMirrorUrl }
    ) }
    var mirrorCustomUrl by remember { mutableStateOf(if (mirrorCustom) currentMirrorUrl else "") }

    GlassCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isChecking || isLaunching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Termux 服务",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = statusText,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                    // 只在启动服务后显示运行时长，测试连接时不显示
                    if (launchStatus != null && uptimeText.isNotEmpty()) {
                        Text(
                            text = "运行时长: $uptimeText",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                    }
                    embedResult?.let { embed ->
                        Text(
                            text = embed,
                            fontSize = 11.5.sp,
                            color = if (embed.contains("运行中")) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                    else MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // ─── PyPI 镜像选择（仅首次初始化前显示） ───
            if (!initialSetupDone) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "PyPI 镜像源",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Spacer(Modifier.height(6.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    pypiMirrors.forEach { (label, url) ->
                        FilterChip(
                            selected = if (url.isEmpty()) currentMirrorUrl.isEmpty() else currentMirrorUrl == url,
                            onClick = {
                                scope.launch {
                                    val s = settingsRepo.getSettings()
                                    settingsRepo.saveSettings(s.copy(pypiMirrorUrl = url))
                                }
                                mirrorCustom = false
                            },
                            label = { Text(label, fontSize = 11.sp) },
                            modifier = Modifier.height(30.dp)
                        )
                    }
                    FilterChip(
                        selected = mirrorCustom,
                        onClick = { mirrorCustom = !mirrorCustom },
                        label = { Text("自定义", fontSize = 11.sp) },
                        modifier = Modifier.height(30.dp)
                    )
                }

                if (mirrorCustom) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = mirrorCustomUrl,
                        onValueChange = { mirrorCustomUrl = it },
                        placeholder = { Text("https://...", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            scope.launch {
                                val s = settingsRepo.getSettings()
                                settingsRepo.saveSettings(s.copy(pypiMirrorUrl = mirrorCustomUrl))
                            }
                        }),
                        trailingIcon = {
                            if (mirrorCustomUrl.isNotBlank()) {
                                IconButton(onClick = {
                                    scope.launch {
                                        val s = settingsRepo.getSettings()
                                        settingsRepo.saveSettings(s.copy(pypiMirrorUrl = mirrorCustomUrl))
                                    }
                                }) {
                                    Icon(Icons.Default.Check, contentDescription = "确认", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            isChecking = true
                            testResult = "正在连接..."
                            embedResult = null
                            when (val r = TermuxServiceClient.healthCheck()) {
                                is TermuxServiceClient.HealthResult.Ok -> {
                                    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                                    testResult = "连接成功 http://127.0.0.1:8000 ($time)"
                                    // 检查嵌入模型服务
                                    embedResult = if (TermuxServiceClient.checkEmbedding())
                                        "嵌入模型: 运行中 (localhost:8080)"
                                    else "嵌入模型: 未启动"
                                    // 连接成功后更新 daemon 状态（已就绪则跳过）
                                    if (AppBootstrapper.daemonStatus.value !is AgentDaemon.DaemonStatus.Ready) {
                                        AppBootstrapper.start()
                                    }
                                }
                                is TermuxServiceClient.HealthResult.Error -> {
                                    testResult = "连接失败: ${r.message}"
                                    embedResult = null
                                }
                            }
                            isChecking = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) { Text("测试连接", maxLines = 1, fontSize = 12.sp) }
                OutlinedButton(
                    onClick = {
                        val hasPerm = ContextCompat.checkSelfPermission(
                            context, "com.termux.permission.RUN_COMMAND"
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!hasPerm) {
                            showPermDialog = true
                        } else {
                            launchService()
                        }
                    },
                    enabled = !isLaunching,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    if (isLaunching) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("启动中", maxLines = 1)
                        }
                    } else {
                        Text("启动服务", maxLines = 1, fontSize = 12.sp)
                    }
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            testResult = "正在关闭..."
                            embedResult = null
                            settingsRepo.setServiceStopRequested(true)
                            // 优先 HTTP 关闭，fallback 到 Termux 脚本
                            TermuxServiceClient.shutdown()
                            delay(1500)
                            // 再检查一下是否真的关了
                            val h = TermuxServiceClient.healthCheck()
                            if (h !is TermuxServiceClient.HealthResult.Error) {
                                // HTTP 没关掉，用 Termux 脚本强杀
                                TermuxLauncher.stopFastAPI(context)
                                delay(1000)
                            }
                            testResult = "服务已关闭"
                            AppBootstrapper.markDisconnected()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFEF5350)
                    )
                ) { Text("关闭服务", maxLines = 1, fontSize = 12.sp) }
            }

            // ─── Termux 权限请求弹窗 ───
            if (showPermDialog) {
                AlertDialog(
                    onDismissRequest = { showPermDialog = false },
                    title = { Text("需要 Termux 权限") },
                    text = {
                        Text("Pocket Agent 需要 Termux 的「RUN_COMMAND」权限来启动服务。")
                    },
                    confirmButton = {
                        Button(onClick = {
                            showPermDialog = false
                            permLauncher.launch("com.termux.permission.RUN_COMMAND")
                        }) { Text("授权") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPermDialog = false }) {
                            Text("取消")
                        }
                    }
                )
            }
        }
    }
}

/**
 * 格式化运行时长
 * 秒数 → "X天X时X分X秒" 或 "X时X分X秒" 或 "X分X秒" 或 "X秒"
 */
private fun formatUptime(seconds: Long): String {
    if (seconds <= 0) return ""

    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return buildString {
        if (days > 0) append("${days}天")
        if (hours > 0) append("${hours}时")
        if (minutes > 0) append("${minutes}分")
        if (secs > 0 || isEmpty()) append("${secs}秒")
    }
}



