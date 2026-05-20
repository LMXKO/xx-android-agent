package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.harness.HarnessStore
import com.lmx.xiaoxuanagent.harness.ScreenAutomationCapabilityStore
import com.lmx.xiaoxuanagent.harness.ScreenAutomationTemplateCapabilityStore
import com.lmx.xiaoxuanagent.taskprofile.AutomationSupportPolicyStore
import com.lmx.xiaoxuanagent.taskprofile.TaskRegistry
import org.json.JSONArray
import org.json.JSONObject

internal object ReplayStoreAnalyticsSupport {
    fun recordHarnessReplaySummary(
        sessionId: String,
        status: String,
        finalMessage: String,
        json: JSONObject,
    ) {
        val finalTaskResult = json.optJSONObject("final_task_result") ?: JSONObject()
        val profileId = json.optString("profile_id")
        val packageName = TaskRegistry.get(profileId)?.packageName.orEmpty()
        val automationPolicy = AutomationSupportPolicyStore.staticPolicy(profileId = profileId, packageName = packageName)
        val safetyRequestedCount = countEvents(json.optJSONArray("events"), "safety_confirmation_requested")
        val safetyApprovedCount = countEvents(json.optJSONArray("events"), "safety_confirmation_approved")
        val waitingExternalCount = countEvents(json.optJSONArray("events"), "external_wait_entered")
        HarnessStore.recordReplaySummary(
            sessionId = sessionId,
            profileId = profileId,
            task = json.optString("task"),
            taskIntent = json.optString("task_intent"),
            routeReason = json.optString("route_reason"),
            planType = latestReplayTurn(json.optJSONArray("turns"))?.optString("plan_type").orEmpty(),
            activeSkillIds = collectActiveSkillIds(json.optJSONArray("turns")),
            recoveryCategories = collectRecoveryCategories(json.optJSONArray("turns")),
            status = status,
            turnCount = json.optJSONArray("turns")?.length() ?: 0,
            finalMessage = finalMessage,
            taskResultIntentType = finalTaskResult.optString("intent_type"),
            taskResultTitle = finalTaskResult.optString("title"),
            taskResultSummary = finalTaskResult.optString("summary"),
            routePolicyTag = json.optString("route_policy_tag"),
            routeModelChoiceProfileId = json.optString("route_model_choice_profile_id"),
            routeSelectedProfileId = json.optString("route_selected_profile_id"),
            routeFallbackReason = json.optString("route_fallback_reason"),
            externalWaitEnteredCount = countEvents(json.optJSONArray("events"), "external_wait_entered"),
            externalWaitResolvedCount = countEvents(json.optJSONArray("events"), "external_wait_resolved"),
            externalWaitResumeGuardCount = countEvents(json.optJSONArray("events"), "external_wait_resume_guard"),
            safetyConfirmationRequestedCount = safetyRequestedCount,
            safetyConfirmationApprovedCount = safetyApprovedCount,
            safetyConfirmationRejectedCount = countEvents(json.optJSONArray("events"), "safety_confirmation_rejected"),
            manualResumeCount = countEvents(json.optJSONArray("events"), "manual_resume"),
            resumeSnapshotRestoredCount = countEvents(json.optJSONArray("events"), "resume_snapshot_restored"),
            resumeSnapshotContinueCount = countEvents(json.optJSONArray("events"), "resume_snapshot_continue"),
            resumeContinuationAttemptCount = countSkillTurns(json.optJSONArray("turns"), "resume_continuation", "pre_plan"),
            resumeContinuationSuccessCount =
                countSkillTurns(
                    turns = json.optJSONArray("turns"),
                    skillId = "resume_continuation",
                    phase = "pre_plan",
                    requireRecoveryCategoryBlank = true,
                ),
            resumeContinuationRecoveryCount = countSkillTurns(json.optJSONArray("turns"), "resume_continuation", "recover"),
            resumeContinuationNoProgressCount =
                countSkillTurnsByRecoveryCategory(json.optJSONArray("turns"), "resume_continuation", "NO_PROGRESS"),
            resumeContinuationEmptyObservationCount =
                countSkillTurnsByRecoveryCategory(json.optJSONArray("turns"), "resume_continuation", "EMPTY_OBSERVATION"),
            resumeContinuationSubmitQueryAttemptCount =
                countSkillStageTurns(json.optJSONArray("turns"), "resume_continuation", "pre_plan", "submit_query"),
            resumeContinuationSubmitQuerySuccessCount =
                countSkillStageTurns(
                    turns = json.optJSONArray("turns"),
                    skillId = "resume_continuation",
                    phase = "pre_plan",
                    stage = "submit_query",
                    requireRecoveryCategoryBlank = true,
                ),
            resumeContinuationSubmitQueryRecoveryCount =
                countSkillStageTurns(json.optJSONArray("turns"), "resume_continuation", "recover", "submit_query"),
            resumeContinuationSubmitQueryNoProgressCount =
                countSkillStageTurnsByRecoveryCategory(
                    turns = json.optJSONArray("turns"),
                    skillId = "resume_continuation",
                    stage = "submit_query",
                    recoveryCategory = "NO_PROGRESS",
                ),
            resumeContinuationSubmitQueryEmptyObservationCount =
                countSkillStageTurnsByRecoveryCategory(
                    turns = json.optJSONArray("turns"),
                    skillId = "resume_continuation",
                    stage = "submit_query",
                    recoveryCategory = "EMPTY_OBSERVATION",
                ),
            resumeContinuationConfirmRouteAttemptCount =
                countSkillStageTurns(json.optJSONArray("turns"), "resume_continuation", "pre_plan", "confirm_route"),
            resumeContinuationConfirmRouteSuccessCount =
                countSkillStageTurns(
                    turns = json.optJSONArray("turns"),
                    skillId = "resume_continuation",
                    phase = "pre_plan",
                    stage = "confirm_route",
                    requireRecoveryCategoryBlank = true,
                ),
            resumeContinuationConfirmRouteRecoveryCount =
                countSkillStageTurns(json.optJSONArray("turns"), "resume_continuation", "recover", "confirm_route"),
            resumeContinuationConfirmRouteNoProgressCount =
                countSkillStageTurnsByRecoveryCategory(
                    turns = json.optJSONArray("turns"),
                    skillId = "resume_continuation",
                    stage = "confirm_route",
                    recoveryCategory = "NO_PROGRESS",
                ),
            resumeContinuationConfirmRouteEmptyObservationCount =
                countSkillStageTurnsByRecoveryCategory(
                    turns = json.optJSONArray("turns"),
                    skillId = "resume_continuation",
                    stage = "confirm_route",
                    recoveryCategory = "EMPTY_OBSERVATION",
                ),
            resumeContinuationConfirmSendAttemptCount =
                countSkillStageTurns(json.optJSONArray("turns"), "resume_continuation", "pre_plan", "confirm_send"),
            resumeContinuationConfirmSendSuccessCount =
                countSkillStageTurns(
                    turns = json.optJSONArray("turns"),
                    skillId = "resume_continuation",
                    phase = "pre_plan",
                    stage = "confirm_send",
                    requireRecoveryCategoryBlank = true,
                ),
            resumeContinuationConfirmSendRecoveryCount =
                countSkillStageTurns(json.optJSONArray("turns"), "resume_continuation", "recover", "confirm_send"),
            resumeContinuationConfirmSendNoProgressCount =
                countSkillStageTurnsByRecoveryCategory(
                    turns = json.optJSONArray("turns"),
                    skillId = "resume_continuation",
                    stage = "confirm_send",
                    recoveryCategory = "NO_PROGRESS",
                ),
            resumeContinuationConfirmSendEmptyObservationCount =
                countSkillStageTurnsByRecoveryCategory(
                    turns = json.optJSONArray("turns"),
                    skillId = "resume_continuation",
                    stage = "confirm_send",
                    recoveryCategory = "EMPTY_OBSERVATION",
                ),
        )
        ScreenAutomationCapabilityStore.recordRun(
            profileId = profileId,
            packageName = packageName,
            taskIntent = json.optString("task_intent"),
            status = status,
            turnCount = json.optJSONArray("turns")?.length() ?: 0,
            handoffReady =
                when {
                    automationPolicy.requiresFinalHandoff -> safetyRequestedCount > 0 || waitingExternalCount > 0 || AgentUiStatus.isCompleted(status)
                    else -> safetyRequestedCount > 0
                },
            handoffCompleted = safetyApprovedCount > 0 || AgentUiStatus.isCompleted(status),
            waitingExternal = waitingExternalCount > 0,
        )
        ScreenAutomationTemplateCapabilityStore.recordReplay(
            profileId = profileId,
            packageName = packageName,
            taskIntent = json.optString("task_intent"),
            turns = json.optJSONArray("turns"),
            handoffReady =
                when {
                    automationPolicy.requiresFinalHandoff -> safetyRequestedCount > 0 || waitingExternalCount > 0 || AgentUiStatus.isCompleted(status)
                    else -> safetyRequestedCount > 0
                },
            handoffCompleted = safetyApprovedCount > 0 || AgentUiStatus.isCompleted(status),
        )
    }

