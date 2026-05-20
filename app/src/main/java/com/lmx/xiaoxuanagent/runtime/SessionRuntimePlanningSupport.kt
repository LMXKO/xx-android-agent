package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.BuildConfig
import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.AgentPlanningContext
import com.lmx.xiaoxuanagent.agent.IndexedScreenObservation
import com.lmx.xiaoxuanagent.agent.MultiStepTaskEngine
import com.lmx.xiaoxuanagent.agent.PlannerArtifactHint
import com.lmx.xiaoxuanagent.agent.RecoveryCategory
import com.lmx.xiaoxuanagent.agent.RecoveryDiagnosis
import com.lmx.xiaoxuanagent.agent.RecoveryPlanHint
import com.lmx.xiaoxuanagent.agent.ResumeContext
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.TaskPlanState
import com.lmx.xiaoxuanagent.memory.PersonalMemoryStore
import com.lmx.xiaoxuanagent.skills.isResumeEntryAlignmentSource
import com.lmx.xiaoxuanagent.skills.resolveResumeAlignmentPrePlanDirective
import com.lmx.xiaoxuanagent.skills.resolveResumeAlignmentRecoveryDirective
import com.lmx.xiaoxuanagent.skills.resolveResumeAlignmentTarget
import com.lmx.xiaoxuanagent.skills.resolveResumeContinuationPrePlanDirective
import com.lmx.xiaoxuanagent.skills.resolveResumeContinuationRecoveryDirective
import com.lmx.xiaoxuanagent.skills.resumeAlignmentStages
import com.lmx.xiaoxuanagent.skills.resumeContinuationStages
import com.lmx.xiaoxuanagent.skills.resumeEntryStageLabel
import com.lmx.xiaoxuanagent.skills.SkillRegistry
import org.json.JSONObject

internal object SessionRuntimePlanningSupport {
    fun resolveTargetAppReturnDirective(
        trigger: TargetAppReturnTrigger,
    ): TargetAppReturnDirective? = SessionRuntimeBootstrapSupport.resolveTargetAppReturnDirective(trigger)

    fun resolveTargetAppLaunchDirective(): TargetAppLaunchDirective? {
        return SessionRuntimeBootstrapSupport.resolveTargetAppLaunchDirective()
    }

    fun resolveBootstrapAutoLaunchDirective(): TargetAppLaunchDirective? {
        return SessionRuntimeBootstrapSupport.resolveBootstrapAutoLaunchDirective()
    }

    fun shouldAutoContinueBootstrappedSession(): Boolean {
        return SessionRuntimeBootstrapSupport.shouldAutoContinueBootstrappedSession()
    }

    fun bootstrapFromLatestResumeSnapshot(): Boolean {
        return SessionRuntimeBootstrapSupport.bootstrapFromLatestResumeSnapshot()
    }

    fun bootstrapFromResumeSnapshot(
        sessionId: String,
    ): Boolean = SessionRuntimeBootstrapSupport.bootstrapFromResumeSnapshot(sessionId)

    fun bootstrapFromResumeSnapshot(
        snapshot: SessionResumeSnapshot,
    ): Boolean = SessionRuntimeBootstrapSupport.bootstrapFromResumeSnapshot(snapshot)

    fun peekPlannerArtifactHints(
        sessionId: String,
        turn: Int,
    ) = SessionRuntimeArtifactHintSupport.peekPlannerArtifactHints(sessionId, turn)

    fun handleExternalWaitObservation(indexedObservation: IndexedScreenObservation): Boolean {
        val session = SessionRuntimeStore.session()
        if (!resolveAgentUiStatusModel(status = session.status, snapshot = session.statusSnapshot)
                .isTakeoverReason(AgentUiTakeoverReason.WAITING_EXTERNAL)
        ) {
            return true
        }
        if (!session.running) {
            return false
        }

        val taskPlanState =
            MultiStepTaskEngine.buildPlanState(
                task = session.task,
                profileId = session.profileId,
                observation = indexedObservation.observation,
                history = session.history,
                resumeContext = session.resumeContext,
            )
        if (taskPlanState.waitingForExternal) {
            enterExternalWait(
                observation = indexedObservation.observation,
                taskPlanState = taskPlanState,
                activeSkillTitles = session.planningSnapshot.activeSkillTitles,
                enteredAtMs = session.externalWaitState?.enteredAtMs ?: System.currentTimeMillis(),
                reason = "runtime_handle_external_wait_observation_waiting",
            )
            return false
        }

        val previousEvent = session.externalWaitState?.event.orEmpty()
        val resolvedResumeContext =
            buildResolvedResumeContext(
                previousWaitState = session.externalWaitState,
                taskPlanState = taskPlanState,
                previousEvent = previousEvent,
            )
        SessionRuntimeStore.dispatch(
            SessionCommand.ResumeExecution(
                resumeContext = resolvedResumeContext,
                nextPlanEligibleAtMs = System.currentTimeMillis() + 350L,
                planningSnapshot =
                    buildPlanningSnapshot(
                        taskPlanState,
                        session.planningSnapshot.activeSkillTitles,
                        session.planningSnapshot,
                    ),
                resultSnapshot =
                    RuntimeResultSnapshot(
                        lastResult = "检测到外部处理已完成，恢复执行。",
                        hint = resolvedResumeContext.resumeHint.ifBlank { "检测到外部处理已完成，恢复执行。" },
                    ),
                takeoverSnapshot =
                    RuntimeTakeoverSnapshot(
                        latestTakeoverType = "external_wait",
                        latestTakeoverReason = buildTakeoverDisplayReason("external_wait_resolved", previousEvent),
                        latestTakeoverResumeHint = resolvedResumeContext.resumeHint,
                        latestTakeoverCorrection = resolvedResumeContext.userCorrection,
                    ),
                reason = "resolve_external_wait",
            ),
        )
        if (session.sessionId.isNotBlank()) {
            ReplayStore.appendEvent(
                session.sessionId,
                "external_wait_resolved",
                "event=${previousEvent.ifBlank { "-" }} subgoal=${resolvedResumeContext.resumedSubgoalId.ifBlank { "-" }} sig=${indexedObservation.observation.signature}",
                attributes =
                    mapOf(
                        "event" to previousEvent,
                        "subgoal_id" to resolvedResumeContext.resumedSubgoalId,
                        "resume_hint" to resolvedResumeContext.resumeHint,
                        "signature" to indexedObservation.observation.signature,
                    ),
            )
        }
        SessionRuntime.dispatchSessionLifecycleHook(
            session = session,
            stage = AgentHookStage.ON_RESUME,
            result = resolvedResumeContext.resumeHint,
            metadata =
                mapOf(
                    "resume_source" to resolvedResumeContext.source,
                    "resume_event" to resolvedResumeContext.resumeEvent,
                    "resume_subgoal_id" to resolvedResumeContext.resumedSubgoalId,
                    "previous_wait_event" to previousEvent,
                    "observation_signature" to indexedObservation.observation.signature,
                ),
        )
        SessionRuntime.publishRuntimeState(session.sessionId)
        SessionRuntimeBridgeSupport.publish(
            SessionBridgeEvent.ExternalWaitResolved(
                sessionId = session.sessionId,
                previousEvent = previousEvent,
                resumeHint = resolvedResumeContext.resumeHint,
                userCorrection = resolvedResumeContext.userCorrection,
                observationSignature = indexedObservation.observation.signature,
            ),
        )
        return true
    }

