package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskStore
import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskType

data class RuntimeKernelResult(
    val success: Boolean,
    val summary: String,
    val sessionId: String = "",
    val workerId: String = "",
    val lines: List<String> = emptyList(),
)

object SessionRuntimeKernel {
    suspend fun startTask(
        task: String,
        entrySource: String,
        sessionId: String = "session_${System.currentTimeMillis()}",
        metadata: Map<String, String> = emptyMap(),
    ): RuntimeKernelResult {
        val trimmedTask = task.trim()
        if (trimmedTask.isBlank()) {
            return RuntimeKernelResult(
                success = false,
                summary = "任务为空，无法启动 session。",
            )
        }
        val started = SessionRuntime.startAgent(sessionId = sessionId, task = trimmedTask, entrySource = entrySource)
        if (started) {
            val worker =
                metadata["worker_id"]
                    ?.takeIf { it.isNotBlank() }
                    ?.let(SessionWorkerStore::readByWorkerId)
            if (worker != null) {
                SessionSessionGraphStore.attachWorkerSession(
                    sessionId = sessionId,
                    worker = worker,
                    entrySource = entrySource,
                    metadata = metadata,
                )
            } else {
                SessionSessionGraphStore.ensureRootSession(
                    sessionId = sessionId,
                    task = trimmedTask,
                    entrySource = entrySource,
                    metadata = metadata,
                )
            }
            SessionExecutionCoordinatorStore.sync(reason = "kernel_start_task")
        }
        return RuntimeKernelResult(
            success = started,
            summary =
                if (started) {
                    "session 已进入 runtime 主链。"
                } else {
                    "session 启动失败。"
                },
            sessionId = sessionId,
            lines =
                if (started) {
                    listOf("session=$sessionId", "entry_source=$entrySource", "task=${trimmedTask.take(96)}")
                } else {
                    listOf("task=${trimmedTask.take(96)}")
                },
        )
    }

    fun resumeSession(
        sessionId: String,
        userCorrection: String = "",
    ): RuntimeKernelResult {
        if (sessionId.isNotBlank() && SessionRuntime.State.runtimeState().session.sessionId != sessionId) {
            if (!SessionRuntime.Planning.bootstrapFromResumeSnapshot(sessionId)) {
                return RuntimeKernelResult(false, "找不到可恢复 session。", sessionId = sessionId)
            }
        }
        SessionRuntime.continueAgent(userCorrection)
        return RuntimeKernelResult(
            success = true,
            summary = "session 已恢复。",
            sessionId = SessionRuntime.State.runtimeState().session.sessionId.ifBlank { sessionId },
            lines = listOf("session=${SessionRuntime.State.runtimeState().session.sessionId.ifBlank { sessionId }}"),
        )
    }

    fun approveSafety(
        sessionId: String,
        userCorrection: String = "",
    ): RuntimeKernelResult {
        val approved =
            if (sessionId.isBlank()) {
                SessionRuntime.Lifecycle.approvePendingSafetyConfirmation(userCorrection) != null
            } else {
                SessionPlatformFacade.approvePendingSafetyForSession(sessionId, userCorrection)
            }
        return RuntimeKernelResult(
            success = approved,
            summary = if (approved) "高风险确认已批准。" else "没有可批准的高风险确认。",
            sessionId = sessionId.ifBlank { SessionRuntime.State.runtimeState().session.sessionId },
            lines = listOf("session=${sessionId.ifBlank { SessionRuntime.State.runtimeState().session.sessionId }}"),
        )
    }

    fun rejectSafety(
        sessionId: String,
    ): RuntimeKernelResult {
        val rejected =
            if (sessionId.isBlank()) {
                SessionRuntime.rejectPendingSafetyConfirmation()
                true
            } else {
                SessionPlatformFacade.rejectPendingSafetyForSession(sessionId)
            }
        return RuntimeKernelResult(
            success = rejected,
            summary = if (rejected) "高风险确认已拒绝。" else "没有可拒绝的高风险确认。",
            sessionId = sessionId.ifBlank { SessionRuntime.State.runtimeState().session.sessionId },
            lines = listOf("session=${sessionId.ifBlank { SessionRuntime.State.runtimeState().session.sessionId }}"),
        )
    }

    fun stopSession(
        sessionId: String,
        reason: String,
    ): RuntimeKernelResult {
        if (sessionId.isNotBlank() && SessionRuntime.State.runtimeState().session.sessionId != sessionId) {
            SessionRuntime.Planning.bootstrapFromResumeSnapshot(sessionId)
        }
        SessionRuntime.Lifecycle.stopAgent(reason.ifBlank { "已停止任务。" })
        return RuntimeKernelResult(
            success = true,
            summary = "session 已停止。",
            sessionId = SessionRuntime.State.runtimeState().session.sessionId.ifBlank { sessionId },
            lines = listOf("session=${SessionRuntime.State.runtimeState().session.sessionId.ifBlank { sessionId }}", "reason=${reason.take(96)}"),
        )
    }

    fun enqueueWorkerFork(
        parentSessionId: String,
        task: String,
        source: String,
        summary: String,
        metadata: Map<String, String> = emptyMap(),
    ): RuntimeKernelResult {
        val trimmedTask = task.trim()
        if (trimmedTask.isBlank()) {
            return RuntimeKernelResult(success = false, summary = "worker 任务为空。")
        }
        val worker =
            SessionWorkerStore.enqueueFork(
                parentSessionId = parentSessionId,
                task = trimmedTask,
                entrySource = "worker:${parentSessionId.ifBlank { "root" }}",
                source = source,
                summary = summary.ifBlank { trimmedTask },
                metadata = metadata + mapOf("parent_session_id" to parentSessionId),
            )
        val proactiveTask =
            AssistantProactiveTaskStore.schedule(
            type = AssistantProactiveTaskType.WORKER_TASK,
            capability = SessionCapabilityKey.START_SESSION,
            title = "执行 worker 任务",
            summary = worker.summary.ifBlank { worker.task },
            task = worker.task,
            parentSessionId = worker.parentSessionId,
            fireAtMs = System.currentTimeMillis(),
            source = "session_worker",
            metadata =
                mapOf(
                    "worker_id" to worker.workerId,
                    "entry_source" to worker.entrySource,
                    "agent_id" to worker.agentId,
                    "parent_session_id" to worker.parentSessionId,
                    "parent_worker_id" to worker.parentWorkerId,
                    "parent_agent_id" to worker.parentAgentId,
                    "root_session_id" to worker.rootSessionId,
                    "coordinator_session_id" to worker.coordinatorSessionId,
                ) + metadata,
        )
        SessionAgentSidechainStore.recordWorkerScheduled(
            worker = worker,
            proactiveTaskId = proactiveTask.id,
        )
        SessionExecutionCoordinatorStore.sync(reason = "kernel_enqueue_worker_fork")
        return RuntimeKernelResult(
            success = true,
            summary = "worker 已排入队列。",
            workerId = worker.workerId,
            lines = listOf(worker.task, worker.summary),
        )
    }

    fun syncPlatformSnapshot(
        snapshot: SessionPlatformSnapshot,
    ) {
        SessionWorkerStore.syncPlatformSnapshot(snapshot)
        SessionSessionGraphStore.syncPlatformSnapshot(snapshot)
        SessionWorkerMailboxStore.syncPlatformSnapshot(snapshot)
        SessionExecutionCoordinatorStore.sync(reason = "kernel_sync_platform_snapshot")
    }
}
