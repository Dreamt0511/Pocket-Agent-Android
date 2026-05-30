package com.pocketagent.app.core

import android.content.Context
import android.util.Log
import com.pocketagent.app.data.SettingsRepository
import com.pocketagent.app.data.settingsDataStore
import com.pocketagent.app.overlay.StreamBridge
import com.pocketagent.app.service.TaskQueueManager
import com.pocketagent.app.update.TaskResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AgentDaemon(
    private val context: Context,
    private val taskQueueManager: TaskQueueManager = TaskQueueManager()
) {
    companion object {
        private const val TAG = "AgentDaemon"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val settingsRepo = SettingsRepository(context.settingsDataStore)

    private val _status = MutableStateFlow<DaemonStatus>(DaemonStatus.Idle)
    val status: StateFlow<DaemonStatus> = _status

    sealed class DaemonStatus {
        object Idle : DaemonStatus()
        object Syncing : DaemonStatus()
        object Initializing : DaemonStatus()
        object Ready : DaemonStatus()
        object Executing : DaemonStatus()
        data class Error(val message: String) : DaemonStatus()
    }

    suspend fun bootstrap(): Boolean {
        Log.i(TAG, "=== Bootstrap start ===")

        _status.value = DaemonStatus.Initializing
        StreamBridge.status("连接 Termux 服务...")
        StreamBridge.out("[info] 检查 Termux FastAPI 服务...\n")

        // 先检查服务是否已在运行
        when (TermuxServiceClient.healthCheck()) {
            is TermuxServiceClient.HealthResult.Ok -> {
                StreamBridge.out("[done] Termux 服务已连接\n")
            }
            is TermuxServiceClient.HealthResult.Error -> {
                // 用户主动关闭过服务，则不自动启动
                if (settingsRepo.isServiceStopRequested()) {
                    StreamBridge.out("[info] 服务已被用户关闭，请手动启动\n")
                    _status.value = DaemonStatus.Idle
                    return false
                }
                StreamBridge.out("[info] 正在启动 Termux 服务（首次需 git clone + pip install，请耐心等待）...\n")
                TermuxLauncher.launchFastAPI(context)
                val r = TermuxServiceClient.waitForService(
                    maxAttempts = 60,
                    intervalMs = 5000L,
                    onAttempt = { attempt, total, error, sec ->
                        StreamBridge.status("连接 Termux 服务: 第${attempt}/${total}次（${sec}秒）")
                        Log.d(TAG, "waitForService attempt $attempt/$total: $error")
                    }
                )
                if (r.success) {
                    StreamBridge.out("[done] Termux 服务已启动\n")
                } else {
                    StreamBridge.error("Termux 服务启动失败: ${r.message}")
                    _status.value = DaemonStatus.Error("Termux 服务不可用")
                    return false
                }
            }
        }

        _status.value = DaemonStatus.Ready
        StreamBridge.status("就绪")
        StreamBridge.out("$ Pocket Agent 就绪 — 等待指令\n")
        // 标记初始化完成，后续隐藏 PyPI 镜像源 UI
        withContext(Dispatchers.IO) { settingsRepo.setInitialSetupDone(true) }
        Log.i(TAG, "=== Bootstrap complete ===")
        return true
    }

    suspend fun execute(command: String, sessionId: String = ""): TaskResult {
        val s = _status.value
        if (s !is DaemonStatus.Ready) {
            return TaskResult.Failure("Agent 未就绪，请等待初始化完成")
        }

        val task = taskQueueManager.enqueue(command, sessionId)
        _status.value = DaemonStatus.Executing
        StreamBridge.status("执行中")

        val output = StringBuilder()
        try {
            // 读取配置随请求发送，确保 Termux 侧使用用户配置的 LLM
            val settings = withContext(Dispatchers.IO) { settingsRepo.getSettings() }
            val config = mutableMapOf<String, String>()
            if (settings.llmBaseUrl.isNotBlank()) config["base_url"] = settings.llmBaseUrl
            if (settings.llmApiKey.isNotBlank()) config["api_key"] = settings.llmApiKey
            if (settings.llmModel.isNotBlank()) config["model"] = settings.llmModel
            config["temperature"] = settings.llmTemperature.toString()
            config["max_tokens"] = settings.llmMaxTokens.toString()

            TermuxServiceClient.chatStream(command, config, sessionId.ifBlank { null }).collect { data ->
                when {
                    data.startsWith("[TOOL]") -> {
                        try {
                            val jsonStr = data.removePrefix("[TOOL]").trim()
                            val event = org.json.JSONObject(jsonStr)
                            when (event.optString("type")) {
                                "tool_start" -> {
                                    val toolName = event.optString("name", "工具")
                                    val toolArgs = event.optJSONObject("args")
                                    val argsStr = toolArgs?.toString() ?: ""
                                    val display = argsStr
                                    StreamBridge.status("⚡ $toolName")
                                    val toolLine = "\n\n[__TOOL_CALL__]${toolName}: ${display}[__TOOL_CALL_END__]\n"
                                    StreamBridge.stream(toolLine)
                                    output.append(toolLine)
                                    task.output.value = output.toString()
                                }
                                "tool_end" -> {
                                    StreamBridge.status("就绪")
                                }
                                "thinking" -> StreamBridge.status("思考中")
                                "executor_start" -> {
                                    val objective = event.optString("objective", "")
                                    val label = "\n\n🟢 子Agent执行: $objective\n"
                                    StreamBridge.stream(label)
                                    output.append(label)
                                    task.output.value = output.toString()
                                    StreamBridge.status("子Agent执行中")
                                }
                                "executor_done" -> {
                                    val status = event.optString("status", "completed")
                                    val elapsed = event.optInt("elapsed", 0)
                                    val tokens = event.optInt("tokens", 0)
                                    val stats = buildList {
                                        if (elapsed > 0) add("⏱️ ${elapsed}s")
                                        if (tokens > 0) add("≈${tokens} tokens")
                                    }.joinToString(" | ")
                                    val statsLine = if (stats.isNotBlank()) "\n📊 $stats" else ""
                                    val msg = when (status) {
                                        "cancelled" -> "\n\n⏹️ 子Agent已中断$statsLine\n"
                                        else -> "\n\n✅ 子Agent完成$statsLine\n"
                                    }
                                    StreamBridge.stream(msg)
                                    output.append(msg)
                                    task.output.value = output.toString()
                                    StreamBridge.status("就绪")
                                }
                                "skill_condense" -> {
                                    val status = event.optString("status", "")
                                    val skill = event.optString("skill", "")
                                    if (status == "start") {
                                        StreamBridge.status("📝 沉淀技能: $skill")
                                        val label = "\n\n📝 沉淀技能: $skill\n"
                                        StreamBridge.stream(label)
                                        output.append(label)
                                        task.output.value = output.toString()
                                    } else if (status == "done") {
                                        StreamBridge.status("就绪")
                                    }
                                }
                                "done_stats" -> {
                                    val elapsed = event.optInt("elapsed", 0)
                                    if (elapsed > 0) {
                                        val statsLine = "\n\n📊 完成 (⏱️ ${elapsed}s)"
                                        StreamBridge.stream(statsLine)
                                        output.append(statsLine)
                                        task.output.value = output.toString()
                                    }
                                }
                                "skill_verify" -> {
                                    val status = event.optString("status", "")
                                    val skill = event.optString("skill", "")
                                    if (status == "start") {
                                        StreamBridge.status("🔍 验证技能: $skill")
                                        val label = "\n\n🔍 验证技能: $skill\n"
                                        StreamBridge.stream(label)
                                        output.append(label)
                                        task.output.value = output.toString()
                                    } else if (status == "done") {
                                        StreamBridge.status("就绪")
                                    } else if (status == "failed") {
                                        val error = event.optString("error", "")
                                        val label = "\n\n❌ 技能验证失败: $skill${if (error.isNotBlank()) " ($error)" else ""}\n"
                                        StreamBridge.stream(label)
                                        output.append(label)
                                        task.output.value = output.toString()
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    else -> {
                        val text = try {
                            org.json.JSONArray("[$data]").getString(0)
                        } catch (_: Exception) { data }
                        StreamBridge.stream(text)
                        output.append(text)
                        task.output.value = output.toString()
                    }
                }
            }
            taskQueueManager.onTaskComplete(task, true, output.toString())
            _status.value = DaemonStatus.Ready
            StreamBridge.status("空闲")
            return TaskResult.Success(output.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Execute failed", e)
            taskQueueManager.onTaskComplete(task, false, "错误: ${e.message}")
            _status.value = DaemonStatus.Ready
            StreamBridge.status("空闲")
            StreamBridge.error(e.message ?: "Unknown error")
            return TaskResult.Failure(e.message ?: "执行失败")
        }
    }

    fun cancel() {
        TermuxServiceClient.cancelChat()
        StreamBridge.info("已中断执行")
    }

    fun markDisconnected() {
        _status.value = DaemonStatus.Idle
    }

    suspend fun forceSync(): Boolean {
        StreamBridge.status("同步代码...")
        StreamBridge.out("[info] 通知 Termux 执行 git pull...\n")
        val settings = withContext(Dispatchers.IO) { settingsRepo.getSettings() }
        return when (val r = TermuxServiceClient.triggerSync(settings.pypiMirrorUrl)) {
            is TermuxServiceClient.SyncResult.Ok -> {
                StreamBridge.done("代码已同步")
                true
            }
            is TermuxServiceClient.SyncResult.Error -> {
                StreamBridge.error("同步失败: ${r.message}")
                false
            }
        }
    }

    fun destroy() {
        scope.cancel()
    }
}
