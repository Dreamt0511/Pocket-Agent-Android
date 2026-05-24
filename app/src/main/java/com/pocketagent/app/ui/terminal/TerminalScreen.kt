package com.pocketagent.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.File

private val terminalBg = Color(0xFF1E1E2E)
private val promptColor = Color(0xFF4CAF50)
private val outputColor = Color(0xFFCDD6F4)
private val errorColor = Color(0xFFF38BA8)
private val infoColor = Color(0xFFA6E3A1)

/**
 * 持久 Shell 会话 — 类似 Termux 终端的实现
 *
 * 维护一个常驻 bash 进程，通过 stdin/stdout 双向通信。
 * cd、export 等命令状态会跨命令保留。
 */
private class ShellSession {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private val mutex = Mutex()
    private val marker = "___SHELL_DONE_${Random.nextInt(100000, 999999)}___"

    var currentDir: String = ""
        private set

    suspend fun start(context: android.content.Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val shell = "/system/bin/sh"
            val homeDir = context.filesDir.absolutePath
            java.io.File(homeDir).mkdirs()

            val pb = ProcessBuilder(shell)
                .directory(java.io.File(homeDir))
                .redirectErrorStream(true)

            val env = pb.environment()
            env["HOME"] = homeDir
            env["PATH"] = "/system/bin:/system/xbin"
            env["TERM"] = "xterm-256color"
            env["LANG"] = "en_US.UTF-8"

            process = pb.start()
            writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))
            reader = BufferedReader(InputStreamReader(process!!.inputStream))

            // 设置 PS1 为一个唯一标记，用于判断命令结束
            writer!!.write("export PS1='$marker'\n")
            writer!!.write("export PROMPT_COMMAND=''\n")
            writer!!.flush()

            // 跳过初始输出
            delay(200)
            while (reader!!.ready()) {
                reader!!.readLine()
            }
            currentDir = resolvePwd()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 在持久会话中执行命令，返回输出文本
     */
    suspend fun execute(command: String): CommandOutput = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val w = writer ?: return@withLock CommandOutput("Shell 未启动", false)

                w.write("$command 2>&1\n")
                w.write("echo \"$marker\"\n")
                w.write("pwd\n")
                w.flush()

                val output = StringBuilder()
                var line: String?
                var foundMarker = false
                val timeout = 30000L
                val startTime = System.currentTimeMillis()

                while (System.currentTimeMillis() - startTime < timeout) {
                    if (reader!!.ready()) {
                        line = reader!!.readLine()
                        if (line == null) break

                        if (line == marker) {
                            foundMarker = true
                            val pwdLine = reader!!.readLine()
                            if (pwdLine != null && pwdLine.isNotBlank() && !pwdLine.contains("No such file")) {
                                currentDir = pwdLine
                            }
                            break
                        }
                        output.appendLine(line)
                    } else {
                        delay(10)
                    }
                }

                val text = output.toString().trimEnd('\n')
                CommandOutput(text = text, success = foundMarker)
            } catch (e: Exception) {
                CommandOutput("执行错误: ${e.message}", false)
            }
        }
    }

    private suspend fun resolvePwd(): String = withContext(Dispatchers.IO) {
        try {
            writer!!.write("pwd\n")
            writer!!.write("echo \"$marker\"\n")
            writer!!.flush()
            delay(200)
            val lines = mutableListOf<String>()
            while (reader!!.ready()) {
                val l = reader!!.readLine() ?: break
                if (l == marker) break
                lines.add(l)
            }
            lines.lastOrNull { it.isNotBlank() && it.startsWith("/") }
                ?: currentDir
        } catch (_: Exception) {
            currentDir
        }
    }

    fun destroy() {
        try {
            writer?.write("exit\n")
            writer?.flush()
            writer?.close()
            reader?.close()
            process?.destroyForcibly()
        } catch (_: Exception) {}
        process = null
        writer = null
        reader = null
    }
}

data class CommandOutput(
    val text: String,
    val success: Boolean
)

