package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentTurnRecord
import com.lmx.xiaoxuanagent.agent.ResumeContext
import com.lmx.xiaoxuanagent.safety.PendingSafetyConfirmation
import org.json.JSONArray
import org.json.JSONObject

internal fun SessionCommand.toJson(): JSONObject =
    JSONObject().apply {
        put("type", javaClass.simpleName)
        when (this@toJson) {
            is SessionCommand.BlockPreflight -> {
                put("session", session.toJson())
                put("result_snapshot", resultSnapshot.toJson())
                put("reason", reason)
            }
            is SessionCommand.EnterRouting -> {
                put("session", session.toJson())
                put("result_snapshot", resultSnapshot.toJson())
                put("reason", reason)
            }
            is SessionCommand.RoutingFailed -> {
                put("route_snapshot", routeSnapshot.toJson())
                put("result_snapshot", resultSnapshot.toJson())
                put("reason", reason)
            }
            is SessionCommand.MarkAppUnavailable -> {
                put("route_snapshot", routeSnapshot.toJson())
                put("result_snapshot", resultSnapshot.toJson())
                put("reason", reason)
            }
            is SessionCommand.RouteResolved -> {
                put("route_snapshot", routeSnapshot.toJson())
                put("result_snapshot", resultSnapshot.toJson())
                put("reason", reason)
            }
            is SessionCommand.StartExecution -> {
                put("session", session.toJson())
                put("result_snapshot", resultSnapshot.toJson())
                put("reason", reason)
            }
            is SessionCommand.RecordPlannerDecisionState -> {
                put("action_label", actionLabel)
                put("raw_response", rawResponse)
                put("reason", reason)
            }
            is SessionCommand.RecordResumeDecisionState -> {
                put("resume_decision", resumeDecision.toJson())
                put("reason", reason)
            }
            is SessionCommand.ResetSafety ->
                put("reason", reason)
            is SessionCommand.RejectSafetyAction ->
                put("reason", reason)
            is SessionCommand.ConsumeGrantedRiskApproval ->
                put("reason", reason)
            is SessionCommand.FinishTurn -> {
                put("turn_record", turnRecord.toJson())
                put("disposition", disposition.toJson())
                put("next_plan_eligible_at_ms", nextPlanEligibleAtMs)
                put("clear_last_observation_signature", clearLastObservationSignature)
                put("clear_resume_context", clearResumeContext)
                put("keep_running", keepRunning)
                put("clear_safety", clearSafety)
                put("result_snapshot", resultSnapshot.toJson())
                put("takeover_snapshot", takeoverSnapshot.toJson())
                put("reason", reason)
            }
            is SessionCommand.AcquirePlanning -> {
                put("observation_signature", observationSignature)
                put("reason", reason)
            }
            is SessionCommand.PlanningAcquired -> {
                put("session_id", sessionId)
                put("profile_id", profileId)
                put("target_package_name", targetPackageName)
                put("task", task)
                put("observation_signature", observationSignature)
                put("planning_snapshot", planningSnapshot.toJson())
                put("reason", reason)
            }
            is SessionCommand.RecordRecoverableError -> {
                put("clear_safety", clearSafety)
                put("result_snapshot", resultSnapshot.toJson())
                put("takeover_snapshot", takeoverSnapshot.toJson())
                put("reason", reason)
            }
            is SessionCommand.TerminateSession -> {
                put("terminal_reason", terminalReason.name)
                put("clear_safety", clearSafety)
                put("result_snapshot", resultSnapshot.toJson())
                put("takeover_snapshot", takeoverSnapshot.toJson())
                put("reason", reason)
            }
            is SessionCommand.EnterTakeover -> {
                put("takeover_reason", takeoverReason.name)
                put("result_snapshot", resultSnapshot.toJson())
                put("takeover_snapshot", takeoverSnapshot.toJson())
                put("external_wait_state", externalWaitState?.toJson() ?: JSONObject())
                put("planning_snapshot", planningSnapshot?.toJson() ?: JSONObject())
                put("safety_state", safetyState.toJson())
                put("reason", reason)
            }
            is SessionCommand.ResumeExecution -> {
                put("resume_context", resumeContext.toJson())
                put("result_snapshot", resultSnapshot.toJson())
                put("takeover_snapshot", takeoverSnapshot.toJson())
                put("next_plan_eligible_at_ms", nextPlanEligibleAtMs)
                put("planning_snapshot", planningSnapshot?.toJson() ?: JSONObject())
                put("safety_state", safetyState.toJson())
                put("reason", reason)
            }
            is SessionCommand.AdoptResumeContext -> {
                put("resume_context", resumeContext.toJson())
                put("reason", reason)
            }
            is SessionCommand.AdvanceMissionLeg -> {
                put("profile_id", profileId)
                put("target_package_name", targetPackageName)
                put("task", task)
                put("reason", reason)
            }
        }
    }

