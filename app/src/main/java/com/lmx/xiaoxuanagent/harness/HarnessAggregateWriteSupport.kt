package com.lmx.xiaoxuanagent.harness

import com.lmx.xiaoxuanagent.runtime.AgentUiStatus
import org.json.JSONObject

internal data class HarnessAggregateUpdateInput(
    val profileId: String,
    val scenarioId: String,
    val scenarioSuite: String,
    val scenarioPersona: String,
    val scenarioMaturity: String,
    val taskIntent: String,
    val planType: String,
    val activeSkillIds: List<String>,
    val recoveryCategories: List<String>,
    val status: String,
    val turnCount: Int,
    val taskResultIntentType: String,
    val routePolicyTag: String,
    val routeOverrideApplied: Boolean,
    val routeFallbackApplied: Boolean,
    val externalWaitEnteredCount: Int,
    val externalWaitResolvedCount: Int,
    val externalWaitResumeGuardCount: Int,
    val safetyConfirmationRequestedCount: Int,
    val safetyConfirmationApprovedCount: Int,
    val safetyConfirmationRejectedCount: Int,
    val manualResumeCount: Int,
    val resumeSnapshotRestoredCount: Int,
    val resumeSnapshotContinueCount: Int,
    val resumeContinuationAttemptCount: Int,
    val resumeContinuationSuccessCount: Int,
    val resumeContinuationRecoveryCount: Int,
    val resumeContinuationNoProgressCount: Int,
    val resumeContinuationEmptyObservationCount: Int,
    val resumeContinuationSubmitQueryAttemptCount: Int,
    val resumeContinuationSubmitQuerySuccessCount: Int,
    val resumeContinuationSubmitQueryRecoveryCount: Int,
    val resumeContinuationSubmitQueryNoProgressCount: Int,
    val resumeContinuationSubmitQueryEmptyObservationCount: Int,
    val resumeContinuationConfirmRouteAttemptCount: Int,
    val resumeContinuationConfirmRouteSuccessCount: Int,
    val resumeContinuationConfirmRouteRecoveryCount: Int,
    val resumeContinuationConfirmRouteNoProgressCount: Int,
    val resumeContinuationConfirmRouteEmptyObservationCount: Int,
    val resumeContinuationConfirmSendAttemptCount: Int,
    val resumeContinuationConfirmSendSuccessCount: Int,
    val resumeContinuationConfirmSendRecoveryCount: Int,
    val resumeContinuationConfirmSendNoProgressCount: Int,
    val resumeContinuationConfirmSendEmptyObservationCount: Int,
)