    private fun countEvents(
        events: JSONArray?,
        type: String,
    ): Int {
        if (events == null) return 0
        var count = 0
        for (index in 0 until events.length()) {
            val event = events.optJSONObject(index) ?: continue
            if (event.optString("type") == type) {
                count += 1
            }
        }
        return count
    }

    private fun collectActiveSkillIds(
        turns: JSONArray?,
    ): List<String> {
        if (turns == null) return emptyList()
        val result = linkedSetOf<String>()
        for (index in 0 until turns.length()) {
            val turn = turns.optJSONObject(index) ?: continue
            val ids = turn.optJSONArray("active_skill_ids") ?: continue
            for (skillIndex in 0 until ids.length()) {
                ids.optString(skillIndex)
                    .takeIf { it.isNotBlank() }
                    ?.let(result::add)
            }
        }
        return result.toList()
    }

    private fun collectRecoveryCategories(
        turns: JSONArray?,
    ): List<String> {
        if (turns == null) return emptyList()
        val result = linkedSetOf<String>()
        for (index in 0 until turns.length()) {
            turns.optJSONObject(index)
                ?.optString("recovery_category")
                ?.takeIf { it.isNotBlank() }
                ?.let(result::add)
        }
        return result.toList()
    }

    private fun countSkillTurns(
        turns: JSONArray?,
        skillId: String,
        phase: String,
        requireRecoveryCategoryBlank: Boolean? = null,
    ): Int {
        if (turns == null || skillId.isBlank() || phase.isBlank()) return 0
        var count = 0
        for (index in 0 until turns.length()) {
            val turn = turns.optJSONObject(index) ?: continue
            val rawResponse = turn.optString("raw_response")
            if (!matchesDecisionMarker(rawResponse, skillId)) continue
            if (!rawResponse.contains(""""phase":"$phase"""")) continue
            val recoveryCategoryBlank = turn.optString("recovery_category").isBlank()
            if (requireRecoveryCategoryBlank != null && recoveryCategoryBlank != requireRecoveryCategoryBlank) {
                continue
            }
            count += 1
        }
        return count
    }

    private fun countSkillStageTurns(
        turns: JSONArray?,
        skillId: String,
        phase: String,
        stage: String,
        requireRecoveryCategoryBlank: Boolean? = null,
    ): Int {
        if (turns == null || skillId.isBlank() || phase.isBlank() || stage.isBlank()) return 0
        var count = 0
        for (index in 0 until turns.length()) {
            val turn = turns.optJSONObject(index) ?: continue
            val rawResponse = turn.optString("raw_response")
            if (!matchesDecisionMarker(rawResponse, skillId)) continue
            if (!rawResponse.contains(""""phase":"$phase"""")) continue
            if (!rawResponse.contains(""""stage":"$stage"""")) continue
            val recoveryCategoryBlank = turn.optString("recovery_category").isBlank()
            if (requireRecoveryCategoryBlank != null && recoveryCategoryBlank != requireRecoveryCategoryBlank) {
                continue
            }
            count += 1
        }
        return count
    }

    private fun countSkillTurnsByRecoveryCategory(
        turns: JSONArray?,
        skillId: String,
        recoveryCategory: String,
    ): Int {
        if (turns == null || skillId.isBlank() || recoveryCategory.isBlank()) return 0
        var count = 0
        for (index in 0 until turns.length()) {
            val turn = turns.optJSONObject(index) ?: continue
            val rawResponse = turn.optString("raw_response")
            if (!matchesDecisionMarker(rawResponse, skillId)) continue
            if (turn.optString("recovery_category") != recoveryCategory) continue
            count += 1
        }
        return count
    }

    private fun countSkillStageTurnsByRecoveryCategory(
        turns: JSONArray?,
        skillId: String,
        stage: String,
        recoveryCategory: String,
    ): Int {
        if (turns == null || skillId.isBlank() || stage.isBlank() || recoveryCategory.isBlank()) return 0
        var count = 0
        for (index in 0 until turns.length()) {
            val turn = turns.optJSONObject(index) ?: continue
            val rawResponse = turn.optString("raw_response")
            if (!matchesDecisionMarker(rawResponse, skillId)) continue
            if (!rawResponse.contains(""""stage":"$stage"""")) continue
            if (turn.optString("recovery_category") != recoveryCategory) continue
            count += 1
        }
        return count
    }

    private fun matchesDecisionMarker(
        rawResponse: String,
        markerId: String,
    ): Boolean =
        rawResponse.contains(""""skill":"$markerId"""") ||
            rawResponse.contains(""""runtime":"$markerId"""")
}
