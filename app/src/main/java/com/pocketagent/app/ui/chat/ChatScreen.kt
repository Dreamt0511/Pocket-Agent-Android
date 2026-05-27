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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.onFocusChanged
import com.mikepenz.markdown.m3.Markdown
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.pocketagent.app.core.AgentDaemon
import com.pocketagent.app.core.AppBootstrapper
import com.pocketagent.app.data.AppDatabase
import com.pocketagent.app.data.ChatRepository
import com.pocketagent.app.overlay.OverlayService
import com.pocketagent.app.ui.theme.GlassCard
import com.pocketagent.app.update.TaskResult
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * 执行任务页面 - 主 Agent 功能入口
 *
 * 在这里给 AI 下达任务指令，由 Agent 规划并操控手机完成。
 * 所有消息持久化到 Room 数据库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(navController: NavController, conversationId: Long? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var currentConvId by remember { mutableStateOf<Long?>(null) }
    var sessionTitle by remember { mutableStateOf("新会话") }

    val daemonStatus by AppBootstrapper.daemonStatus.collectAsState()
    val isDaemonReady = daemonStatus is AgentDaemon.DaemonStatus.Ready
    val toolStatus by OverlayService.taskStatus.collectAsState()

    // 进入页面时创建新会话或加载已有会话
    LaunchedEffect(conversationId) {
        val repo = ChatRepository(AppDatabase.getInstance(context))
        if (conversationId == null) {
            val conv = repo.createConversation("新会话")
            currentConvId = conv.id
        } else {
            val conv = repo.getConversation(conversationId)
            if (conv != null) {
                currentConvId = conv.id
                sessionTitle = conv.title
                val history = repo.getMessages(conv.id)
                messages.clear()
                history.forEach { msg ->
                    messages.add(
                        ChatMessage(text = msg.content, isUser = msg.role == "user", timestamp = msg.timestamp)
                    )
                }
            } else {
                // 如果会话不存在，新建一个
                val conv = repo.createConversation("新会话")
                currentConvId = conv.id
            }
        }
    }

    // 收集悬浮窗流式输出并实时更新最后一条 AI 消息
    val streamText by OverlayService.streamText.collectAsState()

    LaunchedEffect(streamText) {
        if (isProcessing && messages.isNotEmpty()) {
            val lastIdx = messages.size - 1
            val lastMsg = messages[lastIdx]
            if (!lastMsg.isUser && streamText.isNotEmpty() && lastMsg.text != streamText) {
                messages[lastIdx] = messages[lastIdx].copy(text = streamText)
            }
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(sessionTitle, fontSize = 16.sp, maxLines = 1)
                        if (currentConvId != null) {
                            Text(
                                text = formatDateShort(System.currentTimeMillis()),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←")
                    }
                }
            )
        },
        bottomBar = {
            ChatInputBar(
                inputText = inputText,
                onInputChange = { inputText = it },
                onFocusChange = { focused ->
                    if (focused && messages.isNotEmpty()) {
                        scope.launch {
                            try {
                                listState.scrollToItem(messages.size - 1)
                            } catch (_: Exception) {}
                        }
                    }
                },
                onSend = {
                    if (inputText.isNotBlank() && !isProcessing && isDaemonReady && currentConvId != null) {
                        scope.launch {
                            isProcessing = true
                            val convId = currentConvId!!
                            val repo = ChatRepository(AppDatabase.getInstance(context))

                            // 清空流式输出，准备接收新任务的实时输出
                            OverlayService.streamText.value = ""

                            val isFirstMessage = messages.isEmpty()

                            val userMsg = ChatMessage(
                                text = inputText,
                                isUser = true,
                                timestamp = System.currentTimeMillis()
                            )
                            messages.add(userMsg)
                            // 保存用户消息到 Room
                            repo.addMessage(convId, "user", inputText)

                            // 添加占位 AI 消息，由 LaunchedEffect(streamText) 实时更新
                            val placeholderMsg = ChatMessage(
                                text = "",
                                isUser = false,
                                timestamp = System.currentTimeMillis()
                            )
                            messages.add(placeholderMsg)

                            // 首条消息自动设为会话标题
                            if (isFirstMessage) {
                                val title = inputText.take(30)
                                repo.updateConversationTitle(convId, title)
                                sessionTitle = title
                            }

                            val cmd = inputText
                            inputText = ""

                            // 执行指令（suspend 函数，执行期间 streamText 会实时变化）
                            val result = AppBootstrapper.executeCommand(cmd, convId.toString())

                            // 执行完成后用最终结果更新最后一条消息
                            val finalText = when (result) {
                                is TaskResult.Success -> result.message
                                is TaskResult.Failure -> "错误: ${result.error}"
                                is TaskResult.Cancelled -> "任务已取消"
                            }
                            val lastIdx = messages.size - 1
                            if (lastIdx >= 0) {
                                val displayText = if (streamText.isNotEmpty()) streamText else finalText
                                messages[lastIdx] = messages[lastIdx].copy(
                                    text = displayText,
                                    timestamp = System.currentTimeMillis()
                                )
                                // 保存 AI 回复到 Room
                                repo.addMessage(convId, "assistant", displayText)
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
                toolStatus = toolStatus,
                onRetry = { scope.launch { AppBootstrapper.start() } }
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                reverseLayout = false,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message ->
                    ChatMessageItem(message, isProcessing)
                }
            }

            // 自动滚动到底部（新消息时触发，避免 streamText 频繁取消）
            LaunchedEffect(messages.size) {
                if (messages.isNotEmpty()) {
                    try {
                        listState.scrollToItem(messages.size - 1)
                    } catch (_: Exception) {}
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
    status: AgentDaemon.DaemonStatus,
    toolStatus: String,
    onRetry: () -> Unit
) {
    val bgColor: Color
    val message: String
    val showRetry: Boolean

    when (status) {
        is AgentDaemon.DaemonStatus.Ready -> {
            bgColor = Color(0xFF1B5E20).copy(alpha = 0.08f)
            message = "Agent 就绪"
            showRetry = false
        }
        is AgentDaemon.DaemonStatus.Error -> {
            bgColor = Color(0xFFB71C1C).copy(alpha = 0.08f)
            message = "Agent 错误: ${status.message}"
            showRetry = true
        }
        is AgentDaemon.DaemonStatus.Idle -> {
            bgColor = Color(0xFFE65100).copy(alpha = 0.08f)
            message = "Agent 未就绪"
            showRetry = true
        }
        is AgentDaemon.DaemonStatus.Syncing,
        is AgentDaemon.DaemonStatus.Initializing -> {
            bgColor = Color(0xFFE65100).copy(alpha = 0.08f)
            message = "Agent 正在初始化..."
            showRetry = false
        }
        is AgentDaemon.DaemonStatus.Executing -> {
            bgColor = Color(0xFF1565C0).copy(alpha = 0.08f)
            message = "Agent 执行中..."
            showRetry = false
        }
    }

    Surface(modifier = Modifier.fillMaxWidth(), color = bgColor) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(
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
            // 工具执行状态（仅在工具活动时显示）
            if (toolStatus.isNotBlank() && toolStatus !in listOf("空闲", "就绪")) {
                Text(
                    text = toolStatus,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 2.dp)
                )
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
private fun ChatMessageItem(message: ChatMessage, isProcessing: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
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
                    if (message.text.isEmpty() && isProcessing) {
                        ThinkingDots()
                    } else {
                        Markdown(
                            content = message.text,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
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
    onFocusChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 输入框
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focusState ->
                        onFocusChange(focusState.isFocused)
                    },
                placeholder = { Text("输入指令或问题...") },
                singleLine = true,
                enabled = enabled
            )

            // 发送按钮
            Button(
                onClick = onSend,
                enabled = inputText.isNotBlank() && enabled,
                modifier = Modifier.height(40.dp)
            ) {
                Text("发送")
            }
        }
    }
}

// ─── 思考动画 ───────────────────────────────────

@Composable
private fun ThinkingDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val dotCount = 3
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(dotCount) { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 300),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_alpha_$index"
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .padding(horizontal = 2.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            )
        }
    }
}

// ─── 工具函数 ───────────────────────────────────

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatDateShort(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

@Preview(showBackground = true, showSystemUi = true, name = "执行任务亮色")
@Composable
private fun ChatScreenLightPreview() {
    com.pocketagent.app.ui.theme.PocketAgentTheme(darkTheme = false) {
        ChatScreen(
            navController = rememberNavController()
        )
    }
}

@Preview(showBackground = true, showSystemUi = true, name = "执行任务暗色")
@Composable
private fun ChatScreenDarkPreview() {
    com.pocketagent.app.ui.theme.PocketAgentTheme(darkTheme = true) {
        ChatScreen(
            navController = rememberNavController()
        )
    }
}