internal fun applyAggregateUpdate(
    json: JSONObject,
    input: HarnessAggregateUpdateInput,
) {
    incrementHarnessAggregate(
        bucket = json,
        status = input.status,
        turnCount = input.turnCount,
        taskResultIntentType = input.taskResultIntentType,
        routeOverrideApplied = input.routeOverrideApplied,
        routeFallbackApplied = input.routeFallbackApplied,
        externalWaitEnteredCount = input.externalWaitEnteredCount,
        externalWaitResolvedCount = input.externalWaitResolvedCount,
        externalWaitResumeGuardCount = input.externalWaitResumeGuardCount,
        safetyConfirmationRequestedCount = input.safetyConfirmationRequestedCount,
        safetyConfirmationApprovedCount = input.safetyConfirmationApprovedCount,
        safetyConfirmationRejectedCount = input.safetyConfirmationRejectedCount,
        manualResumeCount = input.manualResumeCount,
        resumeContinuationAttemptCount = input.resumeContinuationAttemptCount,
        resumeContinuationSuccessCount = input.resumeContinuationSuccessCount,
        resumeContinuationRecoveryCount = input.resumeContinuationRecoveryCount,
        resumeContinuationNoProgressCount = input.resumeContinuationNoProgressCount,
        resumeContinuationEmptyObservationCount = input.resumeContinuationEmptyObservationCount,
    )

    incrementHarnessAggregateBucket(json, "profiles", input.profileId, input)
    incrementHarnessAggregateBucket(
        json = json,
        bucketGroup = "scenarios",
        bucketName = input.scenarioId,
        input = input,
        resumeSnapshotRestoredCount = input.resumeSnapshotRestoredCount,
        resumeSnapshotContinueCount = input.resumeSnapshotContinueCount,
    )
    incrementHarnessAggregateBucket(json, "suites", input.scenarioSuite, input)
    incrementHarnessAggregateBucket(json, "personas", input.scenarioPersona, input)
    incrementHarnessAggregateBucket(json, "maturities", input.scenarioMaturity, input)
    incrementHarnessAggregateBucket(
        json = json,
        bucketGroup = "intents",
        bucketName = input.taskIntent,
        input = input,
        resumeContinuationAttemptCount = input.resumeContinuationAttemptCount,
        resumeContinuationSuccessCount = input.resumeContinuationSuccessCount,
        resumeContinuationRecoveryCount = input.resumeContinuationRecoveryCount,
        resumeContinuationNoProgressCount = input.resumeContinuationNoProgressCount,
        resumeContinuationEmptyObservationCount = input.resumeContinuationEmptyObservationCount,
    )
    incrementHarnessAggregateBucket(
        json = json,
        bucketGroup = "plan_types",
        bucketName = input.planType,
        input = input,
        resumeContinuationAttemptCount = input.resumeContinuationAttemptCount,
        resumeContinuationSuccessCount = input.resumeContinuationSuccessCount,
        resumeContinuationRecoveryCount = input.resumeContinuationRecoveryCount,
        resumeContinuationNoProgressCount = input.resumeContinuationNoProgressCount,
        resumeContinuationEmptyObservationCount = input.resumeContinuationEmptyObservationCount,
    )

    if (input.activeSkillIds.isNotEmpty()) {
        val skills = json.optJSONObject("skills") ?: JSONObject().also { json.put("skills", it) }
        input.activeSkillIds.forEach { skillId ->
            val bucket = skills.optJSONObject(skillId) ?: JSONObject().also { skills.put(skillId, it) }
            incrementHarnessAggregate(
                bucket = bucket,
                status = input.status,
                turnCount = input.turnCount,
                taskResultIntentType = input.taskResultIntentType,
                routeOverrideApplied = input.routeOverrideApplied,
                routeFallbackApplied = input.routeFallbackApplied,
                externalWaitEnteredCount = input.externalWaitEnteredCount,
                externalWaitResolvedCount = input.externalWaitResolvedCount,
                externalWaitResumeGuardCount = input.externalWaitResumeGuardCount,
                safetyConfirmationRequestedCount = input.safetyConfirmationRequestedCount,
                safetyConfirmationApprovedCount = input.safetyConfirmationApprovedCount,
                safetyConfirmationRejectedCount = input.safetyConfirmationRejectedCount,
                manualResumeCount = input.manualResumeCount,
                resumeContinuationAttemptCount = input.resumeContinuationAttemptCount,
                resumeContinuationSuccessCount = input.resumeContinuationSuccessCount,
                resumeContinuationRecoveryCount = input.resumeContinuationRecoveryCount,
                resumeContinuationNoProgressCount = input.resumeContinuationNoProgressCount,
                resumeContinuationEmptyObservationCount = input.resumeContinuationEmptyObservationCount,
            )
        }
    }

    if (input.recoveryCategories.isNotEmpty()) {
        val recoveries = json.optJSONObject("recoveries") ?: JSONObject().also { json.put("recoveries", it) }
        input.recoveryCategories.forEach { category ->
            val bucket = recoveries.optJSONObject(category) ?: JSONObject().also { recoveries.put(category, it) }
            incrementHarnessAggregate(
                bucket = bucket,
                status = input.status,
                turnCount = input.turnCount,
                taskResultIntentType = input.taskResultIntentType,
                routeOverrideApplied = input.routeOverrideApplied,
                routeFallbackApplied = input.routeFallbackApplied,
                externalWaitEnteredCount = input.externalWaitEnteredCount,
                externalWaitResolvedCount = input.externalWaitResolvedCount,
                externalWaitResumeGuardCount = input.externalWaitResumeGuardCount,
                safetyConfirmationRequestedCount = input.safetyConfirmationRequestedCount,
                safetyConfirmationApprovedCount = input.safetyConfirmationApprovedCount,
                safetyConfirmationRejectedCount = input.safetyConfirmationRejectedCount,
                manualResumeCount = input.manualResumeCount,
                resumeContinuationAttemptCount = input.resumeContinuationAttemptCount,
                resumeContinuationSuccessCount = input.resumeContinuationSuccessCount,
                resumeContinuationRecoveryCount = input.resumeContinuationRecoveryCount,
                resumeContinuationNoProgressCount = input.resumeContinuationNoProgressCount,
                resumeContinuationEmptyObservationCount = input.resumeContinuationEmptyObservationCount,
            )
        }
    }

    incrementHarnessAggregateBucket(json, "result_intents", input.taskResultIntentType, input)
    incrementHarnessAggregateBucket(json, "route_policies", input.routePolicyTag, input)

    val takeovers = json.optJSONObject("takeovers") ?: JSONObject().also { json.put("takeovers", it) }
    if (input.externalWaitEnteredCount > 0 || input.externalWaitResolvedCount > 0 || input.externalWaitResumeGuardCount > 0) {
        incrementHarnessTakeoverBucket(takeovers, "external_wait", input)
    }
    if (input.safetyConfirmationRequestedCount > 0 || input.safetyConfirmationApprovedCount > 0 || input.safetyConfirmationRejectedCount > 0) {
        incrementHarnessTakeoverBucket(takeovers, "safety_confirmation", input)
    }
    if (input.manualResumeCount > 0) {
        incrementHarnessTakeoverBucket(takeovers, "manual_resume", input)
    }
    if (
        input.resumeSnapshotRestoredCount > 0 ||
        input.resumeSnapshotContinueCount > 0 ||
        input.resumeContinuationAttemptCount > 0 ||
        input.resumeContinuationSuccessCount > 0 ||
        input.resumeContinuationRecoveryCount > 0
    ) {
        incrementHarnessTakeoverBucket(
            takeovers = takeovers,
            bucketName = "resume_snapshot",
            input = input,
            resumeSnapshotRestoredCount = input.resumeSnapshotRestoredCount,
            resumeSnapshotContinueCount = input.resumeSnapshotContinueCount,
            resumeContinuationAttemptCount = input.resumeContinuationAttemptCount,
            resumeContinuationSuccessCount = input.resumeContinuationSuccessCount,
            resumeContinuationRecoveryCount = input.resumeContinuationRecoveryCount,
            resumeContinuationNoProgressCount = input.resumeContinuationNoProgressCount,
            resumeContinuationEmptyObservationCount = input.resumeContinuationEmptyObservationCount,
        )
    }
    incrementHarnessResumeContinuationStageBucket(
        takeovers = takeovers,
        stage = "submit_query",
        input = input,
        attemptCount = input.resumeContinuationSubmitQueryAttemptCount,
        successCount = input.resumeContinuationSubmitQuerySuccessCount,
        recoveryCount = input.resumeContinuationSubmitQueryRecoveryCount,
        noProgressCount = input.resumeContinuationSubmitQueryNoProgressCount,
        emptyObservationCount = input.resumeContinuationSubmitQueryEmptyObservationCount,
    )
    incrementHarnessResumeContinuationStageBucket(
        takeovers = takeovers,
        stage = "confirm_route",
        input = input,
        attemptCount = input.resumeContinuationConfirmRouteAttemptCount,
        successCount = input.resumeContinuationConfirmRouteSuccessCount,
        recoveryCount = input.resumeContinuationConfirmRouteRecoveryCount,
        noProgressCount = input.resumeContinuationConfirmRouteNoProgressCount,
        emptyObservationCount = input.resumeContinuationConfirmRouteEmptyObservationCount,
    )
    incrementHarnessResumeContinuationStageBucket(
        takeovers = takeovers,
        stage = "confirm_send",
        input = input,
        attemptCount = input.resumeContinuationConfirmSendAttemptCount,
        successCount = input.resumeContinuationConfirmSendSuccessCount,
        recoveryCount = input.resumeContinuationConfirmSendRecoveryCount,
        noProgressCount = input.resumeContinuationConfirmSendNoProgressCount,
        emptyObservationCount = input.resumeContinuationConfirmSendEmptyObservationCount,
    )

    json.put("updated_at", System.currentTimeMillis())
}