internal fun jsonToSessionCommand(json: JSONObject): SessionCommand? {
    val type = json.optString("type")
    return when (type) {
        SessionCommand.BlockPreflight::class.java.simpleName ->
            SessionCommand.BlockPreflight(
                session = json.optJSONObject("session").toRuntimeSession(),
                resultSnapshot = json.optJSONObject("result_snapshot").toRuntimeResultSnapshot(),
                reason = json.optString("reason"),
            )
        SessionCommand.EnterRouting::class.java.simpleName ->
            SessionCommand.EnterRouting(
                session = json.optJSONObject("session").toRuntimeSession(),
                resultSnapshot = json.optJSONObject("result_snapshot").toRuntimeResultSnapshot(),
                reason = json.optString("reason"),
            )
        SessionCommand.RoutingFailed::class.java.simpleName ->
            SessionCommand.RoutingFailed(
                routeSnapshot = json.optJSONObject("route_snapshot").toRuntimeRouteSnapshot(),
                resultSnapshot = json.optJSONObject("result_snapshot").toRuntimeResultSnapshot(),
                reason = json.optString("reason"),
            )
        SessionCommand.MarkAppUnavailable::class.java.simpleName ->
            SessionCommand.MarkAppUnavailable(
                routeSnapshot = json.optJSONObject("route_snapshot").toRuntimeRouteSnapshot(),
                resultSnapshot = json.optJSONObject("result_snapshot").toRuntimeResultSnapshot(),
                reason = json.optString("reason"),
            )
        SessionCommand.RouteResolved::class.java.simpleName ->
            SessionCommand.RouteResolved(
                routeSnapshot = json.optJSONObject("route_snapshot").toRuntimeRouteSnapshot(),
                resultSnapshot = json.optJSONObject("result_snapshot").toRuntimeResultSnapshot(),
                reason = json.optString("reason"),
            )
        SessionCommand.StartExecution::class.java.simpleName ->
            SessionCommand.StartExecution(
                session = json.optJSONObject("session").toRuntimeSession(),
                resultSnapshot = json.optJSONObject("result_snapshot").toRuntimeResultSnapshot(),
                reason = json.optString("reason"),
            )
        SessionCommand.RecordPlannerDecisionState::class.java.simpleName ->
            SessionCommand.RecordPlannerDecisionState(
                actionLabel = json.optString("action_label"),
                rawResponse = json.optString("raw_response"),
                reason = json.optString("reason"),
            )
        SessionCommand.RecordResumeDecisionState::class.java.simpleName ->
            SessionCommand.RecordResumeDecisionState(
                resumeDecision = json.optJSONObject("resume_decision").toRuntimeResumeDecisionSnapshot(),
                reason = json.optString("reason"),
            )
        SessionCommand.ResetSafety::class.java.simpleName ->
            SessionCommand.ResetSafety(reason = json.optString("reason"))
        SessionCommand.RejectSafetyAction::class.java.simpleName ->
            SessionCommand.RejectSafetyAction(reason = json.optString("reason"))
        SessionCommand.ConsumeGrantedRiskApproval::class.java.simpleName ->
            SessionCommand.ConsumeGrantedRiskApproval(reason = json.optString("reason"))
        SessionCommand.FinishTurn::class.java.simpleName ->
            SessionCommand.FinishTurn(
                turnRecord = json.optJSONObject("turn_record").toAgentTurnRecord(),
                disposition = json.optJSONObject("disposition").toSessionTurnDisposition(json.optString("next_status")),
                nextPlanEligibleAtMs = json.optLong("next_plan_eligible_at_ms"),
                clearLastObservationSignature = json.optBoolean("clear_last_observation_signature"),
                clearResumeContext = json.optBoolean("clear_resume_context"),
                keepRunning = json.optBoolean("keep_running"),
                clearSafety = json.optBoolean("clear_safety"),
                resultSnapshot = json.optJSONObject("result_snapshot").toRuntimeResultSnapshot(),
                takeoverSnapshot = json.optJSONObject("takeover_snapshot").toRuntimeTakeoverSnapshot(),
                reason = json.optString("reason"),
            )
        SessionCommand.AcquirePlanning::class.java.simpleName ->
            SessionCommand.AcquirePlanning(
                observationSignature = json.optString("observation_signature"),
                reason = json.optString("reason"),
            )
        SessionCommand.PlanningAcquired::class.java.simpleName ->
            SessionCommand.PlanningAcquired(
                sessionId = json.optString("session_id"),
                profileId = json.optString("profile_id"),
                targetPackageName = json.optString("target_package_name"),
                task = json.optString("task"),
                observationSignature = json.optString("observation_signature"),
                planningSnapshot = json.optJSONObject("planning_snapshot").toRuntimePlanningSnapshot(),
                reason = json.optString("reason"),
            )
        SessionCommand.RecordRecoverableError::class.java.simpleName ->
            SessionCommand.RecordRecoverableError(
                clearSafety = json.optBoolean("clear_safety"),
                resultSnapshot = json.optJSONObject("result_snapshot").toRuntimeResultSnapshot(),
                takeoverSnapshot = json.optJSONObject("takeover_snapshot").toRuntimeTakeoverSnapshot(),
                reason = json.optString("reason"),
            )
        SessionCommand.TerminateSession::class.java.simpleName ->
            SessionCommand.TerminateSession(
                terminalReason =
                    runCatching { AgentUiTerminalReason.valueOf(json.optString("terminal_reason")) }
                        .getOrDefault(AgentUiTerminalReason.NONE),
                clearSafety = json.optBoolean("clear_safety"),
                resultSnapshot = json.optJSONObject("result_snapshot").toRuntimeResultSnapshot(),
                takeoverSnapshot = json.optJSONObject("takeover_snapshot").toRuntimeTakeoverSnapshot(),
                reason = json.optString("reason"),
            )
        SessionCommand.EnterTakeover::class.java.simpleName ->
            SessionCommand.EnterTakeover(
                takeoverReason =
                    runCatching { AgentUiTakeoverReason.valueOf(json.optString("takeover_reason")) }
                        .getOrDefault(AgentUiTakeoverReason.NONE),
                resultSnapshot = json.optJSONObject("result_snapshot").toRuntimeResultSnapshot(),
                takeoverSnapshot = json.optJSONObject("takeover_snapshot").toRuntimeTakeoverSnapshot(),
                externalWaitState = json.optJSONObject("external_wait_state").toRuntimeExternalWaitState(),
                planningSnapshot = json.optJSONObject("planning_snapshot").toRuntimePlanningSnapshotOrNull(),
                safetyState = json.optJSONObject("safety_state").toRuntimeSafetyState(),
                reason = json.optString("reason"),
            )
        SessionCommand.ResumeExecution::class.java.simpleName ->
            SessionCommand.ResumeExecution(
                resumeContext = json.optJSONObject("resume_context").toResumeContext(),
                resultSnapshot = json.optJSONObject("result_snapshot").toRuntimeResultSnapshot(),
                takeoverSnapshot = json.optJSONObject("takeover_snapshot").toRuntimeTakeoverSnapshot(),
                nextPlanEligibleAtMs = json.optLong("next_plan_eligible_at_ms"),
                planningSnapshot = json.optJSONObject("planning_snapshot").toRuntimePlanningSnapshotOrNull(),
                safetyState = json.optJSONObject("safety_state").toRuntimeSafetyState(),
                reason = json.optString("reason"),
            )
        SessionCommand.AdoptResumeContext::class.java.simpleName ->
            SessionCommand.AdoptResumeContext(
                resumeContext = json.optJSONObject("resume_context").toResumeContext(),
                reason = json.optString("reason"),
            )
        else -> null
    }
}