    fun enterExternalWait(
        observation: ScreenObservation,
        taskPlanState: TaskPlanState,
        activeSkillTitles: List<String> = emptyList(),
        enteredAtMs: Long = System.currentTimeMillis(),
        reason: String = "runtime_enter_external_wait",
    ) {
        val session = SessionRuntimeStore.session()
        val runtimeExternalWaitState =
            RuntimeExternalWaitState(
                event = taskPlanState.waitingForEvent,
                reason = taskPlanState.suspendReason,
                subgoalId = taskPlanState.currentSubgoalId,
                signature = observation.signature,
                enteredAtMs = enteredAtMs,
            )
        SessionRuntimeStore.dispatch(
            SessionCommand.EnterTakeover(
                takeoverReason = AgentUiTakeoverReason.WAITING_EXTERNAL,
                externalWaitState = runtimeExternalWaitState,
                planningSnapshot = buildPlanningSnapshot(taskPlanState, activeSkillTitles, session.planningSnapshot),
                resultSnapshot =
                    RuntimeResultSnapshot(
                        lastResult = "",
                        lastError = "",
                        hint = taskPlanState.suspendReason.ifBlank { "等待外部处理后自动恢复。" },
                    ),
                takeoverSnapshot =
                    RuntimeTakeoverSnapshot(
                        latestTakeoverType = "external_wait",
                        latestTakeoverReason = buildTakeoverDisplayReason("external_wait", taskPlanState.suspendReason),
                        latestTakeoverResumeHint = "等待你处理完成后自动恢复当前子目标。",
                    ),
                reason = reason,
            ),
        )
        val sessionId = SessionRuntimeStore.session().sessionId
        if (sessionId.isNotBlank()) {
            ReplayStore.appendEvent(
                sessionId,
                "external_wait_entered",
                "event=${taskPlanState.waitingForEvent.ifBlank { "-" }} | ${taskPlanState.suspendReason}",
                attributes =
                    mapOf(
                        "event" to taskPlanState.waitingForEvent,
                        "reason" to taskPlanState.suspendReason,
                        "subgoal_id" to taskPlanState.currentSubgoalId,
                        "plan_stage" to taskPlanState.currentStage,
                        "next_objective" to taskPlanState.nextObjective,
                        "signature" to observation.signature,
                        "resume_source" to taskPlanState.resumeContext.source,
                        "resume_event" to taskPlanState.resumeContext.resumeEvent,
                        "resume_attempt" to taskPlanState.resumeContext.resumeAttempt,
                        "user_correction" to taskPlanState.resumeContext.userCorrection,
                    ),
            )
        }
        SessionRuntime.dispatchSessionLifecycleHook(
            session = SessionRuntimeStore.session(),
            stage = AgentHookStage.ON_SUSPEND,
            result = taskPlanState.suspendReason,
            metadata =
                mapOf(
                    "suspend_type" to "external_wait",
                    "waiting_event" to taskPlanState.waitingForEvent,
                    "plan_stage" to taskPlanState.currentStage,
                    "subgoal_id" to taskPlanState.currentSubgoalId,
                    "observation_signature" to observation.signature,
                ),
        )
        SessionRuntime.publishRuntimeState(sessionId)
        SessionRuntimeBridgeSupport.publish(
            SessionBridgeEvent.ExternalWaitEntered(
                sessionId = sessionId,
                waitingEvent = taskPlanState.waitingForEvent,
                suspendReason = taskPlanState.suspendReason,
                observationSignature = observation.signature,
            ),
        )
    }

