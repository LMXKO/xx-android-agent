package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.TaskResultField
import com.lmx.xiaoxuanagent.agent.TaskResultPayload
import org.json.JSONArray
import org.json.JSONObject

internal fun buildReplaySessionSnapshot(
    sessionId: String,
    json: JSONObject,
    recentTurnLimit: Int = 3,
    recentEventLimit: Int = 6,
    artifactTurnLimit: Int = 3,
    artifactLimitPerTurn: Int = 6,
): ReplaySessionSnapshot {
    val turns = json.optJSONArray("turns")
    val latestTurnJson = latestReplayTurn(turns)
    val recentTurns = buildReplayRecentTurnSummaries(turns, recentTurnLimit)
    val recentEvents = buildReplayRecentEventSummaries(json.optJSONArray("events"), recentEventLimit)
    val recentArtifactGroups = buildReplayRecentArtifactGroups(sessionId, turns, artifactTurnLimit, artifactLimitPerTurn)
    val liveResumeSnapshot = SessionResumeStore.readLatestSnapshot()?.takeIf { it.sessionId == sessionId }
    return ReplaySessionSnapshot(
        sessionId = sessionId,
        profileId = json.optString("profile_id"),
        targetPackageName = json.optString("target_package_name"),
        task = json.optString("task"),
        taskIntent = json.optString("task_intent"),
        entrySource = json.optString("entry_source", "app"),
        statusSnapshot =
            json.optJSONObject("status_snapshot")
                .toAgentUiStatusSnapshot(json.optString("status", AgentUiStatus.IDLE)),
        routeReason = json.optString("route_reason"),
        routePolicyTag = json.optString("route_policy_tag"),
        routeSelectedProfileId = json.optString("route_selected_profile_id"),
        routeFallbackReason = json.optString("route_fallback_reason"),
        routeMemoryHints = json.optJSONArray("route_memory_hints").toReplayStringList(),
        startedAt = json.optLong("started_at"),
        updatedAt = json.optLong("updated_at"),
        finishedAt = json.optLong("finished_at"),
        turnCount = turns?.length() ?: 0,
        latestTurn = latestTurnJson?.toReplayTurnSummary(),
        recentTurns = recentTurns,
        recentEvents = recentEvents,
        recentArtifactGroups = recentArtifactGroups,
        finalMessage = json.optString("final_message"),
        finalTaskResult = json.optJSONObject("final_task_result").toReplayTaskResultPayload(),
        resume = buildReplayResumeSummary(latestTurnJson, liveResumeSnapshot),
        pendingSafetyConfirmation = liveResumeSnapshot?.safety?.pendingConfirmation?.summary.orEmpty(),
    )
}

internal fun buildReplayRecentTurnSummaries(
    turns: JSONArray?,
    limit: Int,
): List<ReplayTurnSummary> {
    if (turns == null || limit <= 0) return emptyList()
    val result = mutableListOf<ReplayTurnSummary>()
    for (index in turns.length() - 1 downTo 0) {
        val turnJson = turns.optJSONObject(index) ?: continue
        result += turnJson.toReplayTurnSummary()
        if (result.size >= limit) {
            break
        }
    }
    return result
}

internal fun buildReplayRecentEventSummaries(
    events: JSONArray?,
    limit: Int,
): List<ReplayEventSummary> {
    if (events == null || limit <= 0) return emptyList()
    val result = mutableListOf<ReplayEventSummary>()
    for (index in events.length() - 1 downTo 0) {
        val eventJson = events.optJSONObject(index) ?: continue
        result +=
            ReplayEventSummary(
                timestamp = eventJson.optLong("timestamp"),
                type = eventJson.optString("type"),
                message = eventJson.optString("message"),
                attributes = eventJson.optJSONObject("attributes").toReplayStringMap(),
            )
        if (result.size >= limit) {
            break
        }
    }
    return result
}

internal fun buildReplayRecentArtifactGroups(
    sessionId: String,
    turns: JSONArray?,
    turnLimit: Int,
    artifactLimitPerTurn: Int,
): List<ReplayTurnArtifactGroup> {
    if (turns == null || turnLimit <= 0 || artifactLimitPerTurn <= 0) return emptyList()
    val result = mutableListOf<ReplayTurnArtifactGroup>()
    for (index in turns.length() - 1 downTo 0) {
        val turnJson = turns.optJSONObject(index) ?: continue
        val group =
            buildReplayArtifactGroup(
                sessionId = sessionId,
                turnJson = turnJson,
                artifactLimit = artifactLimitPerTurn,
            ) ?: continue
        if (group.artifacts.isEmpty()) {
            continue
        }
        result += group
        if (result.size >= turnLimit) {
            break
        }
    }
    return result
}

internal fun buildReplayArtifactGroup(
    sessionId: String,
    turnJson: JSONObject,
    artifactLimit: Int,
): ReplayTurnArtifactGroup? {
    val turn = turnJson.optInt("turn", -1)
    val artifactRefs = turnJson.optJSONObject("artifact_refs") ?: return null
    val artifacts =
        replayArtifactRefEntries(artifactRefs)
            .take(artifactLimit)
            .map { (type, artifactId) ->
                val resolved = ArtifactStore.readArtifactRecord(sessionId, artifactId)
                ReplayArtifactSummary(
                    turn = turn,
                    artifactId = artifactId,
                    type = resolved?.type ?: type,
                    summary = resolved?.summary ?: "-",
                    previewLines = ArtifactStore.readArtifactPreviewLines(sessionId, artifactId),
                )
            }
    if (artifacts.isEmpty()) {
        return null
    }
    return ReplayTurnArtifactGroup(turn = turn, artifacts = artifacts)
}

