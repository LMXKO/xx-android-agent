package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.memory.PersonalAssistantUserModelSnapshot
import com.lmx.xiaoxuanagent.memory.PersonalMemoryInsightSnapshot
import org.json.JSONArray
import org.json.JSONObject

internal fun AssistantProductShellSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("updated_at_ms", updatedAtMs)
        put("last_sync_reason", lastSyncReason)
        put("onboarding", onboarding.toJson())
        put("tips", JSONArray().apply { tips.forEach { put(it.toJson()) } })
        put("tip_ledger", JSONArray().apply { tipLedger.forEach { put(it.toJson()) } })
        put("swarm_strategy", swarmStrategy.toJson())
        put("companion_shell", companionShell.toJson())
        put("operator_shell", operatorShell.toJson())
        put("agenda_shell", agendaShell.toJson())
        put("daily_rhythm", dailyRhythm.toJson())
        put("routine_shell", routineShell.toJson())
        put("routine_policy", routinePolicy.toJson())
        put("digest_shell", digestShell.toJson())
        put("digest_policy", digestPolicy.toJson())
        put("quiet_hours", quietHours.toJson())
        put("interrupt_budget", interruptBudget.toJson())
        put("interrupt_policy", interruptPolicy.toJson())
        put("personal_focus", personalFocus.toJson())
        put("viewer_shell", viewerShell.toJson())
        put("governance_shell", governanceShell.toJson())
        put("user_model", userModel.toJson())
        put("memory_insight", memoryInsight.toJson())
        put("voice_interaction", voiceInteraction.toJson())
        put("autonomy_plan", autonomyPlan.toJson())
        put("diagnostics", diagnostics.toJson())
        put("analytics", analytics.toJson())
    }

internal fun JSONObject.toAssistantProductShellSnapshot(): AssistantProductShellSnapshot {
    val visibleTips = optJSONArray("tips").toAssistantTipList()
    val ledgerTips = optJSONArray("tip_ledger").toAssistantTipList().ifEmpty { visibleTips }
    return AssistantProductShellSnapshot(
        onboarding = optJSONObject("onboarding")?.toOnboardingState() ?: AssistantOnboardingState(),
        tips = visibleTips,
        tipLedger = ledgerTips,
        swarmStrategy = optJSONObject("swarm_strategy")?.toSwarmStrategy() ?: AssistantSwarmStrategy(),
        companionShell = optJSONObject("companion_shell")?.toCompanionShell() ?: AssistantCompanionShellSnapshot(),
        operatorShell = optJSONObject("operator_shell")?.toOperatorShell() ?: AssistantOperatorShellSnapshot(),
        agendaShell = optJSONObject("agenda_shell")?.toAgendaShell() ?: AssistantAgendaShellSnapshot(),
        dailyRhythm = optJSONObject("daily_rhythm")?.toDailyRhythm() ?: AssistantDailyRhythmSnapshot(),
        routineShell = optJSONObject("routine_shell")?.toRoutineShell() ?: AssistantRoutineShellSnapshot(),
        routinePolicy = optJSONObject("routine_policy")?.toRoutinePolicy() ?: AssistantRoutinePolicySnapshot(),
        digestShell = optJSONObject("digest_shell")?.toDigestShell() ?: AssistantDigestShellSnapshot(),
        digestPolicy = optJSONObject("digest_policy")?.toDigestPolicy() ?: AssistantDigestPolicySnapshot(),
        quietHours = optJSONObject("quiet_hours")?.toQuietHours() ?: AssistantQuietHoursSnapshot(),
        interruptBudget = optJSONObject("interrupt_budget")?.toInterruptBudget() ?: AssistantInterruptBudgetSnapshot(),
        interruptPolicy = optJSONObject("interrupt_policy")?.toInterruptPolicy() ?: AssistantInterruptPolicySnapshot(),
        personalFocus = optJSONObject("personal_focus")?.toPersonalFocus() ?: AssistantPersonalFocusSnapshot(),
        viewerShell = optJSONObject("viewer_shell")?.toViewerShell() ?: AssistantViewerShellSnapshot(),
        governanceShell = optJSONObject("governance_shell")?.toGovernanceShell() ?: AssistantGovernanceShellSnapshot(),
        userModel = optJSONObject("user_model")?.toUserModel() ?: PersonalAssistantUserModelSnapshot(),
        memoryInsight = optJSONObject("memory_insight")?.toMemoryInsight() ?: PersonalMemoryInsightSnapshot(),
        voiceInteraction = optJSONObject("voice_interaction")?.toVoiceInteraction() ?: AssistantVoiceInteractionSnapshot(),
        autonomyPlan = optJSONObject("autonomy_plan")?.toAutonomyPlan() ?: AssistantAutonomyPlanSnapshot(),
        diagnostics = optJSONObject("diagnostics")?.toDiagnostics() ?: AssistantProductDiagnosticsSnapshot(),
        analytics = optJSONObject("analytics")?.toAnalyticsSummary() ?: AssistantAnalyticsSummary(),
        lastSyncReason = optString("last_sync_reason"),
        updatedAtMs = optLong("updated_at_ms"),
    )
}

