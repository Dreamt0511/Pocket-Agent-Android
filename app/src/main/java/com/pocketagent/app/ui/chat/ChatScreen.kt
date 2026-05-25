package com.pocketagent.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.pocketagent.app.core.AgentDaemonV2
import com.pocketagent.app.core.AppBootstrapper
import com.pocketagent.app.overlay.OverlayService
import com.pocketagent.app.ui.theme.GlassCard
import com.pocketagent.app.update.TaskResult
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * 执行任务页面 - 主 Agent 功能入口
 *
 * 在这里给 AI 下达任务指令，由 Agent 规划并操控手机完成。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }

    val daemonStatus by AppBootstrapper.daemonStatus.collectAsState()
    val isDaemonReady = daemonStatus is AgentDaemonV2.DaemonStatus.Ready

    // 收集悬浮窗流式输出并实时更新最后一条 AI 消息
    val streamText by OverlayService.streamText.collectAsState()

    LaunchedEffect(streamText) {
        if (isProcessing && messages.isNotEmpty()) {
            val lastIdx = messages.size - 1
            val lastMsg = messages[lastIdx]
            if (!lastMsg.isUser && streamText.isNotEmpty() && lastMsg.text != streamText) {
                messages = messages.toMutableList().apply {
                    set(lastIdx, get(lastIdx).copy(text = streamText))
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("执行任务") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←")
                    }
                },
                actions = {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            )
        },
        bottomBar = {
            ChatInputBar(
                inputText = inputText,
                onInputChange = { inputText = it },
                onSend = {
                    if (inputText.isNotBlank() && !isProcessing && isDaemonReady) {
                        scope.launch {
                            isProcessing = true

                            // 清空流式输出，准备接收新任务的实时输出
                            OverlayService.streamText.value = ""

                            messages = messages + ChatMessage(
                                text = inputText,
                                isUser = true,
                                timestamp = System.currentTimeMillis()
                            )
                            // 添加占位 AI 消息，由 LaunchedEffect(streamText) 实时更新
                            messages = messages + ChatMessage(
                                text = "",
                                isUser = false,
                                timestamp = System.currentTimeMillis()
                            )

                            val cmd = inputText
                            inputText = ""

                            // 执行指令（suspend 函数，执行期间 streamText 会实时变化）
                            val result = AppBootstrapper.executeCommand(cmd)

                            // 执行完成后用最终结果更新最后一条消息
                            val finalText = when (result) {
                                is TaskResult.Success -> result.message
                                is TaskResult.Failure -> "错误: ${result.error}"
                                is TaskResult.Cancelled -> "任务已取消"
                            }
                            val lastIdx = messages.size - 1
                            if (lastIdx >= 0) {
                                messages = messages.toMutableList().apply {
                                    set(lastIdx, get(lastIdx).copy(
                                        text = if (streamText.isNotEmpty()) streamText else finalText,
                                        timestamp = System.currentTimeMillis()
                                    ))
                                }
                            }

                            isProcessing = false

                            // 滚动到底部
                            listState.animateScrollToItem(messages.size - 1)
                        }
                    }
                },
                enabled = !isProcessing && isDaemonReady
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            DaemonStatusBar(
                status = daemonStatus,
                onRetry = { scope.launch { AppBootstrapper.start() } }
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                reverseLayout = false,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message ->
                    ChatMessageItem(message)
                }
            }
        }
    }
}

/**
 * Agent 守护进程状态栏 — 在聊天页面顶部展示连接状态
 */
@Composable
private fun DaemonStatusBar(
    status: AgentDaemonV2.DaemonStatus,
    onRetry: () -> Unit
) {
    val bgColor: Color
    val message: String
    val showRetry: Boolean

    when (status) {
        is AgentDaemonV2.DaemonStatus.Ready -> {
            bgColor = Color(0xFF1B5E20).copy(alpha = 0.08f)
            message = "Agent 就绪"
            showRetry = false
        }
        is AgentDaemonV2.DaemonStatus.Error -> {
            bgColor = Color(0xFFB71C1C).copy(alpha = 0.08f)
            message = "Agent 错误: ${status.message}"
            showRetry = true
        }
        is AgentDaemonV2.DaemonStatus.Idle -> {
            bgColor = Color(0xFFE65100).copy(alpha = 0.08f)
            message = "Agent 未就绪"
            showRetry = true
        }
        is AgentDaemonV2.DaemonStatus.Syncing,
        is AgentDaemonV2.DaemonStatus.Initializing -> {
            bgColor = Color(0xFFE65100).copy(alpha = 0.08f)
            message = "Agent 正在初始化..."
            showRetry = false
        }
        is AgentDaemonV2.DaemonStatus.Executing -> {
            bgColor = Color(0xFF1565C0).copy(alpha = 0.08f)
            message = "Agent 执行中..."
            showRetry = false
        }
    }

    Surface(modifier = Modifier.fillMaxWidth(), color = bgColor) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            if (showRetry) {
                TextButton(onClick = onRetry) {
                    Text("重试", fontSize = 12.sp)
                }
            }
        }
    }
}

// ─── 消息模型 ───────────────────────────────────

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long
)

// ─── 消息气泡 ───────────────────────────────────

@Composable
private fun ChatMessageItem(message: ChatMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        if (message.isUser) {
            // 用户消息 — 实心黑底
            Card(
                shape = RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = 16.dp, bottomEnd = 4.dp
                ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = message.text, color = Color.White, fontSize = 14.sp)
                    Text(
                        text = formatTime(message.timestamp),
                        fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            // AI 消息 — 玻璃气泡
            GlassCard(
                shape = RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = 4.dp, bottomEnd = 16.dp
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = message.text,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    )
                    Text(
                        text = formatTime(message.timestamp),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

// ─── 输入栏 ────────────────────────────────────

@Composable
private fun ChatInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 输入框
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入指令或问题...") },
                singleLine = false,
                maxLines = 3,
                enabled = enabled
            )

            // 发送按钮
            Button(
                onClick = onSend,
                enabled = inputText.isNotBlank() && enabled,
                modifier = Modifier.height(56.dp)
            ) {
                Text("发送")
            }
        }
    }
}

// ─── 工具函数 ───────────────────────────────────

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

@Preview(showBackground = true, showSystemUi = true, name = "执行任务亮色")
@Composable
private fun ChatScreenLightPreview() {
    com.pocketagent.app.ui.theme.PocketAgentTheme(darkTheme = false) {
        ChatScreen(navController = rememberNavController())
    }
}

@Preview(showBackground = true, showSystemUi = true, name = "执行任务暗色")
@Composable
private fun ChatScreenDarkPreview() {
    com.pocketagent.app.ui.theme.PocketAgentTheme(darkTheme = true) {
        ChatScreen(navController = rememberNavController())
    }
}