    fun acquirePlanningContext(indexedObservation: IndexedScreenObservation): PlanningAcquireResult {
        val observation = indexedObservation.observation
        val runtimeAcquire = acquirePlanningRuntimeSession(observation)
        if (runtimeAcquire is PlanningRuntimeAcquire.EarlyResult) {
            return runtimeAcquire.result
        }
        val session = (runtimeAcquire as PlanningRuntimeAcquire.SessionReady).session
        val nextTurn = session.turns + 1
        val taskPlanState =
            MultiStepTaskEngine.buildPlanState(
                task = session.task,
                profileId = session.profileId,
                observation = observation,
                history = session.history,
                resumeContext = session.resumeContext,
            )
        val baseMemoryContext = PersonalMemoryStore.recall(session.task, session.profileId)
        val compactSnapshot = SessionConversationCompactStore.readSnapshot(session.sessionId)
        val memoryPolicySnapshot = SessionMemoryPolicyStore.readSnapshot(session.sessionId)
        val workingMemorySnapshot = SessionWorkingMemoryStore.readSnapshot(session.sessionId)
        val runtimeCompactionPlan =
            CompactService.buildRuntimeCompactionPlan(
                turn = nextTurn,
                history = session.history,
                taskPlanState = taskPlanState,
                compactSnapshot = compactSnapshot,
                workingMemorySnapshot = workingMemorySnapshot,
                memoryPolicySnapshot = memoryPolicySnapshot,
            )
        val workingMemoryLines = SessionWorkingMemoryStore.planningMemoryLines(session.sessionId, limit = 6)
        val explanationLines = SessionExplanationStore.planningSidebandLines(session.sessionId, limit = 4)
        val sessionNotebookLines = SessionMemoryNotebookStore.planningLines(session.sessionId, limit = 4)
        val memoryDocumentLines = SessionMemoryDocumentStore.planningLines(session.sessionId, limit = 3)
        val memoryFileLines = SessionMemoryFileStore.planningLines(session.sessionId, limit = 3)
        val memoryFilePolicyLines = SessionMemoryFilePolicyStore.planningLines(session.sessionId, limit = 2)
        val actionLifecycleLines = SessionActionLifecycleStore.planningLines(session.sessionId, limit = 2)
        val toolLedgerLines = SessionToolUseLedgerStore.planningLines(session.sessionId, limit = 4)
        val groundingHealthLines = SessionGroundingHealthStore.planningLines(session.sessionId, limit = 2)
        val loopInboxLines =
            SessionLoopInboxStore.refresh(sessionId = session.sessionId, task = session.task)
                ?.let { SessionLoopInboxStore.planningLines(session.sessionId, limit = 3) }
                .orEmpty()
        val turnAttachmentSnapshot =
            SessionTurnAttachmentStore.refresh(
                sessionId = session.sessionId,
                turn = nextTurn,
                task = session.task,
                profileId = session.profileId,
                limit = 4,
            )
        val turnAttachmentLines = SessionTurnAttachmentStore.planningLines(session.sessionId, limit = 3)
        val turnAttachments = turnAttachmentSnapshot?.attachments.orEmpty()
        val loopAttachmentLines =
            turnAttachments.map { attachment ->
                buildString {
                    append("source=").append(attachment.source)
                    append(" | type=").append(attachment.type)
                    append(" | priority=").append(attachment.priority)
                    append(" | title=").append(attachment.title.take(48))
                    attachment.summary.takeIf { it.isNotBlank() }?.let { append(" | summary=").append(it.take(96)) }
                }
            }
        val loopRuntimeLines =
            SessionLoopRuntimeStore.refresh(
                sessionId = session.sessionId,
                turn = nextTurn,
                task = session.task,
                profileId = session.profileId,
                phase = "planning_sideband",
                turnAttachments = turnAttachmentSnapshot,
            )?.let { SessionLoopRuntimeStore.planningLines(session.sessionId, limit = 2) }.orEmpty()
        val loopRuntimeSnapshot = SessionLoopRuntimeStore.readSnapshot(session.sessionId)
        val loopRuntimeSignals = loopRuntimeSnapshot?.lines?.take(4).orEmpty()
        val loopRuntimeDrainLines = loopRuntimeSnapshot?.drainLines?.take(3).orEmpty()
        val loopRuntimePrefetchLines = loopRuntimeSnapshot?.prefetchLines?.take(3).orEmpty()
        val loopRuntimeTaskSummaryLines = loopRuntimeSnapshot?.taskSummaryLines?.take(2).orEmpty()
        val skillPrefetchLines = loopRuntimeSnapshot?.skillPrefetchLines?.take(3).orEmpty()
        val toolRefreshLines = loopRuntimeSnapshot?.toolRefreshLines?.take(3).orEmpty()
        val memoryMaintenanceLines = SessionMemoryMaintenanceStore.planningLines(limit = 2)
        val memoryPolicyLines = SessionMemoryPolicyStore.planningLines(session.sessionId, limit = 2)
        val memoryCuratorLines = SessionMemoryCuratorStore.planningLines(session.sessionId, limit = 2)
        val memoryForkLines =
            SessionMemoryForkRuntimeStore.refresh(session.sessionId)
                ?.let { SessionMemoryForkRuntimeStore.planningLines(session.sessionId, limit = 2) }
                .orEmpty()
        val permissionProductLines =
            SessionPermissionProductStore.refresh(sessionId = session.sessionId, limit = 8)
                .let { SessionPermissionProductStore.planningLines(session.sessionId, limit = 2) }
        val toolContractLines =
            SessionToolContractStore.readSnapshot(limit = 3).let { snapshot ->
                buildList {
                    add("tool_contracts: ${snapshot.summary.ifBlank { "-" }}")
                    addAll(snapshot.lines.take(1).map { "tool_contract_item: $it" })
                }
            }
        val followUpHealthLines =
            SessionPlatformFacade.readFollowUpHealth(limit = 18).let { health ->
                buildList {
                    add("follow_up_health: ${health.summary.ifBlank { "enabled=${health.totalEnabled}" }}")
                    addAll(health.topLines.take(2).map { "follow_up_health: $it" })
                }
            }
        val todoBoardLines = SessionTodoBoardStore.planningLines(session.sessionId, limit = 3)
        val webResearchLines = PlatformWebResearchTraceStore.planningLines(session.sessionId, limit = 3)
        val turnLoopSnapshot =
            SessionTurnLoopStore.refreshFromRuntime(
                sessionId = session.sessionId,
                task = session.task,
                profileId = session.profileId,
                turn = nextTurn,
                phase = "planning_acquire",
                summary = session.task,
            )
        val turnLoopSideband =
            buildList {
                SessionCommandCenterStore.readRecentForSession(session.sessionId, limit = 2).forEach { receipt ->
                    add("command_receipt: ${receipt.status.name.lowercase()} | ${receipt.summary.take(96)}")
                }
                BackgroundMemoryExtractor.readQueue(includeCompleted = false, limit = 8)
                    .filter { it.sessionId == session.sessionId || (it.sessionId.isBlank() && it.task == session.task) }
                    .take(2)
                    .forEach { task ->
                        add("memory_queue: ${task.type.name.lowercase()} | ${task.status.name.lowercase()}")
                    }
                SessionPlatformFacade.readProactiveTasks(limit = 12)
                    .filter { it.enabled && (it.sessionId == session.sessionId || it.parentSessionId == session.sessionId) }
                    .take(2)
                    .forEach { task ->
                        add("follow_up: ${task.title} | due=${task.fireAtMs}")
                    }
                addAll(SessionMainLoopStore.planningLines(session.sessionId, limit = 2))
                addAll(memoryMaintenanceLines)
                addAll(memoryPolicyLines)
                addAll(memoryCuratorLines)
                addAll(memoryForkLines)
                addAll(memoryFilePolicyLines)
                addAll(turnAttachmentLines)
                addAll(loopInboxLines)
                addAll(loopAttachmentLines.map { "loop_attachment: $it" })
                addAll(loopRuntimeLines)
                addAll(loopRuntimeDrainLines.map { "loop_drain: $it" })
                addAll(loopRuntimePrefetchLines.map { "loop_prefetch: $it" })
                addAll(loopRuntimeTaskSummaryLines.map { "loop_task_summary: $it" })
                addAll(skillPrefetchLines.map { "skill_prefetch: $it" })
                addAll(toolRefreshLines.map { "tool_refresh: $it" })
                addAll(permissionProductLines)
                addAll(followUpHealthLines)
                addAll(todoBoardLines)
                addAll(groundingHealthLines)
                addAll(actionLifecycleLines)
                addAll(toolLedgerLines)
                addAll(toolContractLines)
                addAll(turnLoopSnapshot?.agendaLines?.take(2).orEmpty())
                addAll(explanationLines)
                addAll(sessionNotebookLines)
            }.take(8)
        val memoryContext =
            if (compactSnapshot == null) {
                baseMemoryContext.copy(
                    sessionMemories = (baseMemoryContext.sessionMemories + workingMemoryLines).takeLast(6),
                    shortTermNotes =
                        (
                            baseMemoryContext.shortTermNotes +
                                workingMemoryLines.take(3) +
                                memoryDocumentLines.take(1) +
                                memoryFileLines.take(1) +
                                memoryFilePolicyLines.take(1) +
                                toolLedgerLines.take(2) +
                                loopRuntimeLines.take(1) +
                                loopRuntimeTaskSummaryLines.take(1) +
                                toolContractLines.take(1)
                        ).takeLast(8),
                    turnLoopSideband = (baseMemoryContext.turnLoopSideband + turnLoopSideband).takeLast(8),
                    sessionNotebook =
                        (
                            baseMemoryContext.sessionNotebook +
                                sessionNotebookLines +
                                memoryDocumentLines +
                                memoryFileLines +
                                memoryFilePolicyLines +
                                memoryMaintenanceLines +
                                memoryPolicyLines +
                                memoryCuratorLines +
                                memoryForkLines +
                                memoryFilePolicyLines +
                                turnAttachmentLines +
                                loopInboxLines +
                                loopAttachmentLines.map { "attachment=$it" } +
                                loopRuntimeLines +
                                loopRuntimeDrainLines.map { "drain=$it" } +
                                loopRuntimePrefetchLines.map { "prefetch=$it" } +
                                skillPrefetchLines.map { "skill=$it" } +
                                toolRefreshLines.map { "tool_refresh=$it" } +
                                permissionProductLines +
                                todoBoardLines +
                                webResearchLines +
                                groundingHealthLines +
                                actionLifecycleLines +
                                turnLoopSnapshot?.blockerLines.orEmpty()
                        ).takeLast(6),
                    loopInboxAttachments = (baseMemoryContext.loopInboxAttachments + loopAttachmentLines).takeLast(6),
                    loopRuntimeSignals = (baseMemoryContext.loopRuntimeSignals + loopRuntimeSignals).takeLast(6),
                    loopRuntimeDrain = (baseMemoryContext.loopRuntimeDrain + loopRuntimeDrainLines).takeLast(6),
                    loopRuntimePrefetch = (baseMemoryContext.loopRuntimePrefetch + loopRuntimePrefetchLines).takeLast(6),
                    loopRuntimeTaskSummary = (baseMemoryContext.loopRuntimeTaskSummary + loopRuntimeTaskSummaryLines).takeLast(4),
                    skillPrefetchSignals = (baseMemoryContext.skillPrefetchSignals + skillPrefetchLines).takeLast(4),
                    toolRefreshSignals = (baseMemoryContext.toolRefreshSignals + toolRefreshLines).takeLast(4),
                    permissionProductSignals = (baseMemoryContext.permissionProductSignals + permissionProductLines).takeLast(4),
                    memoryForkSignals = (baseMemoryContext.memoryForkSignals + memoryForkLines).takeLast(4),
                    toolRuntimeContracts = (baseMemoryContext.toolRuntimeContracts + toolContractLines).takeLast(4),
                    compactionSignals = (baseMemoryContext.compactionSignals + runtimeCompactionPlan.injectedLines).takeLast(8),
                    turnAttachments = (baseMemoryContext.turnAttachments + turnAttachments).takeLast(6),
                )
            } else {
                baseMemoryContext.copy(
                    shortTermNotes =
                        (
                            baseMemoryContext.shortTermNotes +
                                listOfNotNull(
                                    compactSnapshot.conversationSummary.takeIf { it.isNotBlank() }?.let { "session_compact: $it" },
                                    compactSnapshot.toolUseSummary.takeIf { it.isNotBlank() }?.let { "tool_use: $it" },
                                compactSnapshot.recoverySummary.takeIf { it.isNotBlank() }?.let { "recovery: $it" },
                                workingMemoryLines.firstOrNull(),
                                toolLedgerLines.firstOrNull(),
                                loopInboxLines.firstOrNull(),
                                loopRuntimeLines.firstOrNull(),
                                memoryForkLines.firstOrNull(),
                            )
                        ).takeLast(8),
                    longTermMemories =
                        (
                            baseMemoryContext.longTermMemories +
                                compactSnapshot.boundaryDigests.takeLast(2).map { "compact_boundary: $it" }
                        ).takeLast(6),
                    sessionMemories = (baseMemoryContext.sessionMemories + workingMemoryLines).takeLast(6),
                    userPreferences =
                        (
                            baseMemoryContext.userPreferences +
                                workingMemoryLines.filter { it.startsWith("tool_preference:", ignoreCase = true) }
                        ).takeLast(8),
                    turnLoopSideband = (baseMemoryContext.turnLoopSideband + turnLoopSideband).takeLast(8),
                    sessionNotebook =
                        (
                            baseMemoryContext.sessionNotebook +
                                sessionNotebookLines +
                                memoryDocumentLines +
                                memoryMaintenanceLines +
                                memoryCuratorLines +
                                memoryForkLines +
                                turnAttachmentLines +
                                permissionProductLines +
                                todoBoardLines +
                                webResearchLines +
                                groundingHealthLines +
                                turnLoopSnapshot?.blockerLines.orEmpty()
                        ).takeLast(6),
                    loopInboxAttachments = (baseMemoryContext.loopInboxAttachments + loopAttachmentLines).takeLast(6),
                    loopRuntimeSignals = (baseMemoryContext.loopRuntimeSignals + loopRuntimeSignals).takeLast(6),
                    loopRuntimeDrain = (baseMemoryContext.loopRuntimeDrain + loopRuntimeDrainLines).takeLast(6),
                    loopRuntimePrefetch = (baseMemoryContext.loopRuntimePrefetch + loopRuntimePrefetchLines).takeLast(6),
                    loopRuntimeTaskSummary = (baseMemoryContext.loopRuntimeTaskSummary + loopRuntimeTaskSummaryLines).takeLast(4),
                    skillPrefetchSignals = (baseMemoryContext.skillPrefetchSignals + skillPrefetchLines).takeLast(4),
                    toolRefreshSignals = (baseMemoryContext.toolRefreshSignals + toolRefreshLines).takeLast(4),
                    permissionProductSignals = (baseMemoryContext.permissionProductSignals + permissionProductLines).takeLast(4),
                    memoryForkSignals = (baseMemoryContext.memoryForkSignals + memoryForkLines).takeLast(4),
                    toolRuntimeContracts = (baseMemoryContext.toolRuntimeContracts + toolContractLines).takeLast(4),
                    compactionSignals = (baseMemoryContext.compactionSignals + runtimeCompactionPlan.injectedLines).takeLast(8),
                    turnAttachments = (baseMemoryContext.turnAttachments + turnAttachments).takeLast(6),
                )
            }
        val transcriptCompaction =
            run {
                SessionRuntime.dispatchHook(
                    stage = AgentHookStage.PRE_COMPACT,
                    payload =
                        AgentHookPayload(
                            sessionId = session.sessionId,
                            task = session.task,
                            turn = nextTurn,
                            observationSignature = observation.signature,
                            pageState = observation.pageState,
                            metadata =
                                mapOf(
                                    "profile_id" to session.profileId,
                                    "plan_stage" to taskPlanState.currentStage,
                                    "compact_mode" to runtimeCompactionPlan.mode,
                                    "compact_trigger" to runtimeCompactionPlan.trigger,
                                    "history_size" to session.history.size.toString(),
                                ),
                        ),
                )
                SessionTranscriptCompactionSupport.compactForPlanner(
                    sessionId = session.sessionId,
                    turn = nextTurn,
                    task = session.task,
                    history = session.history,
                    runtimePlan = runtimeCompactionPlan,
                )
            }
        SessionRuntime.dispatchHook(
            stage = AgentHookStage.POST_COMPACT,
            payload =
                AgentHookPayload(
                    sessionId = session.sessionId,
                    task = session.task,
                    turn = nextTurn,
                    observationSignature = observation.signature,
                    pageState = observation.pageState,
                    result = transcriptCompaction.boundaryDigest,
                    metadata =
                        mapOf(
                            "profile_id" to session.profileId,
                            "plan_stage" to taskPlanState.currentStage,
                            "compact_mode" to transcriptCompaction.mode,
                            "compact_trigger" to transcriptCompaction.trigger,
                            "compacted_turns" to transcriptCompaction.compactedTurnCount.toString(),
                            "preserved_turns" to transcriptCompaction.preservedTurnCount.toString(),
                            "token_before" to transcriptCompaction.tokenEstimateBefore.toString(),
                            "token_after" to transcriptCompaction.tokenEstimateAfter.toString(),
                        ),
                ),
        )
        val effectiveMemoryContext =
            memoryContext.copy(
                compactionSignals =
                    (memoryContext.compactionSignals + transcriptCompaction.signalLines + SessionTranscriptCompactionStore.planningLines(session.sessionId, limit = 2))
                        .takeLast(12),
                turnAttachments =
                    (memoryContext.turnAttachments + listOfNotNull(transcriptCompaction.attachment)).takeLast(8),
                sessionNotebook = (memoryContext.sessionNotebook + todoBoardLines + memoryDocumentLines + memoryFileLines + memoryFilePolicyLines + webResearchLines).takeLast(8),
                sessionMemories = (memoryContext.sessionMemories + memoryDocumentLines + memoryFileLines + memoryFilePolicyLines + webResearchLines).takeLast(8),
            )
        syncResumeContextFromPlanState(
            session = session,
            taskPlanState = taskPlanState,
        )
        publishResumeContextApplied(
            session = session,
            taskPlanState = taskPlanState,
            observation = observation,
        )
        val effectiveTaskPlanState =
            when (val resumeWaitPolicy = resolveResumeWaitPolicy(session, taskPlanState)) {
                ResumeWaitPolicyDecision.Proceed -> taskPlanState

                is ResumeWaitPolicyDecision.ApplyGuard -> {
                    SessionRuntimeStore.dispatch(
                        SessionCommand.AdoptResumeContext(
                            resumeContext = resumeWaitPolicy.guardedTaskPlanState.resumeContext,
                            reason = "apply_resume_guard",
                        ),
                    )
                    if (session.sessionId.isNotBlank()) {
                        ReplayStore.appendEvent(
                            session.sessionId,
                            "external_wait_resume_guard",
                            resumeWaitPolicy.replayMessage,
                            attributes = resumeWaitPolicy.replayAttributes,
                        )
                    }
                    SessionRuntimeBridgeSupport.publish(SessionBridgeEvent.LogMessage(resumeWaitPolicy.logMessage))
                    resumeWaitPolicy.guardedTaskPlanState
                }

                is ResumeWaitPolicyDecision.ReenterWait -> {
                    if (session.sessionId.isNotBlank()) {
                        ReplayStore.appendEvent(
                            session.sessionId,
                            "resume_wait_reentered",
                            resumeWaitPolicy.replayMessage,
                            attributes = resumeWaitPolicy.replayAttributes,
                        )
                    }
                    SessionRuntimeBridgeSupport.publish(SessionBridgeEvent.LogMessage(resumeWaitPolicy.logMessage))
                    resumeWaitPolicy.adjustedTaskPlanState
                }
            }
        if (effectiveTaskPlanState.waitingForExternal) {
            enterExternalWait(
                observation = observation,
                taskPlanState = effectiveTaskPlanState,
                activeSkillTitles = session.planningSnapshot.activeSkillTitles,
            )
            return PlanningAcquireResult.Idle
        }
        val activeSkills =
            SkillRegistry.resolve(
                task = session.task,
                profileId = session.profileId,
                observation = observation,
                memoryContext = effectiveMemoryContext,
                taskPlanState = effectiveTaskPlanState,
                history = session.history,
            )
        val context =
            AgentPlanningContext(
                sessionId = session.sessionId,
                task = session.task,
                turn = nextTurn,
                profileId = session.profileId,
                targetPackageName = session.targetPackageName,
                indexedObservation = indexedObservation,
                observation = observation,
                history = transcriptCompaction.history,
                memoryContext = effectiveMemoryContext,
                activeSkills = activeSkills,
                taskPlanState = effectiveTaskPlanState,
            )
        onPlanningAcquired(context)
        SessionRuntimeBridgeSupport.publish(
            SessionBridgeEvent.PlanningContextAcquired(
                sessionId = session.sessionId,
                turn = nextTurn,
                observationSignature = observation.signature,
                pageState = observation.pageState,
                topTextsPreview = observation.topTexts.joinToString(limit = 4, truncated = "..."),
            ),
        )
        return PlanningAcquireResult.Acquired(context)
    }

