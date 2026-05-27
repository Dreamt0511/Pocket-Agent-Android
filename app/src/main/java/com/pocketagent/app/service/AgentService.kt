package com.pocketagent.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.pocketagent.app.MainActivity
import com.pocketagent.app.R
import com.pocketagent.app.core.AgentDaemon
import com.pocketagent.app.core.TermuxLauncher
import com.pocketagent.app.core.TermuxServiceClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

class AgentService : Service() {

    companion object {
        const val CHANNEL_ID = "agent_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.pocketagent.app.action.STOP"

        // 状态流，供 UI 层订阅
        val serviceState = MutableStateFlow(ServiceState.IDLE)
        val currentTask = MutableStateFlow("")
        val taskOutput = MutableStateFlow("")
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var agentDaemon: AgentDaemon? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val taskQueue = ConcurrentLinkedQueue<TaskItem>()
    private var healthCheckJob: Job? = null

    enum class ServiceState {
        IDLE, RUNNING, ERROR
    }

    data class TaskItem(
        val id: String,
        val prompt: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val prompt = intent?.getStringExtra("task_prompt") ?: ""
        startForeground(NOTIFICATION_ID, createNotification(prompt))

        // 仅在有任务时才获取 WakeLock 和启动健康监控
        if (prompt.isNotBlank()) {
            acquireWakeLock()
            startHealthMonitor()
            taskQueue.add(TaskItem(
                id = "task_${System.currentTimeMillis()}",
                prompt = prompt
            ))
            processQueue()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pocket Agent 服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Pocket Agent 后台运行服务"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(taskDescription: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pocket Agent")
            .setContentText(
                if (taskDescription.isNotBlank())
                    "执行中: $taskDescription"
                else
                    "后台服务运行中"
            )
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "停止",
                stopIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PocketAgent:TaskWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 分钟超时
        }
    }

    private fun startHealthMonitor() {
        healthCheckJob = serviceScope.launch {
            while (isActive) {
                delay(30_000)
                when (TermuxServiceClient.healthCheck()) {
                    is TermuxServiceClient.HealthResult.Ok -> { /* 正常 */ }
                    is TermuxServiceClient.HealthResult.Error -> {
                        TermuxLauncher.launchFastAPI(this@AgentService)
                    }
                }
            }
        }
    }

    private fun processQueue() {
        serviceScope.launch {
            while (taskQueue.isNotEmpty()) {
                val task = taskQueue.poll() ?: break
                serviceState.value = ServiceState.RUNNING
                currentTask.value = task.prompt

                try {
                    executeTask(task)
                } catch (e: Exception) {
                    serviceState.value = ServiceState.ERROR
                    taskOutput.value = "错误: ${e.message}"
                }
            }

            serviceState.value = ServiceState.IDLE
            currentTask.value = ""
            wakeLock?.release()
            wakeLock = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun executeTask(task: TaskItem): String {
        if (agentDaemon == null) {
            agentDaemon = AgentDaemon(this@AgentService)
        }
        val result = agentDaemon!!.execute(task.prompt)
        return when (result) {
            is com.pocketagent.app.update.TaskResult.Success -> result.message
            is com.pocketagent.app.update.TaskResult.Failure -> throw Exception(result.error)
            is com.pocketagent.app.update.TaskResult.Cancelled -> "已取消"
        }
    }

    fun enqueueTask(prompt: String) {
        taskQueue.add(TaskItem(
            id = "task_${System.currentTimeMillis()}",
            prompt = prompt
        ))
        processQueue()
    }

    override fun onDestroy() {
        healthCheckJob?.cancel()
        serviceScope.cancel()
        agentDaemon?.destroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        healthCheckJob?.cancel()
        serviceScope.cancel()
        agentDaemon?.destroy()
        TermuxLauncher.stopFastAPI(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}