private fun incrementHarnessAggregateBucket(
    json: JSONObject,
    bucketGroup: String,
    bucketName: String,
    input: HarnessAggregateUpdateInput,
    resumeSnapshotRestoredCount: Int = 0,
    resumeSnapshotContinueCount: Int = 0,
    resumeContinuationAttemptCount: Int = 0,
    resumeContinuationSuccessCount: Int = 0,
    resumeContinuationRecoveryCount: Int = 0,
    resumeContinuationNoProgressCount: Int = 0,
    resumeContinuationEmptyObservationCount: Int = 0,
) {
    if (bucketName.isBlank()) return
    val buckets = json.optJSONObject(bucketGroup) ?: JSONObject().also { json.put(bucketGroup, it) }
    val bucket = buckets.optJSONObject(bucketName) ?: JSONObject().also { buckets.put(bucketName, it) }
    incrementHarnessAggregate(
        bucket = bucket,
        status = input.status,
        turnCount = input.turnCount,
        taskResultIntentType = input.taskResultIntentType,
        routeOverrideApplied = input.routeOverrideApplied,
        routeFallbackApplied = input.routeFallbackApplied,
        externalWaitEnteredCount = input.externalWaitEnteredCount,
        externalWaitResolvedCount = input.externalWaitResolvedCount,
        externalWaitResumeGuardCount = input.externalWaitResumeGuardCount,
        safetyConfirmationRequestedCount = input.safetyConfirmationRequestedCount,
        safetyConfirmationApprovedCount = input.safetyConfirmationApprovedCount,
        safetyConfirmationRejectedCount = input.safetyConfirmationRejectedCount,
        manualResumeCount = input.manualResumeCount,
        resumeSnapshotRestoredCount = resumeSnapshotRestoredCount,
        resumeSnapshotContinueCount = resumeSnapshotContinueCount,
        resumeContinuationAttemptCount = resumeContinuationAttemptCount,
        resumeContinuationSuccessCount = resumeContinuationSuccessCount,
        resumeContinuationRecoveryCount = resumeContinuationRecoveryCount,
        resumeContinuationNoProgressCount = resumeContinuationNoProgressCount,
        resumeContinuationEmptyObservationCount = resumeContinuationEmptyObservationCount,
    )
}

