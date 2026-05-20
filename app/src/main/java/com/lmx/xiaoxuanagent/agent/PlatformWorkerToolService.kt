package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.runtime.SessionRuntimeKernel

internal object PlatformWorkerToolService {
    fun delegateCurrentSessionTask(
        sessionId: String,
        task: String,
        summary: String,
        role: String,
    ): AgentExecutionResult {
        val roleDescriptor = PlatformWorkerRoleCatalog.resolve(role)
        val result =
            SessionRuntimeKernel.enqueueWorkerFork(
                parentSessionId = sessionId,
                task = task,
                source = "agent_tool",
                summary = summary.ifBlank { task },
                metadata =
                    mapOf(
                        "worker_role" to roleDescriptor.id,
                        "worker_title" to roleDescriptor.title,
                        "worker_guidance" to roleDescriptor.guidance,
                        "join_policy" to roleDescriptor.joinPolicy,
                        "priority" to roleDescriptor.priority.toString(),
                        "requested_by" to "planner",
                    ),
            )
        return AgentExecutionResult(
            message = if (result.success) "已派发本地 ${roleDescriptor.id} worker：${task.take(48)}" else result.summary,
            keepRunning = result.success,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "platform_local_agent_tool",
            resolvedTargetText = task.take(96),
            toolRuntimeDetailLines = result.lines + listOf("worker_id=${result.workerId.ifBlank { "-" }}", "worker_role=${roleDescriptor.id}"),
        )
    }
}
