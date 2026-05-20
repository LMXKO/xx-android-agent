package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.runtime.SessionMemoryFileMaintainer
import com.lmx.xiaoxuanagent.runtime.SessionMemoryNotebookStore
import com.lmx.xiaoxuanagent.runtime.SessionPlatformFacade
import com.lmx.xiaoxuanagent.runtime.SessionRuntime
import com.lmx.xiaoxuanagent.runtime.SessionTodoBoardStore
import com.lmx.xiaoxuanagent.runtime.SessionWorkerMailboxStore
import com.lmx.xiaoxuanagent.runtime.SessionWorkerStore

internal object PlatformWorkerLifecycleToolService {
    fun readWorkerStatus(
        sessionId: String,
        target: String,
        includeChildren: Boolean,
    ): AgentExecutionResult {
        val worker =
            SessionWorkerStore.readByWorkerId(target)
                ?: SessionWorkerStore.readBySessionId(target.ifBlank { sessionId })
                ?: SessionWorkerStore.readBySessionId(sessionId)
        if (worker == null) {
            return AgentExecutionResult(
                message = "没有找到可读的 worker 状态。",
                keepRunning = true,
                shouldImmediateReplan = true,
            )
        }
        val childLines =
            if (includeChildren) {
                SessionWorkerStore.readChildren(worker.sessionId.ifBlank { sessionId }, limit = 4).map { child ->
                    "${child.workerId} | ${child.status.name.lowercase()} | ${child.summary.ifBlank { child.task.take(48) }}"
                }
            } else {
                emptyList()
            }
        return AgentExecutionResult(
            message = "已读取 worker 状态：${worker.workerId}",
            keepRunning = true,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "platform_worker_status",
            resolvedTargetText = worker.summary.ifBlank { worker.task.take(96) },
            toolRuntimeDetailLines =
                buildList {
                    add("worker=${worker.workerId}")
                    add("status=${worker.status.name.lowercase()}")
                    add("role=${worker.metadata["worker_role"].orEmpty().ifBlank { "general" }}")
                    add("join=${worker.joinPolicy.name.lowercase()}")
                    add("result=${worker.resultSummary.ifBlank { "-" }}")
                    addAll(childLines)
                },
        )
    }

    fun mergeWorkerResult(
        sessionId: String,
        task: String,
        profileId: String,
        messageId: String,
        note: String,
    ): AgentExecutionResult {
        val message = SessionWorkerMailboxStore.readById(messageId)
        if (message == null) {
            return AgentExecutionResult(
                message = "未找到可合并的 worker 消息：$messageId",
                keepRunning = true,
                shouldImmediateReplan = true,
            )
        }
        val mergedNote =
            buildString {
                append("worker_result | ")
                append(message.summary.ifBlank { message.title }.take(160))
                note.takeIf { it.isNotBlank() }?.let { append(" | note=").append(it.take(120)) }
            }
        SessionMemoryNotebookStore.appendManualNote(
            sessionId = sessionId,
            task = task,
            profileId = profileId,
            note = mergedNote,
            tag = "worker_merge",
        )
        SessionTodoBoardStore.completeMatching(sessionId, message.senderWorkerId)
        SessionPlatformFacade.acknowledgeWorkerMailboxMessage(messageId, note.ifBlank { "merged_by_planner" })
        val runtimeSession = SessionRuntime.State.runtimeState().session
        SessionMemoryFileMaintainer.refreshIfNeeded(
            sessionId = sessionId,
            task = task,
            profileId = profileId,
            turn = runtimeSession.turns,
            trigger = "merge_worker_result",
            history = runtimeSession.history,
            force = true,
        )
        return AgentExecutionResult(
            message = "已合并 worker 结果：${messageId}",
            keepRunning = true,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "platform_worker_merge",
            resolvedTargetText = message.summary.ifBlank { message.title },
            toolRuntimeDetailLines =
                listOf(
                    "sender=${message.senderWorkerId.ifBlank { message.senderSessionId.ifBlank { "-" } }}",
                    "type=${message.type.name.lowercase()}",
                    "summary=${message.summary.ifBlank { message.title }}",
                ),
        )
    }
}
