package com.pocketagent.app.service

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 任务队列管理器：支持任务排队、优先级、取消、并发控制。
 */
class TaskQueueManager {

    enum class TaskStatus {
        PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
    }

    data class ManagedTask(
        val id: String,
        val sessionId: String = "",
        val prompt: String,
        val status: MutableStateFlow<TaskStatus> = MutableStateFlow(TaskStatus.PENDING),
        val output: MutableStateFlow<String> = MutableStateFlow(""),
        val createdAt: Long = System.currentTimeMillis(),
        val completedAt: MutableStateFlow<Long?> = MutableStateFlow(null)
    )

    private val queue = ConcurrentLinkedQueue<ManagedTask>()
    private val taskHistory = mutableListOf<ManagedTask>()
    private val maxConcurrentTasks = 1 // 单任务执行，避免冲突

    private val _activeTasks = MutableStateFlow(0)
    val activeTasks: StateFlow<Int> = _activeTasks

    private val _allTasks = MutableStateFlow<List<ManagedTask>>(emptyList())
    val allTasks: StateFlow<List<ManagedTask>> = _allTasks

    private var executionCallback: ((ManagedTask) -> Unit)? = null

    fun setExecutionCallback(callback: (ManagedTask) -> Unit) {
        executionCallback = callback
    }

    fun enqueue(prompt: String, sessionId: String = ""): ManagedTask {
        val task = ManagedTask(
            id = "task_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
            sessionId = sessionId,
            prompt = prompt
        )
        queue.add(task)
        taskHistory.add(task)
        updateAllTasks()
        processNext()
        return task
    }

    fun cancel(taskId: String): Boolean {
        val task = queue.find { it.id == taskId }
        if (task != null) {
            task.status.value = TaskStatus.CANCELLED
            task.completedAt.value = System.currentTimeMillis()
            queue.remove(task)
            updateAllTasks()
            return true
        }

        val historyTask = taskHistory.find { it.id == taskId && it.status.value == TaskStatus.RUNNING }
        if (historyTask != null) {
            historyTask.status.value = TaskStatus.CANCELLED
            historyTask.completedAt.value = System.currentTimeMillis()
            _activeTasks.value--
            updateAllTasks()
            return true
        }

        return false
    }

    fun getHistory(): List<ManagedTask> {
        return taskHistory.toList()
    }

    fun clearHistory() {
        taskHistory.clear()
        queue.clear()
        updateAllTasks()
    }

    private fun processNext() {
        if (_activeTasks.value >= maxConcurrentTasks) return

        val task = queue.poll() ?: return

        task.status.value = TaskStatus.RUNNING
        _activeTasks.value++
        updateAllTasks()

        executionCallback?.invoke(task)
    }

    fun onTaskComplete(task: ManagedTask, success: Boolean, output: String) {
        task.status.value = if (success) TaskStatus.COMPLETED else TaskStatus.FAILED
        task.output.value = output
        task.completedAt.value = System.currentTimeMillis()
        _activeTasks.value--
        updateAllTasks()
        processNext()
    }

    private fun updateAllTasks() {
        _allTasks.value = taskHistory.toList()
    }

    fun getQueueSize(): Int = queue.size
}