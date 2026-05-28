package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentTurnRecord
import com.lmx.xiaoxuanagent.agent.CrossAppMission
import com.lmx.xiaoxuanagent.agent.ResumeContext
import com.lmx.xiaoxuanagent.safety.PendingSafetyConfirmation
import com.lmx.xiaoxuanagent.taskprofile.TaskRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SessionResumeSnapshot(
    val resumable: Boolean = false,
    val running: Boolean = false,
    val planning: Boolean = false,
    val paused: Boolean = false,
    val sessionId: String = "",
    val entrySource: String = "app",
    val profileId: String = TaskRegistry.defaultProfile.id,
    val targetPackageName: String = TaskRegistry.defaultProfile.packageName,
    val task: String = "",
    val statusSnapshot: AgentUiStatusSnapshot = AgentUiStatus.resolve(AgentUiStatus.IDLE).toSnapshot(),
    val turns: Int = 0,
    val lastObservationSignature: String = "",
    val nextPlanEligibleAtMs: Long = 0L,
    val history: List<AgentTurnRecord> = emptyList(),
    val recentFingerprints: List<String> = emptyList(),
    val externalWaitState: RuntimeExternalWaitState? = null,
    val resumeContext: ResumeContext = ResumeContext(),
    val planningSnapshot: RuntimePlanningSnapshot = RuntimePlanningSnapshot(),
    val routeSnapshot: RuntimeRouteSnapshot? = null,
    val resultSnapshot: RuntimeResultSnapshot? = null,
    val takeoverSnapshot: RuntimeTakeoverSnapshot? = null,
    val safety: RuntimeSafetyState = RuntimeSafetyState(),
    val mission: CrossAppMission? = null,
    val lastTransition: String = "",
    val updatedAtMs: Long = 0L,
) {
    val status: String
        get() = statusSnapshot.code

    val statusModel: AgentUiStatusModel
        get() = statusSnapshot.resolveModel()
}

object SessionResumeStore {
    private const val RESUME_DIR = "runtime"
    private const val RESUME_FILE = "latest_session_resume.json"
    private const val RESUME_INDEX_FILE = "resumable_session_index.json"
    private const val RESUME_SESSION_DIR = "sessions"
    private val lock = Any()

    fun persist(state: SessionRuntimeState) {
        synchronized(lock) {
            val latestFile = snapshotFile() ?: return
            val sessionId = state.session.sessionId
            if (!shouldPersist(state)) {
                if (sessionId.isNotBlank()) {
                    clearSessionUnlocked(sessionId)
                } else {
                    clearUnlocked(latestFile)
                }
                return
            }
            val snapshot = state.toSnapshot()
            latestFile.parentFile?.mkdirs()
            latestFile.writeText(snapshot.toJson().toString(2))
            sessionSnapshotFile(sessionId)?.let { file ->
                file.parentFile?.mkdirs()
                file.writeText(snapshot.toJson().toString(2))
            }
            writeIndexUnlocked(
                latestSessionId = sessionId,
                sessionIds = listOf(sessionId) + readIndexUnlocked().filterNot { it == sessionId },
            )
        }
    }

    fun readLatestSnapshot(): SessionResumeSnapshot? =
        synchronized(lock) {
            val latestSessionId = readLatestSessionIdUnlocked()
            if (latestSessionId.isNotBlank()) {
                readSessionSnapshotUnlocked(latestSessionId)?.let { return@synchronized it }
            }
            val file = snapshotFile() ?: return null
            if (!file.exists()) return null
            runCatching {
                jsonToSnapshot(JSONObject(file.readText()))
            }.getOrElse {
                clearUnlocked(file)
                null
            }
        }

    fun clear() {
        synchronized(lock) {
            snapshotFile()?.let(::clearUnlocked)
            indexFile()?.let(::clearUnlocked)
            sessionSnapshotDirectory()?.deleteRecursively()
        }
    }

    fun readSessionSnapshot(
        sessionId: String,
    ): SessionResumeSnapshot? =
        synchronized(lock) {
            readSessionSnapshotUnlocked(sessionId)
        }

    fun readResumableSnapshots(
        limit: Int = 8,
    ): List<SessionResumeSnapshot> =
        synchronized(lock) {
            readIndexUnlocked()
                .mapNotNull(::readSessionSnapshotUnlocked)
                .sortedByDescending { it.updatedAtMs }
                .take(limit)
        }

    fun clearSession(
        sessionId: String,
    ) {
        synchronized(lock) {
            clearSessionUnlocked(sessionId)
        }
    }

    fun readSessionJson(
        sessionId: String,
    ): JSONObject? =
        synchronized(lock) {
            val file = sessionSnapshotFile(sessionId) ?: return@synchronized null
            if (!file.exists()) return@synchronized null
            runCatching { JSONObject(file.readText()) }.getOrNull()
        }