internal fun latestReplayTurn(
    turns: JSONArray?,
): JSONObject? {
    if (turns == null || turns.length() <= 0) return null
    return turns.optJSONObject(turns.length() - 1)
}

internal fun buildReplayResumeSummary(
    latestTurnJson: JSONObject?,
    liveResumeSnapshot: SessionResumeSnapshot?,
): ReplayResumeSummary {
    if (liveResumeSnapshot != null) {
        return ReplayResumeSummary(
            active = liveResumeSnapshot.resumeContext.active,
            source = liveResumeSnapshot.resumeContext.source,
            resumeEvent = liveResumeSnapshot.resumeContext.resumeEvent,
            resumeHint = liveResumeSnapshot.resumeContext.resumeHint,
            resumedSubgoalId = liveResumeSnapshot.resumeContext.resumedSubgoalId,
            resumeAttempt = liveResumeSnapshot.resumeContext.resumeAttempt,
            userCorrection = liveResumeSnapshot.resumeContext.userCorrection,
            waitingForExternal = liveResumeSnapshot.externalWaitState != null,
            waitingEvent = liveResumeSnapshot.externalWaitState?.event.orEmpty(),
            suspendReason = liveResumeSnapshot.externalWaitState?.reason.orEmpty(),
            lastResumeDecision = liveResumeSnapshot.planningSnapshot.lastResumeDecision,
        )
    }
    if (latestTurnJson == null) {
        return ReplayResumeSummary()
    }
    return ReplayResumeSummary(
        active = latestTurnJson.optBoolean("resume_active"),
        source = latestTurnJson.optString("resume_source"),
        resumeEvent = latestTurnJson.optString("resume_event"),
        resumeHint = latestTurnJson.optString("resume_hint"),
        resumedSubgoalId = latestTurnJson.optString("resume_subgoal_id"),
        resumeAttempt = latestTurnJson.optInt("resume_attempt"),
        userCorrection = latestTurnJson.optString("resume_user_correction"),
        waitingForExternal = latestTurnJson.optBoolean("waiting_for_external"),
        waitingEvent = latestTurnJson.optString("waiting_for_event"),
        suspendReason = latestTurnJson.optString("suspend_reason"),
    )
}

internal fun JSONObject.toReplayTurnSummary(): ReplayTurnSummary =
    ReplayTurnSummary(
        turn = optInt("turn", -1),
        timestamp = optLong("timestamp"),
        observationSignature = optString("observation_signature"),
        pageState = optString("page_state"),
        packageName = optString("package_name"),
        actionLabel = optString("action"),
        result = optString("result"),
        keepRunning = optBoolean("keep_running"),
        planType = optString("plan_type"),
        planStage = optString("plan_stage"),
        currentSubgoalId = optString("current_subgoal_id"),
        nextObjective = optString("next_objective"),
        recoveryCategory = optString("recovery_category"),
        recoverySummary = optString("recovery_summary"),
        suggestedRecoveryAction = optString("suggested_recovery_action"),
        taskResult = optJSONObject("task_result").toReplayTaskResultPayload(),
    )

internal fun JSONObject?.toReplayTaskResultPayload(): TaskResultPayload? {
    if (this == null || length() == 0) return null
    return TaskResultPayload(
        intentType = optString("intent_type"),
        title = optString("title"),
        summary = optString("summary"),
        highlights = optJSONArray("highlights").toReplayStringList(),
        fields =
            buildList {
                val fields = optJSONArray("fields") ?: return@buildList
                for (index in 0 until fields.length()) {
                    val field = fields.optJSONObject(index) ?: continue
                    add(
                        TaskResultField(
                            key = field.optString("key"),
                            label = field.optString("label"),
                            value = field.optString("value"),
                        ),
                    )
                }
            },
    )
}

internal fun replayArtifactRefEntries(
    artifactRefs: JSONObject,
): List<Pair<String, String>> =
    listOf(
        "task_result_summary" to artifactRefs.optString("task_result_summary_artifact_id"),
        "execution_verification" to artifactRefs.optString("verification_artifact_id"),
        "execution_trace" to artifactRefs.optString("execution_trace_artifact_id"),
        "planner_decision" to artifactRefs.optString("planner_decision_artifact_id"),
        "planning_observation" to artifactRefs.optString("planning_observation_artifact_id"),
        "planning_screenshot" to artifactRefs.optString("screenshot_artifact_id"),
        "ui_xml_snapshot" to artifactRefs.optString("ui_xml_artifact_id"),
        "turn_failure" to artifactRefs.optString("failure_artifact_id"),
    ).filter { (_, artifactId) -> artifactId.isNotBlank() }

private fun JSONObject?.toReplayStringMap(): Map<String, String> {
    if (this == null || length() == 0) return emptyMap()
    val result = linkedMapOf<String, String>()
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        result[key] = optString(key)
    }
    return result
}

private fun JSONArray?.toReplayStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            optString(index)
                .takeIf { it.isNotBlank() }
                ?.let(::add)
        }
    }
}
