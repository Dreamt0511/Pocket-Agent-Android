package com.pocketagent.app.update

sealed class TaskResult {
    data class Success(val message: String) : TaskResult()
    data class Failure(val error: String) : TaskResult()
    object Cancelled : TaskResult()
}

sealed class ActionResult {
    data class Success(val data: String = "") : ActionResult()
    data class Error(val message: String) : ActionResult()
}
