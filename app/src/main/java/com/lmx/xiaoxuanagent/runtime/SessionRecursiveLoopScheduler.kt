package com.lmx.xiaoxuanagent.runtime

data class SessionRecursiveLoopDirective(
    val shouldContinueNow: Boolean = false,
    val summary: String = "",
    val recommendedCommands: List<String> = emptyList(),
)

object SessionRecursiveLoopScheduler {
    fun derive(
        sessionId: String,
        continuation: TurnContinuation,
        nowMs: Long = System.currentTimeMillis(),
    ): SessionRecursiveLoopDirective {
        if (sessionId.isBlank()) return SessionRecursiveLoopDirective()
        if (!continuation.turnCompletion.keepRunning || continuation.turnCompletion.loopDetected) {
            return SessionRecursiveLoopDirective()
        }
        if (continuation.shouldAttemptImmediateReplan) {
            return SessionRecursiveLoopDirective(
                shouldContinueNow = true,
                summary = "main loop 检测到 immediate replan，继续递归推进当前任务。",
                recommendedCommands = listOf("/main-loop --session-id $sessionId", "/turn-loop --session-id $sessionId"),
            )
        }
        val runtimeState = SessionRuntime.State.runtimeState()
        if (runtimeState.session.sessionId != sessionId) return SessionRecursiveLoopDirective()
        if (runtimeState.safety.awaitingConfirmation) return SessionRecursiveLoopDirective()
        if (runtimeState.session.paused) return SessionRecursiveLoopDirective()
        if (runtimeState.session.externalWaitState != null) return SessionRecursiveLoopDirective()
        if (runtimeState.session.nextPlanEligibleAtMs > nowMs) return SessionRecursiveLoopDirective()
        val turnLoop = SessionTurnLoopStore.readSnapshot(sessionId)
        val loopInbox = SessionLoopInboxStore.refresh(sessionId = sessionId)
        val loopRuntime = SessionLoopRuntimeStore.refresh(sessionId = sessionId)
        val permissionCenter = SessionPermissionCenterStore.readSnapshot(sessionId = sessionId, limit = 6)
        val permissionProduct = SessionPermissionProductStore.refresh(sessionId = sessionId, limit = 8)
        val curator = SessionMemoryCuratorStore.readSnapshot(sessionId)
        val memoryFork = SessionMemoryForkRuntimeStore.refresh(sessionId)
        val taskOs = SessionPlatformFacade.readTaskOs(limit = 24)
        val blocked =
            buildList {
                turnLoop?.blockerLines?.forEach(::add)
                if (permissionCenter.pendingSummary.isNotBlank()) add("pending_confirmation")
            }
        if (blocked.any { blocker -> blocker.contains("awaiting", ignoreCase = true) || blocker.contains("blocked", ignoreCase = true) }) {
            return SessionRecursiveLoopDirective()
        }
        if (loopRuntime?.shouldContinueNow == false && !continuation.shouldAttemptImmediateReplan) {
            return SessionRecursiveLoopDirective()
        }
        return SessionRecursiveLoopDirective(
            shouldContinueNow = true,
            summary =
                buildString {
                    append("main loop 继续递归调度")
                    turnLoop?.summary?.takeIf { it.isNotBlank() }?.let { append(" | ").append(it.take(72)) }
                    loopInbox?.summary?.takeIf { it.isNotBlank() }?.let { append(" | inbox=").append(it.take(64)) }
                    loopRuntime?.summary?.takeIf { it.isNotBlank() }?.let { append(" | runtime=").append(it.take(64)) }
                    loopRuntime?.midTurnSummary?.takeIf { it.isNotBlank() }?.let { append(" | mid_turn=").append(it.take(64)) }
                    curator?.summary?.takeIf { it.isNotBlank() }?.let { append(" | curator=").append(it.take(64)) }
                    memoryFork?.summary?.takeIf { it.isNotBlank() }?.let { append(" | fork=").append(it.take(64)) }
                    if (permissionProduct.summary.isNotBlank()) {
                        append(" | permission=").append(permissionProduct.summary.take(48))
                    }
                    if (taskOs.overdueCount > 0 || taskOs.approvalCount > 0) {
                        append(" | task_os=").append(taskOs.summary)
                    }
                },
            recommendedCommands =
                listOfNotNull(
                    "/main-loop --session-id $sessionId",
                    "/turn-loop --session-id $sessionId",
                    loopRuntime?.takeIf { it.summary.isNotBlank() }?.let { "/loop-runtime --session-id $sessionId" },
                    loopInbox?.takeIf { it.totalCount > 0 }?.let { "/loop-inbox --session-id $sessionId" },
                    "/tool-runtime --session-id $sessionId",
                    curator?.takeIf { it.summary.isNotBlank() }?.let { "/memory-curator --session-id $sessionId" },
                    memoryFork?.takeIf { it.summary.isNotBlank() }?.let { "/memory-fork --session-id $sessionId" },
                    permissionCenter.takeIf { it.summary.isNotBlank() }?.let { "/permission-product --session-id $sessionId" },
                ).distinct(),
        )
    }
}
