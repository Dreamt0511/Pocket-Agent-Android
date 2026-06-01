package com.pocketagent.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalView
import com.pocketagent.app.data.SettingsRepository
import com.pocketagent.app.data.settingsDataStore
import android.view.ViewTreeObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * 智能对话页面 - 主 Agent 功能入口
 *
 * 在这里给 AI 下达任务指令，由 Agent 规划并操控手机完成。
 * 所有消息持久化到 Python 后端 SQLite。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(navController: NavController, conversationId: String? = null) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context.settingsDataStore) }
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var currentConvId by remember { mutableStateOf<String?>(null) }
    var sessionTitle by remember { mutableStateOf("新会话") }

    val daemonStatus by AppBootstrapper.daemonStatus.collectAsState()
    val isDaemonReady = daemonStatus is AgentDaemon.DaemonStatus.Ready
    val toolStatus by OverlayService.taskStatus.collectAsState()

    // 新建会话的辅助函数
    val createNewSession: () -> Unit = {
        val newId = UUID.randomUUID().toString()
        currentConvId = newId
        messages.clear()
        sessionTitle = "新会话"
        scope.launch { settingsRepo.setActiveConversationId(newId) }
    }

    // 从后端加载会话消息的辅助函数
    suspend fun loadMessages(convId: String): Boolean {
        when (val result = TermuxServiceClient.fetchMessages(convId)) {
            is TermuxServiceClient.MessagesResult.Ok -> {
                try {
                    val arr = org.json.JSONArray(result.json)
                    messages.clear()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        messages.add(
                            ChatMessage(
                                id = obj.optLong("id", -1).let { if (it == -1L) null else it },
                                text = obj.optString("content", ""),
                                isUser = obj.optString("role") == "user",
                                timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                            )
                        )
                    }
                    val firstUser = messages.firstOrNull { it.isUser }
                    if (firstUser != null) sessionTitle = firstUser.text.take(30)
                    return true
                } catch (_: Exception) {}
            }
            else -> {}
        }
        return false
    }

    // 进入页面时：优先用传入的 conversationId，否则从 DataStore 恢复上次会话
    LaunchedEffect(conversationId) {
        val convId = when {
            conversationId != null -> {
                settingsRepo.setActiveConversationId(conversationId)
                conversationId
            }
            else -> {
                val savedId = settingsRepo.getActiveConversationId()
                savedId ?: run { createNewSession(); return@LaunchedEffect }
            }
        }
        currentConvId = convId
        if (!loadMessages(convId) && conversationId != null) {
            createNewSession()
        }

        // 检查是否有正在执行的任务，恢复 isProcessing 状态
        if (daemonStatus is AgentDaemon.DaemonStatus.Executing) {
            isProcessing = true
            // 如果最后一条消息是用户消息，添加占位 AI 消息
            val lastMsg = messages.lastOrNull()
            if (lastMsg == null || lastMsg.isUser) {
                messages.add(ChatMessage(
                    text = streamText,  // 使用当前的 streamText
                    isUser = false,
                    timestamp = System.currentTimeMillis()
                ))
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
                    delay(350) // 等键盘动画完成
                    try {
                        val lastIdx = messages.lastIndex
                        if (lastIdx >= 0) {
                            // 使用 scrollToItem 而不是 animateScrollToItem，避免与键盘动画冲突
                            listState.scrollToItem(lastIdx)
                        }
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

    // 离开页面（切换 Tab / 切后台）自动弹出悬浮窗药丸，回来时隐藏
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (messages.isNotEmpty() || isProcessing) {
                        OverlayService.showMini(context)
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    OverlayService.hideAll(context)
                    // 恢复执行状态：如果 daemon 仍在执行，恢复 isProcessing
                    if (daemonStatus is AgentDaemon.DaemonStatus.Executing && !isProcessing) {
                        isProcessing = true
                        // 确保有占位 AI 消息，并同步当前 streamText
                        val lastMsg = messages.lastOrNull()
                        if (lastMsg == null || lastMsg.isUser) {
                            messages.add(ChatMessage(
                                text = streamText,
                                isUser = false,
                                timestamp = System.currentTimeMillis()
                            ))
                        } else if (!lastMsg.isUser && streamText.isNotEmpty() && lastMsg.text != streamText) {
                            // 更新最后一条 AI 消息的文本为当前 streamText
                            messages[messages.lastIndex] = lastMsg.copy(text = streamText)
                        }
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 退出页面时不取消任务 — 任务在后台继续执行，悬浮窗显示进度

    // 处理返回键：执行中时不取消任务，让任务在后台继续
    BackHandler(enabled = isProcessing) {
        navController.popBackStack()
    }

    // 流式输出时更新消息文本
    LaunchedEffect(streamText) {
        if (isProcessing && messages.isNotEmpty()) {
            val lastIdx = messages.size - 1
            val lastMsg = messages[lastIdx]
            if (!lastMsg.isUser && streamText.isNotEmpty() && lastMsg.text != streamText) {
                messages[lastIdx] = messages[lastIdx].copy(text = streamText)
            }
        }
    }

    // 同步消息到悬浮窗（消息数变化时完整同步）
    LaunchedEffect(messages.size) {
        OverlayService.conversationMessages.value = messages.map {
            OverlayService.OverlayMessage(text = it.text, isUser = it.isUser)
        }
    }

    // 流式输出时只更新最后一条消息的文本（避免频繁全量同步）
    LaunchedEffect(messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) {
            val list = OverlayService.conversationMessages.value.toMutableList()
            if (list.isNotEmpty()) {
                val last = messages.last()
                list[list.lastIndex] = OverlayService.OverlayMessage(text = last.text, isUser = last.isUser)
                OverlayService.conversationMessages.value = list
            }
        }
    }

    // 统一的自动滚动逻辑：防抖 + 只在接近底部时滚动（仅流式输出期间）
    var isNearBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val visibleItems = info.visibleItemsInfo
            if (visibleItems.isEmpty()) true
            else {
                val lastVisible = visibleItems.last().index
                val totalItems = info.totalItemsCount
                lastVisible >= totalItems - 2
            }
        }
        .distinctUntilChanged()
        .collect { nearBottom -> isNearBottom = nearBottom }
    }

    // 用户发送消息后强制滚动到底部（计数器保证每次发送都触发）
    var forceScrollCount by remember { mutableIntStateOf(0) }
    var userScrolledUp by remember { mutableStateOf(false) }
    LaunchedEffect(isNearBottom) {
        if (isNearBottom) userScrolledUp = false
        else userScrolledUp = true
    }
    LaunchedEffect(forceScrollCount) {
        if (forceScrollCount > 0 && messages.isNotEmpty()) {
            userScrolledUp = false
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    // AI 流式输出时，只在用户没有上滑查看历史时才自动滚动
    LaunchedEffect(streamText) {
        if (!userScrolledUp && streamText.isNotEmpty() && messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
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
                },
                actions = {
                    // 新建会话
                    IconButton(onClick = { createNewSession() }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "新建会话"
                        )
                    }
                    // 历史记录
                    IconButton(onClick = { navController.navigate("history") }) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "历史记录"
                        )
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

                            // 标记需要滚动到底部（由 LaunchedEffect 在布局完成后执行）
                            forceScrollCount++

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

                            // 从后端同步消息 ID（新发消息本地没有 id，需从后端获取才能支持长按删除）
                            val msgResult = TermuxServiceClient.fetchMessages(convId)
                            if (msgResult is TermuxServiceClient.MessagesResult.Ok) {
                                try {
                                    val arr = org.json.JSONArray(msgResult.json)
                                    for (i in 0 until minOf(arr.length(), messages.size)) {
                                        val obj = arr.getJSONObject(i)
                                        val backendId = obj.optLong("id", -1).let { if (it == -1L) null else it }
                                        if (backendId != null && messages[i].id == null) {
                                            messages[i] = messages[i].copy(id = backendId)
                                        }
                                    }
                                } catch (_: Exception) {}
                            }

                            // 标记需要滚动到底部（由 LaunchedEffect 在布局完成后执行）
                            forceScrollCount++
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
                    ChatMessageItem(
                        message = message,
                        isProcessing = isProcessing,
                        onDelete = if (message.id != null) {
                            {
                                scope.launch {
                                    TermuxServiceClient.deleteMessage(message.id)
                                    messages.remove(message)
                                }
                            }
                        } else null
                    )
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
    val id: Long? = null,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long
)

// ─── 消息气泡 ───────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    isProcessing: Boolean,
    onDelete: (() -> Unit)? = null
) {
    val clipboardManager = LocalClipboardManager.current
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除消息") },
            text = { Text(message.text.take(80).let { if (it.length < message.text.length) "$it..." else it }) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete?.invoke()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }

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
                ),
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { if (onDelete != null) showDeleteDialog = true }
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = message.text, color = Color.White, fontSize = 13.sp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text(
                            text = formatTime(message.timestamp),
                            fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制",
                            modifier = Modifier
                                .size(14.dp)
                                .padding(2.dp)
                                .clickable {
                                    clipboardManager.setText(AnnotatedString(message.text))
                                },
                            tint = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        } else {
            // AI 消息 — 玻璃气泡
            GlassCard(
                shape = RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = 4.dp, bottomEnd = 16.dp
                ),
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { if (onDelete != null) showDeleteDialog = true }
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
                                CollapsibleToolCall(
                                    toolText = text,
                                    initiallyExpanded = isProcessing
                                )
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text(
                            text = formatTime(message.timestamp),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制",
                            modifier = Modifier
                                .size(14.dp)
                                .padding(2.dp)
                                .clickable {
                                    clipboardManager.setText(AnnotatedString(message.text))
                                },
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 可折叠的工具调用组件
 * 执行中默认展开，完成后自动折叠，点击可切换
 */
@Composable
private fun CollapsibleToolCall(toolText: String, initiallyExpanded: Boolean) {
    // 从工具文本中提取工具名称作为摘要
    val toolName = toolText.substringBefore(":").trim().ifEmpty { "工具调用" }
    val summary = if (toolText.length > 40) "${toolText.take(40)}..." else toolText

    // key 变化时重置展开状态：执行中展开，完成后折叠
    var expanded by remember(initiallyExpanded) { mutableStateOf(initiallyExpanded) }

    Column(modifier = Modifier.padding(top = 4.dp)) {
        // 可点击的摘要行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (expanded) "▼" else "▶",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(end = 6.dp)
            )
            Text(
                text = if (expanded) toolName else summary,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        }

        // 展开的详细内容
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Text(
                text = toolText,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(start = 14.dp, top = 2.dp, end = 4.dp)
            )
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

@Preview(showBackground = true, showSystemUi = true, name = "智能对话亮色")
@Composable
private fun ChatScreenLightPreview() {
    com.pocketagent.app.ui.theme.PocketAgentTheme(darkTheme = false) {
        ChatScreen(
            navController = rememberNavController()
        )
    }
}

@Preview(showBackground = true, showSystemUi = true, name = "智能对话暗色")
@Composable
private fun ChatScreenDarkPreview() {
    com.pocketagent.app.ui.theme.PocketAgentTheme(darkTheme = true) {
        ChatScreen(
            navController = rememberNavController()
        )
    }
}
