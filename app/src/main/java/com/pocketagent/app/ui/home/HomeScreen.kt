package com.pocketagent.app.ui.home

import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
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
import com.pocketagent.app.core.TermuxLauncher
import com.pocketagent.app.core.TermuxServiceClient
import com.pocketagent.app.core.ScriptProgress
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

private data class NavEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: String
)

private val navEntries = listOf(
    NavEntry("执行任务", "用 AI 操控手机，一步到位", Icons.Default.PlayArrow, "execute"),
    NavEntry("设置", "模型 / MCP / 高级参数", Icons.Default.Settings, "config"),
    NavEntry("技能库", "Agent 已掌握的技能与工具", Icons.Default.Psychology, "skills"),
    NavEntry("悬浮窗", "后台执行与状态监控", Icons.Default.PictureInPicture, "overlay"),
    NavEntry("历史记录", "查看过往任务与执行结果", Icons.Default.History, "history"),
    NavEntry("终端", "命令行与调试控制台", Icons.Default.Terminal, "terminal"),
)

@Composable
fun HomeScreen(navController: NavController, modelConfigured: Boolean, settingsRepo: SettingsRepository) {
    val context = LocalContext.current
    val isPythonReady = remember { mutableStateOf(false) } // 不再使用内嵌 Python
    val settings by settingsRepo.settingsFlow.collectAsState(initial = null)

    Box(modifier = Modifier.fillMaxSize()) {
        // ─── 液态玻璃动态背景 ───
        AnimatedBackground(
            orbColors = orbColors(),
            modifier = Modifier.fillMaxSize()
        )

        // ─── 边缘流光（极淡） ───
        GlowingBorder(modifier = Modifier.fillMaxSize())

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
                                    Text(
                                        text = "GH",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
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

            // ─── 环境配置卡片（常驻首页） ───
            if (true) {
                AnimatedStaggeredItem(delayMs = 80) {
                    TermuxStatusCard(
                        currentMirrorUrl = settings?.pypiMirrorUrl ?: "",
                        settingsRepo = settingsRepo,
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

            // 底部 Agent 状态
            if (modelConfigured) {
                Spacer(Modifier.height(8.dp))
                AnimatedStaggeredItem(delayMs = 100 + navEntries.size * 80) {
                    AgentStatusBadge()
                }
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

// ─── Agent 就绪徽章 ────────────────────────────

@Composable
private fun AgentStatusBadge() {
    GlassSurface(shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // 绿点指示灯
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Agent 就绪",
                fontSize = 12.5.sp,
                letterSpacing = (0.3f).sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                textAlign = TextAlign.Center
            )
        }
    }
}

// ─── 边缘流光动画（极淡蓝光沿边框流动） ─────────

@Composable
private fun GlowingBorder(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "border_glow")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "glow_progress"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0 || h <= 0) return@Canvas
        val perimeter = 2 * (w + h)
        val particleCount = 3

        for (i in 0 until particleCount) {
            val pos = (progress * perimeter + i * perimeter / particleCount) % perimeter
            val p = perimeterPosToPoint(pos, w, h)

            // 极淡光晕
            drawCircle(Color(0xFF4FC3F7), radius = 2.dp.toPx(), center = p, alpha = 0.15f)
            drawCircle(Color(0xFF4FC3F7), radius = 5.dp.toPx(), center = p, alpha = 0.06f)
            drawCircle(Color(0xFF4FC3F7), radius = 10.dp.toPx(), center = p, alpha = 0.03f)
        }
    }
}

/** 将周长上的位置 (0..perimeter) 映射到 Canvas 上的 (x, y) */
private fun perimeterPosToPoint(pos: Float, w: Float, h: Float): Offset {
    val top = w
    val right = w + h
    val bottom = 2 * w + h
    return when {
        pos < top -> Offset(pos, 0f)                          // 上边 →
        pos < right -> Offset(w, pos - top)                   // 右边 ↓
        pos < bottom -> Offset(w - (pos - right), h)          // 下边 ←
        else -> Offset(0f, h - (pos - bottom))                // 左边 ↑
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
    onLaunch: (mirrorUrl: String) -> Unit
) {
    val context = LocalContext.current
    var isChecking by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var showPermDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 全局状态，切页面回来不丢失
    val launchStatus by ScriptProgress.status.collectAsState()
    val isLaunching by ScriptProgress.isLaunching.collectAsState()
    val statusText = testResult ?: launchStatus ?: "点击测试连接 Termux 服务"

    val launchService: () -> Unit = {
        onLaunch(currentMirrorUrl)
        testResult = null
        ScriptProgress.startLaunch()
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchService()
        } else {
            testResult = "权限被拒绝，请到系统设置 → 应用 → Termux → 权限中手动授权"
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
                }
            }

            // ─── PyPI 镜像选择 ───
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

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            isChecking = true
                            testResult = "正在连接..."
                            when (val r = TermuxServiceClient.healthCheck()) {
                                is TermuxServiceClient.HealthResult.Ok -> testResult = "连接成功! ${r.body}"
                                is TermuxServiceClient.HealthResult.Error -> testResult = "连接失败: ${r.message}"
                            }
                            isChecking = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("测试连接", fontSize = 12.sp) }
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
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isLaunching) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("启动中", fontSize = 12.sp)
                        }
                    } else {
                        Text("启动服务", fontSize = 12.sp)
                    }
                }
                OutlinedButton(
                    onClick = {
                        val ok = TermuxLauncher.stopFastAPI(context)
                        Toast.makeText(context, if (ok) "关闭指令已发送" else "关闭失败", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFEF5350)
                    )
                ) { Text("关闭服务", fontSize = 12.sp, color = Color(0xFFEF5350)) }
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