private fun AssistantOnboardingState.toJson(): JSONObject =
    JSONObject().apply {
        put("status", status)
        put("summary", summary)
        put("completed_steps", JSONArray(completedSteps))
        put("pending_steps", JSONArray(pendingSteps))
        put("updated_at_ms", updatedAtMs)
        put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
    }

private fun AssistantOnboardingStepState.toJson(): JSONObject =
    JSONObject().apply {
        put("id", id)
        put("title", title)
        put("summary", summary)
        put("action_label", actionLabel)
        put("category", category)
        put("risk_level", riskLevel)
        put("priority", priority)
        put("blocking", blocking)
        put("status", status)
        put("requirement_met", requirementMet)
        put("manual_state", manualState)
        put("note", note)
        put("updated_at_ms", updatedAtMs)
        put("completed_at_ms", completedAtMs)
    }

private fun AssistantTipCard.toJson(): JSONObject =
    JSONObject().apply {
        put("id", id)
        put("dedupe_key", dedupeKey)
        put("title", title)
        put("summary", summary)
        put("action_label", actionLabel)
        put("recommended_page", recommendedPage)
        put("priority", priority)
        put("source", source)
        put("audience", audience)
        put("status", status)
        put("note", note)
        put("next_eligible_at_ms", nextEligibleAtMs)
        put("last_presented_at_ms", lastPresentedAtMs)
        put("last_shown_at_ms", lastShownAtMs)
        put("shown_count", shownCount)
        put("cooldown_sessions", cooldownSessions)
        put("eligibility_reason", eligibilityReason)
        put("dismissed_at_ms", dismissedAtMs)
        put("completed_at_ms", completedAtMs)
        put("created_at_ms", createdAtMs)
        put("updated_at_ms", updatedAtMs)
    }

private fun AssistantSwarmStrategy.toJson(): JSONObject =
    JSONObject().apply {
        put("mode", mode)
        put("summary", summary)
        put("max_concurrent_workers", maxConcurrentWorkers)
        put("mailbox_batch_size", mailboxBatchSize)
        put("mailbox_priority_mode", mailboxPriorityMode)
        put("fairness_mode", fairnessMode)
        put("lease_policy_mode", leasePolicyMode)
        put("coordination_mode", coordinationMode)
        put("lease_pressure_summary", leasePressureSummary)
        put("dispatch_summary", dispatchSummary)
        put("mission_summary", missionSummary)
        put("escalation_summary", escalationSummary)
        put("join_summary", joinSummary)
        put("collaborator_summary", collaboratorSummary)
        put("dispatch_candidates", JSONArray(dispatchCandidates))
        put("recommended_worker_ids", JSONArray(recommendedWorkerIds))
        put("focus_session_ids", JSONArray(focusSessionIds))
        put("blocked_coordinator_ids", JSONArray(blockedCoordinatorIds))
        put("lane_lines", JSONArray(laneLines))
        put("handoff_actions", JSONArray(handoffActions))
        put("recommended_commands", JSONArray(recommendedCommands))
        put("pending_permission_requests", pendingPermissionRequests)
        put("pending_mailbox_messages", pendingMailboxMessages)
        put("updated_at_ms", updatedAtMs)
    }

private fun AssistantCompanionShellSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("mode", mode)
        put("summary", summary)
        put("presence_summary", presenceSummary)
        put("voice_summary", voiceSummary)
        put("swarm_summary", swarmSummary)
        put("memory_summary", memorySummary)
        put("provider_summary", providerSummary)
        put("next_action_summary", nextActionSummary)
        put("lane_lines", JSONArray(laneLines))
        put("recommended_commands", JSONArray(recommendedCommands))
        put("updated_at_ms", updatedAtMs)
    }

private fun AssistantOperatorShellSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("mode", mode)
        put("summary", summary)
        put("provider_health_summary", providerHealthSummary)
        put("provider_coverage_summary", providerCoverageSummary)
        put("provider_diagnostics_summary", providerDiagnosticsSummary)
        put("artifact_health_summary", artifactHealthSummary)
        put("replay_health_summary", replayHealthSummary)
        put("worker_health_summary", workerHealthSummary)
        put("worker_mission_summary", workerMissionSummary)
        put("worker_lease_summary", workerLeaseSummary)
        put("graph_health_summary", graphHealthSummary)
        put("swarm_policy_summary", swarmPolicySummary)
        put("urgent_lines", JSONArray(urgentLines))
        put("recommended_commands", JSONArray(recommendedCommands))
        put("updated_at_ms", updatedAtMs)
    }

private fun AssistantAgendaShellSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("mode", mode)
        put("summary", summary)
        put("due_now_count", dueNowCount)
        put("upcoming_count", upcomingCount)
        put("signal_count", signalCount)
        put("focus_title", focusTitle)
        put("agenda_lines", JSONArray(agendaLines))
        put("recommended_commands", JSONArray(recommendedCommands))
        put("updated_at_ms", updatedAtMs)
    }

