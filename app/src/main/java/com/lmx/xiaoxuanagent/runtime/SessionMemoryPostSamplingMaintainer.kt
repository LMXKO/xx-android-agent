package com.lmx.xiaoxuanagent.runtime

internal object SessionMemoryPostSamplingMaintainer {
    fun maintainAfterPlan(
        payload: AgentHookPayload,
    ): Boolean {
        if (payload.sessionId.isBlank() || payload.task.isBlank()) return false
        val profileId = payload.metadata["profile_id"].orEmpty()
        if (profileId.isBlank()) return false
        val policy = SessionMemoryPolicyStore.readSnapshot(payload.sessionId) ?: return false
        val session = SessionRuntime.State.runtimeState().session
        val shouldEnqueue =
            policy.notebookUpdateQueued ||
                policy.turnsSinceNotebookUpdate >= 2 ||
                policy.toolCallsSinceNotebookUpdate >= 2 ||
                payload.actionLabel.contains("web_", ignoreCase = true) ||
                payload.actionLabel.contains("delegate_local_agent", ignoreCase = true)
        if (!shouldEnqueue) return false
        SessionMemoryDocumentStore.refresh(
            sessionId = payload.sessionId,
            task = payload.task,
            profileId = profileId,
            trigger = "post_sampling:${payload.actionLabel.ifBlank { "planner" }}",
        )
        SessionMemoryFileMaintainer.refreshIfNeeded(
            sessionId = payload.sessionId,
            task = payload.task,
            profileId = profileId,
            turn = session.turns,
            trigger = "post_sampling:${payload.actionLabel.ifBlank { "planner" }}",
            history = session.history,
        )
        SessionMemoryCuratorWorkerBridge.ensureQueued(
            sessionId = payload.sessionId,
            task = payload.task,
            profileId = profileId,
            summary = "post-sampling session memory curator 已排队。",
            policyReason = "after_plan_threshold",
        )
        SessionMemoryCuratorStore.markQueued(
            sessionId = payload.sessionId,
            workerId = SessionMemoryCuratorStore.readSnapshot(payload.sessionId)?.workerId.orEmpty(),
            summary = "post-sampling session memory curator 已排队。",
            policyReason = "after_plan_threshold",
        )
        BackgroundMemoryExtractor.enqueueSessionNotebookUpdate(
            sessionId = payload.sessionId,
            task = payload.task,
            profileId = profileId,
        )
        return true
    }
}