    fun acquirePlanningDirective(indexedObservation: IndexedScreenObservation): PlanningDirective =
        SessionRuntime.toPlanningDirective(acquirePlanningContext(indexedObservation))

    fun resolveRuntimeResumeIntervention(
        planningContext: AgentPlanningContext,
    ): RuntimeResumeDecisionDirective? =
        SessionRuntimeResumeSupport.resolveRuntimeResumeIntervention(planningContext)

    fun resolveRuntimeResumeRecoveryIntervention(
        planningContext: AgentPlanningContext,
        diagnosis: RecoveryDiagnosis,
        observation: ScreenObservation,
    ): RuntimeResumeDecisionDirective? =
        SessionRuntimeResumeSupport.resolveRuntimeResumeRecoveryIntervention(
            planningContext = planningContext,
            diagnosis = diagnosis,
            observation = observation,
        )

    fun resolveRecoveryPlan(
        planningContext: AgentPlanningContext,
        observation: ScreenObservation,
        diagnosis: RecoveryDiagnosis?,
    ): RecoveryPlanHint {
        if (diagnosis == null) {
            return RecoveryPlanHint(diagnosis = null, recoveryDecision = null)
        }
        val runtimeResumeRecoveryDecision =
            resolveRuntimeResumeRecoveryIntervention(
                planningContext = planningContext,
                diagnosis = diagnosis,
                observation = observation,
            )
        if (runtimeResumeRecoveryDecision != null) {
            SessionRuntimeStore.dispatch(
                SessionCommand.RecordResumeDecisionState(
                    resumeDecision = runtimeResumeRecoveryDecision.snapshot,
                    reason = "runtime_record_resume_recovery",
                ),
            )
            return RecoveryPlanHint(
                diagnosis = diagnosis,
                recoveryDecision = runtimeResumeRecoveryDecision.decision,
            )
        }
        return com.lmx.xiaoxuanagent.agent.RecoveryEngine.planRecovery(
            task = planningContext.task,
            profile = SessionRuntime.currentTaskProfile(),
            profileId = planningContext.profileId,
            observation = observation,
            memoryContext = planningContext.memoryContext,
            taskPlanState = planningContext.taskPlanState,
            activeSkillIds = planningContext.activeSkills.map { it.id },
            history = planningContext.history,
            diagnosis = diagnosis,
        )
    }