private fun AssistantDailyRhythmSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("mode", mode)
        put("summary", summary)
        put("next_window_summary", nextWindowSummary)
        put("attention_summary", attentionSummary)
        put("signal_pressure_summary", signalPressureSummary)
        put("proactive_pressure_summary", proactivePressureSummary)
        put("follow_up_summary", followUpSummary)
        put("rhythm_lines", JSONArray(rhythmLines))
        put("updated_at_ms", updatedAtMs)
    }

private fun AssistantRoutineShellSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("mode", mode)
        put("summary", summary)
        put("next_window_summary", nextWindowSummary)
        put("focus_theme", focusTheme)
        put("checklist_lines", JSONArray(checklistLines))
        put("follow_up_lines", JSONArray(followUpLines))
        put("recommended_commands", JSONArray(recommendedCommands))
        put("updated_at_ms", updatedAtMs)
    }

private fun AssistantRoutinePolicySnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("focus_theme", focusTheme)
        put("review_window", reviewWindow)
        put("follow_up_window", followUpWindow)
        put("checklist_templates", JSONArray(checklistTemplates))
        put("preferred_surfaces", JSONArray(preferredSurfaces))
        put("summary", summary)
        put("updated_at_ms", updatedAtMs)
    }

private fun AssistantDigestShellSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("mode", mode)
        put("title", title)
        put("summary", summary)
        put("highlight_lines", JSONArray(highlightLines))
        put("action_label", actionLabel)
        put("action_command", actionCommand)
        put("updated_at_ms", updatedAtMs)
    }

private fun AssistantDigestPolicySnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("enabled", enabled)
        put("cadence", cadence)
        put("max_highlights", maxHighlights)
        put("delivery_surfaces", JSONArray(deliverySurfaces))
        put("summary", summary)
        put("updated_at_ms", updatedAtMs)
    }

private fun AssistantQuietHoursSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("enabled", enabled)
        put("start_local_time", startLocalTime)
        put("end_local_time", endLocalTime)
        put("active_now", activeNow)
        put("summary", summary)
        put("updated_at_ms", updatedAtMs)
    }

private fun AssistantInterruptBudgetSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("mode", mode)
        put("summary", summary)
        put("total_budget", totalBudget)
        put("consumed_budget", consumedBudget)
        put("remaining_budget", remainingBudget)
        put("hard_block", hardBlock)
        put("cooldown_summary", cooldownSummary)
        put("allowed_sources", JSONArray(allowedSources))
        put("blocked_sources", JSONArray(blockedSources))
        put("recommended_commands", JSONArray(recommendedCommands))
        put("updated_at_ms", updatedAtMs)
    }

private fun AssistantInterruptPolicySnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("base_budget", baseBudget)
        put("focus_budget", focusBudget)
        put("hard_block_in_quiet_hours", hardBlockInQuietHours)
        put("preferred_sources", JSONArray(preferredSources))
        put("blocked_sources", JSONArray(blockedSources))
        put("summary", summary)
        put("updated_at_ms", updatedAtMs)
    }

private fun AssistantPersonalFocusSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("mode", mode)
        put("summary", summary)
        put("focus_session_id", focusSessionId)
        put("focus_task", focusTask)
        put("focus_reason", focusReason)
        put("mission_summary", missionSummary)
        put("coordination_summary", coordinationSummary)
        put("user_model_summary", userModelSummary)
        put("next_best_actions", JSONArray(nextBestActions))
        put("updated_at_ms", updatedAtMs)
    }

private fun AssistantViewerShellSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("mode", mode)
        put("focus_session_id", focusSessionId)
        put("focus_task", focusTask)
        put("detail_summary", detailSummary)
        put("timeline_summary", timelineSummary)
        put("graph_summary", graphSummary)
        put("approval_summary", approvalSummary)
        put("result_summary", resultSummary)
        put("action_lane_summary", actionLaneSummary)
        put("detail_lines", JSONArray(detailLines))
        put("timeline_lines", JSONArray(timelineLines))
        put("graph_lines", JSONArray(graphLines))
        put("approval_lines", JSONArray(approvalLines))
        put("trace_lines", JSONArray(traceLines))
        put("action_lines", JSONArray(actionLines))
        put("recommended_commands", JSONArray(recommendedCommands))
        put("updated_at_ms", updatedAtMs)
    }

private fun AssistantGovernanceShellSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("mode", mode)
        put("summary", summary)
        put("consent_summary", consentSummary)
        put("privacy_summary", privacySummary)
        put("history_summary", historySummary)
        put("approval_summary", approvalSummary)
        put("retention_summary", retentionSummary)
        put("provider_policy_summary", providerPolicySummary)
        put("autonomy_summary", autonomySummary)
        put("permission_product_summary", permissionProductSummary)
        put("permission_product_active_tab", permissionProductActiveTab)
        put("permission_product_tabs", JSONArray().apply { permissionProductTabs.forEach { put(it.toJson()) } })
        put("permission_product_cards", JSONArray().apply { permissionProductCards.forEach { put(it.toJson()) } })
        put("explanation_lines", JSONArray(explanationLines))
        put("control_lines", JSONArray(controlLines))
        put("action_lines", JSONArray(actionLines))
        put("recommended_commands", JSONArray(recommendedCommands))
        put("updated_at_ms", updatedAtMs)
    }

private fun AssistantPermissionProductTabSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("id", id)
        put("title", title)
        put("count", count)
        put("summary", summary)
        put("active", active)
    }

private fun AssistantPermissionProductCardSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("card_id", cardId)
        put("card_type", cardType)
        put("behavior", behavior)
        put("title", title)
        put("subtitle", subtitle)
        put("scope", scope)
        put("source_tag", sourceTag)
        put("surface_hint", surfaceHint)
        put("explanation", explanation)
        put("primary_command", primaryCommand)
    }

private fun PersonalAssistantUserModelSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("summary", summary)
        put("identity_summary", identitySummary)
        put("preference_summary", preferenceSummary)
        put("relationship_summary", relationshipSummary)
        put("location_summary", locationSummary)
        put("app_summary", appSummary)
        put("routine_summary", routineSummary)
        put("safety_summary", safetySummary)
        put("workspace_summary", workspaceSummary)
        put("top_profile_ids", JSONArray(topProfileIds))
        put("top_contact_names", JSONArray(topContactNames))
        put("top_location_names", JSONArray(topLocationNames))
        put("preferred_themes", JSONArray(preferredThemes))
        put("recommended_commands", JSONArray(recommendedCommands))
        put("lines", JSONArray(lines))
    }

private fun PersonalMemoryInsightSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("summary", summary)
        put("away_summary", awaySummary)
        put("thinkback_summary", thinkbackSummary)
        put("consolidation_summary", consolidationSummary)
        put("dream_summary", dreamSummary)
        put("suggestion_summary", suggestionSummary)
        put("away_lines", JSONArray(awayLines))
        put("thinkback_lines", JSONArray(thinkbackLines))
        put("consolidation_lines", JSONArray(consolidationLines))
        put("dream_lines", JSONArray(dreamLines))
        put("recommended_commands", JSONArray(recommendedCommands))
        put("updated_at_ms", updatedAtMs)
    }

private fun AssistantVoiceInteractionSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("state", state)
        put("summary", summary)
        put("availability_summary", availabilitySummary)
        put("interaction_mode", interactionMode)
        put("partial_transcript", partialTranscript)
        put("final_transcript", finalTranscript)
        put("pending_confirmation", pendingConfirmation)
        put("spoken_summary", spokenSummary)
        put("last_heard_command_type", lastHeardCommandType)
        put("auto_execute_eligible", autoExecuteEligible)
        put("last_spoken_at_ms", lastSpokenAtMs)
        put("blocked_reason", blockedReason)
        put("last_error", lastError)
        put("active_session_id", activeSessionId)
        put("transcript_history", JSONArray(transcriptHistory))
        put("recommended_commands", JSONArray(recommendedCommands))
        put("updated_at_ms", updatedAtMs)
    }

private fun AssistantAutonomyPlanSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("mode", mode)
        put("summary", summary)
        put("trigger_policy_mode", triggerPolicyMode)
        put("restore_mode", restoreMode)
        put("proactive_mode", proactiveMode)
        put("entry_surface_mode", entrySurfaceMode)
        put("event_priority_summary", eventPrioritySummary)
        put("user_model_summary", userModelSummary)
        put("workbench_summary", workbenchSummary)
        put("engine_phase_order", JSONArray(enginePhaseOrder))
        put("recommended_commands", JSONArray(recommendedCommands))
        put("lines", JSONArray(lines))
        put("updated_at_ms", updatedAtMs)
    }

private fun AssistantProductDiagnosticsSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("status", status)
        put("summary", summary)
        put("provider_summary", providerSummary)
        put("memory_summary", memorySummary)
        put("history_summary", historySummary)
        put("strategy_summary", strategySummary)
        put("app_preflight_status", appPreflightStatus)
        put("app_preflight_summary", appPreflightSummary)
        put("app_preflight_lines", JSONArray(appPreflightLines))
        put("app_preflight_recommended_commands", JSONArray(appPreflightRecommendedCommands))
        put("super_assistant_status", superAssistantStatus)
        put("super_assistant_summary", superAssistantSummary)
        put("super_assistant_lines", JSONArray(superAssistantLines))
        put("stale_runtime_detected", staleRuntimeDetected)
        put("stale_runtime_summary", staleRuntimeSummary)
        put("lines", JSONArray(lines))
    }

private fun AssistantAnalyticsSummary.toJson(): JSONObject =
    JSONObject().apply {
        put("total_events", totalEvents)
        put("top_counters", JSONArray(topCounters))
        put("enabled_sinks", JSONArray(enabledSinks))
        put("recent_events", JSONArray(recentEvents))
    }

private fun JSONObject.toOnboardingState(): AssistantOnboardingState =
    AssistantOnboardingState(
        status = optString("status"),
        summary = optString("summary"),
        completedSteps = optJSONArray("completed_steps").toAssistantStringList(),
        pendingSteps = optJSONArray("pending_steps").toAssistantStringList(),
        steps = optJSONArray("steps").toAssistantStepList(),
        updatedAtMs = optLong("updated_at_ms"),
    )

