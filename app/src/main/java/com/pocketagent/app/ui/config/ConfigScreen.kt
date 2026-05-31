package com.pocketagent.app.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.pocketagent.app.core.AppBootstrapper
import com.pocketagent.app.core.ConfigManager
import com.pocketagent.app.core.NeuralBridgeHelper
import com.pocketagent.app.data.SettingsRepository
import com.pocketagent.app.data.settingsDataStore
import com.pocketagent.app.ui.theme.GlassCard
import com.pocketagent.app.core.TermuxServiceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ConfigScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsRepo = remember { SettingsRepository(context.settingsDataStore) }

    var configMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var saved by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    // LLM 验证状态
    var verifying by remember { mutableStateOf(false) }
    var verifyResult by remember { mutableStateOf<String?>(null) }
    var verifyOk by remember { mutableStateOf(false) }

    // 子模型测试状态
    var executorVerifying by remember { mutableStateOf(false) }
    var executorVerifyResult by remember { mutableStateOf<String?>(null) }
    var executorVerifyOk by remember { mutableStateOf(false) }

    // MCP 测试状态
    var testingMcp by remember { mutableStateOf(false) }
    var mcpResult by remember { mutableStateOf<String?>(null) }
    var mcpOk by remember { mutableStateOf(false) }

    // 更新状态
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<String?>(null) }
    var remoteVersion by remember { mutableStateOf<String?>(null) }
    var remoteVersionMessage by remember { mutableStateOf("") }
    var updating by remember { mutableStateOf(false) }
    var pendingRollback by remember { mutableStateOf<TermuxServiceClient.VersionEntry?>(null) }
    var localCodeVersion by remember { mutableStateOf("") }
    var versionHistory by remember { mutableStateOf<List<TermuxServiceClient.VersionEntry>>(emptyList()) }

    // 从 Termux 服务获取当前代码版本和历史
    LaunchedEffect(Unit) {
        val ver = TermuxServiceClient.fetchVersion()
        if (ver.isNotBlank()) localCodeVersion = ver
        versionHistory = TermuxServiceClient.fetchVersionHistory()
    }

    // 加载配置：优先从 DataStore 读取（可靠持久化），
    // ConfigManager 补充 .env 文件中的 executor/高级字段
    LaunchedEffect(Unit) {
        ConfigManager.ensureEnvFile()
        try {
            val ds = settingsRepo.getSettings()
            // 从 DataStore 加载配置，覆盖远程默认值
            val fromDataStore = mutableMapOf(
                "LLM_TEMPERATURE" to ds.llmTemperature.toString(),
                "LLM_MAX_TOKENS" to ds.llmMaxTokens.toString(),
            )
            if (ds.llmBaseUrl.isNotBlank()) fromDataStore["DEFAULT_LLM_BASE_URL"] = ds.llmBaseUrl
            if (ds.llmApiKey.isNotBlank()) fromDataStore["LLM_API_KEY"] = ds.llmApiKey
            if (ds.llmModel.isNotBlank()) fromDataStore["LLM_MODEL"] = ds.llmModel
            if (ds.mcpServerUrl.isNotBlank()) fromDataStore["MCP_SERVER_URL"] = ds.mcpServerUrl
            // 嵌入模型路径：优先使用 DataStore 中的值
            if (ds.embeddingModelPath.isNotBlank()) {
                fromDataStore["EMBEDDING_MODEL_PATH"] = ds.embeddingModelPath
            }
            configMap = ConfigManager.loadAll() + fromDataStore
        } catch (_: Exception) {
            configMap = ConfigManager.loadAll()
        }
        isLoading = false
    }

    // 保存配置的函数
    suspend fun saveConfig(map: Map<String, String>) {
        // DataStore 始终保存（本地持久化）
        try {
            settingsRepo.saveSettings(
                com.pocketagent.app.data.Settings(
                    llmBaseUrl = map["DEFAULT_LLM_BASE_URL"] ?: "",
                    llmApiKey = map["LLM_API_KEY"] ?: "",
                    llmModel = map["LLM_MODEL"] ?: "",
                    llmTemperature = map["LLM_TEMPERATURE"]?.toFloatOrNull() ?: 0.7f,
                    llmMaxTokens = map["LLM_MAX_TOKENS"]?.toIntOrNull() ?: 8000,
                    mcpServerUrl = map["MCP_SERVER_URL"] ?: "",
                    embeddingModelPath = map["EMBEDDING_MODEL_PATH"] ?: ""
                )
            )
        } catch (_: Exception) {}
        // HTTP 同步到 Termux（服务未运行时会静默失败，不影响本地保存）
        ConfigManager.saveAll(map)
    }

    // 自动保存：监听 configMap 变化，debounce 1.5 秒后自动持久化
    LaunchedEffect(Unit) {
        snapshotFlow { configMap }
            .debounce(1500L)
            .collect { map ->
                if (!isLoading && map.isNotEmpty()) {
                    saveConfig(map)
                    saved = true
                    kotlinx.coroutines.delay(2000)
                    saved = false
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) {
                        Text("← 返回", fontSize = 16.sp)
                    }
                },
                actions = {
                    if (saved) {
                        Text("已保存", color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp))
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ===== 主模型 =====
                SectionCard(title = "主模型") {
                    Text(
                        "密钥信息仅保存在本地设备，不会上传到任何第三方服务器",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ConfigField(
                        label = "服务地址",
                        value = configMap["DEFAULT_LLM_BASE_URL"] ?: "",
                        placeholder = "https://api.openai.com/v1",
                        onValueChange = { configMap = configMap + ("DEFAULT_LLM_BASE_URL" to it) }
                    )
                    ConfigField(
                        label = "API 密钥",
                        value = configMap["LLM_API_KEY"] ?: "",
                        placeholder = "sk-...",
                        isSecret = true,
                        onValueChange = { configMap = configMap + ("LLM_API_KEY" to it) }
                    )
                    ConfigField(
                        label = "模型名称",
                        value = configMap["LLM_MODEL"] ?: "",
                        placeholder = "gpt-4o",
                        onValueChange = { configMap = configMap + ("LLM_MODEL" to it) }
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ConfigField(
                            label = "温度",
                            value = configMap["LLM_TEMPERATURE"] ?: "0.7",
                            placeholder = "0.7",
                            modifier = Modifier.weight(1f),
                            onValueChange = { configMap = configMap + ("LLM_TEMPERATURE" to it) }
                        )
                        ConfigField(
                            label = "模型最大输出",
                            value = configMap["LLM_MAX_TOKENS"] ?: "8000",
                            placeholder = "8000",
                            modifier = Modifier.weight(1f),
                            onValueChange = { configMap = configMap + ("LLM_MAX_TOKENS" to it) }
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // 验证按钮
                    Button(
                        onClick = {
                            scope.launch {
                                verifying = true
                                verifyResult = null
                                val result = testLlmConnection(configMap)
                                verifyOk = result.first
                                verifyResult = result.second
                                verifying = false
                            }
                        },
                        enabled = !verifying && configMap["DEFAULT_LLM_BASE_URL"]?.isNotBlank() == true,
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (verifyOk) Color(0xFF16A34A) else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (verifying) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (verifying) "验证中..." else if (verifyOk) "✔ 连接正常" else "验证配置",
                            fontSize = 14.sp
                        )
                    }
                    verifyResult?.let {
                        Text(it, fontSize = 12.sp, color = if (verifyOk) Color(0xFF16A34A) else Color(0xFFDC2626),
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }

                // ===== 子模型 =====
                SectionCard(title = "子模型（可选）") {
                    Text(
                        "留空则继承主模型的配置",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ConfigField(
                        label = "服务地址",
                        value = configMap["EXECUTOR_LLM_BASE_URL"] ?: "",
                        placeholder = "留空同主模型",
                        onValueChange = { configMap = configMap + ("EXECUTOR_LLM_BASE_URL" to it) }
                    )
                    ConfigField(
                        label = "API 密钥",
                        value = configMap["EXECUTOR_API_KEY"] ?: "",
                        placeholder = "留空同主模型",
                        isSecret = true,
                        onValueChange = { configMap = configMap + ("EXECUTOR_API_KEY" to it) }
                    )
                    ConfigField(
                        label = "模型名称",
                        value = configMap["EXECUTOR_MODEL"] ?: "",
                        placeholder = "留空同主模型",
                        onValueChange = { configMap = configMap + ("EXECUTOR_MODEL" to it) }
                    )
                    Spacer(Modifier.height(4.dp))
                    // 子模型验证按钮
                    val executorUrl = configMap["EXECUTOR_LLM_BASE_URL"] ?: ""
                    Button(
                        onClick = {
                            scope.launch {
                                executorVerifying = true
                                executorVerifyResult = null
                                // 构建子模型配置 map，键名与 testLlmConnection 期望的一致
                                val executorConfig = mapOf(
                                    "DEFAULT_LLM_BASE_URL" to executorUrl,
                                    "LLM_API_KEY" to (configMap["EXECUTOR_API_KEY"] ?: ""),
                                    "LLM_MODEL" to (configMap["EXECUTOR_MODEL"] ?: "")
                                )
                                val result = testLlmConnection(executorConfig)
                                executorVerifyOk = result.first
                                executorVerifyResult = result.second
                                executorVerifying = false
                            }
                        },
                        enabled = !executorVerifying && executorUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (executorVerifyOk) Color(0xFF16A34A) else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (executorVerifying) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (executorVerifying) "验证中..." else if (executorVerifyOk) "✔ 连接正常" else "验证子模型",
                            fontSize = 14.sp
                        )
                    }
                    executorVerifyResult?.let {
                        Text(it, fontSize = 12.sp, color = if (executorVerifyOk) Color(0xFF16A34A) else Color(0xFFDC2626),
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }

                // ===== 嵌入模型 =====
                SectionCard(title = "嵌入模型（语义检索）") {
                    ConfigField(
                        label = "模型文件路径",
                        value = configMap["EMBEDDING_MODEL_PATH"] ?: "",
                        placeholder = "/sdcard/手机agent开发/Pocket-Agent/bge-m3-Q4_K_M.gguf",
                        onValueChange = { configMap = configMap + ("EMBEDDING_MODEL_PATH" to it) }
                    )
                    Text(
                        "BGE-M3 GGUF 模型的绝对路径。留空则不启用语义检索，仅使用全文搜索。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                saveConfig(configMap)
                                saved = true
                                kotlinx.coroutines.delay(2000)
                                saved = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("保存配置") }
                }

                // ===== MCP =====
                SectionCard(title = "MCP 连接") {
                    ConfigField(
                        label = "服务地址",
                        value = configMap["MCP_SERVER_URL"] ?: "http://127.0.0.1:7474/mcp",
                        placeholder = "http://127.0.0.1:7474/mcp",
                        onValueChange = { configMap = configMap + ("MCP_SERVER_URL" to it) }
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                testingMcp = true
                                mcpResult = null
                                val result = testMcpConnection(configMap["MCP_SERVER_URL"] ?: "")
                                mcpOk = result.first
                                mcpResult = result.second
                                testingMcp = false
                            }
                        },
                        enabled = !testingMcp && configMap["MCP_SERVER_URL"]?.isNotBlank() == true,
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (mcpOk) Color(0xFF16A34A) else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (testingMcp) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (testingMcp) "测试中..." else if (mcpOk) "✔ MCP 已连接" else "测试 MCP 连接",
                            fontSize = 14.sp
                        )
                    }
                    mcpResult?.let {
                        Text(it, fontSize = 12.sp, color = if (mcpOk) Color(0xFF16A34A) else Color(0xFFDC2626),
                            modifier = Modifier.padding(top = 4.dp))
                    }

                    // ── NeuralBridge 状态 ──
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    var neuralStatus by remember { mutableStateOf(NeuralBridgeHelper.checkStatus(context)) }
                    // 页面恢复时刷新状态（从无障碍设置返回后）
                    val lifecycleOwner = LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                // 立即检查一次
                                neuralStatus = NeuralBridgeHelper.checkStatus(context)
                                // 延迟再检查一次，系统可能需要时间更新无障碍状态
                                if (neuralStatus !is NeuralBridgeHelper.Status.Ready) {
                                    scope.launch {
                                        kotlinx.coroutines.delay(1500)
                                        neuralStatus = NeuralBridgeHelper.checkStatus(context)
                                    }
                                }
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("NeuralBridge", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = when (neuralStatus) {
                                is NeuralBridgeHelper.Status.Ready -> "✔ 就绪"
                                is NeuralBridgeHelper.Status.NotInstalled -> "未安装"
                                is NeuralBridgeHelper.Status.AccessibilityDisabled -> "未启用无障碍"
                            },
                            fontSize = 12.sp,
                            color = when (neuralStatus) {
                                is NeuralBridgeHelper.Status.Ready -> Color(0xFF16A34A)
                                else -> Color(0xFFDC2626)
                            }
                        )
                    }

                    when (neuralStatus) {
                        is NeuralBridgeHelper.Status.NotInstalled -> {
                            Spacer(Modifier.height(6.dp))
                            Button(
                                onClick = {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        data = android.net.Uri.parse(NeuralBridgeHelper.installUrl)
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.fillMaxWidth().height(36.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("下载 NeuralBridge", fontSize = 13.sp)
                            }
                        }
                        is NeuralBridgeHelper.Status.AccessibilityDisabled -> {
                            Spacer(Modifier.height(6.dp))
                            Button(
                                onClick = {
                                    val launchIntent = context.packageManager.getLaunchIntentForPackage(NeuralBridgeHelper.packageName)
                                    if (launchIntent != null) {
                                        context.startActivity(launchIntent)
                                    } else {
                                        context.startActivity(
                                            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = android.net.Uri.parse("package:${NeuralBridgeHelper.packageName}")
                                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(36.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("前往应用设置", fontSize = 13.sp)
                            }
                        }
                        else -> {}
                    }

                }

                // ===== 高级设置（折叠）=====
                SectionCard(
                    title = "高级设置",
                    trailing = {
                        TextButton(onClick = { showAdvanced = !showAdvanced }) {
                            Text(if (showAdvanced) "收起 ▲" else "展开 ▼", fontSize = 13.sp)
                        }
                    }
                ) {
                    if (showAdvanced) {
                        ConfigField(
                            label = "最大迭代次数",
                            value = configMap["MAX_ITERATIONS"] ?: "300",
                            placeholder = "300",
                            onValueChange = { configMap = configMap + ("MAX_ITERATIONS" to it) }
                        )
                        ConfigField(
                            label = "递归限制",
                            value = configMap["RECURSION_LIMIT"] ?: "600",
                            placeholder = "600",
                            onValueChange = { configMap = configMap + ("RECURSION_LIMIT" to it) }
                        )
                        ConfigField(
                            label = "上下文窗口",
                            value = configMap["MAX_CONTEXT_TOKENS"] ?: "128000",
                            placeholder = "128000",
                            onValueChange = { configMap = configMap + ("MAX_CONTEXT_TOKENS" to it) }
                        )
                    } else {
                        Text(
                            "迭代次数 / 递归限制 / 上下文窗口",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }

                // ===== 主库代码更新 =====
                SectionCard(title = "主库代码更新") {
                    val codeVersion = localCodeVersion.ifBlank { "未连接" }

                    Text("当前版本: $codeVersion", fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))

                    if (remoteVersion != null) {
                        // 发现新版本，显示确认按钮
                        Text("发现新版本: ${remoteVersion}", fontSize = 14.sp,
                            color = Color(0xFF16A34A))
                        if (remoteVersionMessage.isNotBlank()) {
                            Text(remoteVersionMessage, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    updating = true
                                    updateInfo = null
                                    when (val r = TermuxServiceClient.triggerSync()) {
                                        is TermuxServiceClient.SyncResult.Ok -> {
                                            // 刷新本地版本号
                                            val ver = TermuxServiceClient.fetchVersion()
                                            if (ver.isNotBlank()) localCodeVersion = ver
                                            updateInfo = "更新成功，请重启服务"
                                            remoteVersion = null
                                        }
                                        is TermuxServiceClient.SyncResult.Error -> {
                                            updateInfo = "更新失败: ${r.message}"
                                        }
                                    }
                                    updating = false
                                }
                            },
                            enabled = !updating,
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
                        ) {
                            if (updating) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (updating) "更新中..." else "确认更新", fontSize = 14.sp)
                        }
                    } else {
                        // 检查更新按钮
                        Button(
                            onClick = {
                                scope.launch {
                                    checkingUpdate = true
                                    updateInfo = null
                                    try {
                                        val localVer = localCodeVersion.ifBlank { TermuxServiceClient.fetchVersion() }
                                        if (localVer.isNotBlank()) localCodeVersion = localVer
                                        val (remoteSha, remoteMsg) = fetchRemoteVersion()
                                        if (remoteSha != localVer) {
                                            remoteVersion = remoteSha
                                            remoteVersionMessage = remoteMsg
                                        } else {
                                            updateInfo = "已是最新版本"
                                        }
                                    } catch (e: Exception) {
                                        updateInfo = "检查失败: ${e.message}"
                                    }
                                    checkingUpdate = false
                                }
                            },
                            enabled = !checkingUpdate,
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (checkingUpdate) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (checkingUpdate) "检查中..." else "检查更新", fontSize = 14.sp)
                        }
                    }
                    updateInfo?.let {
                        Text(it, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp))
                    }

                    // 版本历史
                    if (versionHistory.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        Spacer(Modifier.height(8.dp))
                        Text("版本历史（点击回退）", fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.height(4.dp))
                        for (entry in versionHistory) {
                            val timeStr = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(entry.timestamp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { pendingRollback = entry }
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(entry.sha, fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(timeStr, fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                }
                                if (entry.message.isNotBlank()) {
                                    Text(entry.message, fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }

                    }

                    // App 更新链接（在版本回退下面）
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(8.dp))
                    val annotatedString = androidx.compose.ui.text.buildAnnotatedString {
                        append("App 更新请前往: ")
                        pushStringAnnotation(tag = "URL", annotation = "https://github.com/Dreamt0511/Pocket-Agent-Android/releases")
                        withStyle(style = androidx.compose.ui.text.SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                        )) {
                            append("github.com/Dreamt0511/Pocket-Agent-Android/releases")
                        }
                        pop()
                    }
                    androidx.compose.foundation.text.ClickableText(
                        text = annotatedString,
                        onClick = { offset ->
                            annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                .firstOrNull()?.let { annotation ->
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        data = android.net.Uri.parse(annotation.item)
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                }
                        },
                        style = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // 版本回退确认弹窗
    val pendingEntry = pendingRollback
    if (pendingEntry != null) {
        val entry = pendingEntry
        val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(entry.timestamp))
        AlertDialog(
            onDismissRequest = { pendingRollback = null },
            title = { Text("确认回退") },
            text = {
                Column {
                    Text("将代码回退到以下版本，当前版本会被覆盖。")
                    Spacer(Modifier.height(12.dp))
                    Text(entry.sha, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary)
                    Text(timeStr, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    if (entry.message.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(entry.message, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val sha = entry.sha
                    pendingRollback = null
                    scope.launch {
                        updating = true
                        updateInfo = null
                        when (val r = TermuxServiceClient.rollbackVersion(sha)) {
                            is TermuxServiceClient.RollbackResult.Ok -> {
                                localCodeVersion = r.version
                                updateInfo = "已回退到 ${r.version}，请重启服务"
                                versionHistory = TermuxServiceClient.fetchVersionHistory()
                            }
                            is TermuxServiceClient.RollbackResult.Error -> updateInfo = "回退失败: ${r.message}"
                        }
                        updating = false
                    }
                }) { Text("确认回退") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRollback = null }) { Text("取消") }
            }
        )
    }
}

// ─── 网络测试函数 ────────────────────────────────

private suspend fun testLlmConnection(config: Map<String, String>): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    val baseUrl = config["DEFAULT_LLM_BASE_URL"]?.trimEnd('/') ?: return@withContext false to "未配置服务地址"
    val apiKey = config["LLM_API_KEY"] ?: ""
    val model = config["LLM_MODEL"] ?: ""

    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    try {
        // 先用 /models 试试（兼容 OpenAI API）
        val modelsUrl = if (baseUrl.contains("/v1")) "$baseUrl/models" else "${baseUrl}/v1/models"
        val req = Request.Builder().url(modelsUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .build()
        val resp = client.newCall(req).execute()

        if (resp.isSuccessful) {
            val body = JSONObject(resp.body?.string() ?: "{}")
            val models = body.optJSONArray("data")
            if (models != null && models.length() > 0) {
                val modelNames = (0 until models.length()).map { models.getJSONObject(it).optString("id", "") }
                if (model.isNotBlank() && modelNames.none { it.contains(model, ignoreCase = true) }) {
                    return@withContext false to "模型 $model 不存在，可用模型: ${modelNames.take(5).joinToString(", ")}"
                }
                val found = if (model.isNotBlank()) "（模型匹配）" else ""
                return@withContext true to "连接成功，可用模型: ${modelNames.take(5).joinToString(", ")}$found"
            }
            return@withContext true to "连接成功（未返回模型列表）"
        } else {
            val errBody = resp.body?.string() ?: "无响应"
            // 尝试 chat completion 作为 fallback
            return@withContext try {
                val chatUrl = if (baseUrl.contains("/v1")) "$baseUrl/chat/completions" else "${baseUrl}/v1/chat/completions"
                val json = """{"model":"${model.ifBlank { "gpt-3.5-turbo" }}","messages":[{"role":"user","content":"ping"}],"max_tokens":5}"""
                val chatReq = Request.Builder().url(chatUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .build()
                val chatResp = client.newCall(chatReq).execute()
                if (chatResp.isSuccessful) {
                    true to "连接成功（ping 通过）"
                } else {
                    val code = chatResp.code
                    val msg = when (code) {
                        401 -> "认证失败（API Key 无效）"
                        403 -> "权限不足"
                        404 -> "接口地址错误（$code）"
                        429 -> "请求过于频繁"
                        else -> "HTTP $code: ${chatResp.body?.string()?.take(100) ?: ""}"
                    }
                    false to msg
                }
            } catch (_: Exception) {
                false to "连接失败: HTTP ${resp.code}（${errBody.take(100)}）"
            }
        }
    } catch (e: java.net.ConnectException) {
        false to "无法连接（连接被拒绝），请检查服务地址"
    } catch (e: java.net.SocketTimeoutException) {
        false to "连接超时（10s），请检查服务地址和网络"
    } catch (e: Exception) {
        false to "错误: ${e.localizedMessage ?: "未知错误"}"
    }
}

private suspend fun testMcpConnection(url: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    if (url.isBlank()) return@withContext false to "未配置 MCP 地址"

    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    try {
        val json = """{"jsonrpc":"2.0","method":"tools/list","id":1}"""
        val req = Request.Builder().url(url)
            .post(json.toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .build()
        val resp = client.newCall(req).execute()

        if (resp.isSuccessful) {
            val body = JSONObject(resp.body?.string() ?: "{}")
            val tools = body.optJSONArray("result") ?: body.optJSONArray("tools")
            if (tools != null && tools.length() > 0) {
                val names = (0 until tools.length()).map {
                    val t = tools.getJSONObject(it)
                    t.optString("name", t.optString("function", ""))
                }
                return@withContext true to "MCP 已连接，可用工具 (${names.size}): ${names.joinToString(", ")}"
            }
            return@withContext true to "MCP 已连接（无工具列表返回）"
        } else {
            val code = resp.code
            val errBody = resp.body?.string()?.take(100) ?: ""
            false to "MCP 连接失败: HTTP $code ${errBody}"
        }
    } catch (e: java.net.ConnectException) {
        false to "无法连接 MCP 服务（连接被拒绝）"
    } catch (e: java.net.SocketTimeoutException) {
        false to "MCP 连接超时（10s）"
    } catch (e: Exception) {
        false to "MCP 测试错误: ${e.localizedMessage ?: "未知错误"}"
    }
}

// ─── 小组件 ──────────────────────────────────────

@Composable
private fun SectionCard(
    title: String,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                trailing()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ConfigField(
    label: String,
    value: String,
    placeholder: String,
    isSecret: Boolean = false,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit
) {
    var showPassword by remember { mutableStateOf(!isSecret) }

    Column(modifier.padding(bottom = 8.dp)) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, fontSize = 14.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
            visualTransformation = if (showPassword) VisualTransformation.None
                else PasswordVisualTransformation(),
            trailingIcon = if (isSecret) {
                {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(if (showPassword) "隐藏" else "显示", fontSize = 12.sp)
                    }
                }
            } else null,
            shape = RoundedCornerShape(8.dp)
        )
    }
}

// ─── 获取远程版本 ───────────────────────────────

private suspend fun fetchRemoteVersion(): Pair<String, String> = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    val url = "https://api.github.com/repos/Dreamt0511/Pocket-Agent/commits/main"
    val request = Request.Builder()
        .url(url)
        .addHeader("Accept", "application/vnd.github.v3+json")
        .build()
    val response = client.newCall(request).execute()
    if (response.isSuccessful) {
        val json = JSONObject(response.body?.string() ?: "")
        val sha = json.optString("sha", "").take(7)
        val commitObj = json.optJSONObject("commit")
        val message = commitObj?.optString("message", "")?.lines()?.firstOrNull() ?: ""
        sha to message
    } else {
        throw Exception("HTTP ${response.code}")
    }
}

@Preview(showBackground = true, showSystemUi = true, name = "设置亮色")
@Composable
private fun ConfigScreenLightPreview() {
    com.pocketagent.app.ui.theme.PocketAgentTheme(darkTheme = false) {
        ConfigScreen(navController = rememberNavController())
    }
}

@Preview(showBackground = true, showSystemUi = true, name = "设置暗色")
@Composable
private fun ConfigScreenDarkPreview() {
    com.pocketagent.app.ui.theme.PocketAgentTheme(darkTheme = true) {
        ConfigScreen(navController = rememberNavController())
    }
}