    private fun addPlannerArtifactHint(
        hints: MutableMap<String, PlannerArtifactHint>,
        artifact: ArtifactRecord?,
    ) {
        if (artifact == null || artifact.artifactId.isBlank()) {
            return
        }
        hints.putIfAbsent(
            artifact.artifactId,
            PlannerArtifactHint(
                artifactId = artifact.artifactId,
                type = artifact.type,
                summary = artifact.summary,
            ),
        )
    }

    private fun plannerArtifactTypePriority(type: String): Int =
        when (type) {
            "planning_observation" -> 5
            "task_result_summary" -> 4
            "execution_verification" -> 3
            "turn_failure" -> 2
            "execution_trace" -> 1
            "planner_decision" -> 0
            else -> 0
        }

    internal fun onPlanningAcquired(planningContext: AgentPlanningContext) {
        val artifact =
            ArtifactStore.recordPlanningObservation(
                sessionId = planningContext.sessionId,
                turn = planningContext.turn,
                task = planningContext.task,
                observation = planningContext.observation,
                taskPlanState = planningContext.taskPlanState,
                activeSkills = planningContext.activeSkills,
            )
        SessionRuntimeStateSupport.updateTurnArtifacts(planningContext.sessionId, planningContext.turn) { current ->
            current.copy(planningObservationArtifactId = artifact?.artifactId.orEmpty())
        }
        artifact?.let {
            SessionRuntimeBridgeSupport.publishArtifactRecorded(planningContext.sessionId, planningContext.turn, it)
        }
        SessionRuntime.dispatchHook(
            stage = AgentHookStage.BEFORE_PLAN,
            payload =
                AgentHookPayload(
                    sessionId = planningContext.sessionId,
                    task = planningContext.task,
                    turn = planningContext.turn,
                    observationSignature = planningContext.observation.signature,
                    pageState = planningContext.observation.pageState,
                    metadata =
                        mapOf(
                            "profile_id" to planningContext.profileId,
                            "plan_stage" to planningContext.taskPlanState.currentStage,
                            "active_skill_ids" to planningContext.activeSkills.joinToString(",") { it.id },
                        ),
                ),
        )
        SessionRuntimeStore.dispatch(
            SessionCommand.PlanningAcquired(
                sessionId = planningContext.sessionId,
                profileId = planningContext.profileId,
                targetPackageName = planningContext.targetPackageName,
                task = planningContext.task,
                observationSignature = planningContext.observation.signature,
                planningSnapshot =
                    buildPlanningSnapshot(
                        planningContext.taskPlanState,
                        planningContext.activeSkills.map { it.title },
                        SessionRuntimeStore.session().planningSnapshot,
                    ),
                reason = "planning_acquired",
            ),
        )
        SessionRuntime.publishRuntimeState(planningContext.sessionId)
    }

