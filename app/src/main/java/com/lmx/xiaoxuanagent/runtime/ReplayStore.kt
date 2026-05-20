package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.RecoveryDiagnosis
import com.lmx.xiaoxuanagent.agent.SkillContext
import com.lmx.xiaoxuanagent.agent.TaskPlanState
import com.lmx.xiaoxuanagent.agent.TaskResultField
import com.lmx.xiaoxuanagent.agent.TaskResultPayload
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.TaskIntentParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ReplayArtifactSummary(
    val turn: Int,
    val artifactId: String,
    val type: String,
    val summary: String,
    val previewLines: List<String> = emptyList(),
)

data class ReplayTurnArtifactGroup(
    val turn: Int,
    val artifacts: List<ReplayArtifactSummary>,
)

data class ReplayEventSummary(
    val timestamp: Long,
    val type: String,
    val message: String,
    val attributes: Map<String, String> = emptyMap(),
)

data class ReplayTurnSummary(
    val turn: Int,
    val timestamp: Long,
    val observationSignature: String,
    val pageState: String,
    val packageName: String,
    val actionLabel: String,
    val result: String,
    val keepRunning: Boolean,
    val planType: String,
    val planStage: String,
    val currentSubgoalId: String,
    val nextObjective: String,
    val recoveryCategory: String,
    val recoverySummary: String,
    val suggestedRecoveryAction: String,
    val taskResult: TaskResultPayload? = null,
)

data class ReplayResumeSummary(
    val active: Boolean = false,
    val source: String = "",
    val resumeEvent: String = "",
    val resumeHint: String = "",
    val resumedSubgoalId: String = "",
    val resumeAttempt: Int = 0,
    val userCorrection: String = "",
    val waitingForExternal: Boolean = false,
    val waitingEvent: String = "",
    val suspendReason: String = "",
    val lastResumeDecision: RuntimeResumeDecisionSnapshot = RuntimeResumeDecisionSnapshot(),
)

data class ReplaySessionSnapshot(
    val sessionId: String,
    val profileId: String,
    val targetPackageName: String,
    val task: String,
    val taskIntent: String,
    val entrySource: String,
    val statusSnapshot: AgentUiStatusSnapshot = AgentUiStatus.resolve(AgentUiStatus.IDLE).toSnapshot(),
    val routeReason: String,
    val routePolicyTag: String,
    val routeSelectedProfileId: String,
    val routeFallbackReason: String,
    val routeMemoryHints: List<String>,
    val startedAt: Long,
    val updatedAt: Long,
    val finishedAt: Long,
    val turnCount: Int,
    val latestTurn: ReplayTurnSummary? = null,
    val recentTurns: List<ReplayTurnSummary> = emptyList(),
    val recentEvents: List<ReplayEventSummary> = emptyList(),
    val recentArtifactGroups: List<ReplayTurnArtifactGroup> = emptyList(),
    val finalMessage: String = "",
    val finalTaskResult: TaskResultPayload? = null,
    val resume: ReplayResumeSummary = ReplayResumeSummary(),
    val pendingSafetyConfirmation: String = "",
) {
    val status: String
        get() = statusSnapshot.code

    val statusModel: AgentUiStatusModel
        get() = statusSnapshot.resolveModel()

    fun summaryLine(): String {
        val resultTitle = finalTaskResult?.title?.take(24).orEmpty().ifBlank { "-" }
        val resultSummary =
            finalTaskResult?.summary?.take(48).orEmpty().ifBlank {
                finalMessage.take(48).ifBlank { latestTurn?.result?.take(48).orEmpty().ifBlank { "-" } }
            }
        return "$status | $task | $resultTitle | $resultSummary"
    }
}

object ReplayStore {
    private const val REPLAY_DIR = "replays"
    private val lock = Any()

