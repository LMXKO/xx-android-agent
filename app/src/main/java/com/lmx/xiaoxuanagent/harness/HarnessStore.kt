package com.lmx.xiaoxuanagent.harness

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object HarnessStore {
    private const val HARNESS_DIR = "harness"
    private const val REPORT_FILE = "replay_report.json"
    private const val AGGREGATE_FILE = "aggregate_report.json"
    private const val MAX_REPORTS = 100
    private val lock = Any()

    data class AggregateBucketSnapshot(
        val name: String,
        val runCount: Int = 0,
        val successRate: Double = 0.0,
        val avgTurns: Double = 0.0,
        val externalWaitEnteredCount: Int = 0,
        val externalWaitResolvedCount: Int = 0,
        val externalWaitResumeGuardCount: Int = 0,
        val safetyConfirmationRequestedCount: Int = 0,
        val safetyConfirmationApprovedCount: Int = 0,
        val safetyConfirmationRejectedCount: Int = 0,
        val manualResumeCount: Int = 0,
        val resumeSnapshotRestoredCount: Int = 0,
        val resumeSnapshotContinueCount: Int = 0,
        val resumeContinuationAttemptCount: Int = 0,
        val resumeContinuationSuccessCount: Int = 0,
        val resumeContinuationRecoveryCount: Int = 0,
        val resumeContinuationNoProgressCount: Int = 0,
        val resumeContinuationEmptyObservationCount: Int = 0,
        val takeoverSessionCount: Int = 0,
        val takeoverResolvedSessionCount: Int = 0,
        val takeoverRejectedSessionCount: Int = 0,
        val takeoverResumeSuccessCount: Int = 0,
        val routeOverrideCount: Int = 0,
        val fallbackCount: Int = 0,
    )

    data class AggregateSnapshot(
        val runCount: Int = 0,
        val successRate: Double = 0.0,
        val avgTurns: Double = 0.0,
        val externalWaitEnteredCount: Int = 0,
        val externalWaitResolvedCount: Int = 0,
        val externalWaitResumeGuardCount: Int = 0,
        val safetyConfirmationRequestedCount: Int = 0,
        val safetyConfirmationApprovedCount: Int = 0,
        val safetyConfirmationRejectedCount: Int = 0,
        val manualResumeCount: Int = 0,
        val resumeSnapshotRestoredCount: Int = 0,
        val resumeSnapshotContinueCount: Int = 0,
        val resumeContinuationAttemptCount: Int = 0,
        val resumeContinuationSuccessCount: Int = 0,
        val resumeContinuationRecoveryCount: Int = 0,
        val resumeContinuationNoProgressCount: Int = 0,
        val resumeContinuationEmptyObservationCount: Int = 0,
        val takeoverSessionCount: Int = 0,
        val takeoverResolvedSessionCount: Int = 0,
        val takeoverRejectedSessionCount: Int = 0,
        val takeoverResumeSuccessCount: Int = 0,
        val intents: List<AggregateBucketSnapshot> = emptyList(),
        val scenarios: List<AggregateBucketSnapshot> = emptyList(),
        val resultIntents: List<AggregateBucketSnapshot> = emptyList(),
        val skills: List<AggregateBucketSnapshot> = emptyList(),
        val profiles: List<AggregateBucketSnapshot> = emptyList(),
        val planTypes: List<AggregateBucketSnapshot> = emptyList(),
        val suites: List<AggregateBucketSnapshot> = emptyList(),
        val personas: List<AggregateBucketSnapshot> = emptyList(),
        val maturities: List<AggregateBucketSnapshot> = emptyList(),
        val routePolicies: List<AggregateBucketSnapshot> = emptyList(),
        val takeovers: List<AggregateBucketSnapshot> = emptyList(),
    )

    fun dashboardSummary(): String {
        val json = readAggregateReport() ?: return "暂无回归统计。"
        val lines = mutableListOf<String>()
        val runCount = json.optInt("run_count", 0)
        if (runCount > 0) {
            val successRate = (json.optDouble("success_rate", 0.0) * 100.0).toInt()
            val avgTurns = json.optDouble("avg_turns", 0.0)
            lines += "总任务: $runCount | 完成率: ${successRate}% | 平均轮数: ${"%.1f".format(avgTurns)}"
        }
        val resultIntents = json.optJSONObject("result_intents") ?: JSONObject()
        val topResultIntent =
            resultIntents.keys().asSequence()
                .mapNotNull { key ->
                    val bucket = resultIntents.optJSONObject(key) ?: return@mapNotNull null
                    key to bucket.optInt("run_count", 0)
                }
                .maxByOrNull { it.second }
        topResultIntent?.let { (intent, count) ->
            lines += "高频结果类型: $intent ($count)"
        }
        val intents = json.optJSONObject("intents") ?: JSONObject()
        val topIntent =
            intents.keys().asSequence()
                .mapNotNull { key ->
                    val bucket = intents.optJSONObject(key) ?: return@mapNotNull null
                    key to bucket.optDouble("success_rate", 0.0)
                }
                .maxByOrNull { it.second }
        topIntent?.let { (intent, successRate) ->
            lines += "最佳意图完成率: $intent (${(successRate * 100.0).toInt()}%)"
        }
        val routePolicies = json.optJSONObject("route_policies") ?: JSONObject()
        val topRoutePolicy =
            routePolicies.keys().asSequence()
                .mapNotNull { key ->
                    val bucket = routePolicies.optJSONObject(key) ?: return@mapNotNull null
                    key to bucket.optInt("run_count", 0)
                }
                .maxByOrNull { it.second }
        topRoutePolicy?.let { (policy, count) ->
            lines += "高频路由策略: $policy ($count)"
        }
        takeoverHeaderLine(snapshotFromAggregate(json))?.let(lines::add)
        return lines.joinToString("\n").ifBlank { "暂无回归统计。" }
    }

    fun detailedSummary(limit: Int = 5): List<String> {
        val json = readAggregateReport() ?: return listOf("暂无回归统计。")
        return com.lmx.xiaoxuanagent.harness.detailedSummaryFromAggregate(json, limit)
    }

    fun routePolicySummary(limit: Int = 3): String {
        val json = readAggregateReport() ?: return "暂无路由回归统计。"
        return com.lmx.xiaoxuanagent.harness.routePolicySummaryFromSnapshot(snapshotFromAggregate(json), limit)
    }

    fun takeoverSummary(limit: Int = 3): String {
        val json = readAggregateReport() ?: return "暂无人工接管回归统计。"
        return com.lmx.xiaoxuanagent.harness.takeoverSummaryFromSnapshot(snapshotFromAggregate(json), limit)
    }

    fun regressionSummary(limit: Int = 4): String {
        val snapshot = readAggregateSnapshot() ?: return "暂无回归执行计划。"
        return RegressionRunner.dashboardLines(snapshot, limit).joinToString("\n").ifBlank { "暂无回归执行计划。" }
    }

    fun readAggregateSnapshot(): AggregateSnapshot? {
        val json = readAggregateReport() ?: return null
        return com.lmx.xiaoxuanagent.harness.snapshotFromAggregate(json)
    }

    fun recordReplaySummary(
        sessionId: String,
        profileId: String,
        task: String,
        taskIntent: String,
        routeReason: String,
        planType: String,
        activeSkillIds: List<String>,
        recoveryCategories: List<String>,
        status: String,
        turnCount: Int,
        finalMessage: String,
        taskResultIntentType: String = "",
        taskResultTitle: String = "",
        taskResultSummary: String = "",
        routePolicyTag: String = "",
        routeModelChoiceProfileId: String = "",
        routeSelectedProfileId: String = "",
        routeFallbackReason: String = "",
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
        resumeContinuationSubmitQueryAttemptCount: Int = 0,
        resumeContinuationSubmitQuerySuccessCount: Int = 0,
        resumeContinuationSubmitQueryRecoveryCount: Int = 0,
        resumeContinuationSubmitQueryNoProgressCount: Int = 0,
        resumeContinuationSubmitQueryEmptyObservationCount: Int = 0,
        resumeContinuationConfirmRouteAttemptCount: Int = 0,
        resumeContinuationConfirmRouteSuccessCount: Int = 0,
        resumeContinuationConfirmRouteRecoveryCount: Int = 0,
        resumeContinuationConfirmRouteNoProgressCount: Int = 0,
        resumeContinuationConfirmRouteEmptyObservationCount: Int = 0,
        resumeContinuationConfirmSendAttemptCount: Int = 0,
        resumeContinuationConfirmSendSuccessCount: Int = 0,
        resumeContinuationConfirmSendRecoveryCount: Int = 0,
        resumeContinuationConfirmSendNoProgressCount: Int = 0,
        resumeContinuationConfirmSendEmptyObservationCount: Int = 0,
    ) {
        synchronized(lock) {
            val json = readReport() ?: JSONObject()
            val runs = json.optJSONArray("runs") ?: JSONArray().also { json.put("runs", it) }
            val matchedScenario = HarnessScenarioRegistry.match(task, profileId)
            runs.put(
                JSONObject().apply {
                    put("session_id", sessionId)
                    put("profile_id", profileId)
                    put("task", task)
                    put("task_intent", taskIntent)
                    put("route_reason", routeReason.take(120))
                    put("plan_type", planType)
                    put("active_skill_ids", JSONArray(activeSkillIds))
                    put("recovery_categories", JSONArray(recoveryCategories))
                    put("status", status)
                    put("turn_count", turnCount)
                    put("final_message", finalMessage.take(200))
                    put("task_result_intent_type", taskResultIntentType)
                    put("task_result_title", taskResultTitle.take(80))
                    put("task_result_summary", taskResultSummary.take(160))
                    put("route_policy_tag", routePolicyTag)
                    put("route_model_choice_profile_id", routeModelChoiceProfileId)
                    put("route_selected_profile_id", routeSelectedProfileId)
                    put("route_fallback_reason", routeFallbackReason.take(120))
                    put(
                        "route_override_applied",
                        routeModelChoiceProfileId.isNotBlank() &&
                            routeSelectedProfileId.isNotBlank() &&
                            routeModelChoiceProfileId != routeSelectedProfileId,
                    )
                    put("external_wait_entered_count", externalWaitEnteredCount)
                    put("external_wait_resolved_count", externalWaitResolvedCount)
                    put("external_wait_resume_guard_count", externalWaitResumeGuardCount)
                    put("safety_confirmation_requested_count", safetyConfirmationRequestedCount)
                    put("safety_confirmation_approved_count", safetyConfirmationApprovedCount)
                    put("safety_confirmation_rejected_count", safetyConfirmationRejectedCount)
                    put("manual_resume_count", manualResumeCount)
                    put("resume_snapshot_restored_count", resumeSnapshotRestoredCount)
                    put("resume_snapshot_continue_count", resumeSnapshotContinueCount)
                    put("resume_continuation_attempt_count", resumeContinuationAttemptCount)
                    put("resume_continuation_success_count", resumeContinuationSuccessCount)
                    put("resume_continuation_recovery_count", resumeContinuationRecoveryCount)
                    put("resume_continuation_no_progress_count", resumeContinuationNoProgressCount)
                    put("resume_continuation_empty_observation_count", resumeContinuationEmptyObservationCount)
                    put("resume_continuation_submit_query_attempt_count", resumeContinuationSubmitQueryAttemptCount)
                    put("resume_continuation_submit_query_success_count", resumeContinuationSubmitQuerySuccessCount)
                    put("resume_continuation_submit_query_recovery_count", resumeContinuationSubmitQueryRecoveryCount)
                    put("resume_continuation_submit_query_no_progress_count", resumeContinuationSubmitQueryNoProgressCount)
                    put("resume_continuation_submit_query_empty_observation_count", resumeContinuationSubmitQueryEmptyObservationCount)
                    put("resume_continuation_confirm_route_attempt_count", resumeContinuationConfirmRouteAttemptCount)
                    put("resume_continuation_confirm_route_success_count", resumeContinuationConfirmRouteSuccessCount)
                    put("resume_continuation_confirm_route_recovery_count", resumeContinuationConfirmRouteRecoveryCount)
                    put("resume_continuation_confirm_route_no_progress_count", resumeContinuationConfirmRouteNoProgressCount)
                    put("resume_continuation_confirm_route_empty_observation_count", resumeContinuationConfirmRouteEmptyObservationCount)
                    put("resume_continuation_confirm_send_attempt_count", resumeContinuationConfirmSendAttemptCount)
                    put("resume_continuation_confirm_send_success_count", resumeContinuationConfirmSendSuccessCount)
                    put("resume_continuation_confirm_send_recovery_count", resumeContinuationConfirmSendRecoveryCount)
                    put("resume_continuation_confirm_send_no_progress_count", resumeContinuationConfirmSendNoProgressCount)
                    put("resume_continuation_confirm_send_empty_observation_count", resumeContinuationConfirmSendEmptyObservationCount)
                    put("scenario_id", matchedScenario?.id ?: "")
                    put("timestamp", System.currentTimeMillis())
                },
            )
            while (runs.length() > MAX_REPORTS) {
                runs.remove(0)
            }
            json.put("updated_at", System.currentTimeMillis())
            writeReport(json)
            updateAggregateReport(
                profileId = profileId,
                scenarioId = matchedScenario?.id.orEmpty(),
                scenarioSuite = matchedScenario?.suite.orEmpty(),
                scenarioPersona = matchedScenario?.persona.orEmpty(),
                scenarioMaturity = matchedScenario?.maturity.orEmpty(),
                taskIntent = taskIntent,
                planType = planType,
                activeSkillIds = activeSkillIds,
                recoveryCategories = recoveryCategories,
                status = status,
                turnCount = turnCount,
                taskResultIntentType = taskResultIntentType,
                routePolicyTag = routePolicyTag,
                routeOverrideApplied =
                    routeModelChoiceProfileId.isNotBlank() &&
                        routeSelectedProfileId.isNotBlank() &&
                        routeModelChoiceProfileId != routeSelectedProfileId,
                routeFallbackApplied = routeFallbackReason.isNotBlank(),
                externalWaitEnteredCount = externalWaitEnteredCount,
                externalWaitResolvedCount = externalWaitResolvedCount,
                externalWaitResumeGuardCount = externalWaitResumeGuardCount,
                safetyConfirmationRequestedCount = safetyConfirmationRequestedCount,
                safetyConfirmationApprovedCount = safetyConfirmationApprovedCount,
                safetyConfirmationRejectedCount = safetyConfirmationRejectedCount,
                manualResumeCount = manualResumeCount,
                resumeSnapshotRestoredCount = resumeSnapshotRestoredCount,
                resumeSnapshotContinueCount = resumeSnapshotContinueCount,
                resumeContinuationAttemptCount = resumeContinuationAttemptCount,
                resumeContinuationSuccessCount = resumeContinuationSuccessCount,
                resumeContinuationRecoveryCount = resumeContinuationRecoveryCount,
                resumeContinuationNoProgressCount = resumeContinuationNoProgressCount,
                resumeContinuationEmptyObservationCount = resumeContinuationEmptyObservationCount,
                resumeContinuationSubmitQueryAttemptCount = resumeContinuationSubmitQueryAttemptCount,
                resumeContinuationSubmitQuerySuccessCount = resumeContinuationSubmitQuerySuccessCount,
                resumeContinuationSubmitQueryRecoveryCount = resumeContinuationSubmitQueryRecoveryCount,
                resumeContinuationSubmitQueryNoProgressCount = resumeContinuationSubmitQueryNoProgressCount,
                resumeContinuationSubmitQueryEmptyObservationCount = resumeContinuationSubmitQueryEmptyObservationCount,
                resumeContinuationConfirmRouteAttemptCount = resumeContinuationConfirmRouteAttemptCount,
                resumeContinuationConfirmRouteSuccessCount = resumeContinuationConfirmRouteSuccessCount,
                resumeContinuationConfirmRouteRecoveryCount = resumeContinuationConfirmRouteRecoveryCount,
                resumeContinuationConfirmRouteNoProgressCount = resumeContinuationConfirmRouteNoProgressCount,
                resumeContinuationConfirmRouteEmptyObservationCount = resumeContinuationConfirmRouteEmptyObservationCount,
                resumeContinuationConfirmSendAttemptCount = resumeContinuationConfirmSendAttemptCount,
                resumeContinuationConfirmSendSuccessCount = resumeContinuationConfirmSendSuccessCount,
                resumeContinuationConfirmSendRecoveryCount = resumeContinuationConfirmSendRecoveryCount,
                resumeContinuationConfirmSendNoProgressCount = resumeContinuationConfirmSendNoProgressCount,
                resumeContinuationConfirmSendEmptyObservationCount = resumeContinuationConfirmSendEmptyObservationCount,
            )
        }
    }

    private fun readReport(): JSONObject? {
        val file = reportFile() ?: return null
        if (!file.exists()) {
            return JSONObject().apply {
                put("runs", JSONArray())
                put("updated_at", System.currentTimeMillis())
            }
        }
        return JSONObject(file.readText())
    }

    private fun writeReport(json: JSONObject) {
        val file = reportFile() ?: return
        file.writeText(json.toString(2))
    }

    private fun updateAggregateReport(
        profileId: String,
        scenarioId: String,
        scenarioSuite: String,
        scenarioPersona: String,
        scenarioMaturity: String,
        taskIntent: String,
        planType: String,
        activeSkillIds: List<String>,
        recoveryCategories: List<String>,
        status: String,
        turnCount: Int,
        taskResultIntentType: String,
        routePolicyTag: String,
        routeOverrideApplied: Boolean,
        routeFallbackApplied: Boolean,
        externalWaitEnteredCount: Int,
        externalWaitResolvedCount: Int,
        externalWaitResumeGuardCount: Int,
        safetyConfirmationRequestedCount: Int,
        safetyConfirmationApprovedCount: Int,
        safetyConfirmationRejectedCount: Int,
        manualResumeCount: Int,
        resumeSnapshotRestoredCount: Int = 0,
        resumeSnapshotContinueCount: Int = 0,
        resumeContinuationAttemptCount: Int = 0,
        resumeContinuationSuccessCount: Int = 0,
        resumeContinuationRecoveryCount: Int = 0,
        resumeContinuationNoProgressCount: Int = 0,
        resumeContinuationEmptyObservationCount: Int = 0,
        resumeContinuationSubmitQueryAttemptCount: Int = 0,
        resumeContinuationSubmitQuerySuccessCount: Int = 0,
        resumeContinuationSubmitQueryRecoveryCount: Int = 0,
        resumeContinuationSubmitQueryNoProgressCount: Int = 0,
        resumeContinuationSubmitQueryEmptyObservationCount: Int = 0,
        resumeContinuationConfirmRouteAttemptCount: Int = 0,
        resumeContinuationConfirmRouteSuccessCount: Int = 0,
        resumeContinuationConfirmRouteRecoveryCount: Int = 0,
        resumeContinuationConfirmRouteNoProgressCount: Int = 0,
        resumeContinuationConfirmRouteEmptyObservationCount: Int = 0,
        resumeContinuationConfirmSendAttemptCount: Int = 0,
        resumeContinuationConfirmSendSuccessCount: Int = 0,
        resumeContinuationConfirmSendRecoveryCount: Int = 0,
        resumeContinuationConfirmSendNoProgressCount: Int = 0,
        resumeContinuationConfirmSendEmptyObservationCount: Int = 0,
    ) {
        val json = readAggregateReport() ?: JSONObject()
        applyAggregateUpdate(
            json = json,
            input =
                HarnessAggregateUpdateInput(
                    profileId = profileId,
                    scenarioId = scenarioId,
                    scenarioSuite = scenarioSuite,
                    scenarioPersona = scenarioPersona,
                    scenarioMaturity = scenarioMaturity,
                    taskIntent = taskIntent,
                    planType = planType,
                    activeSkillIds = activeSkillIds,
                    recoveryCategories = recoveryCategories,
                    status = status,
                    turnCount = turnCount,
                    taskResultIntentType = taskResultIntentType,
                    routePolicyTag = routePolicyTag,
                    routeOverrideApplied = routeOverrideApplied,
                    routeFallbackApplied = routeFallbackApplied,
                    externalWaitEnteredCount = externalWaitEnteredCount,
                    externalWaitResolvedCount = externalWaitResolvedCount,
                    externalWaitResumeGuardCount = externalWaitResumeGuardCount,
                    safetyConfirmationRequestedCount = safetyConfirmationRequestedCount,
                    safetyConfirmationApprovedCount = safetyConfirmationApprovedCount,
                    safetyConfirmationRejectedCount = safetyConfirmationRejectedCount,
                    manualResumeCount = manualResumeCount,
                    resumeSnapshotRestoredCount = resumeSnapshotRestoredCount,
                    resumeSnapshotContinueCount = resumeSnapshotContinueCount,
                    resumeContinuationAttemptCount = resumeContinuationAttemptCount,
                    resumeContinuationSuccessCount = resumeContinuationSuccessCount,
                    resumeContinuationRecoveryCount = resumeContinuationRecoveryCount,
                    resumeContinuationNoProgressCount = resumeContinuationNoProgressCount,
                    resumeContinuationEmptyObservationCount = resumeContinuationEmptyObservationCount,
                    resumeContinuationSubmitQueryAttemptCount = resumeContinuationSubmitQueryAttemptCount,
                    resumeContinuationSubmitQuerySuccessCount = resumeContinuationSubmitQuerySuccessCount,
                    resumeContinuationSubmitQueryRecoveryCount = resumeContinuationSubmitQueryRecoveryCount,
                    resumeContinuationSubmitQueryNoProgressCount = resumeContinuationSubmitQueryNoProgressCount,
                    resumeContinuationSubmitQueryEmptyObservationCount = resumeContinuationSubmitQueryEmptyObservationCount,
                    resumeContinuationConfirmRouteAttemptCount = resumeContinuationConfirmRouteAttemptCount,
                    resumeContinuationConfirmRouteSuccessCount = resumeContinuationConfirmRouteSuccessCount,
                    resumeContinuationConfirmRouteRecoveryCount = resumeContinuationConfirmRouteRecoveryCount,
                    resumeContinuationConfirmRouteNoProgressCount = resumeContinuationConfirmRouteNoProgressCount,
                    resumeContinuationConfirmRouteEmptyObservationCount = resumeContinuationConfirmRouteEmptyObservationCount,
                    resumeContinuationConfirmSendAttemptCount = resumeContinuationConfirmSendAttemptCount,
                    resumeContinuationConfirmSendSuccessCount = resumeContinuationConfirmSendSuccessCount,
                    resumeContinuationConfirmSendRecoveryCount = resumeContinuationConfirmSendRecoveryCount,
                    resumeContinuationConfirmSendNoProgressCount = resumeContinuationConfirmSendNoProgressCount,
                    resumeContinuationConfirmSendEmptyObservationCount = resumeContinuationConfirmSendEmptyObservationCount,
                ),
        )
        writeAggregateReport(json)
    }

    internal fun detailedSummaryFromAggregate(
        json: JSONObject,
        limit: Int = 5,
    ): List<String> = com.lmx.xiaoxuanagent.harness.detailedSummaryFromAggregate(json, limit)

    internal fun detailedSummaryFromSnapshot(
        snapshot: AggregateSnapshot,
        limit: Int = 5,
    ): List<String> = com.lmx.xiaoxuanagent.harness.detailedSummaryFromSnapshot(snapshot, limit)

    internal fun routePolicySummaryFromSnapshot(
        snapshot: AggregateSnapshot,
        limit: Int = 3,
    ): String = com.lmx.xiaoxuanagent.harness.routePolicySummaryFromSnapshot(snapshot, limit)

    internal fun takeoverSummaryFromSnapshot(
        snapshot: AggregateSnapshot,
        limit: Int = 3,
    ): String = com.lmx.xiaoxuanagent.harness.takeoverSummaryFromSnapshot(snapshot, limit)

    private fun reportFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        val dir = File(context.filesDir, HARNESS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, REPORT_FILE)
    }

    private fun readAggregateReport(): JSONObject? {
        val file = aggregateFile() ?: return null
        if (!file.exists()) {
            return JSONObject().apply {
                put("profiles", JSONObject())
                put("scenarios", JSONObject())
                put("intents", JSONObject())
                put("plan_types", JSONObject())
                put("skills", JSONObject())
                put("recoveries", JSONObject())
                put("suites", JSONObject())
                put("personas", JSONObject())
                put("maturities", JSONObject())
                put("result_intents", JSONObject())
                put("route_policies", JSONObject())
                put("takeovers", JSONObject())
                put("updated_at", System.currentTimeMillis())
            }
        }
        return JSONObject(file.readText())
    }

    private fun writeAggregateReport(json: JSONObject) {
        val file = aggregateFile() ?: return
        file.writeText(json.toString(2))
    }

    private fun aggregateFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        val dir = File(context.filesDir, HARNESS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, AGGREGATE_FILE)
    }
}