    internal fun acquirePlanningRuntimeSession(
        observation: ScreenObservation,
    ): PlanningRuntimeAcquire {
        val currentState = SessionRuntimeStore.read()
        val current = currentState.session
        return when {
            !current.running || current.planning || current.paused ->
                PlanningRuntimeAcquire.EarlyResult(PlanningAcquireResult.Idle)

            System.currentTimeMillis() < current.nextPlanEligibleAtMs ->
                PlanningRuntimeAcquire.EarlyResult(PlanningAcquireResult.Idle)

            current.lastObservationSignature == observation.signature ->
                PlanningRuntimeAcquire.EarlyResult(PlanningAcquireResult.Idle)

            current.turns >= BuildConfig.AGENT_MAX_TURNS -> {
                val message = "已达到最大执行轮数，自动任务已停止。"
                SessionRuntimeStore.dispatch(
                    SessionCommand.TerminateSession(
                        terminalReason = AgentUiTerminalReason.MAX_TURNS_REACHED,
                        clearSafety = true,
                        resultSnapshot =
                            RuntimeResultSnapshot(
                                lastResult = message,
                                lastError = "已达到最大执行轮数。",
                                hint = message,
                            ),
                        takeoverSnapshot = RuntimeTakeoverSnapshot(),
                        reason = "acquire_planning_context_max_turns",
                    ),
                )
                PlanningRuntimeAcquire.EarlyResult(PlanningAcquireResult.MaxTurnsReached)
            }

            else -> {
                SessionRuntimeStore.dispatch(
                    SessionCommand.AcquirePlanning(
                        observationSignature = observation.signature,
                        reason = "acquire_planning_context",
                    ),
                )
                PlanningRuntimeAcquire.SessionReady(SessionRuntimeStore.session())
            }
        }
    }

    internal fun hasRestoredOrActiveRuntimeState(
        state: SessionRuntimeState,
    ): Boolean {
        val session = state.session
        val semantics = state.resolveSessionSemantics()
        return state.updatedAtMs > 0L ||
            state.lastTransition.isNotBlank() ||
            session.sessionId.isNotBlank() ||
            session.task.isNotBlank() ||
            session.turns > 0 ||
            session.running ||
            session.planning ||
            session.paused ||
            semantics.statusModel.code != AgentUiStatus.IDLE
    }

    internal fun isBootstrapResumePending(
        state: SessionRuntimeState,
    ): Boolean =
        state.lastTransition == "bootstrap_from_resume_snapshot" &&
            state.resolveSessionSemantics().statusModel.isTakeoverReason(AgentUiTakeoverReason.PAUSED) &&
            !state.safety.awaitingConfirmation &&
            !state.resolveSessionSemantics().statusModel.isTakeoverReason(AgentUiTakeoverReason.WAITING_EXTERNAL)

