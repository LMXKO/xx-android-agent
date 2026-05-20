package com.lmx.xiaoxuanagent.runtime

internal object SessionMemoryCuratorWorkerBridge {
    private const val WORKER_ROLE = "memory_curator"

    fun ensureQueued(
        sessionId: String,
        task: String,
        profileId: String,
        summary: String,
        policyReason: String = "",
    ): SessionWorkerRecord? {
        if (sessionId.isBlank()) return null
        val existing = current(sessionId)
        if (existing != null && existing.status != SessionWorkerStatus.COMPLETED && existing.status != SessionWorkerStatus.FAILED && existing.status != SessionWorkerStatus.STOPPED) {
            return existing
        }
        val worker =
            SessionWorkerStore.enqueueFork(
                parentSessionId = sessionId,
                task = task.ifBlank { "整理 session notebook" },
                entrySource = "memory_curator:$sessionId",
                source = "memory_curator",
                summary = summary.ifBlank { "session notebook curator 已排队。" },
                metadata =
                    mapOf(
                        "worker_role" to WORKER_ROLE,
                        "profile_id" to profileId,
                        "policy_reason" to policyReason,
                        "join_policy" to "detached",
                        "priority" to "6",
                        "max_retries" to "3",
                    ),
            )
        postStatus(
            worker = worker,
            type = SessionWorkerMailboxMessageType.STATUS_UPDATE,
            summary = summary.ifBlank { "session notebook curator 已排队。" },
            payload = mapOf("policy_reason" to policyReason),
        )
        return worker
    }

    fun markRunning(
        sessionId: String,
        taskId: String,
        task: String,
        profileId: String,
        policyReason: String = "",
    ): SessionWorkerRecord? {
        val worker =
            ensureQueued(
                sessionId = sessionId,
                task = task,
                profileId = profileId,
                summary = "session notebook curator 正在整理记忆。",
                policyReason = policyReason,
            ) ?: return null
        val running =
            SessionWorkerStore.markRunning(
                workerId = worker.workerId,
                summary = "session notebook curator 正在整理记忆。",
            ) ?: worker
        postStatus(
            worker = running,
            type = SessionWorkerMailboxMessageType.STATUS_UPDATE,
            summary = "session notebook curator 正在整理记忆。",
            payload = mapOf("task_id" to taskId),
        )
        return running
    }

    fun markCompleted(
        sessionId: String,
        summary: String,
        notebookPath: String = "",
    ): SessionWorkerRecord? {
        val worker = current(sessionId) ?: return null
        val completed =
            SessionWorkerStore.markCompleted(
                workerId = worker.workerId,
                summary = summary,
                resultSummary = notebookPath.ifBlank { summary },
            ) ?: return null
        postStatus(
            worker = completed,
            type = SessionWorkerMailboxMessageType.PARTIAL_RESULT,
            summary = summary,
            payload = mapOf("notebook_path" to notebookPath),
        )
        return completed
    }

    fun markFailed(
        sessionId: String,
        reason: String,
        deferred: Boolean,
    ): SessionWorkerRecord? {
        val worker = current(sessionId) ?: return null
        val updated =
            if (deferred) {
                SessionWorkerStore.markDeferred(
                    workerId = worker.workerId,
                    summary = "session notebook curator 已延后。",
                    deferByMs = 60_000L,
                    blockedReason = reason,
                    incrementRetry = false,
                )
            } else {
                SessionWorkerStore.markFailed(
                    workerId = worker.workerId,
                    summary = "session notebook curator 失败：${reason.take(96)}",
                )
            } ?: return null
        postStatus(
            worker = updated,
            type = SessionWorkerMailboxMessageType.STATUS_UPDATE,
            summary = if (deferred) "session notebook curator 已延后：${reason.take(96)}" else "session notebook curator 失败：${reason.take(96)}",
            payload = mapOf("reason" to reason.take(160)),
        )
        return updated
    }

    private fun current(
        sessionId: String,
    ): SessionWorkerRecord? =
        SessionWorkerStore.readChildren(parentSessionId = sessionId, limit = 24)
            .filter { it.metadata["worker_role"] == WORKER_ROLE }
            .maxByOrNull { it.updatedAtMs }

    private fun postStatus(
        worker: SessionWorkerRecord,
        type: SessionWorkerMailboxMessageType,
        summary: String,
        payload: Map<String, String>,
    ) {
        SessionWorkerMailboxStore.postMessage(
            type = type,
            senderSessionId = worker.parentSessionId.ifBlank { worker.sessionId },
            senderWorkerId = worker.workerId,
            senderAgentId = worker.agentId,
            recipientSessionId = worker.parentSessionId.ifBlank { worker.rootSessionId },
            rootSessionId = worker.rootSessionId.ifBlank { worker.parentSessionId },
            coordinatorSessionId = worker.coordinatorSessionId.ifBlank { worker.parentSessionId },
            title = "memory curator",
            summary = summary,
            payload =
                payload +
                    mapOf(
                        "worker_role" to WORKER_ROLE,
                        "worker_id" to worker.workerId,
                        "session_id" to worker.parentSessionId.ifBlank { worker.sessionId },
                    ),
            dedupeKey = "memory_curator:${worker.workerId}:${type.name.lowercase()}:${summary.hashCode()}",
            priority = if (type == SessionWorkerMailboxMessageType.PARTIAL_RESULT) 82 else 66,
        )
    }
}