private fun JSONObject.toSwarmStrategy(): AssistantSwarmStrategy =
    AssistantSwarmStrategy(
        mode = optString("mode"),
        summary = optString("summary"),
        maxConcurrentWorkers = optInt("max_concurrent_workers", 3),
        mailboxBatchSize = optInt("mailbox_batch_size", 8),
        mailboxPriorityMode = optString("mailbox_priority_mode", "balanced"),
        fairnessMode = optString("fairness_mode", "coordinator_fair"),
        leasePolicyMode = optString("lease_policy_mode", "runtime_lease"),
        coordinationMode = optString("coordination_mode", "coordinator_claim_release"),
        leasePressureSummary = optString("lease_pressure_summary"),
        dispatchSummary = optString("dispatch_summary"),
        missionSummary = optString("mission_summary"),
        escalationSummary = optString("escalation_summary"),
        joinSummary = optString("join_summary"),
        collaboratorSummary = optString("collaborator_summary"),
        dispatchCandidates = optJSONArray("dispatch_candidates").toAssistantStringList(),
        recommendedWorkerIds = optJSONArray("recommended_worker_ids").toAssistantStringList(),
        focusSessionIds = optJSONArray("focus_session_ids").toAssistantStringList(),
        blockedCoordinatorIds = optJSONArray("blocked_coordinator_ids").toAssistantStringList(),
        laneLines = optJSONArray("lane_lines").toAssistantStringList(),
        handoffActions = optJSONArray("handoff_actions").toAssistantStringList(),
        recommendedCommands = optJSONArray("recommended_commands").toAssistantStringList(),
        pendingPermissionRequests = optInt("pending_permission_requests"),
        pendingMailboxMessages = optInt("pending_mailbox_messages"),
        updatedAtMs = optLong("updated_at_ms"),
    )

private fun JSONObject.toCompanionShell(): AssistantCompanionShellSnapshot =
    AssistantCompanionShellSnapshot(
        mode = optString("mode"),
        summary = optString("summary"),
        presenceSummary = optString("presence_summary"),
        voiceSummary = optString("voice_summary"),
        swarmSummary = optString("swarm_summary"),
        memorySummary = optString("memory_summary"),
        providerSummary = optString("provider_summary"),
        nextActionSummary = optString("next_action_summary"),
        laneLines = optJSONArray("lane_lines").toAssistantStringList(),
        recommendedCommands = optJSONArray("recommended_commands").toAssistantStringList(),
        updatedAtMs = optLong("updated_at_ms"),
    )

private fun JSONObject.toOperatorShell(): AssistantOperatorShellSnapshot =
    AssistantOperatorShellSnapshot(
        mode = optString("mode"),
        summary = optString("summary"),
        providerHealthSummary = optString("provider_health_summary"),
        providerCoverageSummary = optString("provider_coverage_summary"),
        providerDiagnosticsSummary = optString("provider_diagnostics_summary"),
        artifactHealthSummary = optString("artifact_health_summary"),
        replayHealthSummary = optString("replay_health_summary"),
        workerHealthSummary = optString("worker_health_summary"),
        workerMissionSummary = optString("worker_mission_summary"),
        workerLeaseSummary = optString("worker_lease_summary"),
        graphHealthSummary = optString("graph_health_summary"),
        swarmPolicySummary = optString("swarm_policy_summary"),
        urgentLines = optJSONArray("urgent_lines").toAssistantStringList(),
        recommendedCommands = optJSONArray("recommended_commands").toAssistantStringList(),
        updatedAtMs = optLong("updated_at_ms"),
    )

private fun JSONObject.toAgendaShell(): AssistantAgendaShellSnapshot =
    AssistantAgendaShellSnapshot(
        mode = optString("mode"),
        summary = optString("summary"),
        dueNowCount = optInt("due_now_count"),
        upcomingCount = optInt("upcoming_count"),
        signalCount = optInt("signal_count"),
        focusTitle = optString("focus_title"),
        agendaLines = optJSONArray("agenda_lines").toAssistantStringList(),
        recommendedCommands = optJSONArray("recommended_commands").toAssistantStringList(),
        updatedAtMs = optLong("updated_at_ms"),
    )

private fun JSONObject.toDailyRhythm(): AssistantDailyRhythmSnapshot =
    AssistantDailyRhythmSnapshot(
        mode = optString("mode"),
        summary = optString("summary"),
        nextWindowSummary = optString("next_window_summary"),
        attentionSummary = optString("attention_summary"),
        signalPressureSummary = optString("signal_pressure_summary"),
        proactivePressureSummary = optString("proactive_pressure_summary"),
        followUpSummary = optString("follow_up_summary"),
        rhythmLines = optJSONArray("rhythm_lines").toAssistantStringList(),
        updatedAtMs = optLong("updated_at_ms"),
    )

private fun JSONObject.toRoutineShell(): AssistantRoutineShellSnapshot =
    AssistantRoutineShellSnapshot(
        mode = optString("mode"),
        summary = optString("summary"),
        nextWindowSummary = optString("next_window_summary"),
        focusTheme = optString("focus_theme"),
        checklistLines = optJSONArray("checklist_lines").toAssistantStringList(),
        followUpLines = optJSONArray("follow_up_lines").toAssistantStringList(),
        recommendedCommands = optJSONArray("recommended_commands").toAssistantStringList(),
        updatedAtMs = optLong("updated_at_ms"),
    )