    internal fun resolveResumeBootstrapDecision(
        snapshot: SessionResumeSnapshot,
    ): ResumeBootstrapDecision {
        val now = System.currentTimeMillis()
        val session = SessionRuntimeTransitionFactory.restoreSession(snapshot)
        val resumedSubgoalId =
            snapshot.resumeContext.resumedSubgoalId
                .ifBlank { snapshot.planningSnapshot.currentSubgoalId }
        val resumedNextObjective = snapshot.planningSnapshot.nextObjective
        val fallbackResumeContext =
            buildManualResumeContext(
                subgoalId = resumedSubgoalId,
                nextObjective = resumedNextObjective,
            )
        val baseState =
            SessionRuntimeState(
                session = session,
                safety = RuntimeSafetyState(),
                routeSnapshot = snapshot.routeSnapshot,
                resultSnapshot = snapshot.resultSnapshot,
                takeoverSnapshot = snapshot.takeoverSnapshot,
                lastTransition = "bootstrap_from_resume_snapshot",
                updatedAtMs = now,
            )

        if (snapshot.safety.awaitingConfirmation && snapshot.safety.pendingConfirmation != null) {
            val restoredState =
                baseState.copy(
                    session =
                        session.copy(
                            running = snapshot.running,
                            planning = false,
                            paused = true,
                            statusSnapshot = AgentUiStatus.resolve(AgentUiStatus.AWAITING_CONFIRMATION).toSnapshot(),
                            lastObservationSignature = "",
                            nextPlanEligibleAtMs = 0L,
                        ),
                    safety = snapshot.safety,
                    resultSnapshot =
                        (snapshot.resultSnapshot ?: RuntimeResultSnapshot()).copy(
                            hint = "检测到上次任务仍在等待高风险确认，请确认后继续执行。",
                        ),
                    takeoverSnapshot =
                        (snapshot.takeoverSnapshot ?: RuntimeTakeoverSnapshot()).copy(
                            latestTakeoverType =
                                snapshot.takeoverSnapshot?.latestTakeoverType?.ifBlank { "safety_confirmation" }
                                    ?: "safety_confirmation",
                            latestTakeoverReason =
                                snapshot.takeoverSnapshot?.latestTakeoverReason?.ifBlank {
                                    buildTakeoverDisplayReason("resume_snapshot", "恢复到待确认状态")
                                } ?: buildTakeoverDisplayReason("resume_snapshot", "恢复到待确认状态"),
                            latestTakeoverResumeHint =
                                snapshot.takeoverSnapshot?.latestTakeoverResumeHint?.ifBlank {
                                    "确认后从当前子目标继续执行。"
                                } ?: "确认后从当前子目标继续执行。",
                        ),
                )
            return ResumeBootstrapDecision(
                restoredState = restoredState,
                logMessage = "检测到未完成的高风险确认，已从本地快照恢复待确认 session。",
                replayMessage = "restored awaiting confirmation from local resume snapshot",
                replayAttributes =
                    mapOf(
                        "policy" to "awaiting_confirmation",
                        "previous_status" to snapshot.status,
                        "session_id" to snapshot.sessionId,
                    ),
            )
        }

        val snapshotStatusModel = resolveAgentUiStatusModel(status = snapshot.status, snapshot = snapshot.statusSnapshot)

        if (snapshot.externalWaitState != null || snapshotStatusModel.isTakeoverReason(AgentUiTakeoverReason.WAITING_EXTERNAL)) {
            val restoredState =
                baseState.copy(
                    session =
                        session.copy(
                            running = snapshot.running,
                            planning = false,
                            paused = true,
                            statusSnapshot = AgentUiStatus.resolve(AgentUiStatus.WAITING_EXTERNAL).toSnapshot(),
                            externalWaitState = snapshot.externalWaitState,
                        ),
                    resultSnapshot =
                        (snapshot.resultSnapshot ?: RuntimeResultSnapshot()).copy(
                            hint =
                                snapshot.resultSnapshot?.hint?.ifBlank {
                                    snapshot.externalWaitState?.reason?.ifBlank {
                                        "等待外部处理完成后继续执行。"
                                    } ?: "等待外部处理完成后继续执行。"
                                } ?: snapshot.externalWaitState?.reason?.ifBlank {
                                    "等待外部处理完成后继续执行。"
                                } ?: "等待外部处理完成后继续执行。",
                        ),
                    takeoverSnapshot =
                        (snapshot.takeoverSnapshot ?: RuntimeTakeoverSnapshot()).copy(
                            latestTakeoverType =
                                snapshot.takeoverSnapshot?.latestTakeoverType?.ifBlank { "external_wait" }
                                    ?: "external_wait",
                            latestTakeoverReason =
                                snapshot.takeoverSnapshot?.latestTakeoverReason?.ifBlank {
                                    buildTakeoverDisplayReason(
                                        "resume_snapshot",
                                        snapshot.externalWaitState?.reason.orEmpty(),
                                    )
                                } ?: buildTakeoverDisplayReason(
                                    "resume_snapshot",
                                    snapshot.externalWaitState?.reason.orEmpty(),
                                ),
                            latestTakeoverResumeHint =
                                snapshot.takeoverSnapshot?.latestTakeoverResumeHint?.ifBlank {
                                    "等待外部处理完成后会继续当前子目标。"
                                } ?: "等待外部处理完成后会继续当前子目标。",
                        ),
                )
            return ResumeBootstrapDecision(
                restoredState = restoredState,
                logMessage = "检测到上次任务处于外部等待，已从本地快照恢复等待态 session。",
                replayMessage = "restored waiting_external from local resume snapshot",
                replayAttributes =
                    mapOf(
                        "policy" to "waiting_external",
                        "previous_status" to snapshot.status,
                        "waiting_event" to snapshot.externalWaitState?.event.orEmpty(),
                        "session_id" to snapshot.sessionId,
                    ),
            )
        }

        if (snapshotStatusModel.isTakeoverReason(AgentUiTakeoverReason.PAUSED)) {
            val restoredResumeContext =
                snapshot.resumeContext.takeIf { it.active } ?: fallbackResumeContext
            val restoredState =
                baseState.copy(
                    session =
                        session.copy(
                            running = snapshot.running,
                            planning = false,
                            paused = true,
                            statusSnapshot = AgentUiStatus.resolve(AgentUiStatus.PAUSED).toSnapshot(),
                            lastObservationSignature = "",
                            nextPlanEligibleAtMs = 0L,
                            resumeContext = restoredResumeContext,
                            externalWaitState = null,
                        ),
                    resultSnapshot =
                        (snapshot.resultSnapshot ?: RuntimeResultSnapshot()).copy(
                            hint = restoredResumeContext.resumeHint.ifBlank { "恢复到人工接管状态，等待你继续任务。" },
                        ),
                    takeoverSnapshot =
                        (snapshot.takeoverSnapshot ?: RuntimeTakeoverSnapshot()).copy(
                            latestTakeoverType =
                                snapshot.takeoverSnapshot?.latestTakeoverType?.ifBlank { "manual_pause" }
                                    ?: "manual_pause",
                            latestTakeoverReason =
                                snapshot.takeoverSnapshot?.latestTakeoverReason?.ifBlank {
                                    buildTakeoverDisplayReason("resume_snapshot", "恢复到人工接管状态")
                                } ?: buildTakeoverDisplayReason("resume_snapshot", "恢复到人工接管状态"),
                            latestTakeoverResumeHint =
                                snapshot.takeoverSnapshot?.latestTakeoverResumeHint?.ifBlank {
                                    restoredResumeContext.resumeHint
                                } ?: restoredResumeContext.resumeHint,
                            latestTakeoverCorrection =
                                snapshot.takeoverSnapshot?.latestTakeoverCorrection?.ifBlank {
                                    restoredResumeContext.userCorrection
                                } ?: restoredResumeContext.userCorrection,
                        ),
                )
            return ResumeBootstrapDecision(
                restoredState = restoredState,
                logMessage = "检测到上次任务停在人工接管状态，已从本地快照恢复待继续 session。",
                replayMessage = "restored paused session from local resume snapshot",
                replayAttributes =
                    mapOf(
                        "policy" to "paused_takeover",
                        "previous_status" to snapshot.status,
                        "session_id" to snapshot.sessionId,
                    ),
            )
        }

        val restoredResumeContext =
            snapshot.resumeContext.takeIf { it.active } ?: fallbackResumeContext
        val restoredState =
            baseState.copy(
                session =
                    session.copy(
                        running = true,
                        planning = false,
                        paused = true,
                        statusSnapshot = AgentUiStatus.resolve(AgentUiStatus.PAUSED).toSnapshot(),
                        lastObservationSignature = "",
                        nextPlanEligibleAtMs = 0L,
                        resumeContext = restoredResumeContext,
                        externalWaitState = null,
                    ),
                resultSnapshot =
                    (snapshot.resultSnapshot ?: RuntimeResultSnapshot()).copy(
                        lastResult = "检测到应用冷启动，任务已恢复为待继续状态。",
                        hint =
                            restoredResumeContext.resumeHint.ifBlank {
                                "检测到应用冷启动，已恢复当前任务，请确认页面后继续。"
                            },
                    ),
                takeoverSnapshot =
                    RuntimeTakeoverSnapshot(
                        latestTakeoverType = "cold_start_resume",
                        latestTakeoverReason = buildTakeoverDisplayReason("cold_start_resume", "应用冷启动后恢复任务"),
                        latestTakeoverResumeHint =
                            restoredResumeContext.resumeHint.ifBlank {
                                "恢复当前子目标后继续执行，不要从头重复。"
                            },
                        latestTakeoverCorrection = restoredResumeContext.userCorrection,
                    ),
            )
        return ResumeBootstrapDecision(
            restoredState = restoredState,
            logMessage = "检测到未完成的执行中 session，已从本地快照恢复为待继续状态。",
            replayMessage = "restored active session into paused takeover from local resume snapshot",
            replayAttributes =
                mapOf(
                    "policy" to "cold_start_takeover",
                    "previous_status" to snapshot.status,
                    "session_id" to snapshot.sessionId,
                ),
        )
    }

