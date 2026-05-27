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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalView
import android.view.ViewTreeObserver
import dev.jeziellago.compose.markdowntext.MarkdownText
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.pocketagent.app.core.AgentDaemon
import com.pocketagent.app.core.AppBootstrapper
import com.pocketagent.app.core.TermuxServiceClient
import com.pocketagent.app.overlay.OverlayService
import com.pocketagent.app.ui.theme.GlassCard
import com.pocketagent.app.update.TaskResult
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * 执行任务页面 - 主 Agent 功能入口
 *
 * 在这里给 AI 下达任务指令，由 Agent 规划并操控手机完成。
 * 所有消息持久化到 Python 后端 SQLite。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(navController: NavController, conversationId: String? = null) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var currentConvId by remember { mutableStateOf<String?>(null) }
    var sessionTitle by remember { mutableStateOf("新会话") }

    val daemonStatus by AppBootstrapper.daemonStatus.collectAsState()
    val isDaemonReady = daemonStatus is AgentDaemon.DaemonStatus.Ready
    val toolStatus by OverlayService.taskStatus.collectAsState()

    // 进入页面时创建新会话或从后端加载已有会话
    LaunchedEffect(conversationId) {
        if (conversationId == null) {
            // 新会话：本地生成 UUID
            currentConvId = UUID.randomUUID().toString()
        } else {
            // 加载已有会话：从后端获取消息历史
            currentConvId = conversationId
            when (val result = TermuxServiceClient.fetchMessages(conversationId)) {
                is TermuxServiceClient.MessagesResult.Ok -> {
                    try {
                        val arr = org.json.JSONArray(result.json)
                        messages.clear()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            messages.add(
                                ChatMessage(
                                    text = obj.optString("content", ""),
                                    isUser = obj.optString("role") == "user",
                                    timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                                )
                            )
                        }
                        // 用首条用户消息作为标题
                        val firstUser = messages.firstOrNull { it.isUser }
                        if (firstUser != null) sessionTitle = firstUser.text.take(30)
                    } catch (_: Exception) {}
                }
                else -> {
                    // 加载失败，创建新会话
                    currentConvId = UUID.randomUUID().toString()
                }
            }
        }
    }

    // 收集悬浮窗流式输出并实时更新最后一条 AI 消息
    val streamText by OverlayService.streamText.collectAsState()

    // 键盘弹起时自动滚动到底部（通过 ViewTreeObserver 检测窗口可视区域变化）
    val view = LocalView.current
    DisposableEffect(Unit) {
        val rootView = view.rootView
        var wasKeyboardVisible = false
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            rootView.getWindowVisibleDisplayFrame(rect)
            val heightDiff = rootView.height - rect.bottom
            val isKeyboardVisible = heightDiff > rootView.height * 0.15
            if (isKeyboardVisible && !wasKeyboardVisible && messages.isNotEmpty()) {
                scope.launch {
                    delay(280) // 等键盘动画完成
                    try {
                        listState.scrollToItem(messages.size - 1)
                    } catch (_: Exception) {}
                }
            }
            wasKeyboardVisible = isKeyboardVisible
        }
        rootView.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose {
            rootView.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }

    // 退出页面时终止后台执行（仅页面销毁，不包括切换后台应用）
    DisposableEffect(Unit) {
        onDispose {
            if (isProcessing) {
                AppBootstrapper.cancelCommand()
            }
        }
    }

    // 处理返回键：执行中时先取消再退出
    BackHandler(enabled = isProcessing) {
        AppBootstrapper.cancelCommand()
        isProcessing = false
        navController.popBackStack()
    }

    LaunchedEffect(streamText) {
        if (isProcessing && messages.isNotEmpty()) {
            val lastIdx = messages.size - 1
            val lastMsg = messages[lastIdx]
            if (!lastMsg.isUser && streamText.isNotEmpty() && lastMsg.text != streamText) {
                messages[lastIdx] = messages[lastIdx].copy(text = streamText)
                // 流式文本增长时自动滚动到底部
                try {
                    listState.animateScrollToItem(lastIdx)
                } catch (_: Exception) {}
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
                onFocusChange = { },
                onStop = {
                    AppBootstrapper.cancelCommand()
                    isProcessing = false
                },
                isProcessing = isProcessing,
                onSend = {
                    if (inputText.isNotBlank() && !isProcessing && isDaemonReady && currentConvId != null) {
                        scope.launch {
                            isProcessing = true
                            val convId = currentConvId!!

                            // 清空流式输出，准备接收新任务的实时输出
                            OverlayService.streamText.value = ""

                            val isFirstMessage = messages.isEmpty()

                            val userMsg = ChatMessage(
                                text = inputText,
                                isUser = true,
                                timestamp = System.currentTimeMillis()
                            )
                            messages.add(userMsg)

                            // 添加占位 AI 消息，由 LaunchedEffect(streamText) 实时更新
                            val placeholderMsg = ChatMessage(
                                text = "",
                                isUser = false,
                                timestamp = System.currentTimeMillis()
                            )
                            messages.add(placeholderMsg)

                            // 首条消息设为会话标题（本地显示）
                            if (isFirstMessage) {
                                sessionTitle = inputText.take(30)
                            }

                            val cmd = inputText
                            inputText = ""

                            // 执行指令（后端自动保存消息到 SQLite）
                            val result = AppBootstrapper.executeCommand(cmd, convId)

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

            // 自动滚动到底部（新消息时触发）
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
                    Text(text = message.text, color = Color.White, fontSize = 13.sp)
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
                        // 解析工具调用标记，分段渲染
                        // 兼容新旧格式：新格式用 [__TOOL_CALL__]...[__TOOL_CALL_END__]，旧格式只有 [__TOOL_CALL__]
                        val segments = parseMessageSegments(message.text)
                        for ((index, seg) in segments.withIndex()) {
                            val text = seg.second.trim()
                            if (text.isEmpty()) continue
                            if (seg.first == "text") {
                                androidx.compose.material3.ProvideTextStyle(
                                    value = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp)
                                ) {
                                    MarkdownText(markdown = text, modifier = Modifier.fillMaxWidth())
                                }
                            } else {
                                Text(
                                    text = text,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    modifier = Modifier.padding(top = if (index > 0) 4.dp else 0.dp)
                                )
                            }
                        }
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
    onStop: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    enabled: Boolean,
    isProcessing: Boolean = false
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

            // 发送/停止按钮
            if (isProcessing) {
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.height(40.dp)
                ) {
                    Text("停止")
                }
            } else {
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

/**
 * 解析消息文本，将工具调用和正常文本分离
 * 返回 List<Pair<type, content>>，type 为 "text" 或 "tool"
 * 兼容新旧格式：新格式 [__TOOL_CALL__]...[__TOOL_CALL_END__]，旧格式只有 [__TOOL_CALL__]
 */
private fun parseMessageSegments(text: String): List<Pair<String, String>> {
    val result = mutableListOf<Pair<String, String>>()
    val toolStart = "[__TOOL_CALL__]"
    val toolEnd = "[__TOOL_CALL_END__]"
    var pos = 0
    while (pos < text.length) {
        val startIdx = text.indexOf(toolStart, pos)
        if (startIdx == -1) {
            // 剩余全是正常文本
            result.add("text" to text.substring(pos))
            break
        }
        // 工具调用前的正常文本
        if (startIdx > pos) {
            result.add("text" to text.substring(pos, startIdx))
        }
        val afterStart = startIdx + toolStart.length
        val endIdx = text.indexOf(toolEnd, afterStart)
        if (endIdx != -1) {
            // 新格式：有结束标记
            result.add("tool" to text.substring(afterStart, endIdx))
            pos = endIdx + toolEnd.length
        } else {
            // 旧格式：无结束标记，工具调用到下一个 [__TOOL_CALL__] 或文末
            val nextStart = text.indexOf(toolStart, afterStart)
            if (nextStart != -1) {
                result.add("tool" to text.substring(afterStart, nextStart))
                pos = nextStart
            } else {
                result.add("tool" to text.substring(afterStart))
                break
            }
        }
    }
    return result
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