internal fun RuntimeSession.toJson(): JSONObject =
    JSONObject().apply {
        put("running", running)
        put("planning", planning)
        put("paused", paused)
        put("status", status)
        put("status_snapshot", statusSnapshot.toJson())
        put("session_id", sessionId)
        put("entry_source", entrySource)
        put("profile_id", profileId)
        put("target_package_name", targetPackageName)
        put("task", task)
        put("turns", turns)
        put("last_observation_signature", lastObservationSignature)
        put("next_plan_eligible_at_ms", nextPlanEligibleAtMs)
        put("history", JSONArray(history.map { it.toJson() }))
        put("recent_fingerprints", JSONArray(recentFingerprints))
        put("external_wait_state", externalWaitState?.toJson() ?: JSONObject())
        put("resume_context", resumeContext.toJson())
        put("planning_snapshot", planningSnapshot.toJson())
        put("mission", mission?.toJson() ?: JSONObject())
    }

internal fun JSONObject?.toRuntimeSession(): RuntimeSession {
    if (this == null || length() == 0) return RuntimeSession()
    return RuntimeSession(
        running = optBoolean("running"),
        planning = optBoolean("planning"),
        paused = optBoolean("paused"),
        statusSnapshot = optJSONObject("status_snapshot").toAgentUiStatusSnapshot(optString("status", AgentUiStatus.IDLE)),
        sessionId = optString("session_id"),
        entrySource = optString("entry_source", "app"),
        profileId = optString("profile_id"),
        targetPackageName = optString("target_package_name"),
        task = optString("task"),
        turns = optInt("turns"),
        lastObservationSignature = optString("last_observation_signature"),
        nextPlanEligibleAtMs = optLong("next_plan_eligible_at_ms"),
        history = optJSONArray("history").toAgentTurnRecords(),
        recentFingerprints = optJSONArray("recent_fingerprints").toStringList(),
        externalWaitState = optJSONObject("external_wait_state").toRuntimeExternalWaitState(),
        resumeContext = optJSONObject("resume_context").toResumeContext(),
        planningSnapshot = optJSONObject("planning_snapshot").toRuntimePlanningSnapshot(),
        mission = optJSONObject("mission").toCrossAppMission(),
    )
}