    internal fun buildPlanningSnapshot(
        taskPlanState: TaskPlanState,
        activeSkillTitles: List<String>,
        previousSnapshot: RuntimePlanningSnapshot = RuntimePlanningSnapshot(),
    ): RuntimePlanningSnapshot =
        RuntimePlanningSnapshot(
            planType = taskPlanState.planType,
            currentPlanStage = taskPlanState.currentStage,
            currentSubgoalId = taskPlanState.currentSubgoalId,
            nextObjective = taskPlanState.nextObjective,
            activeSkillTitles = activeSkillTitles,
            lastPlannerAction = "",
            lastPlannerRaw = "",
            lastResumeDecision = previousSnapshot.lastResumeDecision,
        )

    internal fun buildRuntimeResumeDecisionDirective(
        channel: RuntimeResumeDecisionChannel,
        phase: RuntimeResumeDecisionPhase,
        stage: String,
        action: AgentAction,
        policy: RuntimeResumePolicy,
        reason: String,
        recoveryCategory: String = "",
        extraJsonFields: Map<String, String> = emptyMap(),
    ): RuntimeResumeDecisionDirective =
        SessionRuntimeResumeSupport.buildRuntimeResumeDecisionDirective(
            channel = channel,
            phase = phase,
            stage = stage,
            action = action,
            policy = policy,
            reason = reason,
            recoveryCategory = recoveryCategory,
            extraJsonFields = extraJsonFields,
        )

    internal fun syncResumeContextFromPlanState(
        session: RuntimeSession,
        taskPlanState: TaskPlanState,
    ) {
        val effectiveResumeContext = taskPlanState.resumeContext
        if (!effectiveResumeContext.active || effectiveResumeContext == session.resumeContext) {
            return
        }
        SessionRuntimeStore.dispatch(
            SessionCommand.AdoptResumeContext(
                resumeContext = effectiveResumeContext,
                reason = "sync_resume_context_from_plan_state",
            ),
        )
    }

    internal fun publishResumeContextApplied(
        session: RuntimeSession,
        taskPlanState: TaskPlanState,
        observation: ScreenObservation,
    ) = SessionRuntimeResumeSupport.publishResumeContextApplied(
        session = session,
        taskPlanState = taskPlanState,
        observation = observation,
    )

    internal fun previewRuntimeResumeDirective(
        session: RuntimeSession,
        taskPlanState: TaskPlanState,
        observation: ScreenObservation,
    ): RuntimeResumeDecisionSnapshot? =
        SessionRuntimeResumeSupport.previewRuntimeResumeDirective(
            session = session,
            taskPlanState = taskPlanState,
            observation = observation,
        )

    internal fun shouldApplyResumeGuard(
        session: RuntimeSession,
        taskPlanState: TaskPlanState,
    ): Boolean = SessionRuntimeResumeSupport.shouldApplyResumeGuard(session, taskPlanState)

    internal fun resolveResumeWaitPolicy(
        session: RuntimeSession,
        taskPlanState: TaskPlanState,
    ): ResumeWaitPolicyDecision =
        SessionRuntimeResumeSupport.resolveResumeWaitPolicy(
            session = session,
            taskPlanState = taskPlanState,
        )

    internal fun buildResolvedResumeContext(
        previousWaitState: RuntimeExternalWaitState?,
        taskPlanState: TaskPlanState,
        previousEvent: String,
    ): ResumeContext =
        SessionRuntimeResumeSupport.buildResolvedResumeContext(
            previousWaitState = previousWaitState,
            taskPlanState = taskPlanState,
            previousEvent = previousEvent,
        )
}