private fun incrementHarnessTakeoverBucket(
    takeovers: JSONObject,
    bucketName: String,
    input: HarnessAggregateUpdateInput,
    resumeSnapshotRestoredCount: Int = 0,
    resumeSnapshotContinueCount: Int = 0,
    resumeContinuationAttemptCount: Int = 0,
    resumeContinuationSuccessCount: Int = 0,
    resumeContinuationRecoveryCount: Int = 0,
    resumeContinuationNoProgressCount: Int = 0,
    resumeContinuationEmptyObservationCount: Int = 0,
) {
    val bucket = takeovers.optJSONObject(bucketName) ?: JSONObject().also { takeovers.put(bucketName, it) }
    incrementHarnessAggregate(
        bucket = bucket,
        status = input.status,
        turnCount = input.turnCount,
        taskResultIntentType = input.taskResultIntentType,
        routeOverrideApplied = input.routeOverrideApplied,
        routeFallbackApplied = input.routeFallbackApplied,
        externalWaitEnteredCount = input.externalWaitEnteredCount,
        externalWaitResolvedCount = input.externalWaitResolvedCount,
        externalWaitResumeGuardCount = input.externalWaitResumeGuardCount,
        safetyConfirmationRequestedCount = input.safetyConfirmationRequestedCount,
        safetyConfirmationApprovedCount = input.safetyConfirmationApprovedCount,
        safetyConfirmationRejectedCount = input.safetyConfirmationRejectedCount,
        manualResumeCount = input.manualResumeCount,
        resumeSnapshotRestoredCount = resumeSnapshotRestoredCount,
        resumeSnapshotContinueCount = resumeSnapshotContinueCount,
        resumeContinuationAttemptCount = resumeContinuationAttemptCount,
        resumeContinuationSuccessCount = resumeContinuationSuccessCount,
        resumeContinuationRecoveryCount = resumeContinuationRecoveryCount,
        resumeContinuationNoProgressCount = resumeContinuationNoProgressCount,
        resumeContinuationEmptyObservationCount = resumeContinuationEmptyObservationCount,
    )
}

