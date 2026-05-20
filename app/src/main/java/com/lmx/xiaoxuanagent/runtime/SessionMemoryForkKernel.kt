package com.lmx.xiaoxuanagent.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal object SessionMemoryForkKernel {
    private val forkExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "session-memory-fork").apply {
                isDaemon = true
            }
        }
    private val inFlightTaskIds = ConcurrentHashMap<String, String>()

    fun prepareNotebookFork(
        task: BackgroundMemoryQueueTask,
    ) {
        if (task.type != BackgroundMemoryTaskType.SESSION_NOTEBOOK || task.sessionId.isBlank()) return
        val worker =
            SessionMemoryCuratorWorkerBridge.markRunning(
                sessionId = task.sessionId,
                taskId = task.taskId,
                task = task.task,
                profileId = task.profileId,
                policyReason = SessionMemoryPolicyStore.readSnapshot(task.sessionId)?.lastReason.orEmpty(),
            )
        SessionMemoryCuratorStore.markRunning(
            sessionId = task.sessionId,
            taskId = task.taskId,
            workerId = worker?.workerId.orEmpty(),
        )
        SessionExplanationStore.record(
            sessionId = task.sessionId,
            phase = "memory_fork",
            title = "Memory fork running",
            summary = "session notebook curator fork 已启动。",
            detailLines =
                buildList {
                    add("task_id=${task.taskId}")
                    worker?.workerId?.takeIf { it.isNotBlank() }?.let { add("worker_id=$it") }
                    SessionMemoryPolicyStore.readSnapshot(task.sessionId)?.lastReason?.takeIf { it.isNotBlank() }?.let { add("policy_reason=$it") }
                },
        )
        SessionMemoryForkRuntimeStore.refresh(task.sessionId)
    }

    fun dispatchNotebookFork(
        task: BackgroundMemoryQueueTask,
        onCompleted: () -> Unit,
        onFailed: (String) -> Unit,
    ): Boolean {
        if (task.type != BackgroundMemoryTaskType.SESSION_NOTEBOOK || task.sessionId.isBlank()) return false
        if (inFlightTaskIds.putIfAbsent(task.taskId, task.sessionId) != null) {
            return true
        }
        prepareNotebookFork(task)
        return runCatching {
            forkExecutor.execute {
                try {
                    runNotebookForkInline(task)
                    onCompleted()
                } catch (throwable: Throwable) {
                    onFailed(throwable.message.orEmpty().ifBlank { throwable.javaClass.simpleName })
                } finally {
                    inFlightTaskIds.remove(task.taskId)
                }
            }
            true
        }.getOrElse { error ->
            inFlightTaskIds.remove(task.taskId)
            SessionExplanationStore.record(
                sessionId = task.sessionId,
                phase = "memory_fork",
                title = "Memory fork dispatch failed",
                summary = "无法派发 detached memory fork worker。",
                detailLines = listOf(error.message.orEmpty().ifBlank { error.javaClass.simpleName }),
            )
            false
        }
    }

    private fun runNotebookForkInline(
        task: BackgroundMemoryQueueTask,
    ) {
        val forkContext =
            SessionMemoryNotebookStore.buildForkContext(
                sessionId = task.sessionId,
                task = task.task,
                profileId = task.profileId,
            ) ?: error("无法构建 session memory fork context。")
        val output = SessionMemoryNotebookForkAgent.run(forkContext)
        SessionExplanationStore.record(
            sessionId = task.sessionId,
            phase = "memory_fork",
            title = "Memory fork agent",
            summary = output.forkSummary,
            detailLines = output.sourceLines.take(4),
        )
        SessionMemoryNotebookStore.applyForkOutput(output)
    }

    fun completeNotebookFork(
        task: BackgroundMemoryQueueTask,
    ) {
        if (task.type != BackgroundMemoryTaskType.SESSION_NOTEBOOK || task.sessionId.isBlank()) return
        val notebookPath = SessionMemoryNotebookStore.readSnapshot(task.sessionId)?.markdownPath.orEmpty()
        val worker =
            SessionMemoryCuratorWorkerBridge.markCompleted(
                sessionId = task.sessionId,
                summary = "session notebook curator fork 已完成 ${task.type.name.lowercase()}。",
                notebookPath = notebookPath,
            )
        SessionMemoryCuratorStore.markCompleted(
            sessionId = task.sessionId,
            summary = "session notebook curator fork 已完成 ${task.type.name.lowercase()}。",
            notebookPath = notebookPath,
            workerId = worker?.workerId.orEmpty(),
        )
        SessionExplanationStore.record(
            sessionId = task.sessionId,
            phase = "memory_fork",
            title = "Memory fork completed",
            summary = "session notebook curator fork 已完成。",
            detailLines = listOfNotNull(task.taskId.takeIf { it.isNotBlank() }?.let { "task_id=$it" }, notebookPath.takeIf { it.isNotBlank() }?.let { "notebook=$it" }),
        )
        SessionMemoryForkRuntimeStore.refresh(task.sessionId)
    }

    fun failNotebookFork(
        task: BackgroundMemoryQueueTask,
        reason: String,
        deferred: Boolean,
    ) {
        if (task.type != BackgroundMemoryTaskType.SESSION_NOTEBOOK || task.sessionId.isBlank()) return
        val worker =
            SessionMemoryCuratorWorkerBridge.markFailed(
                sessionId = task.sessionId,
                reason = reason,
                deferred = deferred,
            )
        SessionMemoryCuratorStore.markFailed(
            sessionId = task.sessionId,
            reason = reason,
            deferred = deferred,
            workerId = worker?.workerId.orEmpty(),
        )
        SessionExplanationStore.record(
            sessionId = task.sessionId,
            phase = "memory_fork",
            title = "Memory fork failed",
            summary = if (deferred) "session notebook curator fork 已延后。" else "session notebook curator fork 失败。",
            detailLines = listOf(reason.take(120)),
        )
        SessionMemoryForkRuntimeStore.refresh(task.sessionId)
    }
}
