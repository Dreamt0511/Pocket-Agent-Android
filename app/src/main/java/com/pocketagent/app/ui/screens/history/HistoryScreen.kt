package com.pocketagent.app.ui.screens.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.pocketagent.app.service.SessionManager
import com.pocketagent.app.ui.theme.GlassCard
import com.pocketagent.app.ui.theme.PocketAgentTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    navController: NavController,
    sessionManager: SessionManager
) {
    var sessions by remember { mutableStateOf<List<SessionManager.Session>>(emptyList()) }
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    var selectedTask by remember { mutableStateOf<SessionManager.StoredTask?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteTargetSession by remember { mutableStateOf<SessionManager.Session?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshTrigger) {
        sessions = sessionManager.getSessions()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selectedTask != null) {
                        Text("任务详情")
                    } else if (selectedSessionId != null) {
                        val s = sessions.find { it.id == selectedSessionId }
                        Text(s?.title ?: "会话详情")
                    } else {
                        Text("历史记录")
                    }
                },
                navigationIcon = {
                    if (selectedTask != null || selectedSessionId != null) {
                        IconButton(onClick = {
                            if (selectedTask != null) {
                                selectedTask = null
                            } else {
                                selectedSessionId = null
                            }
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (selectedSessionId == null && selectedTask == null && sessions.isNotEmpty()) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "清除全部")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ─── 任务详情视图 ───────────────────────
            if (selectedTask != null) {
                SessionTaskDetailView(
                    task = selectedTask!!,
                    onBack = { selectedTask = null }
                )
                return@Scaffold
            }

            // ─── 会话任务列表 ───────────────────────
            if (selectedSessionId != null) {
                val session = sessions.find { it.id == selectedSessionId }
                SessionTaskList(
                    sessionManager = sessionManager,
                    sessionId = selectedSessionId!!,
                    sessionTitle = session?.title ?: "",
                    onTaskClick = { selectedTask = it }
                )
                return@Scaffold
            }

            // ─── 会话列表 ───────────────────────────
            SessionStatsBar(sessions)

            HorizontalDivider()

            if (sessions.isEmpty()) {
                EmptyHistoryView()
            } else {
                // 按日期分组
                val grouped = groupSessionsByDate(sessions)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    grouped.forEach { (label, list) ->
                        item {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(
                            items = list,
                            key = { it.id }
                        ) { session ->
                            SessionItem(
                                session = session,
                                onClick = { selectedSessionId = session.id },
                                onDelete = {
                                    deleteTargetSession = session
                                    showDeleteDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // ─── 删除对话框 ─────────────────────────────
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    if (deleteTargetSession != null) "删除会话" else "清除全部"
                )
            },
            text = {
                Text(
                    if (deleteTargetSession != null)
                        "确定要删除「${deleteTargetSession!!.title}」吗？此操作不可撤销。"
                    else
                        "确定要清除所有会话记录吗？此操作不可撤销。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (deleteTargetSession != null) {
                        scope.launch {
                            sessionManager.deleteSession(deleteTargetSession!!.id)
                            deleteTargetSession = null
                            refreshTrigger++
                        }
                    } else {
                        scope.launch {
                            sessionManager.clearAllSessions()
                            refreshTrigger++
                        }
                    }
                    showDeleteDialog = false
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    deleteTargetSession = null
                    showDeleteDialog = false
                }) {
                    Text("取消")
                }
            }
        )
    }
}

// ─── 空状态 ──────────────────────────────────

@Composable
private fun EmptyHistoryView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Inbox,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "暂无历史记录",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                text = "执行任务后会自动创建会话",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// ─── 统计栏 ──────────────────────────────────

@Composable
private fun SessionStatsBar(sessions: List<SessionManager.Session>) {
    val totalTasks = sessions.sumOf { it.taskCount }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatChip("会话", sessions.size, MaterialTheme.colorScheme.onSurfaceVariant)
        StatChip("任务", totalTasks, MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun StatChip(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "$count",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

// ─── 会话条目 ────────────────────────────────

@Composable
private fun SessionItem(
    session: SessionManager.Session,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Chat,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${session.taskCount} 条消息",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = " · ${formatSessionTime(session.updatedAt)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除会话",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

// ─── 会话内任务列表 ───────────────────────────

@Composable
private fun SessionTaskList(
    sessionManager: SessionManager,
    sessionId: String,
    sessionTitle: String,
    onTaskClick: (SessionManager.StoredTask) -> Unit
) {
    var tasks by remember { mutableStateOf<List<SessionManager.StoredTask>>(emptyList()) }

    LaunchedEffect(sessionId) {
        tasks = sessionManager.getSessionTasks(sessionId)
    }

    if (tasks.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Inbox,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "该会话暂无任务",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                Text(
                    text = sessionTitle,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            items(
                items = tasks,
                key = { it.id }
            ) { task ->
                SessionTaskItem(
                    task = task,
                    onClick = { onTaskClick(task) }
                )
            }
        }
    }
}

// ─── 会话任务条目 ────────────────────────────

@Composable
private fun SessionTaskItem(
    task: SessionManager.StoredTask,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = when (task.status) {
                    "completed" -> Icons.Default.CheckCircle
                    "failed" -> Icons.Default.Error
                    "cancelled" -> Icons.Default.Cancel
                    else -> Icons.Default.Schedule
                },
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = when (task.status) {
                    "completed" -> MaterialTheme.colorScheme.primary
                    "failed" -> MaterialTheme.colorScheme.error
                    "cancelled" -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.outline
                }
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.prompt,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row {
                    Text(
                        text = formatTaskTime(task.createdAt),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )

                    if (task.completedAt != null) {
                        Text(
                            text = " · 耗时 ${formatDuration(task.createdAt, task.completedAt)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

// ─── 任务详情视图 ────────────────────────────

@Composable
private fun SessionTaskDetailView(
    task: SessionManager.StoredTask,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 任务描述
        Text(
            text = "任务",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = task.prompt,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(16.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 状态
        Text(
            text = "状态",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row {
                    Text("状态: ", fontWeight = FontWeight.Medium)
                    Text(
                        text = when (task.status) {
                            "completed" -> "已完成"
                            "failed" -> "失败"
                            "cancelled" -> "已取消"
                            else -> "未知"
                        }
                    )
                }
                Text(
                    text = "创建时间: ${formatTaskTime(task.createdAt)}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline
                )
                task.completedAt?.let {
                    Text(
                        text = "完成时间: ${formatTaskTime(it)}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 输出
        Text(
            text = "输出",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            if (task.output.isNotBlank()) {
                Text(
                    text = task.output,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "无输出",
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

// ─── 工具函数 ─────────────────────────────────

private fun groupSessionsByDate(sessions: List<SessionManager.Session>): List<Pair<String, List<SessionManager.Session>>> {
    val calendar = Calendar.getInstance()

    // 今天开始时间
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    val todayStart = calendar.timeInMillis

    // 昨天开始时间
    calendar.add(Calendar.DAY_OF_MONTH, -1)
    val yesterdayStart = calendar.timeInMillis

    val todayList = mutableListOf<SessionManager.Session>()
    val yesterdayList = mutableListOf<SessionManager.Session>()
    val earlierList = mutableListOf<SessionManager.Session>()

    sessions.forEach { s ->
        when {
            s.updatedAt >= todayStart -> todayList.add(s)
            s.updatedAt >= yesterdayStart -> yesterdayList.add(s)
            else -> earlierList.add(s)
        }
    }

    val result = mutableListOf<Pair<String, List<SessionManager.Session>>>()
    if (todayList.isNotEmpty()) result.add("今天" to todayList)
    if (yesterdayList.isNotEmpty()) result.add("昨天" to yesterdayList)
    if (earlierList.isNotEmpty()) result.add("更早" to earlierList)
    return result
}

private fun formatSessionTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3600_000} 小时前"
        else -> {
            val sdf = SimpleDateFormat("MM-dd", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}

private fun formatTaskTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatDuration(start: Long, end: Long): String {
    val diff = (end - start) / 1000
    return when {
        diff < 60 -> "${diff}s"
        diff < 3600 -> "${diff / 60}m${diff % 60}s"
        else -> "${diff / 3600}h${(diff % 3600) / 60}m"
    }
}

// ─── 预览 ──────────────────────────────────

@Preview(showBackground = true, showSystemUi = true, name = "历史记录亮色")
@Composable
private fun HistoryScreenLightPreview() {
    PocketAgentTheme(darkTheme = false) {
        HistoryScreen(
            navController = rememberNavController(),
            sessionManager = androidx.compose.runtime.remember {
                SessionManager(androidx.compose.ui.platform.LocalContext.current)
            }
        )
    }
}

@Preview(showBackground = true, showSystemUi = true, name = "历史记录暗色")
@Composable
private fun HistoryScreenDarkPreview() {
    PocketAgentTheme(darkTheme = true) {
        HistoryScreen(
            navController = rememberNavController(),
            sessionManager = androidx.compose.runtime.remember {
                SessionManager(androidx.compose.ui.platform.LocalContext.current)
            }
        )
    }
}