internal fun SessionTurnDisposition.toJson(): JSONObject =
    JSONObject().apply {
        when (this@toJson) {
            is SessionTurnDisposition.ContinueExecution -> {
                put("type", "continue_execution")
                put("phase", phase.name)
            }
            is SessionTurnDisposition.Terminate -> {
                put("type", "terminate")
                put("terminal_reason", reason.name)
            }
        }
    }

internal fun JSONObject?.toSessionTurnDisposition(
    legacyNextStatus: String = "",
): SessionTurnDisposition {
    if (this == null || length() == 0) {
        val legacyModel = AgentUiStatus.resolve(legacyNextStatus.ifBlank { AgentUiStatus.RUNNING })
        return if (legacyModel.category == AgentUiStatusCategory.TERMINAL) {
            SessionTurnDisposition.Terminate(legacyModel.terminalReason)
        } else {
            SessionTurnDisposition.ContinueExecution(legacyModel.executionPhase.takeIf { it != AgentUiExecutionPhase.NONE } ?: AgentUiExecutionPhase.RUNNING)
        }
    }
    return when (optString("type")) {
        "terminate" ->
            SessionTurnDisposition.Terminate(
                runCatching { AgentUiTerminalReason.valueOf(optString("terminal_reason")) }
                    .getOrDefault(AgentUiTerminalReason.STOPPED),
            )
        else ->
            SessionTurnDisposition.ContinueExecution(
                runCatching { AgentUiExecutionPhase.valueOf(optString("phase")) }
                    .getOrDefault(AgentUiExecutionPhase.RUNNING),
            )
    }
}