    fun importSnapshot(
        snapshot: SessionResumeSnapshot,
        markLatest: Boolean = true,
    ) {
        if (snapshot.sessionId.isBlank()) return
        synchronized(lock) {
            sessionSnapshotFile(snapshot.sessionId)?.let { file ->
                file.parentFile?.mkdirs()
                file.writeText(snapshot.toJson().toString(2))
            }
            if (markLatest) {
                snapshotFile()?.let { file ->
                    file.parentFile?.mkdirs()
                    file.writeText(snapshot.toJson().toString(2))
                }
            }
            writeIndexUnlocked(
                latestSessionId =
                    if (markLatest) {
                        snapshot.sessionId
                    } else {
                        readLatestSessionIdUnlocked().ifBlank { snapshot.sessionId }
                    },
                sessionIds = listOf(snapshot.sessionId) + readIndexUnlocked().filterNot { it == snapshot.sessionId },
            )
        }
    }

    fun importSnapshotJson(
        json: JSONObject,
        markLatest: Boolean = true,
    ) {
        importSnapshot(jsonToSnapshot(json), markLatest = markLatest)
    }

    private fun shouldPersist(state: SessionRuntimeState): Boolean {
        val session = state.session
        return session.resolveSemantics(state.safety).resumable
    }

    private fun snapshotFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, RESUME_DIR), RESUME_FILE)
    }

    private fun indexFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, RESUME_DIR), RESUME_INDEX_FILE)
    }

    private fun sessionSnapshotDirectory(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, RESUME_DIR), RESUME_SESSION_DIR)
    }

    private fun sessionSnapshotFile(
        sessionId: String,
    ): File? =
        sessionSnapshotDirectory()?.takeIf { sessionId.isNotBlank() }?.let { dir ->
            File(dir, "$sessionId.json")
        }

    private fun clearUnlocked(file: File) {
        if (file.exists()) {
            file.delete()
        }
    }

    private fun clearSessionUnlocked(
        sessionId: String,
    ) {
        if (sessionId.isBlank()) return
        sessionSnapshotFile(sessionId)?.let(::clearUnlocked)
        val latestId = readLatestSessionIdUnlocked()
        if (latestId == sessionId) {
            snapshotFile()?.let(::clearUnlocked)
        }
        writeIndexUnlocked(
            latestSessionId =
                readIndexUnlocked()
                    .filterNot { it == sessionId }
                    .firstOrNull()
                    .orEmpty(),
            sessionIds = readIndexUnlocked().filterNot { it == sessionId },
        )
    }

    private fun readSessionSnapshotUnlocked(
        sessionId: String,
    ): SessionResumeSnapshot? {
        if (sessionId.isBlank()) return null
        val file = sessionSnapshotFile(sessionId) ?: return null
        if (!file.exists()) return null
        return runCatching {
            jsonToSnapshot(JSONObject(file.readText()))
        }.getOrElse {
            clearSessionUnlocked(sessionId)
            null
        }
    }

    private fun readLatestSessionIdUnlocked(): String {
        val file = indexFile() ?: return ""
        if (!file.exists()) return ""
        return runCatching {
            JSONObject(file.readText()).optString("latest_session_id")
        }.getOrDefault("")
    }

    private fun readIndexUnlocked(): List<String> {
        val file = indexFile() ?: return emptyList()
        if (!file.exists()) return emptyList()
        return runCatching {
            JSONObject(file.readText()).optJSONArray("session_ids").toStringList()
        }.getOrDefault(emptyList())
    }

    private fun writeIndexUnlocked(
        latestSessionId: String,
        sessionIds: List<String>,
    ) {
        val file = indexFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put("latest_session_id", latestSessionId)
                put("session_ids", JSONArray(sessionIds.distinct()))
            }.toString(2),
        )
    }

    private fun SessionRuntimeState.toSnapshot(): SessionResumeSnapshot =
        SessionResumeSnapshot(
            resumable = shouldPersist(this),
            running = session.running,
            planning = session.planning,
            paused = session.paused,
            sessionId = session.sessionId,
            entrySource = session.entrySource,
            profileId = session.profileId,
            targetPackageName = session.targetPackageName,
            task = session.task,
            statusSnapshot = session.statusSnapshot,
            turns = session.turns,
            lastObservationSignature = session.lastObservationSignature,
            nextPlanEligibleAtMs = session.nextPlanEligibleAtMs,
            history = session.history,
            recentFingerprints = session.recentFingerprints,
            externalWaitState = session.externalWaitState,
            resumeContext = session.resumeContext,
            planningSnapshot = session.planningSnapshot,
            routeSnapshot = routeSnapshot,
            resultSnapshot = resultSnapshot,
            takeoverSnapshot = takeoverSnapshot,
            safety = safety,
            mission = session.mission,
            lastTransition = lastTransition,
            updatedAtMs = updatedAtMs,
        )

    private fun SessionResumeSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("resumable", resumable)
            put("running", running)
            put("planning", planning)
            put("paused", paused)
            put("session_id", sessionId)
            put("entry_source", entrySource)
            put("profile_id", profileId)
            put("target_package_name", targetPackageName)
            put("task", task)
            put("status", status)
            put("status_snapshot", statusSnapshot.toJson())
            put("turns", turns)
            put("last_observation_signature", lastObservationSignature)
            put("next_plan_eligible_at_ms", nextPlanEligibleAtMs)
            put(
                "history",
                JSONArray().apply {
                    history.forEach { put(it.toJson()) }
                },
            )
            put("recent_fingerprints", JSONArray(recentFingerprints))
            put("external_wait_state", externalWaitState?.toJson() ?: JSONObject())
            put("resume_context", resumeContext.toJson())
            put("planning_snapshot", planningSnapshot.toJson())
            put("route_snapshot", routeSnapshot?.toJson() ?: JSONObject())
            put("result_snapshot", resultSnapshot?.toJson() ?: JSONObject())
            put("takeover_snapshot", takeoverSnapshot?.toJson() ?: JSONObject())
            put("safety", safety.toJson())
            put("mission", mission?.toJson() ?: JSONObject())
            put("last_transition", lastTransition)
            put("updated_at_ms", updatedAtMs)
        }

    private fun jsonToSnapshot(json: JSONObject): SessionResumeSnapshot =
        SessionResumeSnapshot(
            resumable = json.optBoolean("resumable"),
            running = json.optBoolean("running"),
            planning = json.optBoolean("planning"),
            paused = json.optBoolean("paused"),
            sessionId = json.optString("session_id"),
            entrySource = json.optString("entry_source", "app"),
            profileId = json.optString("profile_id", TaskRegistry.defaultProfile.id),
            targetPackageName = json.optString("target_package_name", TaskRegistry.defaultProfile.packageName),
            task = json.optString("task"),
            statusSnapshot = json.optJSONObject("status_snapshot").toAgentUiStatusSnapshot(json.optString("status", AgentUiStatus.IDLE)),
            turns = json.optInt("turns"),
            lastObservationSignature = json.optString("last_observation_signature"),
            nextPlanEligibleAtMs = json.optLong("next_plan_eligible_at_ms"),
            history = json.optJSONArray("history").toTurnHistory(),
            recentFingerprints = json.optJSONArray("recent_fingerprints").toStringList(),
            externalWaitState = json.optJSONObject("external_wait_state").toExternalWaitState(),
            resumeContext = json.optJSONObject("resume_context").toResumeContext(),
            planningSnapshot = json.optJSONObject("planning_snapshot").toPlanningSnapshot(),
            routeSnapshot = json.optJSONObject("route_snapshot").toRouteSnapshot(),
            resultSnapshot = json.optJSONObject("result_snapshot").toResultSnapshot(),
            takeoverSnapshot = json.optJSONObject("takeover_snapshot").toTakeoverSnapshot(),
            safety = json.optJSONObject("safety").toSafetyState(),
            mission = json.optJSONObject("mission").toCrossAppMission(),
            lastTransition = json.optString("last_transition"),
            updatedAtMs = json.optLong("updated_at_ms"),
        )

    private fun AgentTurnRecord.toJson(): JSONObject =
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

    private fun ResumeContext.toJson(): JSONObject =
        JSONObject().apply {
            put("active", active)
            put("source", source)
            put("resume_event", resumeEvent)
            put("resume_hint", resumeHint)
            put("resumed_subgoal_id", resumedSubgoalId)
            put("resume_attempt", resumeAttempt)
            put("user_correction", userCorrection)
        }

    private fun RuntimeExternalWaitState.toJson(): JSONObject =
        JSONObject().apply {
            put("event", event)
            put("reason", reason)
            put("subgoal_id", subgoalId)
            put("signature", signature)
            put("entered_at_ms", enteredAtMs)
        }

    private fun RuntimePlanningSnapshot.toJson(): JSONObject =
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

    private fun RuntimeResumeDecisionSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("channel", channel.name)
            put("phase", phase.name)
            put("stage", stage)
            put("action_label", actionLabel)
            put("policy", policy.wireName)
            put("recovery_category", recoveryCategory)
        }

    private fun RuntimeRouteSnapshot.toJson(): JSONObject =
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

    private fun RuntimeResultSnapshot.toJson(): JSONObject =
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

    private fun RuntimeTakeoverSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("latest_takeover_type", latestTakeoverType)
            put("latest_takeover_reason", latestTakeoverReason)
            put("latest_takeover_resume_hint", latestTakeoverResumeHint)
            put("latest_takeover_correction", latestTakeoverCorrection)
        }

    private fun RuntimeSafetyState.toJson(): JSONObject =
        JSONObject().apply {
            put("mode", mode.name)
            put("pending_confirmation", pendingConfirmation?.toJson() ?: JSONObject())
            put("granted_approval_key", grantedApprovalKey.orEmpty())
        }

    private fun PendingSafetyConfirmation.toJson(): JSONObject =
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
        }

    private fun JSONArray?.toTurnHistory(): List<AgentTurnRecord> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                val json = optJSONObject(index) ?: continue
                add(
                    AgentTurnRecord(
                        observationSignature = json.optString("observation_signature"),
                        pageState = json.optString("page_state"),
                        action = json.optString("action"),
                        result = json.optString("result"),
                        decisionReason = json.optString("decision_reason"),
                        recoveryCategory = json.optString("recovery_category"),
                        recoverySummary = json.optString("recovery_summary"),
                        suggestedRecoveryAction = json.optString("suggested_recovery_action"),
                    ),
                )
            }
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                val value = optString(index)
                if (value.isNotBlank()) {
                    add(value)
                }
            }
        }
    }

    private fun JSONObject?.toResumeContext(): ResumeContext {
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

    private fun JSONObject?.toExternalWaitState(): RuntimeExternalWaitState? {
        if (this == null || length() == 0) return null
        return RuntimeExternalWaitState(
            event = optString("event"),
            reason = optString("reason"),
            subgoalId = optString("subgoal_id"),
            signature = optString("signature"),
            enteredAtMs = optLong("entered_at_ms"),
        )
    }

    private fun JSONObject?.toPlanningSnapshot(): RuntimePlanningSnapshot {
        if (this == null || length() == 0) return RuntimePlanningSnapshot()
        return RuntimePlanningSnapshot(
            planType = optString("plan_type"),
            currentPlanStage = optString("current_plan_stage"),
            currentSubgoalId = optString("current_subgoal_id"),
            nextObjective = optString("next_objective"),
            activeSkillTitles = optJSONArray("active_skill_titles").toStringList(),
            lastPlannerAction = optString("last_planner_action"),
            lastPlannerRaw = optString("last_planner_raw"),
            lastResumeDecision = optJSONObject("last_resume_decision").toResumeDecisionSnapshot(),
        )
    }

    private fun JSONObject?.toResumeDecisionSnapshot(): RuntimeResumeDecisionSnapshot {
        if (this == null || length() == 0) return RuntimeResumeDecisionSnapshot()
        return RuntimeResumeDecisionSnapshot(
            channel =
                runCatching {
                    RuntimeResumeDecisionChannel.valueOf(optString("channel", RuntimeResumeDecisionChannel.NONE.name))
                }.getOrDefault(RuntimeResumeDecisionChannel.NONE),
            phase =
                runCatching {
                    RuntimeResumeDecisionPhase.valueOf(optString("phase", RuntimeResumeDecisionPhase.NONE.name))
                }.getOrDefault(RuntimeResumeDecisionPhase.NONE),
            stage = optString("stage"),
            actionLabel = optString("action_label"),
            policy = RuntimeResumePolicy.fromWireName(optString("policy")),
            recoveryCategory = optString("recovery_category"),
        )
    }

    private fun JSONObject?.toRouteSnapshot(): RuntimeRouteSnapshot? {
        if (this == null || length() == 0) return null
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

    private fun JSONObject?.toResultSnapshot(): RuntimeResultSnapshot? {
        if (this == null || length() == 0) return null
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

    private fun JSONObject?.toTakeoverSnapshot(): RuntimeTakeoverSnapshot? {
        if (this == null || length() == 0) return null
        return RuntimeTakeoverSnapshot(
            latestTakeoverType = optString("latest_takeover_type"),
            latestTakeoverReason = optString("latest_takeover_reason"),
            latestTakeoverResumeHint = optString("latest_takeover_resume_hint"),
            latestTakeoverCorrection = optString("latest_takeover_correction"),
        )
    }

    private fun JSONObject?.toSafetyState(): RuntimeSafetyState {
        if (this == null || length() == 0) return RuntimeSafetyState()
        val mode =
            runCatching {
                RuntimeSafetyMode.valueOf(optString("mode", RuntimeSafetyMode.IDLE.name))
            }.getOrElse {
                RuntimeSafetyMode.IDLE
            }
        return RuntimeSafetyState(
            mode = mode,
            pendingConfirmation = optJSONObject("pending_confirmation").toPendingConfirmation(),
            grantedApprovalKey = optString("granted_approval_key").ifBlank { null },
        )
    }

    private fun JSONObject?.toPendingConfirmation(): PendingSafetyConfirmation? {
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
        )
    }
}
