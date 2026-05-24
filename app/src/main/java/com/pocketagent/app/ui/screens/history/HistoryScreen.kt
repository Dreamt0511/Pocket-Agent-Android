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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.pocketagent.app.service.TaskQueueManager
import com.pocketagent.app.service.TaskQueueManager.TaskStatus
import com.pocketagent.app.ui.theme.PocketAgentTheme
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    navController: NavController,
    taskQueueManager: TaskQueueManager
) {
    val allTasks by taskQueueManager.allTasks.collectAsState()
    var selectedTask by remember { mutableStateOf<TaskQueueManager.ManagedTask?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    PocketAgentTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("任务历史") },
                    actions = {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "清除历史"
                            )
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
                // 统计栏
                TaskStatsBar(allTasks)

                HorizontalDivider()

                if (selectedTask != null) {
                    // 详情视图
                    TaskDetailView(selectedTask!!) {
                        selectedTask = null
                    }
                } else if (allTasks.isEmpty()) {
                    // 空状态
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Inbox,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "暂无任务记录",
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                text = "执行任务后会自动记录在这里",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                } else {
                    // 列表视图
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(
                            items = allTasks.sortedByDescending { it.createdAt },
                            key = { it.id }
                        ) { task ->
                            TaskHistoryItem(
                                task = task,
                                onClick = { selectedTask = task }
                            )
                        }
                    }
                }
            }
        }

        // 清除确认对话框
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("清除历史") },
                text = { Text("确定要清除所有任务记录吗？此操作不可撤销。") },
                confirmButton = {
                    TextButton(onClick = {
                        taskQueueManager.clearHistory()
                        showDeleteDialog = false
                    }) {
                        Text("清除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

@Composable
fun TaskStatsBar(tasks: List<TaskQueueManager.ManagedTask>) {
    val completed = tasks.count { it.status.value == TaskStatus.COMPLETED }
    val failed = tasks.count { it.status.value == TaskStatus.FAILED }
    val running = tasks.count { it.status.value == TaskStatus.RUNNING }
    val pending = tasks.count { it.status.value == TaskStatus.PENDING }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatChip("总计", tasks.size, MaterialTheme.colorScheme.onSurfaceVariant)
        StatChip("完成", completed, MaterialTheme.colorScheme.primary)
        StatChip("失败", failed, MaterialTheme.colorScheme.error)
        StatChip("进行中", running + pending, MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
fun StatChip(label: String, count: Int, color: androidx.compose.ui.graphics.Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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

@Composable
fun TaskHistoryItem(
    task: TaskQueueManager.ManagedTask,
    onClick: () -> Unit
) {
    val status by task.status.collectAsState()
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 状态图标
            Icon(
                imageVector = when (status) {
                    TaskStatus.COMPLETED -> Icons.Default.CheckCircle
                    TaskStatus.FAILED -> Icons.Default.Error
                    TaskStatus.RUNNING -> Icons.Default.PlayArrow
                    TaskStatus.CANCELLED -> Icons.Default.Cancel
                    TaskStatus.PENDING -> Icons.Default.Schedule
                },
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = when (status) {
                    TaskStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                    TaskStatus.FAILED -> MaterialTheme.colorScheme.error
                    TaskStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
                    TaskStatus.CANCELLED -> MaterialTheme.colorScheme.outline
                    TaskStatus.PENDING -> MaterialTheme.colorScheme.outline
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
                        text = formatTimestamp(task.createdAt),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )

                    if (task.completedAt.value != null) {
                        Text(
                            text = " · 耗时 ${formatDuration(task.createdAt, task.completedAt.value!!)}",
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

@Composable
fun TaskDetailView(
    task: TaskQueueManager.ManagedTask,
    onBack: () -> Unit
) {
    val status by task.status.collectAsState()
    val output by task.output.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 返回按钮
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
        }

        Spacer(modifier = Modifier.height(8.dp))

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
                        text = when (status) {
                            TaskStatus.COMPLETED -> "已完成"
                            TaskStatus.FAILED -> "失败"
                            TaskStatus.RUNNING -> "执行中"
                            TaskStatus.CANCELLED -> "已取消"
                            TaskStatus.PENDING -> "等待中"
                        }
                    )
                }
                Text(
                    text = "创建时间: ${formatTimestamp(task.createdAt)}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline
                )
                task.completedAt.value?.let {
                    Text(
                        text = "完成时间: ${formatTimestamp(it)}",
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
            if (output.isNotBlank()) {
                Text(
                    text = output,
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
                        text = if (status == TaskStatus.RUNNING) "执行中..." else "无输出",
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(millis: Long): String {
    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(millis))
}

private fun formatDuration(start: Long, end: Long): String {
    val diff = (end - start) / 1000
    return when {
        diff < 60 -> "${diff}s"
        diff < 3600 -> "${diff / 60}m${diff % 60}s"
        else -> "${diff / 3600}h${(diff % 3600) / 60}m"
    }
}

@Preview(showBackground = true, showSystemUi = true, name = "历史记录亮色")
@Composable
private fun HistoryScreenLightPreview() {
    PocketAgentTheme(darkTheme = false) {
        HistoryScreen(
            navController = rememberNavController(),
            taskQueueManager = androidx.compose.runtime.remember { TaskQueueManager() }
        )
    }
}