// ─── UI ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    var isExecuting by remember { mutableStateOf(false) }
    var terminalLines by remember { mutableStateOf<List<TerminalLine>>(emptyList()) }
    var sessionReady by remember { mutableStateOf(false) }
    var currentDir by remember { mutableStateOf("~") }
    val session = remember { ShellSession() }
    val context = androidx.compose.ui.platform.LocalContext.current
    // 启动 Shell 会话
    LaunchedEffect(Unit) {
        terminalLines = listOf(
            TerminalLine("Pocket Agent · Shell", TerminalLineType.INFO),
            TerminalLine("系统 Shell 会话启动中...", TerminalLineType.INFO),
            TerminalLine("", TerminalLineType.EMPTY),
        )

        val started = session.start(context)
        if (started) {
            currentDir = session.currentDir
            sessionReady = true
            terminalLines += TerminalLine("会话已就绪  •  $currentDir", TerminalLineType.INFO)
        } else {
            sessionReady = false
            terminalLines += TerminalLine("Shell 会话启动失败", TerminalLineType.ERROR)
        }
        terminalLines += TerminalLine("", TerminalLineType.EMPTY)
    }

    // 清理会话
    DisposableEffect(Unit) {
        onDispose {
            session.destroy()
        }
    }

    fun executeCommand(cmd: String) {
        if (cmd.isBlank() || isExecuting || !sessionReady) return
        scope.launch {
            isExecuting = true
            terminalLines = terminalLines + TerminalLine("$ $cmd", TerminalLineType.COMMAND)

            val result = session.execute(cmd)
            currentDir = session.currentDir

            if (result.text.isNotBlank()) {
                result.text.lines().forEach { line ->
                    terminalLines = terminalLines + TerminalLine(line, TerminalLineType.OUTPUT)
                }
            }
            terminalLines = terminalLines + TerminalLine("", TerminalLineType.EMPTY)

            // 行数上限，防止内存溢出
            if (terminalLines.size > 1000) {
                terminalLines = terminalLines.takeLast(800)
            }

            inputText = ""
            isExecuting = false
            listState.animateScrollToItem((terminalLines.size - 1).coerceAtLeast(0))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("终端")
                        if (sessionReady) {
                            Text(
                                text = currentDir,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF1A1D23),
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
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = when {
                                    isExecuting -> Color(0xFFFFD700)
                                    sessionReady -> Color(0xFF4CAF50)
                                    else -> Color(0xFFF44336)
                                },
                                shape = RoundedCornerShape(4.dp)
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = when {
                            !sessionReady -> "启动中..."
                            isExecuting -> "执行中"
                            else -> "sh"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.width(12.dp))

                    TextButton(onClick = {
                        terminalLines = listOf(
                            TerminalLine("Pocket Agent · 终端", TerminalLineType.INFO),
                            TerminalLine("  $currentDir", TerminalLineType.INFO),
                            TerminalLine("", TerminalLineType.EMPTY),
                        )
                    }) {
                        Text("清屏", fontSize = 12.sp)
                    }
                }
            )
        },
        bottomBar = {
            TerminalInputBar(
                inputText = inputText,
                onInputChange = { inputText = it },
                onExecute = { executeCommand(inputText) },
                enabled = sessionReady && !isExecuting
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(terminalBg)
                .padding(paddingValues),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            items(terminalLines) { line ->
                TerminalLineView(line)
            }
        }
    }
}

// ─── 模型 ────────────────────────────────────────

data class TerminalLine(
    val text: String,
    val type: TerminalLineType
)

enum class TerminalLineType {
    COMMAND, OUTPUT, ERROR, INFO, WARNING, EMPTY
}

// ─── 行渲染 ──────────────────────────────────────

@Composable
private fun TerminalLineView(line: TerminalLine) {
    val color = when (line.type) {
        TerminalLineType.COMMAND -> promptColor
        TerminalLineType.OUTPUT -> outputColor
        TerminalLineType.ERROR -> errorColor
        TerminalLineType.INFO -> infoColor
        TerminalLineType.WARNING -> Color(0xFFFFD700)
        TerminalLineType.EMPTY -> Color.Transparent
    }

    Text(
        text = line.text,
        color = color,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    )
}

// ─── 输入栏 ──────────────────────────────────────

@Composable
private fun TerminalInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onExecute: () -> Unit,
    enabled: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF2D2D3F),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "$ ",
                    color = promptColor,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )

                BasicTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        color = outputColor
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onExecute() }),
                    singleLine = true,
                    decorationBox = { innerTextField ->
                        if (inputText.isEmpty()) {
                            Text(
                                text = "输入命令...",
                                fontSize = 15.sp,
                                fontFamily = FontFamily.Monospace,
                                color = outputColor.copy(alpha = 0.3f)
                            )
                        }
                        innerTextField()
                    }
                )

                Spacer(Modifier.width(8.dp))

                FilledTonalButton(
                    onClick = onExecute,
                    enabled = inputText.isNotBlank() && enabled,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("↵", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            QuickCommandsRow(
                onCommandClick = { cmd -> onInputChange(cmd) },
                enabled = enabled
            )
        }
    }
}

// ─── 快捷命令（横向滚动）─────────────────────────

@Composable
private fun QuickCommandsRow(
    onCommandClick: (String) -> Unit,
    enabled: Boolean
) {
    val quickCommands = listOf(
        "pwd" to "当前路径",
        "ls -la" to "文件列表",
        "whoami" to "当前用户",
        "uname -a" to "系统信息",
        "df -h" to "磁盘空间",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        quickCommands.forEach { (cmd, label) ->
            SuggestionChip(
                onClick = { if (enabled) onCommandClick(cmd) },
                label = {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                },
                shape = RoundedCornerShape(6.dp),
            )
        }
    }
}