internal fun AgentUiStatusSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("code", code)
        put("category", category.name)
        put("blocked_reason", blockedReason.name)
        put("execution_phase", executionPhase.name)
        put("takeover_reason", takeoverReason.name)
        put("terminal_reason", terminalReason.name)
    }

internal fun JSONObject?.toAgentUiStatusSnapshot(
    fallbackCode: String = AgentUiStatus.IDLE,
): AgentUiStatusSnapshot {
    if (this == null || length() == 0) return AgentUiStatus.resolve(fallbackCode).toSnapshot()
    return AgentUiStatusSnapshot(
        code = optString("code", fallbackCode),
        category =
            runCatching { AgentUiStatusCategory.valueOf(optString("category")) }
                .getOrDefault(AgentUiStatus.resolve(fallbackCode).category),
        blockedReason =
            runCatching { AgentUiBlockedReason.valueOf(optString("blocked_reason")) }
                .getOrDefault(AgentUiStatus.resolve(fallbackCode).blockedReason),
        executionPhase =
            runCatching { AgentUiExecutionPhase.valueOf(optString("execution_phase")) }
                .getOrDefault(AgentUiStatus.resolve(fallbackCode).executionPhase),
        takeoverReason =
            runCatching { AgentUiTakeoverReason.valueOf(optString("takeover_reason")) }
                .getOrDefault(AgentUiStatus.resolve(fallbackCode).takeoverReason),
        terminalReason =
            runCatching { AgentUiTerminalReason.valueOf(optString("terminal_reason")) }
                .getOrDefault(AgentUiStatus.resolve(fallbackCode).terminalReason),
    )
}

internal fun RuntimeExternalWaitState.toJson(): JSONObject =
    JSONObject().apply {
        put("event", event)
        put("reason", reason)
        put("subgoal_id", subgoalId)
        put("signature", signature)
        put("entered_at_ms", enteredAtMs)
    }

internal fun JSONObject?.toRuntimeExternalWaitState(): RuntimeExternalWaitState? {
    if (this == null || length() == 0) return null
    return RuntimeExternalWaitState(
        event = optString("event"),
        reason = optString("reason"),
        subgoalId = optString("subgoal_id"),
        signature = optString("signature"),
        enteredAtMs = optLong("entered_at_ms"),
    )
}

internal fun RuntimeResumeDecisionSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("channel", channel.name)
        put("phase", phase.name)
        put("stage", stage)
        put("action_label", actionLabel)
        put("policy", policy.name)
        put("recovery_category", recoveryCategory)
    }

internal fun JSONObject?.toRuntimeResumeDecisionSnapshot(): RuntimeResumeDecisionSnapshot {
    if (this == null || length() == 0) return RuntimeResumeDecisionSnapshot()
    return RuntimeResumeDecisionSnapshot(
        channel = runCatching { RuntimeResumeDecisionChannel.valueOf(optString("channel")) }.getOrDefault(RuntimeResumeDecisionChannel.NONE),
        phase = runCatching { RuntimeResumeDecisionPhase.valueOf(optString("phase")) }.getOrDefault(RuntimeResumeDecisionPhase.NONE),
        stage = optString("stage"),
        actionLabel = optString("action_label"),
        policy = runCatching { RuntimeResumePolicy.valueOf(optString("policy")) }.getOrDefault(RuntimeResumePolicy.NONE),
        recoveryCategory = optString("recovery_category"),
    )
}

internal fun RuntimePlanningSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("plan_type", planType)
        put("current_plan_stage", currentPlanStage)
        put("current_subgoal_id", currentSubgoalId)
        put("next_objective", nextObjective)
        put("active_skill_titles", JSONArray(activeSkillTitles))
        put("last_planner_action", lastPlannerAction)
        put("last_planner_raw", lastPlannerRaw)
        put("last_resume_decision", lastResumeDecision.toJson())
    }