private fun JSONObject.toRoutinePolicy(): AssistantRoutinePolicySnapshot =
    AssistantRoutinePolicySnapshot(
        focusTheme = optString("focus_theme", "默认主焦点"),
        reviewWindow = optString("review_window", "09:00-11:30"),
        followUpWindow = optString("follow_up_window", "14:00-18:00"),
        checklistTemplates = optJSONArray("checklist_templates").toAssistantStringList(),
        preferredSurfaces = optJSONArray("preferred_surfaces").toAssistantStringList(),
        summary = optString("summary"),
        updatedAtMs = optLong("updated_at_ms"),
    )

private fun JSONObject.toDigestShell(): AssistantDigestShellSnapshot =
    AssistantDigestShellSnapshot(
        mode = optString("mode"),
        title = optString("title"),
        summary = optString("summary"),
        highlightLines = optJSONArray("highlight_lines").toAssistantStringList(),
        actionLabel = optString("action_label"),
        actionCommand = optString("action_command"),
        updatedAtMs = optLong("updated_at_ms"),
    )

private fun JSONObject.toDigestPolicy(): AssistantDigestPolicySnapshot =
    AssistantDigestPolicySnapshot(
        enabled = if (has("enabled")) optBoolean("enabled") else true,
        cadence = optString("cadence", "adaptive"),
        maxHighlights = optInt("max_highlights", 4),
        deliverySurfaces = optJSONArray("delivery_surfaces").toAssistantStringList(),
        summary = optString("summary"),
        updatedAtMs = optLong("updated_at_ms"),
    )

private fun JSONObject.toQuietHours(): AssistantQuietHoursSnapshot =
    AssistantQuietHoursSnapshot(
        enabled = if (has("enabled")) optBoolean("enabled") else false,
        startLocalTime = optString("start_local_time", "22:00"),
        endLocalTime = optString("end_local_time", "08:00"),
        activeNow = if (has("active_now")) optBoolean("active_now") else false,
        summary = optString("summary"),
        updatedAtMs = optLong("updated_at_ms"),
    )

private fun JSONObject.toInterruptBudget(): AssistantInterruptBudgetSnapshot =
    AssistantInterruptBudgetSnapshot(
        mode = optString("mode"),
        summary = optString("summary"),
        totalBudget = optInt("total_budget", 4),
        consumedBudget = optInt("consumed_budget"),
        remainingBudget = optInt("remaining_budget", 4),
        hardBlock = optBoolean("hard_block"),
        cooldownSummary = optString("cooldown_summary"),
        allowedSources = optJSONArray("allowed_sources").toAssistantStringList(),
        blockedSources = optJSONArray("blocked_sources").toAssistantStringList(),
        recommendedCommands = optJSONArray("recommended_commands").toAssistantStringList(),
        updatedAtMs = optLong("updated_at_ms"),
    )

private fun JSONObject.toInterruptPolicy(): AssistantInterruptPolicySnapshot =
    AssistantInterruptPolicySnapshot(
        baseBudget = optInt("base_budget", 4),
        focusBudget = optInt("focus_budget", 2),
        hardBlockInQuietHours = if (has("hard_block_in_quiet_hours")) optBoolean("hard_block_in_quiet_hours") else true,
        preferredSources = optJSONArray("preferred_sources").toAssistantStringList(),
        blockedSources = optJSONArray("blocked_sources").toAssistantStringList(),
        summary = optString("summary"),
        updatedAtMs = optLong("updated_at_ms"),
    )

private fun JSONObject.toPersonalFocus(): AssistantPersonalFocusSnapshot =
    AssistantPersonalFocusSnapshot(
        mode = optString("mode"),
        summary = optString("summary"),
        focusSessionId = optString("focus_session_id"),
        focusTask = optString("focus_task"),
        focusReason = optString("focus_reason"),
        missionSummary = optString("mission_summary"),
        coordinationSummary = optString("coordination_summary"),
        userModelSummary = optString("user_model_summary"),
        nextBestActions = optJSONArray("next_best_actions").toAssistantStringList(),
        updatedAtMs = optLong("updated_at_ms"),
    )

private fun JSONObject.toViewerShell(): AssistantViewerShellSnapshot =
    AssistantViewerShellSnapshot(
        mode = optString("mode"),
        focusSessionId = optString("focus_session_id"),
        focusTask = optString("focus_task"),
        detailSummary = optString("detail_summary"),
        timelineSummary = optString("timeline_summary"),
        graphSummary = optString("graph_summary"),
        approvalSummary = optString("approval_summary"),
        resultSummary = optString("result_summary"),
        actionLaneSummary = optString("action_lane_summary"),
        detailLines = optJSONArray("detail_lines").toAssistantStringList(),
        timelineLines = optJSONArray("timeline_lines").toAssistantStringList(),
        graphLines = optJSONArray("graph_lines").toAssistantStringList(),
        approvalLines = optJSONArray("approval_lines").toAssistantStringList(),
        traceLines = optJSONArray("trace_lines").toAssistantStringList(),
        actionLines = optJSONArray("action_lines").toAssistantStringList(),
        recommendedCommands = optJSONArray("recommended_commands").toAssistantStringList(),
        updatedAtMs = optLong("updated_at_ms"),
    )