    fun startSession(
        sessionId: String,
        profileId: String,
        targetPackageName: String,
        task: String,
        entrySource: String = "app",
        routeReason: String,
        routePolicyTag: String = "",
        routeModelChoiceProfileId: String = "",
        routeSelectedProfileId: String = "",
        routeFallbackReason: String = "",
        routeMemoryHints: List<String> = emptyList(),
        routeModelRaw: String = "",
    ) {
        val now = System.currentTimeMillis()
        val constraints = TaskIntentParser.parse(task)
        writeSession(sessionId) {
            JSONObject().apply {
                put("session_id", sessionId)
                put("profile_id", profileId)
                put("target_package_name", targetPackageName)
                put("task", task)
                put("task_intent", constraints.intentType.name.lowercase())
                put("entry_source", entrySource)
                put("route_reason", routeReason)
                put("route_policy_tag", routePolicyTag)
                put("route_model_choice_profile_id", routeModelChoiceProfileId)
                put("route_selected_profile_id", routeSelectedProfileId)
                put("route_fallback_reason", routeFallbackReason)
                put("route_memory_hints", JSONArray(routeMemoryHints))
                put("route_model_raw", routeModelRaw)
                put("status", "RUNNING")
                put("status_snapshot", AgentUiStatus.resolve(AgentUiStatus.RUNNING).toSnapshot().toJson())
                put("started_at", now)
                put("updated_at", now)
                put("turns", JSONArray())
                put("events", JSONArray())
            }
        }
    }