internal fun JSONObject?.toRuntimePlanningSnapshot(): RuntimePlanningSnapshot {
    if (this == null || length() == 0) return RuntimePlanningSnapshot()
    return RuntimePlanningSnapshot(
        planType = optString("plan_type"),
        currentPlanStage = optString("current_plan_stage"),
        currentSubgoalId = optString("current_subgoal_id"),
        nextObjective = optString("next_objective"),
        activeSkillTitles = optJSONArray("active_skill_titles").toStringList(),
        lastPlannerAction = optString("last_planner_action"),
        lastPlannerRaw = optString("last_planner_raw"),
        lastResumeDecision = optJSONObject("last_resume_decision").toRuntimeResumeDecisionSnapshot(),
    )
}

internal fun JSONObject?.toRuntimePlanningSnapshotOrNull(): RuntimePlanningSnapshot? =
    if (this == null || length() == 0) null else toRuntimePlanningSnapshot()

internal fun RuntimeRouteSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("active_profile_id", activeProfileId)
        put("active_target_package", activeTargetPackage)
        put("reason", reason)
        put("policy_tag", policyTag)
        put("model_choice_profile_id", modelChoiceProfileId)
        put("selected_profile_id", selectedProfileId)
        put("fallback_reason", fallbackReason)
        put("memory_hints", JSONArray(memoryHints))
        put("model_raw", modelRaw)
    }

internal fun JSONObject?.toRuntimeRouteSnapshot(): RuntimeRouteSnapshot {
    if (this == null || length() == 0) return RuntimeRouteSnapshot()
    return RuntimeRouteSnapshot(
        activeProfileId = optString("active_profile_id"),
        activeTargetPackage = optString("active_target_package"),
        reason = optString("reason"),
        policyTag = optString("policy_tag"),
        modelChoiceProfileId = optString("model_choice_profile_id"),
        selectedProfileId = optString("selected_profile_id"),
        fallbackReason = optString("fallback_reason"),
        memoryHints = optJSONArray("memory_hints").toStringList(),
        modelRaw = optString("model_raw"),
    )
}

internal fun RuntimeResultSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("last_action", lastAction)
        put("last_result", lastResult)
        put("intent_type", intentType)
        put("title", title)
        put("summary", summary)
        put("highlights", JSONArray(highlights))
        put("last_error", lastError)
        put("hint", hint)
    }

internal fun JSONObject?.toRuntimeResultSnapshot(): RuntimeResultSnapshot {
    if (this == null || length() == 0) return RuntimeResultSnapshot()
    return RuntimeResultSnapshot(
        lastAction = optString("last_action"),
        lastResult = optString("last_result"),
        intentType = optString("intent_type"),
        title = optString("title"),
        summary = optString("summary"),
        highlights = optJSONArray("highlights").toStringList(),
        lastError = optString("last_error"),
        hint = optString("hint"),
    )
}

internal fun RuntimeTakeoverSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("latest_takeover_type", latestTakeoverType)
        put("latest_takeover_reason", latestTakeoverReason)
        put("latest_takeover_resume_hint", latestTakeoverResumeHint)
        put("latest_takeover_correction", latestTakeoverCorrection)
    }

internal fun JSONObject?.toRuntimeTakeoverSnapshot(): RuntimeTakeoverSnapshot {
    if (this == null || length() == 0) return RuntimeTakeoverSnapshot()
    return RuntimeTakeoverSnapshot(
        latestTakeoverType = optString("latest_takeover_type"),
        latestTakeoverReason = optString("latest_takeover_reason"),
        latestTakeoverResumeHint = optString("latest_takeover_resume_hint"),
        latestTakeoverCorrection = optString("latest_takeover_correction"),
    )
}

internal fun RuntimeSafetyState.toJson(): JSONObject =
    JSONObject().apply {
        put("mode", mode.name)
        put("pending_confirmation", pendingConfirmation?.toJson() ?: JSONObject())
        put("granted_approval_key", grantedApprovalKey.orEmpty())
    }

