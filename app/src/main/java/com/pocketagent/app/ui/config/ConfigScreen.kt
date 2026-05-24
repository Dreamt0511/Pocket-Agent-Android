package com.pocketagent.app.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.pocketagent.app.core.ConfigManager
import kotlinx.coroutines.launch

/**
 * 配置管理页面 — 简洁版
 *
 * 只展示用户真正需要关心的配置：
 *  1. 主模型（LLM 端点 / API Key / 模型名 / 温度 / 最大Token）
 *  2. 子模型（可选，不填则继承主模型）
 *  3. MCP 连接（NeuralBridge 地址）
 *  4. 高级（折叠）：迭代次数、递归限制、上下文窗口
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(navController: NavController) {
    val scope = rememberCoroutineScope()

    var configMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var saved by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    // 加载配置
    LaunchedEffect(Unit) {
        configMap = ConfigManager.loadAll()
        isLoading = false
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
                            label = "最大 Token",
                            value = configMap["LLM_MAX_TOKENS"] ?: "8000",
                            placeholder = "8000",
                            modifier = Modifier.weight(1f),
                            onValueChange = { configMap = configMap + ("LLM_MAX_TOKENS" to it) }
                        )
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
                }

                // ===== MCP =====
                SectionCard(title = "MCP 连接") {
                    ConfigField(
                        label = "服务地址",
                        value = configMap["MCP_SERVER_URL"] ?: "http://127.0.0.1:7474/mcp",
                        placeholder = "http://127.0.0.1:7474/mcp",
                        onValueChange = { configMap = configMap + ("MCP_SERVER_URL" to it) }
                    )
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

                // ===== 保存按钮 =====
                Button(
                    onClick = {
                        scope.launch {
                            ConfigManager.saveAll(configMap)
                            saved = true
                            kotlinx.coroutines.delay(3000)
                            saved = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 32.dp)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("保存设置", fontSize = 16.sp)
                }
            }
        }
    }
}

// ─── 小组件 ──────────────────────────────────────

@Composable
private fun SectionCard(
    title: String,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
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