    fun appendTurn(
        sessionId: String,
        turn: Int,
        observation: ScreenObservation,
        decision: AgentDecision,
        taskPlanState: TaskPlanState?,
        activeSkills: List<SkillContext> = emptyList(),
        result: String,
        taskResult: TaskResultPayload? = null,
        keepRunning: Boolean,
        recoveryDiagnosis: RecoveryDiagnosis? = null,
        suggestedRecoveryAction: String = "",
        artifactRefs: TurnArtifactRefs = TurnArtifactRefs(),
    ) {
        val now = System.currentTimeMillis()
        updateSession(sessionId) { json ->
            json.getJSONArray("turns").put(
                JSONObject().apply {
                    put("turn", turn)
                    put("timestamp", now)
                    put("observation_signature", observation.signature)
                    put("page_state", observation.pageState)
                    put("package_name", observation.packageName)
                    put("screen_summary", observation.screenSummary)
                    put("plan_type", taskPlanState?.planType.orEmpty())
                    put("plan_stage", taskPlanState?.currentStage.orEmpty())
                    put("current_subgoal_id", taskPlanState?.currentSubgoalId.orEmpty())
                    put("next_objective", taskPlanState?.nextObjective.orEmpty())
                    put("waiting_for_external", taskPlanState?.waitingForExternal ?: false)
                    put("waiting_for_event", taskPlanState?.waitingForEvent.orEmpty())
                    put("suspendable", taskPlanState?.suspendable ?: false)
                    put("suspend_reason", taskPlanState?.suspendReason.orEmpty())
                    put("orchestration_summary", taskPlanState?.orchestrationSummary.orEmpty())
                    put("resume_active", taskPlanState?.resumeContext?.active ?: false)
                    put("resume_source", taskPlanState?.resumeContext?.source.orEmpty())
                    put("resume_event", taskPlanState?.resumeContext?.resumeEvent.orEmpty())
                    put("resume_hint", taskPlanState?.resumeContext?.resumeHint.orEmpty())
                    put("resume_subgoal_id", taskPlanState?.resumeContext?.resumedSubgoalId.orEmpty())
                    put("resume_attempt", taskPlanState?.resumeContext?.resumeAttempt ?: 0)
                    put("resume_user_correction", taskPlanState?.resumeContext?.userCorrection.orEmpty())
                    put("active_skill_ids", JSONArray(activeSkills.map { it.id }))
                    put("active_skill_titles", JSONArray(activeSkills.map { it.title }))
                    put(
                        "wait_conditions",
                        JSONArray().apply {
                            taskPlanState?.waitConditions?.forEach { condition ->
                                put(
                                    JSONObject().apply {
                                        put("id", condition.id)
                                        put("title", condition.title)
                                        put("type", condition.type)
                                        put("status", condition.status)
                                        put("reason", condition.reason)
                                        put("resume_event", condition.resumeEvent)
                                        put("resume_hint", condition.resumeHint)
                                        put("blocking_node_id", condition.blockingNodeId)
                                        put("source_hints", JSONArray(condition.sourceHints))
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "task_stack",
                        JSONArray().apply {
                            taskPlanState?.taskStack?.forEach { frame ->
                                put(
                                    JSONObject().apply {
                                        put("id", frame.id)
                                        put("title", frame.title)
                                        put("status", frame.status)
                                        put("resume_hint", frame.resumeHint)
                                        put("frame_type", frame.frameType)
                                        put("depth", frame.depth)
                                        put("active", frame.active)
                                        put("blocking_reason", frame.blockingReason)
                                        put("child_ids", JSONArray(frame.childIds))
                                    },
                                )
                            }
                        },
                    )
                    put("top_texts", JSONArray(observation.topTexts))
                    put("action", decision.action.label)
                    put("reason", decision.reason)
                    put("raw_response", decision.rawResponse)
                    put("result", result)
                    put("task_result", taskResult?.toJson() ?: JSONObject())
                    put("keep_running", keepRunning)
                    put("recovery_category", recoveryDiagnosis?.category?.name.orEmpty())
                    put("recovery_summary", recoveryDiagnosis?.summary.orEmpty())
                    put("suggested_recovery_action", suggestedRecoveryAction)
                    put(
                        "artifact_refs",
                        JSONObject().apply {
                            put("planning_observation_artifact_id", artifactRefs.planningObservationArtifactId)
                            put("ui_xml_artifact_id", artifactRefs.uiXmlArtifactId)
                            put("screenshot_artifact_id", artifactRefs.screenshotArtifactId)
                            put("planner_decision_artifact_id", artifactRefs.plannerDecisionArtifactId)
                            put("execution_trace_artifact_id", artifactRefs.executionTraceArtifactId)
                            put("task_result_summary_artifact_id", artifactRefs.taskResultSummaryArtifactId)
                            put("verification_artifact_id", artifactRefs.verificationArtifactId)
                            put("failure_artifact_id", artifactRefs.failureArtifactId)
                        },
                    )
                },
            )
            json.put("updated_at", now)
        }
    }

    fun appendEvent(
        sessionId: String,
        type: String,
        message: String,
        attributes: Map<String, Any?> = emptyMap(),
    ) {
        val now = System.currentTimeMillis()
        updateSession(sessionId) { json ->
            json.getJSONArray("events").put(
                JSONObject().apply {
                    put("timestamp", now)
                    put("type", type)
                    put("message", message)
                    put(
                        "attributes",
                        JSONObject().apply {
                            attributes.forEach { (key, value) ->
                                when (value) {
                                    null -> Unit
                                    is Boolean -> put(key, value)
                                    is Int -> put(key, value)
                                    is Long -> put(key, value)
                                    is Double -> put(key, value)
                                    else -> put(key, value.toString())
                                }
                            }
                        },
                    )
                },
            )
            json.put("updated_at", now)
        }
    }

    fun finishSession(
        sessionId: String,
        status: String,
        finalMessage: String,
        taskResult: TaskResultPayload? = null,
    ) {
        val now = System.currentTimeMillis()
        updateSession(sessionId) { json ->
            json.put("status", status)
            json.put("status_snapshot", AgentUiStatus.resolve(status).toSnapshot().toJson())
            json.put("final_message", finalMessage)
            json.put("final_task_result", taskResult?.toJson() ?: JSONObject())
            json.put("finished_at", now)
            json.put("updated_at", now)
        }
        val file = sessionFile(sessionId) ?: return
        if (file.exists()) {
            val json = JSONObject(file.readText())
            ReplayStoreAnalyticsSupport.recordHarnessReplaySummary(
                sessionId = sessionId,
                status = status,
                finalMessage = finalMessage,
                json = json,
            )
        }
    }

    fun sessionFileName(sessionId: String): String = "$sessionId.json"

    fun recentSessionSummaries(limit: Int = 4): List<String> {
        val context = AppRuntimeContext.get() ?: return emptyList()
        val dir = File(context.filesDir, REPLAY_DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            ?.mapNotNull { file ->
                runCatching {
                    val json = JSONObject(file.readText())
                    buildReplaySessionSnapshot(
                        sessionId = json.optString("session_id").ifBlank { file.nameWithoutExtension },
                        json = json,
                        recentTurnLimit = 0,
                        recentEventLimit = 0,
                        artifactTurnLimit = 0,
                        artifactLimitPerTurn = 0,
                    ).summaryLine()
                }.getOrNull()
            }
            .orEmpty()
    }

    fun listSessionIds(limit: Int = 50): List<String> {
        val context = AppRuntimeContext.get() ?: return emptyList()
        val dir = File(context.filesDir, REPLAY_DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            ?.map { it.nameWithoutExtension }
            .orEmpty()
    }

    fun readRecentSessionSnapshots(limit: Int = 8): List<ReplaySessionSnapshot> {
        if (limit <= 0) return emptyList()
        val context = AppRuntimeContext.get() ?: return emptyList()
        val dir = File(context.filesDir, REPLAY_DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            ?.mapNotNull { file ->
                runCatching {
                    val json = JSONObject(file.readText())
                    buildReplaySessionSnapshot(
                        sessionId = json.optString("session_id").ifBlank { file.nameWithoutExtension },
                        json = json,
                        recentTurnLimit = 2,
                        recentEventLimit = 4,
                        artifactTurnLimit = 2,
                        artifactLimitPerTurn = 4,
                    )
                }.getOrNull()
            }
            .orEmpty()
    }

    fun readSessionSnapshot(
        sessionId: String,
        recentTurnLimit: Int = 3,
        recentEventLimit: Int = 6,
        artifactTurnLimit: Int = 3,
        artifactLimitPerTurn: Int = 6,
    ): ReplaySessionSnapshot? {
        if (sessionId.isBlank()) {
            return null
        }
        return synchronized(lock) {
            val file = sessionFile(sessionId) ?: return@synchronized null
            if (!file.exists()) {
                return@synchronized null
            }
            buildReplaySessionSnapshot(
                sessionId = sessionId,
                json = JSONObject(file.readText()),
                recentTurnLimit = recentTurnLimit,
                recentEventLimit = recentEventLimit,
                artifactTurnLimit = artifactTurnLimit,
                artifactLimitPerTurn = artifactLimitPerTurn,
            )
        }
    }

    fun importSessionSnapshotJson(
        sessionId: String,
        json: JSONObject,
    ) {
        if (sessionId.isBlank()) return
        synchronized(lock) {
            val file = sessionFile(sessionId) ?: return
            file.writeText(json.toString(2))
        }
    }

    fun readSessionJson(
        sessionId: String,
    ): JSONObject? {
        if (sessionId.isBlank()) return null
        return synchronized(lock) {
            val file = sessionFile(sessionId) ?: return@synchronized null
            if (!file.exists()) return@synchronized null
            runCatching { JSONObject(file.readText()) }.getOrNull()
        }
    }

    fun readTurnArtifactGroup(
        sessionId: String,
        turn: Int,
        artifactLimit: Int = 6,
    ): ReplayTurnArtifactGroup? {
        if (sessionId.isBlank() || turn < 0 || artifactLimit <= 0) {
            return null
        }
        return synchronized(lock) {
            val file = sessionFile(sessionId) ?: return@synchronized null
            if (!file.exists()) {
                return@synchronized null
            }
            val turns = JSONObject(file.readText()).optJSONArray("turns") ?: return@synchronized null
            for (index in turns.length() - 1 downTo 0) {
                val turnJson = turns.optJSONObject(index) ?: continue
                if (turnJson.optInt("turn", -1) != turn) continue
                return@synchronized buildReplayArtifactGroup(
                    sessionId = sessionId,
                    turnJson = turnJson,
                    artifactLimit = artifactLimit,
                )
            }
            null
        }
    }

    fun readRecentTurnArtifactSummaries(
        sessionId: String,
        limit: Int = 8,
    ): List<ReplayArtifactSummary> {
        if (sessionId.isBlank() || limit <= 0) {
            return emptyList()
        }
        return synchronized(lock) {
            val file = sessionFile(sessionId) ?: return@synchronized emptyList()
            if (!file.exists()) {
                return@synchronized emptyList()
            }
            val turns = JSONObject(file.readText()).optJSONArray("turns") ?: return@synchronized emptyList()
            val result = mutableListOf<ReplayArtifactSummary>()
            for (index in turns.length() - 1 downTo 0) {
                val turnJson = turns.optJSONObject(index) ?: continue
                val turn = turnJson.optInt("turn", -1)
                val artifactRefs = turnJson.optJSONObject("artifact_refs") ?: continue
                replayArtifactRefEntries(artifactRefs).forEach { (type, artifactId) ->
                    if (result.size >= limit) {
                        return@synchronized result
                    }
                    val resolved = ArtifactStore.readArtifactRecord(sessionId, artifactId)
                    result +=
                        ReplayArtifactSummary(
                            turn = turn,
                            artifactId = artifactId,
                            type = resolved?.type ?: type,
                            summary = resolved?.summary ?: "-",
                            previewLines = ArtifactStore.readArtifactPreviewLines(sessionId, artifactId),
                        )
                }
                if (result.size >= limit) {
                    return@synchronized result
                }
            }
            result
        }
    }

    fun readRecentTurnArtifactGroups(
        sessionId: String,
        turnLimit: Int = 3,
        artifactLimitPerTurn: Int = 6,
    ): List<ReplayTurnArtifactGroup> {
        if (sessionId.isBlank() || turnLimit <= 0 || artifactLimitPerTurn <= 0) {
            return emptyList()
        }
        return synchronized(lock) {
            val file = sessionFile(sessionId) ?: return@synchronized emptyList()
            if (!file.exists()) {
                return@synchronized emptyList()
            }
            val turns = JSONObject(file.readText()).optJSONArray("turns") ?: return@synchronized emptyList()
            val groups = mutableListOf<ReplayTurnArtifactGroup>()
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
                groups += group
                if (groups.size >= turnLimit) {
                    return@synchronized groups
                }
            }
            groups
        }
    }

    private fun updateSession(
        sessionId: String,
        updater: (JSONObject) -> Unit,
    ) {
        synchronized(lock) {
            val file = sessionFile(sessionId) ?: return
            val json =
                if (file.exists()) {
                    JSONObject(file.readText())
                } else {
                    JSONObject()
                }
            updater(json)
            file.writeText(json.toString(2))
        }
    }

    private fun writeSession(
        sessionId: String,
        creator: () -> JSONObject,
    ) {
        synchronized(lock) {
            val file = sessionFile(sessionId) ?: return
            file.writeText(creator().toString(2))
        }
    }

    private fun sessionFile(sessionId: String): File? {
        val context = AppRuntimeContext.get() ?: return null
        val dir = File(context.filesDir, REPLAY_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "$sessionId.json")
    }

    private fun TaskResultPayload.toJson(): JSONObject =
        JSONObject().apply {
            put("intent_type", intentType)
            put("title", title)
            put("summary", summary)
            put("highlights", JSONArray(highlights))
            put(
                "fields",
                JSONArray().apply {
                    fields.forEach { field ->
                        put(
                            JSONObject().apply {
                                put("key", field.key)
                                put("label", field.label)
                                put("value", field.value)
                            },
                        )
                    }
                },
            )
        }
}