private fun incrementHarnessResumeContinuationStageBucket(
    takeovers: JSONObject,
    stage: String,
    input: HarnessAggregateUpdateInput,
    attemptCount: Int,
    successCount: Int,
    recoveryCount: Int,
    noProgressCount: Int,
    emptyObservationCount: Int,
) {
    if (attemptCount <= 0 && successCount <= 0 && recoveryCount <= 0 && noProgressCount <= 0 && emptyObservationCount <= 0) {
        return
    }
    incrementHarnessTakeoverBucket(
        takeovers = takeovers,
        bucketName = "resume_continuation_$stage",
        input = input,
        resumeContinuationAttemptCount = attemptCount,
        resumeContinuationSuccessCount = successCount,
        resumeContinuationRecoveryCount = recoveryCount,
        resumeContinuationNoProgressCount = noProgressCount,
        resumeContinuationEmptyObservationCount = emptyObservationCount,
    )
}

private fun incrementHarnessAggregate(
    bucket: JSONObject,
    status: String,
    turnCount: Int,
    taskResultIntentType: String = "",
    routeOverrideApplied: Boolean = false,
    routeFallbackApplied: Boolean = false,
    externalWaitEnteredCount: Int = 0,
    externalWaitResolvedCount: Int = 0,
    externalWaitResumeGuardCount: Int = 0,
    safetyConfirmationRequestedCount: Int = 0,
    safetyConfirmationApprovedCount: Int = 0,
    safetyConfirmationRejectedCount: Int = 0,
    manualResumeCount: Int = 0,
    resumeSnapshotRestoredCount: Int = 0,
    resumeSnapshotContinueCount: Int = 0,
    resumeContinuationAttemptCount: Int = 0,
    resumeContinuationSuccessCount: Int = 0,
    resumeContinuationRecoveryCount: Int = 0,
    resumeContinuationNoProgressCount: Int = 0,
    resumeContinuationEmptyObservationCount: Int = 0,
) {
    val runCount = bucket.optInt("run_count", 0) + 1
    val completedCount = bucket.optInt("completed_count", 0) + if (AgentUiStatus.isCompleted(status)) 1 else 0
    val failedCount = bucket.optInt("failed_count", 0) + if (AgentUiStatus.isFailed(status)) 1 else 0
    val stoppedCount = bucket.optInt("stopped_count", 0) + if (AgentUiStatus.isStopped(status)) 1 else 0
    val totalTurns = bucket.optInt("total_turns", 0) + turnCount
    val routeOverrideCount = bucket.optInt("route_override_count", 0) + if (routeOverrideApplied) 1 else 0
    val fallbackCount = bucket.optInt("route_fallback_count", 0) + if (routeFallbackApplied) 1 else 0
    val totalExternalWaitEntered = bucket.optInt("external_wait_entered_count", 0) + externalWaitEnteredCount
    val totalExternalWaitResolved = bucket.optInt("external_wait_resolved_count", 0) + externalWaitResolvedCount
    val totalExternalWaitResumeGuard = bucket.optInt("external_wait_resume_guard_count", 0) + externalWaitResumeGuardCount
    val totalSafetyConfirmationRequested = bucket.optInt("safety_confirmation_requested_count", 0) + safetyConfirmationRequestedCount
    val totalSafetyConfirmationApproved = bucket.optInt("safety_confirmation_approved_count", 0) + safetyConfirmationApprovedCount
    val totalSafetyConfirmationRejected = bucket.optInt("safety_confirmation_rejected_count", 0) + safetyConfirmationRejectedCount
    val totalManualResumeCount = bucket.optInt("manual_resume_count", 0) + manualResumeCount
    val totalResumeSnapshotRestored = bucket.optInt("resume_snapshot_restored_count", 0) + resumeSnapshotRestoredCount
    val totalResumeSnapshotContinue = bucket.optInt("resume_snapshot_continue_count", 0) + resumeSnapshotContinueCount
    val totalResumeContinuationAttempt = bucket.optInt("resume_continuation_attempt_count", 0) + resumeContinuationAttemptCount
    val totalResumeContinuationSuccess = bucket.optInt("resume_continuation_success_count", 0) + resumeContinuationSuccessCount
    val totalResumeContinuationRecovery = bucket.optInt("resume_continuation_recovery_count", 0) + resumeContinuationRecoveryCount
    val totalResumeContinuationNoProgress = bucket.optInt("resume_continuation_no_progress_count", 0) + resumeContinuationNoProgressCount
    val totalResumeContinuationEmptyObservation =
        bucket.optInt("resume_continuation_empty_observation_count", 0) + resumeContinuationEmptyObservationCount
    val takeoverCount = externalWaitEnteredCount + safetyConfirmationRequestedCount + manualResumeCount + resumeSnapshotRestoredCount
    val takeoverResolvedCount = externalWaitResolvedCount + safetyConfirmationApprovedCount + manualResumeCount + resumeSnapshotContinueCount
    val takeoverRejectedCount = safetyConfirmationRejectedCount
    val nextTakeoverSessionCount = bucket.optInt("takeover_session_count", 0) + if (takeoverCount > 0) 1 else 0
    val nextTakeoverResolvedSessionCount = bucket.optInt("takeover_resolved_session_count", 0) + if (takeoverResolvedCount > 0) 1 else 0
    val nextTakeoverRejectedSessionCount = bucket.optInt("takeover_rejected_session_count", 0) + if (takeoverRejectedCount > 0) 1 else 0
    val nextTakeoverResumeSuccessCount =
        bucket.optInt("takeover_resume_success_count", 0) + if (takeoverResolvedCount > 0 && AgentUiStatus.isCompleted(status)) 1 else 0

    bucket.put("run_count", runCount)
    bucket.put("completed_count", completedCount)
    bucket.put("failed_count", failedCount)
    bucket.put("stopped_count", stoppedCount)
    bucket.put("total_turns", totalTurns)
    bucket.put("route_override_count", routeOverrideCount)
    bucket.put("route_fallback_count", fallbackCount)
    bucket.put("external_wait_entered_count", totalExternalWaitEntered)
    bucket.put("external_wait_resolved_count", totalExternalWaitResolved)
    bucket.put("external_wait_resume_guard_count", totalExternalWaitResumeGuard)
    bucket.put("safety_confirmation_requested_count", totalSafetyConfirmationRequested)
    bucket.put("safety_confirmation_approved_count", totalSafetyConfirmationApproved)
    bucket.put("safety_confirmation_rejected_count", totalSafetyConfirmationRejected)
    bucket.put("manual_resume_count", totalManualResumeCount)
    bucket.put("resume_snapshot_restored_count", totalResumeSnapshotRestored)
    bucket.put("resume_snapshot_continue_count", totalResumeSnapshotContinue)
    bucket.put("resume_continuation_attempt_count", totalResumeContinuationAttempt)
    bucket.put("resume_continuation_success_count", totalResumeContinuationSuccess)
    bucket.put("resume_continuation_recovery_count", totalResumeContinuationRecovery)
    bucket.put("resume_continuation_no_progress_count", totalResumeContinuationNoProgress)
    bucket.put("resume_continuation_empty_observation_count", totalResumeContinuationEmptyObservation)
    bucket.put("takeover_session_count", nextTakeoverSessionCount)
    bucket.put("takeover_resolved_session_count", nextTakeoverResolvedSessionCount)
    bucket.put("takeover_rejected_session_count", nextTakeoverRejectedSessionCount)
    bucket.put("takeover_resume_success_count", nextTakeoverResumeSuccessCount)
    bucket.put("avg_turns", totalTurns.toDouble() / runCount.toDouble())
    bucket.put("success_rate", completedCount.toDouble() / runCount.toDouble())
    if (taskResultIntentType.isNotBlank()) {
        bucket.put("last_result_intent_type", taskResultIntentType)
    }
}
