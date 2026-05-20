package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.resolveResumeCorrectionDirective
import com.lmx.xiaoxuanagent.assistantos.AssistantControlSurfaceLauncher
import com.lmx.xiaoxuanagent.assistantos.AssistantOsController
import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskStore
import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskType
import com.lmx.xiaoxuanagent.memory.PersonalMemoryStore
import com.lmx.xiaoxuanagent.safety.PendingSafetyConfirmation
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyGrantStore
import com.lmx.xiaoxuanagent.taskprofile.TaskRouteResolution
import com.lmx.xiaoxuanagent.taskprofile.TaskRouteStatus
import com.lmx.xiaoxuanagent.taskprofile.TaskRegistry

internal object SessionRuntimeLifecycleSupport {
    private const val APPROVAL_FOLLOW_UP_PRIORITY = 100
    private const val APPROVAL_FOLLOW_UP_DEADLINE_MS = 15 * 60 * 1000L

    fun handleMaxTurnsReached() {
        val message = "已达到最大执行轮数，自动任务已停止。"
        val session = SessionRuntimeStore.session()
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
                reason = "max_turns_reached",
            ),
        )
        if (session.sessionId.isNotBlank()) {
            AssistantProactiveTaskStore.markCompletedMatching(
                type = AssistantProactiveTaskType.APPROVAL_FOLLOW_UP,
                sessionId = session.sessionId,
            )
            SessionWorkingMemoryStore.markFinished(
                sessionId = session.sessionId,
                finalStatus = AgentUiStatus.MAX_TURNS_REACHED,
                finalResult = message,
            )
            ReplayStore.finishSession(session.sessionId, AgentUiStatus.MAX_TURNS_REACHED, message)
            SessionRuntime.persistMemoryOutcome(session.sessionId, session.task, session.profileId, AgentUiStatus.MAX_TURNS_REACHED, message)
            SessionSessionGraphStore.syncPlatformSnapshot(SessionPlatformFacade.readCurrentSnapshot())
            PlatformCapabilityApprovalStore.expireSessionGrantsForSession(
                sessionId = session.sessionId,
                note = "session_terminal:max_turns_reached",
            )
            RuntimeSafetyGrantStore.expireSessionGrantsForSession(
                sessionId = session.sessionId,
                note = "session_terminal:max_turns_reached",
            )
        }
        SessionRuntime.publishRuntimeState(session.sessionId)
        SessionRuntimeBridgeSupport.publish(
            SessionBridgeEvent.AgentStopped(
                sessionId = session.sessionId,
                reason = message,
                status = AgentUiStatus.MAX_TURNS_REACHED,
            ),
        )
    }

    suspend fun startAgent(
        sessionId: String,
        task: String,
        entrySource: String,
    ): Boolean {
        val trimmedTask = task.trim()
        SessionSessionGraphStore.ensureRootSession(
            sessionId = sessionId,
            task = trimmedTask,
            entrySource = entrySource,
        )
        SessionRuntimeStore.dispatch(
            SessionCommand.EnterRouting(
                session =
                    SessionRuntimeTransitionFactory.routingSession(
                        sessionId = sessionId,
                        task = trimmedTask,
                        entrySource = entrySource,
                    ),
                resultSnapshot =
                    RuntimeResultSnapshot(
                        lastAction = "任务路由",
                        lastResult = "",
                        lastError = "",
                        hint = "正在调用大模型选择目标 App...",
                    ),
                reason = "enter_routing",
            ),
        )
        SessionRuntime.publishRuntimeState(sessionId)
        SessionRuntimeBridgeSupport.publish(
            SessionBridgeEvent.RoutingStarted(
                sessionId = sessionId,
                task = trimmedTask,
                entrySource = entrySource,
            ),
        )

        val routeResolution = TaskRegistry.resolve(trimmedTask)
        publishRoutingDiagnostics(sessionId = sessionId, routeResolution = routeResolution)
        val route = routeResolution.result
        val profile = route.profile
        when {
            route.status == TaskRouteStatus.ROUTING_FAILED -> {
                SessionRuntimeStore.dispatch(
                    SessionCommand.RoutingFailed(
                        routeSnapshot = SessionRuntime.buildRuntimeRouteSnapshot(routeResolution),
                        resultSnapshot =
                            RuntimeResultSnapshot(
                                lastAction = "任务路由",
                                lastResult = "",
                                lastError = route.reason,
                                hint = route.reason,
                            ),
                        reason = "finish_routing_failed",
                    ),
                )
                SessionRuntime.publishRuntimeState(sessionId)
                SessionRuntimeBridgeSupport.publish(
                    SessionBridgeEvent.RoutingFailed(
                        sessionId = sessionId,
                        task = trimmedTask,
                        entrySource = entrySource,
                        routeReason = route.reason,
                    ),
                )
                return false
            }

            route.status == TaskRouteStatus.APP_UNAVAILABLE || !route.installed -> {
                SessionRuntimeStore.dispatch(
                    SessionCommand.MarkAppUnavailable(
                        routeSnapshot = SessionRuntime.buildRuntimeRouteSnapshot(routeResolution),
                        resultSnapshot =
                            RuntimeResultSnapshot(
                                lastAction = "任务路由",
                                lastResult = "",
                                lastError = route.reason,
                                hint = route.reason,
                            ),
                        reason = "finish_routing_app_unavailable",
                    ),
                )
                SessionRuntime.publishRuntimeState(sessionId)
                SessionRuntimeBridgeSupport.publish(
                    SessionBridgeEvent.AppUnavailable(
                        sessionId = sessionId,
                        task = trimmedTask,
                        entrySource = entrySource,
                        profileDisplayName = profile.displayName,
                        targetPackageName = profile.packageName,
                        routeReason = route.reason,
                    ),
                )
                return false
            }

            else -> {
                SessionRuntimeStore.dispatch(
                    SessionCommand.RouteResolved(
                        routeSnapshot = SessionRuntime.buildRuntimeRouteSnapshot(routeResolution),
                        resultSnapshot =
                            RuntimeResultSnapshot(
                                lastAction = "任务路由",
                                lastResult = "",
                                lastError = "",
                                hint = "任务路由完成，准备启动 ${profile.displayName}。",
                            ),
                        reason = "finish_routing_resolved",
                    ),
                )
                SessionRuntime.publishRuntimeState(sessionId)
                SessionRuntimeBridgeSupport.publish(
                    SessionBridgeEvent.RouteResolved(
                        sessionId = sessionId,
                        task = trimmedTask,
                        entrySource = entrySource,
                        profileDisplayName = profile.displayName,
                        targetPackageName = profile.packageName,
                        routeReason = route.reason,
                    ),
                )
                startSession(
                    sessionId = sessionId,
                    task = trimmedTask,
                    entrySource = entrySource,
                    routeResolution = routeResolution,
                )
                return true
            }
        }
    }

    fun pauseAgent() {
        val session = SessionRuntimeStore.session()
        SessionRuntimeStore.dispatch(
            SessionCommand.EnterTakeover(
                takeoverReason = AgentUiTakeoverReason.PAUSED,
                resultSnapshot =
                    RuntimeResultSnapshot(
                        lastResult = "自动任务已暂停。",
                        hint = "自动任务已暂停。",
                    ),
                takeoverSnapshot = RuntimeTakeoverSnapshot(),
                reason = "enter_manual_pause",
            ),
        )
        SessionRuntime.dispatchSessionLifecycleHook(
            session = SessionRuntimeStore.session(),
            stage = AgentHookStage.ON_SUSPEND,
            result = "自动任务已暂停。",
            metadata =
                mapOf(
                    "suspend_type" to "manual_pause",
                    "plan_stage" to session.planningSnapshot.currentPlanStage,
                    "subgoal_id" to session.planningSnapshot.currentSubgoalId,
                ),
        )
        SessionRuntime.publishRuntimeState()
        AssistantControlSurfaceLauncher.bringToFront("manual_pause_control_surface")
        SessionRuntimeBridgeSupport.publish(
            SessionBridgeEvent.AgentPaused(
                sessionId = SessionRuntimeStore.session().sessionId,
            ),
        )
    }

    fun continueAgent(
        userCorrection: String = "",
    ) {
        if (SessionRuntimePlanningSupport.isBootstrapResumePending(SessionRuntimeStore.read())) {
            continueBootstrappedSession(userCorrection)
        } else {
            resumeAgent(userCorrection)
        }
    }

    fun resumeAgent(
        userCorrection: String = "",
    ) {
        val current = SessionRuntimeStore.session()
        val planningSnapshot = current.planningSnapshot
        val normalizedCorrection = normalizeHumanCorrection(userCorrection)
        val correctionDirective = resolveResumeCorrectionDirective(normalizedCorrection)
        val resumeContext =
            buildManualResumeContext(
                subgoalId = planningSnapshot.currentSubgoalId,
                nextObjective = planningSnapshot.nextObjective,
                userCorrection = normalizedCorrection,
            )
        SessionRuntimeStore.dispatch(
            SessionCommand.ResumeExecution(
                resumeContext = resumeContext,
                resultSnapshot =
                    RuntimeResultSnapshot(
                        lastResult = "自动任务已继续。",
                        hint =
                            if (normalizedCorrection.isBlank()) {
                                "自动任务已继续，等待下一次页面变化。"
                            } else {
                                "已带纠错恢复任务，等待下一次页面变化。"
                            },
                    ),
                takeoverSnapshot =
                    RuntimeTakeoverSnapshot(
                        latestTakeoverType = "manual_resume",
                        latestTakeoverReason = buildTakeoverDisplayReason("manual_resume", "用户手动恢复任务"),
                        latestTakeoverResumeHint = resumeContext.resumeHint,
                        latestTakeoverCorrection = normalizedCorrection,
                    ),
                reason = "resume_after_manual_takeover",
            ),
        )
        val resumedSession = SessionRuntimeStore.session()
        if (resumedSession.sessionId.isNotBlank()) {
            ReplayStore.appendEvent(
                resumedSession.sessionId,
                "manual_resume",
                "用户手动恢复任务",
                attributes =
                    mapOf(
                        "subgoal_id" to planningSnapshot.currentSubgoalId,
                        "plan_stage" to planningSnapshot.currentPlanStage,
                        "next_objective" to planningSnapshot.nextObjective,
                        "user_correction" to normalizedCorrection,
                        "correction_intent" to correctionDirective.intent.name.lowercase(),
                        "correction_policy" to correctionDirective.policy,
                    ),
            )
        }
        if (normalizedCorrection.isNotBlank()) {
            BackgroundMemoryExtractor.enqueueCorrectionTemplate(
                task = resumedSession.task,
                profileId = resumedSession.profileId,
                correction = normalizedCorrection,
            )
        }
        SessionRuntime.dispatchSessionLifecycleHook(
            session = resumedSession,
            stage = AgentHookStage.ON_RESUME,
            result = resumeContext.resumeHint,
            metadata =
                mapOf(
                    "resume_source" to resumeContext.source,
                    "resume_event" to resumeContext.resumeEvent,
                    "resume_subgoal_id" to resumeContext.resumedSubgoalId,
                    "plan_stage" to planningSnapshot.currentPlanStage,
                    "user_correction" to normalizedCorrection,
                ),
        )
        SessionRuntime.publishRuntimeState(resumedSession.sessionId)
        SessionRuntimeBridgeSupport.publish(
            SessionBridgeEvent.AgentResumed(
                sessionId = resumedSession.sessionId,
                resumeHint = resumeContext.resumeHint,
                resumeSource = resumeContext.source,
                userCorrection = normalizedCorrection,
            ),
        )
    }

    fun requestSafetyConfirmation(
        confirmation: PendingSafetyConfirmation,
    ) {
        val session = SessionRuntimeStore.session()
        val planningSnapshot = session.planningSnapshot
        val enrichedConfirmation =
            enrichPendingSafetyConfirmation(
                confirmation = confirmation,
                planStage = planningSnapshot.currentPlanStage,
                subgoalId = planningSnapshot.currentSubgoalId,
                nextObjective = planningSnapshot.nextObjective,
                targetPackageName = session.targetPackageName,
                profileId = session.profileId,
            )
        SessionRuntimeStore.dispatch(
            SessionCommand.EnterTakeover(
                takeoverReason = AgentUiTakeoverReason.AWAITING_CONFIRMATION,
                resultSnapshot =
                    RuntimeResultSnapshot(
                        lastResult = "",
                        lastError = "",
                        hint = "检测到高风险动作，等待你确认。",
                    ),
                takeoverSnapshot =
                    RuntimeTakeoverSnapshot(
                        latestTakeoverType = "safety_confirmation",
                        latestTakeoverReason =
                            buildTakeoverDisplayReason("safety_confirmation", enrichedConfirmation.summary, enrichedConfirmation.actionLabel),
                        latestTakeoverResumeHint = buildSafetyApprovalResumeContext(enrichedConfirmation).resumeHint,
                    ),
                safetyState =
                    RuntimeSafetyState(
                        mode = RuntimeSafetyMode.AWAITING_CONFIRMATION,
                        pendingConfirmation = enrichedConfirmation,
                    ),
                reason = "enter_safety_confirmation",
            ),
        )
        if (session.sessionId.isNotBlank()) {
            ReplayStore.appendEvent(
                sessionId = session.sessionId,
                type = "safety_confirmation_requested",
                message = "${enrichedConfirmation.title} | ${enrichedConfirmation.actionLabel} | ${enrichedConfirmation.summary}",
                attributes =
                    mapOf(
                        "title" to enrichedConfirmation.title,
                        "action_label" to enrichedConfirmation.actionLabel,
                        "summary" to enrichedConfirmation.summary,
                        "plan_stage" to enrichedConfirmation.planStage,
                        "subgoal_id" to enrichedConfirmation.subgoalId,
                        "next_objective" to enrichedConfirmation.nextObjective,
                        "target_package_name" to enrichedConfirmation.targetPackageName,
                    ),
            )
        }
        SessionRuntime.dispatchSessionLifecycleHook(
            session = SessionRuntimeStore.session(),
            stage = AgentHookStage.ON_SUSPEND,
            result = enrichedConfirmation.summary,
            metadata =
                mapOf(
                    "suspend_type" to "safety_confirmation",
                    "action_label" to enrichedConfirmation.actionLabel,
                    "plan_stage" to enrichedConfirmation.planStage,
                    "subgoal_id" to enrichedConfirmation.subgoalId,
                ),
        )
        if (session.sessionId.isNotBlank()) {
            val followUpFireAt = System.currentTimeMillis()
            AssistantProactiveTaskStore.schedule(
                type = AssistantProactiveTaskType.APPROVAL_FOLLOW_UP,
                capability = SessionCapabilityKey.READ_OPERATOR_CONSOLE,
                dedupeKey = "approval:${session.sessionId}:${enrichedConfirmation.approvalKey}",
                title = "处理风险确认",
                summary = enrichedConfirmation.summary.ifBlank { enrichedConfirmation.actionLabel },
                task = session.task,
                sessionId = session.sessionId,
                fireAtMs = followUpFireAt,
                source = "runtime_safety_confirmation",
                priority = APPROVAL_FOLLOW_UP_PRIORITY,
                deadlineAtMs = followUpFireAt + APPROVAL_FOLLOW_UP_DEADLINE_MS,
                recommendedCommand = "/approve --session-id ${session.sessionId}",
                metadata =
                    mapOf(
                        "approval_key" to enrichedConfirmation.approvalKey,
                        "action_label" to enrichedConfirmation.actionLabel,
                        "target_package_name" to enrichedConfirmation.targetPackageName,
                        "grant_scope_hint" to enrichedConfirmation.grantScopeHint,
                        "priority" to APPROVAL_FOLLOW_UP_PRIORITY.toString(),
                        "recommended_command" to "/approve --session-id ${session.sessionId}",
                    ),
            )
        }
        SessionRuntime.publishRuntimeState(session.sessionId)
        AssistantControlSurfaceLauncher.bringToFront("safety_confirmation_control_surface")
        SessionRuntimeBridgeSupport.publish(
            SessionBridgeEvent.SafetyConfirmationRequested(
                sessionId = session.sessionId,
                confirmation = enrichedConfirmation,
            ),
        )
    }

    fun approvePendingSafetyConfirmation(userCorrection: String = ""): PendingSafetyConfirmation? {
        val normalizedCorrection = normalizeHumanCorrection(userCorrection)
        val correctionDirective = resolveResumeCorrectionDirective(normalizedCorrection)
        val safetyState = SessionRuntimeStore.safety()
        val pendingFromRuntime =
            safetyState.pendingConfirmation.takeIf { safetyState.awaitingConfirmation } ?: return null
        val resumeContext = buildSafetyApprovalResumeContext(pendingFromRuntime, normalizedCorrection)
        SessionRuntimeStore.dispatch(
            SessionCommand.ResumeExecution(
                resumeContext = resumeContext,
                resultSnapshot =
                    RuntimeResultSnapshot(
                        lastResult = "已确认继续执行高风险动作。",
                        hint = resumeContext.resumeHint,
                    ),
                takeoverSnapshot =
                    RuntimeTakeoverSnapshot(
                        latestTakeoverType = "safety_confirmation",
                        latestTakeoverReason =
                            buildTakeoverDisplayReason("safety_confirmation_approved", pendingFromRuntime.summary, pendingFromRuntime.actionLabel),
                        latestTakeoverResumeHint = resumeContext.resumeHint,
                        latestTakeoverCorrection = normalizedCorrection,
                    ),
                safetyState =
                    RuntimeSafetyState(
                        mode = RuntimeSafetyMode.APPROVED_ONCE,
                        grantedApprovalKey = pendingFromRuntime.approvalKey,
                    ),
                reason = "approve_safety_and_resume",
            ),
        )
        val runtimeSession = SessionRuntimeStore.session()
        if (normalizedCorrection.isNotBlank()) {
            BackgroundMemoryExtractor.enqueueCorrectionTemplate(
                task = runtimeSession.task,
                profileId = runtimeSession.profileId,
                correction = normalizedCorrection,
            )
        }
        if (runtimeSession.sessionId.isNotBlank()) {
            SessionWorkingMemoryStore.markApproval(
                sessionId = runtimeSession.sessionId,
                confirmation = pendingFromRuntime,
                userCorrection = normalizedCorrection,
            )
            val memoryPolicyDecision =
                SessionMemoryPolicyStore.recordTurn(
                    sessionId = runtimeSession.sessionId,
                    turn = runtimeSession.turns,
                    summary = pendingFromRuntime.summary.ifBlank { "approval_approved" },
                    forceNotebookUpdate = true,
                )
            if (memoryPolicyDecision.shouldEnqueueNotebookUpdate) {
                val worker =
                    SessionMemoryCuratorWorkerBridge.ensureQueued(
                        sessionId = runtimeSession.sessionId,
                        task = runtimeSession.task,
                        profileId = runtimeSession.profileId,
                        summary = "session notebook curator 已因审批通过排队。",
                        policyReason = memoryPolicyDecision.snapshot.lastReason,
                    )
                SessionMemoryCuratorStore.markQueued(
                    sessionId = runtimeSession.sessionId,
                    workerId = worker?.workerId.orEmpty(),
                    policyReason = memoryPolicyDecision.snapshot.lastReason,
                    summary = "session notebook curator 已因审批通过排队。",
                )
                SessionMemoryForkRuntimeStore.refresh(runtimeSession.sessionId)
                BackgroundMemoryExtractor.enqueueSessionNotebookUpdate(
                    sessionId = runtimeSession.sessionId,
                    task = runtimeSession.task,
                    profileId = runtimeSession.profileId,
                )
            }
            SessionSafetyDecisionStore.record(
                sessionId = runtimeSession.sessionId,
                turn = runtimeSession.turns,
                actionLabel = pendingFromRuntime.actionLabel,
                actionFamily = pendingFromRuntime.actionFamily,
                targetPackageName = pendingFromRuntime.targetPackageName,
                outcome = "approved",
                summary = pendingFromRuntime.summary.ifBlank { "用户已批准风险动作" },
                detailLines = listOfNotNull(normalizedCorrection.takeIf { it.isNotBlank() }),
            )
            SessionActionLifecycleStore.recordSynthetic(
                sessionId = runtimeSession.sessionId,
                turn = runtimeSession.turns,
                toolName = "safety.confirmation",
                actionLabel = pendingFromRuntime.actionLabel,
                phase = "approval",
                status = "approved",
                summary = pendingFromRuntime.summary.ifBlank { "用户已批准风险动作" },
                permissionFamily = pendingFromRuntime.actionFamily,
                progressLabel = "风险确认",
                recommendedCommands = listOf("/permission-center --session-id ${runtimeSession.sessionId}", "/action-lifecycle --session-id ${runtimeSession.sessionId}"),
                detailLines = listOfNotNull(normalizedCorrection.takeIf { it.isNotBlank() }),
            )
        }
        val grant =
            RuntimeSafetyGrantStore.issueGrant(
                sessionId = runtimeSession.sessionId,
                review =
                    com.lmx.xiaoxuanagent.safety.SafetyReview(
                        level = com.lmx.xiaoxuanagent.safety.RiskLevel.CONFIRM,
                        summary = pendingFromRuntime.summary,
                        actionLabel = pendingFromRuntime.actionLabel,
                        approvalKey = pendingFromRuntime.approvalKey,
                        targetPackageName = pendingFromRuntime.targetPackageName,
                        actionFamily = pendingFromRuntime.actionFamily,
                        riskLevelLabel = pendingFromRuntime.riskLevelLabel,
                        grantScopeHint = pendingFromRuntime.grantScopeHint,
                    ),
                permissionMode = AssistantOsController.currentPermissionMode(),
                note =
                    buildString {
                        append(pendingFromRuntime.summary.take(96))
                        normalizedCorrection.takeIf { it.isNotBlank() }?.let { append(" | correction=").append(it.take(48)) }
                    },
            )
        grant?.let {
            PersonalMemoryStore.recordSafetyRuleGrant(
                packageName = pendingFromRuntime.targetPackageName,
                actionFamily = pendingFromRuntime.actionFamily,
                scope = it.scope,
                note = pendingFromRuntime.summary,
            )
            SessionSafetyDecisionStore.record(
                sessionId = runtimeSession.sessionId,
                turn = runtimeSession.turns,
                actionLabel = pendingFromRuntime.actionLabel,
                actionFamily = pendingFromRuntime.actionFamily,
                targetPackageName = pendingFromRuntime.targetPackageName,
                outcome = "grant_issued",
                summary = "已签发运行时安全授权 ${it.scope}",
                detailLines = listOfNotNull(it.grantId, it.note.takeIf(String::isNotBlank)),
            )
        }
        if (runtimeSession.sessionId.isNotBlank()) {
            AssistantProactiveTaskStore.markCompletedMatching(
                type = AssistantProactiveTaskType.APPROVAL_FOLLOW_UP,
                sessionId = runtimeSession.sessionId,
            )
            ReplayStore.appendEvent(
                runtimeSession.sessionId,
                "safety_confirmation_approved",
                pendingFromRuntime.actionLabel,
                attributes =
                    mapOf(
                        "action_label" to pendingFromRuntime.actionLabel,
                        "plan_stage" to pendingFromRuntime.planStage,
                        "subgoal_id" to pendingFromRuntime.subgoalId,
                        "next_objective" to pendingFromRuntime.nextObjective,
                        "resume_hint" to resumeContext.resumeHint,
                        "user_correction" to normalizedCorrection,
                        "correction_intent" to correctionDirective.intent.name.lowercase(),
                        "correction_policy" to correctionDirective.policy,
                        "grant_scope" to grant?.scope.orEmpty(),
                    ),
            )
            SessionTurnLoopStore.refreshFromRuntime(
                sessionId = runtimeSession.sessionId,
                task = runtimeSession.task,
                profileId = runtimeSession.profileId,
                turn = runtimeSession.turns,
                phase = "approval_approved",
                summary = pendingFromRuntime.summary.ifBlank { pendingFromRuntime.actionLabel },
                recommendedCommands = listOf("/resume --session-id ${runtimeSession.sessionId}", "/why --session-id ${runtimeSession.sessionId}"),
            )
        }
        SessionRuntime.dispatchSessionLifecycleHook(
            session = runtimeSession,
            stage = AgentHookStage.ON_RESUME,
            result = resumeContext.resumeHint,
            metadata =
                mapOf(
                    "resume_source" to resumeContext.source,
                    "resume_event" to resumeContext.resumeEvent,
                    "resume_subgoal_id" to resumeContext.resumedSubgoalId,
                    "action_label" to pendingFromRuntime.actionLabel,
                    "user_correction" to normalizedCorrection,
                ),
        )
        SessionRuntime.publishRuntimeState(runtimeSession.sessionId)
        SessionRuntimeBridgeSupport.publish(
            SessionBridgeEvent.SafetyConfirmationApproved(
                sessionId = runtimeSession.sessionId,
                confirmation = pendingFromRuntime,
                resumeHint = resumeContext.resumeHint,
                userCorrection = normalizedCorrection,
            ),
        )
        return pendingFromRuntime
    }

    fun rejectPendingSafetyConfirmation() {
        val safetyState = SessionRuntimeStore.safety()
        val confirmation = safetyState.pendingConfirmation.takeIf { safetyState.awaitingConfirmation }
        SessionRuntimeStore.dispatch(
            SessionCommand.RejectSafetyAction(
                reason = "reject_safety_action",
            ),
        )
        val session = SessionRuntimeStore.session()
        confirmation?.let {
            AssistantProactiveTaskStore.markCompletedMatching(
                type = AssistantProactiveTaskType.APPROVAL_FOLLOW_UP,
                sessionId = session.sessionId,
            )
        }
        if (session.sessionId.isNotBlank() && confirmation != null) {
            SessionSafetyDecisionStore.record(
                sessionId = session.sessionId,
                turn = session.turns,
                actionLabel = confirmation.actionLabel,
                actionFamily = confirmation.actionFamily,
                targetPackageName = confirmation.targetPackageName,
                outcome = "rejected",
                summary = confirmation.summary.ifBlank { "用户已拒绝风险动作" },
                detailLines = listOfNotNull(confirmation.grantScopeHint.takeIf { it.isNotBlank() }),
            )
            ReplayStore.appendEvent(
                session.sessionId,
                "safety_confirmation_rejected",
                confirmation.actionLabel,
                attributes =
                    mapOf(
                        "action_label" to confirmation.actionLabel,
                        "summary" to confirmation.summary,
                        "plan_stage" to confirmation.planStage,
                        "subgoal_id" to confirmation.subgoalId,
                    ),
            )
            val memoryPolicyDecision =
                SessionMemoryPolicyStore.recordTurn(
                    sessionId = session.sessionId,
                    turn = session.turns,
                    summary = confirmation.summary.ifBlank { "approval_rejected" },
                    forceNotebookUpdate = true,
                )
            if (memoryPolicyDecision.shouldEnqueueNotebookUpdate) {
                val worker =
                    SessionMemoryCuratorWorkerBridge.ensureQueued(
                        sessionId = session.sessionId,
                        task = session.task,
                        profileId = session.profileId,
                        summary = "session notebook curator 已因审批拒绝排队。",
                        policyReason = memoryPolicyDecision.snapshot.lastReason,
                    )
                SessionMemoryCuratorStore.markQueued(
                    sessionId = session.sessionId,
                    workerId = worker?.workerId.orEmpty(),
                    policyReason = memoryPolicyDecision.snapshot.lastReason,
                    summary = "session notebook curator 已因审批拒绝排队。",
                )
                SessionMemoryForkRuntimeStore.refresh(session.sessionId)
                BackgroundMemoryExtractor.enqueueSessionNotebookUpdate(
                    sessionId = session.sessionId,
                    task = session.task,
                    profileId = session.profileId,
                )
            }
            SessionTurnLoopStore.refreshFromRuntime(
                sessionId = session.sessionId,
                task = session.task,
                profileId = session.profileId,
                turn = session.turns,
                phase = "approval_rejected",
                summary = confirmation.summary.ifBlank { confirmation.actionLabel },
                blockers = listOf("approval_rejected"),
            )
            SessionActionLifecycleStore.recordSynthetic(
                sessionId = session.sessionId,
                turn = session.turns,
                toolName = "safety.confirmation",
                actionLabel = confirmation.actionLabel,
                phase = "approval",
                status = "rejected",
                summary = confirmation.summary.ifBlank { "用户已拒绝风险动作" },
                permissionFamily = confirmation.actionFamily,
                progressLabel = "风险确认",
                recommendedCommands = listOf("/permission-center --session-id ${session.sessionId}", "/action-lifecycle --session-id ${session.sessionId}"),
                detailLines = listOfNotNull(confirmation.grantScopeHint.takeIf { it.isNotBlank() }),
            )
        }
        stopAgent(
            reason =
                if (confirmation == null) {
                    "已取消当前任务。"
                } else {
                    "已取消高风险动作：${confirmation.actionLabel}"
                },
        )
    }

    fun stopAgent(reason: String) {
        val session = SessionRuntimeStore.session()
        SessionRuntimeStore.dispatch(
            SessionCommand.TerminateSession(
                terminalReason = AgentUiTerminalReason.STOPPED,
                clearSafety = true,
                resultSnapshot =
                    RuntimeResultSnapshot(
                        lastResult = reason,
                        hint = reason,
                    ),
                takeoverSnapshot = RuntimeTakeoverSnapshot(),
                reason = "stop_agent",
            ),
        )
        if (session.sessionId.isNotBlank()) {
            AssistantProactiveTaskStore.markCompletedMatching(
                type = AssistantProactiveTaskType.APPROVAL_FOLLOW_UP,
                sessionId = session.sessionId,
            )
            SessionWorkingMemoryStore.markFinished(
                sessionId = session.sessionId,
                finalStatus = AgentUiStatus.STOPPED,
                finalResult = reason,
            )
            ReplayStore.finishSession(session.sessionId, AgentUiStatus.STOPPED, reason)
            SessionRuntime.persistMemoryOutcome(session.sessionId, session.task, session.profileId, AgentUiStatus.STOPPED, reason)
            PlatformCapabilityApprovalStore.expireSessionGrantsForSession(
                sessionId = session.sessionId,
                note = "session_terminal:stopped",
            )
            RuntimeSafetyGrantStore.expireSessionGrantsForSession(
                sessionId = session.sessionId,
                note = "session_terminal:stopped",
            )
            SessionTurnLoopStore.refreshFromRuntime(
                sessionId = session.sessionId,
                task = session.task,
                profileId = session.profileId,
                turn = session.turns,
                phase = "stopped",
                summary = reason,
                blockers = listOf("session_stopped"),
            )
        }
        SessionSessionGraphStore.syncPlatformSnapshot(SessionPlatformFacade.readCurrentSnapshot())
        SessionRuntime.publishRuntimeState(session.sessionId)
        SessionRuntimeBridgeSupport.publish(
            SessionBridgeEvent.AgentStopped(
                sessionId = session.sessionId,
                reason = reason,
                status = AgentUiStatus.STOPPED,
            ),
        )
    }

    private fun continueBootstrappedSession(
        userCorrection: String = "",
    ) {
        val currentState = SessionRuntimeStore.read()
        val current = currentState.session
        val planningSnapshot = current.planningSnapshot
        val normalizedCorrection = normalizeHumanCorrection(userCorrection)
        val correctionDirective = resolveResumeCorrectionDirective(normalizedCorrection)
        val resumeContext =
            buildBootstrapResumeContext(
                previousResumeContext = current.resumeContext,
                subgoalId = planningSnapshot.currentSubgoalId,
                nextObjective = planningSnapshot.nextObjective,
                userCorrection = normalizedCorrection,
            )
        SessionRuntimeStore.dispatch(
            SessionCommand.ResumeExecution(
                resumeContext = resumeContext,
                resultSnapshot =
                    RuntimeResultSnapshot(
                        lastResult = "已从恢复快照继续任务。",
                        hint =
                            if (normalizedCorrection.isBlank()) {
                                "已从恢复快照继续任务，等待下一次页面变化。"
                            } else {
                                "已带纠错从恢复快照继续任务，等待下一次页面变化。"
                            },
                    ),
                takeoverSnapshot =
                    RuntimeTakeoverSnapshot(
                        latestTakeoverType = "resume_snapshot_continue",
                        latestTakeoverReason = buildTakeoverDisplayReason("resume_snapshot_continue", "从本地恢复快照继续任务"),
                        latestTakeoverResumeHint = resumeContext.resumeHint,
                        latestTakeoverCorrection = resumeContext.userCorrection,
                    ),
                reason = "resume_after_bootstrap_restore",
            ),
        )
        val resumedSession = SessionRuntimeStore.session()
        if (resumedSession.sessionId.isNotBlank()) {
            ReplayStore.appendEvent(
                resumedSession.sessionId,
                "resume_snapshot_continue",
                "从本地恢复快照继续任务",
                attributes =
                    mapOf(
                        "previous_resume_source" to current.resumeContext.source,
                        "previous_resume_event" to current.resumeContext.resumeEvent,
                        "subgoal_id" to planningSnapshot.currentSubgoalId,
                        "plan_stage" to planningSnapshot.currentPlanStage,
                        "next_objective" to planningSnapshot.nextObjective,
                        "user_correction" to resumeContext.userCorrection,
                        "correction_intent" to correctionDirective.intent.name.lowercase(),
                        "correction_policy" to correctionDirective.policy,
                    ),
            )
        }
        if (normalizedCorrection.isNotBlank()) {
            BackgroundMemoryExtractor.enqueueCorrectionTemplate(
                task = resumedSession.task,
                profileId = resumedSession.profileId,
                correction = normalizedCorrection,
            )
        }
        SessionRuntime.dispatchSessionLifecycleHook(
            session = resumedSession,
            stage = AgentHookStage.ON_RESUME,
            result = resumeContext.resumeHint,
            metadata =
                mapOf(
                    "resume_source" to resumeContext.source,
                    "resume_event" to resumeContext.resumeEvent,
                    "resume_subgoal_id" to resumeContext.resumedSubgoalId,
                    "plan_stage" to planningSnapshot.currentPlanStage,
                    "user_correction" to resumeContext.userCorrection,
                ),
        )
        SessionRuntime.publishRuntimeState(resumedSession.sessionId)
        SessionRuntimeBridgeSupport.publish(
            SessionBridgeEvent.AgentResumed(
                sessionId = resumedSession.sessionId,
                resumeHint = resumeContext.resumeHint,
                resumeSource = resumeContext.source,
                userCorrection = resumeContext.userCorrection,
            ),
        )
    }

    private fun startSession(
        sessionId: String,
        task: String,
        entrySource: String,
        routeResolution: TaskRouteResolution,
    ) {
        val route = routeResolution.result
        val profile = route.profile
        SessionRuntimeStore.dispatch(
            SessionCommand.StartExecution(
                session =
                    SessionRuntimeTransitionFactory.executionSession(
                        sessionId = sessionId,
                        task = task,
                        entrySource = entrySource,
                        profileId = profile.id,
                        targetPackageName = profile.packageName,
                        executionPhase = AgentUiExecutionPhase.STARTING,
                    ),
                resultSnapshot =
                    RuntimeResultSnapshot(
                        lastAction = "启动自动任务",
                        lastResult = "",
                        lastError = "",
                        hint = "自动任务已启动，目标=${profile.displayName}，等待页面 observation。",
                    ),
                reason = "start_execution",
            ),
        )
        ReplayStore.startSession(
            sessionId = sessionId,
            profileId = profile.id,
            targetPackageName = profile.packageName,
            task = task,
            entrySource = entrySource,
            routeReason = route.reason,
            routePolicyTag = routeResolution.debug.policyTag,
            routeModelChoiceProfileId = routeResolution.debug.modelChoiceProfileId,
            routeSelectedProfileId = routeResolution.debug.selectedProfileId.ifBlank { profile.id },
            routeFallbackReason = routeResolution.debug.fallbackReason,
            routeMemoryHints = routeResolution.debug.memoryHintPreview,
            routeModelRaw = routeResolution.debug.modelRawResponse,
        )
        SessionRuntime.publishRuntimeState(sessionId)
        SessionRuntimeBridgeSupport.publish(
            SessionBridgeEvent.AgentStarted(
                sessionId = sessionId,
                task = task,
                profileDisplayName = profile.displayName,
                targetPackageName = profile.packageName,
                routeReason = route.reason,
                entrySource = entrySource,
            ),
        )
    }

    private fun publishRoutingDiagnostics(
        sessionId: String,
        routeResolution: TaskRouteResolution,
    ) {
        val route = routeResolution.result
        val profile = route.profile
        val messages =
            buildList {
                add(
                    "路由候选: count=${routeResolution.debug.candidateCount}, " +
                        "preview=${routeResolution.debug.candidatePreview.joinToString(" || ").ifBlank { "-" }}",
                )
                if (routeResolution.debug.memoryHintPreview.isNotEmpty()) {
                    add(
                        "路由记忆: ${routeResolution.debug.memoryHintPreview.joinToString(" || ").ifBlank { "-" }}",
                    )
                }
                if (routeResolution.debug.modelRawResponse.isNotBlank()) {
                    add("路由模型返回: ${routeResolution.debug.modelRawResponse}")
                }
                add(
                    "路由决策链: ${TaskRegistry.renderRouteDebugLines(routeResolution.debug).joinToString(" | ").ifBlank { "-" }}",
                )
                add(
                    "路由结果: profile=${profile.displayName}, targetPkg=${profile.packageName}, " +
                        "status=${route.status}, score=${route.score}, reason=${route.reason}",
                )
                if (routeResolution.debug.fallbackReason.isNotBlank()) {
                    add("路由回退: ${routeResolution.debug.fallbackReason}")
                }
            }
        SessionRuntimeBridgeSupport.publish(
            SessionBridgeEvent.RoutingDiagnostics(
                sessionId = sessionId,
                messages = messages,
            ),
        )
    }
}