internal fun JSONObject?.toRuntimeSafetyState(): RuntimeSafetyState {
    if (this == null || length() == 0) return RuntimeSafetyState()
    return RuntimeSafetyState(
        mode = runCatching { RuntimeSafetyMode.valueOf(optString("mode")) }.getOrDefault(RuntimeSafetyMode.IDLE),
        pendingConfirmation = optJSONObject("pending_confirmation").toPendingSafetyConfirmation(),
        grantedApprovalKey = optString("granted_approval_key").ifBlank { null },
    )
}

internal fun ResumeContext.toJson(): JSONObject =
    JSONObject().apply {
        put("active", active)
        put("source", source)
        put("resume_event", resumeEvent)
        put("resume_hint", resumeHint)
        put("resumed_subgoal_id", resumedSubgoalId)
        put("resume_attempt", resumeAttempt)
        put("user_correction", userCorrection)
    }

internal fun JSONObject?.toResumeContext(): ResumeContext {
    if (this == null || length() == 0) return ResumeContext()
    return ResumeContext(
        active = optBoolean("active"),
        source = optString("source"),
        resumeEvent = optString("resume_event"),
        resumeHint = optString("resume_hint"),
        resumedSubgoalId = optString("resumed_subgoal_id"),
        resumeAttempt = optInt("resume_attempt"),
        userCorrection = optString("user_correction"),
    )
}

internal fun AgentTurnRecord.toJson(): JSONObject =
    JSONObject().apply {
        put("observation_signature", observationSignature)
        put("page_state", pageState)
        put("action", action)
        put("result", result)
        put("decision_reason", decisionReason)
        put("recovery_category", recoveryCategory)
        put("recovery_summary", recoverySummary)
        put("suggested_recovery_action", suggestedRecoveryAction)
    }

internal fun JSONObject?.toAgentTurnRecord(): AgentTurnRecord {
    if (this == null || length() == 0) return AgentTurnRecord("", "", "", "")
    return AgentTurnRecord(
        observationSignature = optString("observation_signature"),
        pageState = optString("page_state"),
        action = optString("action"),
        result = optString("result"),
        decisionReason = optString("decision_reason"),
        recoveryCategory = optString("recovery_category"),
        recoverySummary = optString("recovery_summary"),
        suggestedRecoveryAction = optString("suggested_recovery_action"),
    )
}

internal fun PendingSafetyConfirmation.toJson(): JSONObject =
    JSONObject().apply {
        put("approval_key", approvalKey)
        put("title", title)
        put("summary", summary)
        put("action_label", actionLabel)
        put("plan_stage", planStage)
        put("subgoal_id", subgoalId)
        put("next_objective", nextObjective)
        put("target_package_name", targetPackageName)
        put("action_family", actionFamily)
        put("risk_level_label", riskLevelLabel)
        put("grant_scope_hint", grantScopeHint)
        put("handoff_reason", handoffReason)
        put("completion_summary", completionSummary)
        put("final_user_step", finalUserStep)
    }

internal fun JSONObject?.toPendingSafetyConfirmation(): PendingSafetyConfirmation? {
    if (this == null || length() == 0) return null
    val approvalKey = optString("approval_key")
    if (approvalKey.isBlank()) return null
    return PendingSafetyConfirmation(
        approvalKey = approvalKey,
        title = optString("title"),
        summary = optString("summary"),
        actionLabel = optString("action_label"),
        planStage = optString("plan_stage"),
        subgoalId = optString("subgoal_id"),
        nextObjective = optString("next_objective"),
        targetPackageName = optString("target_package_name"),
        actionFamily = optString("action_family"),
        riskLevelLabel = optString("risk_level_label"),
        grantScopeHint = optString("grant_scope_hint"),
        handoffReason = optString("handoff_reason"),
        completionSummary = optString("completion_summary"),
        finalUserStep = optString("final_user_step"),
    )
}

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private fun JSONArray?.toAgentTurnRecords(): List<AgentTurnRecord> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            add(optJSONObject(index).toAgentTurnRecord())
        }
    }
}