private fun JSONObject.toGovernanceShell(): AssistantGovernanceShellSnapshot =
    AssistantGovernanceShellSnapshot(
        mode = optString("mode"),
        summary = optString("summary"),
        consentSummary = optString("consent_summary"),
        privacySummary = optString("privacy_summary"),
        historySummary = optString("history_summary"),
        approvalSummary = optString("approval_summary"),
        retentionSummary = optString("retention_summary"),
        providerPolicySummary = optString("provider_policy_summary"),
        autonomySummary = optString("autonomy_summary"),
        permissionProductSummary = optString("permission_product_summary"),
        permissionProductActiveTab = optString("permission_product_active_tab"),
        permissionProductTabs =
            optJSONArray("permission_product_tabs")
                ?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            array.optJSONObject(index)?.let { add(it.toPermissionProductTab()) }
                        }
                    }
                }.orEmpty(),
        permissionProductCards =
            optJSONArray("permission_product_cards")
                ?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            array.optJSONObject(index)?.let { add(it.toPermissionProductCard()) }
                        }
                    }
                }.orEmpty(),
        explanationLines = optJSONArray("explanation_lines").toAssistantStringList(),
        controlLines = optJSONArray("control_lines").toAssistantStringList(),
        actionLines = optJSONArray("action_lines").toAssistantStringList(),
        recommendedCommands = optJSONArray("recommended_commands").toAssistantStringList(),
        updatedAtMs = optLong("updated_at_ms"),
    )

private fun JSONObject.toPermissionProductTab(): AssistantPermissionProductTabSnapshot =
    AssistantPermissionProductTabSnapshot(
        id = optString("id"),
        title = optString("title"),
        count = optInt("count"),
        summary = optString("summary"),
        active = optBoolean("active"),
    )

private fun JSONObject.toPermissionProductCard(): AssistantPermissionProductCardSnapshot =
    AssistantPermissionProductCardSnapshot(
        cardId = optString("card_id"),
        cardType = optString("card_type"),
        behavior = optString("behavior"),
        title = optString("title"),
        subtitle = optString("subtitle"),
        scope = optString("scope"),
        sourceTag = optString("source_tag"),
        surfaceHint = optString("surface_hint"),
        explanation = optString("explanation"),
        primaryCommand = optString("primary_command"),
    )

private fun JSONObject.toUserModel(): PersonalAssistantUserModelSnapshot =
    PersonalAssistantUserModelSnapshot(
        summary = optString("summary"),
        identitySummary = optString("identity_summary"),
        preferenceSummary = optString("preference_summary"),
        relationshipSummary = optString("relationship_summary"),
        locationSummary = optString("location_summary"),
        appSummary = optString("app_summary"),
        routineSummary = optString("routine_summary"),
        safetySummary = optString("safety_summary"),
        workspaceSummary = optString("workspace_summary"),
        topProfileIds = optJSONArray("top_profile_ids").toAssistantStringList(),
        topContactNames = optJSONArray("top_contact_names").toAssistantStringList(),
        topLocationNames = optJSONArray("top_location_names").toAssistantStringList(),
        preferredThemes = optJSONArray("preferred_themes").toAssistantStringList(),
        recommendedCommands = optJSONArray("recommended_commands").toAssistantStringList(),
        lines = optJSONArray("lines").toAssistantStringList(),
    )

private fun JSONObject.toMemoryInsight(): PersonalMemoryInsightSnapshot =
    PersonalMemoryInsightSnapshot(
        summary = optString("summary"),
        awaySummary = optString("away_summary"),
        thinkbackSummary = optString("thinkback_summary"),
        consolidationSummary = optString("consolidation_summary"),
        dreamSummary = optString("dream_summary"),
        suggestionSummary = optString("suggestion_summary"),
        awayLines = optJSONArray("away_lines").toAssistantStringList(),
        thinkbackLines = optJSONArray("thinkback_lines").toAssistantStringList(),
        consolidationLines = optJSONArray("consolidation_lines").toAssistantStringList(),
        dreamLines = optJSONArray("dream_lines").toAssistantStringList(),
        recommendedCommands = optJSONArray("recommended_commands").toAssistantStringList(),
        updatedAtMs = optLong("updated_at_ms"),
    )

private fun JSONObject.toVoiceInteraction(): AssistantVoiceInteractionSnapshot =
    AssistantVoiceInteractionSnapshot(
        state = optString("state", "idle"),
        summary = optString("summary"),
        availabilitySummary = optString("availability_summary"),
        interactionMode = optString("interaction_mode", "idle"),
        partialTranscript = optString("partial_transcript"),
        finalTranscript = optString("final_transcript"),
        pendingConfirmation = optString("pending_confirmation"),
        spokenSummary = optString("spoken_summary"),
        lastHeardCommandType = optString("last_heard_command_type"),
        autoExecuteEligible = optBoolean("auto_execute_eligible", false),
        lastSpokenAtMs = optLong("last_spoken_at_ms"),
        blockedReason = optString("blocked_reason"),
        lastError = optString("last_error"),
        activeSessionId = optString("active_session_id"),
        transcriptHistory = optJSONArray("transcript_history").toAssistantStringList(),
        recommendedCommands = optJSONArray("recommended_commands").toAssistantStringList(),
        updatedAtMs = optLong("updated_at_ms"),
    )

private fun JSONObject.toAutonomyPlan(): AssistantAutonomyPlanSnapshot =
    AssistantAutonomyPlanSnapshot(
        mode = optString("mode"),
        summary = optString("summary"),
        triggerPolicyMode = optString("trigger_policy_mode", "balanced"),
        restoreMode = optString("restore_mode", "balanced"),
        proactiveMode = optString("proactive_mode", "balanced"),
        entrySurfaceMode = optString("entry_surface_mode", "balanced"),
        eventPrioritySummary = optString("event_priority_summary"),
        userModelSummary = optString("user_model_summary"),
        workbenchSummary = optString("workbench_summary"),
        enginePhaseOrder = optJSONArray("engine_phase_order").toAssistantStringList(),
        recommendedCommands = optJSONArray("recommended_commands").toAssistantStringList(),
        lines = optJSONArray("lines").toAssistantStringList(),
        updatedAtMs = optLong("updated_at_ms"),
    )

private fun JSONObject.toDiagnostics(): AssistantProductDiagnosticsSnapshot =
    AssistantProductDiagnosticsSnapshot(
        status = optString("status"),
        summary = optString("summary"),
        providerSummary = optString("provider_summary"),
        memorySummary = optString("memory_summary"),
        historySummary = optString("history_summary"),
        strategySummary = optString("strategy_summary"),
        appPreflightStatus = optString("app_preflight_status"),
        appPreflightSummary = optString("app_preflight_summary"),
        appPreflightLines = optJSONArray("app_preflight_lines").toAssistantStringList(),
        appPreflightRecommendedCommands = optJSONArray("app_preflight_recommended_commands").toAssistantStringList(),
        superAssistantStatus = optString("super_assistant_status"),
        superAssistantSummary = optString("super_assistant_summary"),
        superAssistantLines = optJSONArray("super_assistant_lines").toAssistantStringList(),
        staleRuntimeDetected = optBoolean("stale_runtime_detected"),
        staleRuntimeSummary = optString("stale_runtime_summary"),
        lines = optJSONArray("lines").toAssistantStringList(),
    )

private fun JSONObject.toAnalyticsSummary(): AssistantAnalyticsSummary =
    AssistantAnalyticsSummary(
        totalEvents = optInt("total_events"),
        topCounters = optJSONArray("top_counters").toAssistantStringList(),
        enabledSinks = optJSONArray("enabled_sinks").toAssistantStringList(),
        recentEvents = optJSONArray("recent_events").toAssistantStringList(),
    )

internal fun JSONArray?.toAssistantStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private fun JSONArray?.toAssistantStepList(): List<AssistantOnboardingStepState> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            val json = optJSONObject(index) ?: continue
            add(
                AssistantOnboardingStepState(
                    id = json.optString("id"),
                    title = json.optString("title"),
                    summary = json.optString("summary"),
                    actionLabel = json.optString("action_label"),
                    category = json.optString("category", "core"),
                    riskLevel = json.optString("risk_level", "medium"),
                    priority = json.optInt("priority"),
                    blocking = json.optBoolean("blocking"),
                    status = json.optString("status", "pending"),
                    requirementMet = json.optBoolean("requirement_met"),
                    manualState = json.optString("manual_state"),
                    note = json.optString("note"),
                    updatedAtMs = json.optLong("updated_at_ms"),
                    completedAtMs = json.optLong("completed_at_ms"),
                ),
            )
        }
    }
}

private fun JSONArray?.toAssistantTipList(): List<AssistantTipCard> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            val json = optJSONObject(index) ?: continue
            add(
                AssistantTipCard(
                    id = json.optString("id"),
                    dedupeKey = json.optString("dedupe_key"),
                    title = json.optString("title"),
                    summary = json.optString("summary"),
                    actionLabel = json.optString("action_label"),
                    recommendedPage = json.optString("recommended_page"),
                    priority = json.optInt("priority"),
                    source = json.optString("source"),
                    audience = json.optString("audience", "all"),
                    status = json.optString("status", "active"),
                    note = json.optString("note"),
                    nextEligibleAtMs = json.optLong("next_eligible_at_ms"),
                    lastPresentedAtMs = json.optLong("last_presented_at_ms"),
                    lastShownAtMs = json.optLong("last_shown_at_ms"),
                    shownCount = json.optInt("shown_count"),
                    cooldownSessions = json.optInt("cooldown_sessions"),
                    eligibilityReason = json.optString("eligibility_reason"),
                    dismissedAtMs = json.optLong("dismissed_at_ms"),
                    completedAtMs = json.optLong("completed_at_ms"),
                    createdAtMs = json.optLong("created_at_ms"),
                    updatedAtMs = json.optLong("updated_at_ms"),
                ),
            )
        }
    }
}
