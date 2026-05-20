package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.runtime.AgentUiStatus
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.toAgentUiStatusSnapshot
import com.lmx.xiaoxuanagent.runtime.toJson
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

object AssistantOsStore {
    private const val OS_DIR = "assistant_os"
    private const val OS_FILE = "control_center.json"
    private const val MAX_ENTRY_RECORDS = 24
    private val lock = Any()
    @Volatile
    private var hydrated = false
    private var snapshot = AssistantOsSnapshot()
    private val snapshotFlow = MutableStateFlow(snapshot)

    fun read(): AssistantOsSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshot
        }

    fun observe(): StateFlow<AssistantOsSnapshot> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshotFlow.asStateFlow()
        }

    fun update(
        reducer: (AssistantOsSnapshot) -> AssistantOsSnapshot,
    ): AssistantOsSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val current = snapshot
            val reduced = reducer(current)
            if (isEquivalent(current, reduced)) {
                return@synchronized current
            }
            val next = reduced.copy(updatedAtMs = System.currentTimeMillis())
            writeUnlocked(next)
            next
        }

    internal fun isEquivalent(
        left: AssistantOsSnapshot,
        right: AssistantOsSnapshot,
    ): Boolean = left.semanticComparable() == right.semanticComparable()

    private fun readUnlocked(): AssistantOsSnapshot {
        hydrateIfNeededUnlocked()
        return snapshot
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = snapshotFile()
        snapshot =
            if (file == null || !file.exists()) {
                AssistantOsSnapshot()
            } else {
                runCatching {
                    jsonToSnapshot(JSONObject(file.readText()))
                }.getOrElse {
                    AssistantOsSnapshot()
                }
            }
        snapshotFlow.value = snapshot
    }

    private fun writeUnlocked(snapshot: AssistantOsSnapshot) {
        this.snapshot = snapshot
        snapshotFlow.value = snapshot
        val file = snapshotFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(snapshot.toJson().toString(2))
    }

    private fun snapshotFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, OS_DIR), OS_FILE)
    }

    private fun AssistantOsSnapshot.semanticComparable(): AssistantOsSnapshot =
        copy(
            activeSession = activeSession.copy(updatedAtMs = 0L),
            sessionBacklog = sessionBacklog.map { it.copy(updatedAtMs = 0L) },
            failedSessions = failedSessions.map { it.copy(updatedAtMs = 0L) },
            approvalSessions = approvalSessions.map { it.copy(updatedAtMs = 0L) },
            health = health.copy(lastProjectionAtMs = 0L),
            updatedAtMs = 0L,
        )

    private fun AssistantOsSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("permission_mode", permissionMode.name)
            put("safety_mode", safetyMode.name)
            put(
                "feature_flags",
                JSONArray().apply {
                    featureFlags.forEach { state ->
                        put(
                            JSONObject().apply {
                                put("key", state.key.name)
                                put("enabled", state.enabled)
                            },
                        )
                    }
                },
            )
            put(
                "experiments",
                JSONArray().apply {
                    experiments.forEach { state ->
                        put(
                            JSONObject().apply {
                                put("key", state.key.name)
                                put("enabled", state.enabled)
                            },
                        )
                    }
                },
            )
            put(
                "recent_entries",
                JSONArray().apply {
                    recentEntries.take(MAX_ENTRY_RECORDS).forEach { record ->
                        put(
                            JSONObject().apply {
                                put("surface", record.surface.name)
                                put("action", record.action)
                                put("summary", record.summary)
                                put("created_at_ms", record.createdAtMs)
                            },
                        )
                    }
                },
            )
            put(
                "surfaces",
                JSONArray().apply {
                    surfaces.forEach { state ->
                        put(
                            JSONObject().apply {
                                put("surface", state.surface.name)
                                put("supported", state.supported)
                                put("enabled", state.enabled)
                                put("available", state.available)
                                put("summary", state.summary)
                            },
                        )
                    }
                },
            )
            put(
                "pending_actions",
                JSONArray().apply {
                    pendingActions.forEach { action ->
                        put(
                            JSONObject().apply {
                                put("type", action.type.name)
                                put("title", action.title)
                                put("summary", action.summary)
                                put("surface", action.surface.name)
                                put("session_id", action.sessionId)
                                put("created_at_ms", action.createdAtMs)
                            },
                        )
                    }
                },
            )
            put(
                "task_inbox",
                JSONArray().apply {
                    taskInbox.forEach { item ->
                        put(
                            JSONObject().apply {
                                put("id", item.id)
                                put("type", item.type.name)
                                put("title", item.title)
                                put("summary", item.summary)
                                put("session_id", item.sessionId)
                                put("status_code", item.statusCode)
                                put("source", item.source)
                                put("created_at_ms", item.createdAtMs)
                            },
                        )
                    }
                },
            )
            put(
                "active_session",
                JSONObject().apply {
                    put("session_id", activeSession.sessionId)
                    put("status_code", activeSession.statusCode)
                    put("status_snapshot", activeSession.statusSnapshot.toJson())
                    put("task", activeSession.task)
                    put("entry_source", activeSession.entrySource)
                    put("target_package_name", activeSession.targetPackageName)
                    put("route_reason", activeSession.routeReason)
                    put("summary", activeSession.summary)
                    put("resumable", activeSession.resumable)
                    put("awaiting_confirmation", activeSession.awaitingConfirmation)
                    put("target_app_return_eligible", activeSession.targetAppReturnEligible)
                    put("waiting_for_external", activeSession.waitingForExternal)
                    put("turn", activeSession.turn)
                    put("updated_at_ms", activeSession.updatedAtMs)
                },
            )
            put(
                "session_backlog",
                JSONArray().apply {
                    sessionBacklog.forEach { session ->
                        put(session.toJson())
                    }
                },
            )
            put(
                "failed_sessions",
                JSONArray().apply {
                    failedSessions.forEach { session ->
                        put(session.toJson())
                    }
                },
            )
            put(
                "approval_sessions",
                JSONArray().apply {
                    approvalSessions.forEach { session ->
                        put(session.toJson())
                    }
                },
            )
            put(
                "health",
                JSONObject().apply {
                    put("status", health.status)
                    put("summary", health.summary)
                    put("active_session_id", health.activeSessionId)
                    put("stale_runtime", health.staleRuntime)
                    put("backlog_count", health.backlogCount)
                    put("resumable_session_count", health.resumableSessionCount)
                    put("failed_session_count", health.failedSessionCount)
                    put("approval_session_count", health.approvalSessionCount)
                    put("due_trigger_count", health.dueTriggerCount)
                    put("last_projection_at_ms", health.lastProjectionAtMs)
                },
            )
            put("updated_at_ms", updatedAtMs)
        }

    private fun jsonToSnapshot(json: JSONObject): AssistantOsSnapshot =
        AssistantOsSnapshot(
            permissionMode =
                runCatching {
                    AssistantPermissionMode.valueOf(
                        json.optString("permission_mode", AssistantPermissionMode.ASSISTED.name),
                    )
                }.getOrDefault(AssistantPermissionMode.ASSISTED),
            safetyMode =
                runCatching {
                    AssistantSafetyMode.valueOf(
                        json.optString("safety_mode", AssistantSafetyMode.BALANCED.name),
                    )
                }.getOrDefault(AssistantSafetyMode.BALANCED),
            featureFlags = json.optJSONArray("feature_flags").toFeatureFlags(),
            experiments = json.optJSONArray("experiments").toExperiments(),
            recentEntries = json.optJSONArray("recent_entries").toEntryRecords(),
            surfaces = json.optJSONArray("surfaces").toSurfaceStates(),
            pendingActions = json.optJSONArray("pending_actions").toPendingActions(),
            taskInbox = json.optJSONArray("task_inbox").toTaskInboxItems(),
            activeSession = json.optJSONObject("active_session").toActiveSession(),
            sessionBacklog = json.optJSONArray("session_backlog").toSessionCards(),
            failedSessions = json.optJSONArray("failed_sessions").toSessionCards(),
            approvalSessions = json.optJSONArray("approval_sessions").toSessionCards(),
            health = json.optJSONObject("health").toHealthSnapshot(),
            updatedAtMs = json.optLong("updated_at_ms"),
        )

    private fun JSONArray?.toFeatureFlags(): List<AssistantFeatureFlagState> {
        if (this == null || length() == 0) {
            return AssistantFeatureFlagKey.entries.map { AssistantFeatureFlagState(it, it.defaultEnabled) }
        }
        val states = mutableMapOf<AssistantFeatureFlagKey, Boolean>()
        for (index in 0 until length()) {
            val json = optJSONObject(index) ?: continue
            val key =
                runCatching {
                    AssistantFeatureFlagKey.valueOf(json.optString("key"))
                }.getOrNull() ?: continue
            states[key] = json.optBoolean("enabled", key.defaultEnabled)
        }
        return AssistantFeatureFlagKey.entries.map { key ->
            AssistantFeatureFlagState(key, states[key] ?: key.defaultEnabled)
        }
    }

    private fun JSONArray?.toExperiments(): List<AssistantExperimentState> {
        if (this == null || length() == 0) {
            return AssistantExperimentKey.entries.map { AssistantExperimentState(it, it.defaultEnabled) }
        }
        val states = mutableMapOf<AssistantExperimentKey, Boolean>()
        for (index in 0 until length()) {
            val json = optJSONObject(index) ?: continue
            val key =
                runCatching {
                    AssistantExperimentKey.valueOf(json.optString("key"))
                }.getOrNull() ?: continue
            states[key] = json.optBoolean("enabled", key.defaultEnabled)
        }
        return AssistantExperimentKey.entries.map { key ->
            AssistantExperimentState(key, states[key] ?: key.defaultEnabled)
        }
    }

    private fun JSONArray?.toEntryRecords(): List<AssistantEntryRecord> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                val json = optJSONObject(index) ?: continue
                val surface =
                    runCatching {
                        AssistantEntrySurface.valueOf(json.optString("surface"))
                    }.getOrNull() ?: AssistantEntrySurface.SYSTEM
                add(
                    AssistantEntryRecord(
                        surface = surface,
                        action = json.optString("action"),
                        summary = json.optString("summary"),
                        createdAtMs = json.optLong("created_at_ms"),
                    ),
                )
            }
        }.take(MAX_ENTRY_RECORDS)
    }

    private fun JSONObject?.toActiveSession(): AssistantActiveSession {
        if (this == null || length() == 0) return AssistantActiveSession()
        return AssistantActiveSession(
            sessionId = optString("session_id"),
            statusSnapshot = optJSONObject("status_snapshot").toAgentUiStatusSnapshot(optString("status_code", AgentUiStatus.IDLE)),
            task = optString("task"),
            entrySource = optString("entry_source"),
            targetPackageName = optString("target_package_name"),
            routeReason = optString("route_reason"),
            summary = optString("summary"),
            resumable = optBoolean("resumable"),
            awaitingConfirmation = optBoolean("awaiting_confirmation"),
            targetAppReturnEligible = optBoolean("target_app_return_eligible"),
            waitingForExternal = optBoolean("waiting_for_external"),
            turn = optInt("turn"),
            updatedAtMs = optLong("updated_at_ms"),
        )
    }

    private fun JSONArray?.toSurfaceStates(): List<AssistantSurfaceState> {
        if (this == null || length() == 0) {
            return AssistantEntrySurface.entries.map { AssistantSurfaceState(surface = it) }
        }
        val states = mutableMapOf<AssistantEntrySurface, AssistantSurfaceState>()
        for (index in 0 until length()) {
            val json = optJSONObject(index) ?: continue
            val surface =
                runCatching {
                    AssistantEntrySurface.valueOf(json.optString("surface"))
                }.getOrNull() ?: continue
            states[surface] =
                AssistantSurfaceState(
                    surface = surface,
                    supported = json.optBoolean("supported", true),
                    enabled = json.optBoolean("enabled", true),
                    available = json.optBoolean("available", true),
                    summary = json.optString("summary"),
                )
        }
        return AssistantEntrySurface.entries.map { surface ->
            states[surface] ?: AssistantSurfaceState(surface = surface)
        }
    }

    private fun JSONArray?.toPendingActions(): List<AssistantPendingAction> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                val json = optJSONObject(index) ?: continue
                val type =
                    runCatching {
                        AssistantPendingActionType.valueOf(json.optString("type"))
                    }.getOrNull() ?: continue
                val surface =
                    runCatching {
                        AssistantEntrySurface.valueOf(json.optString("surface"))
                    }.getOrNull() ?: AssistantEntrySurface.SYSTEM
                add(
                    AssistantPendingAction(
                        type = type,
                        title = json.optString("title"),
                        summary = json.optString("summary"),
                        surface = surface,
                        sessionId = json.optString("session_id"),
                        createdAtMs = json.optLong("created_at_ms"),
                    ),
                )
            }
        }
    }

    private fun JSONArray?.toTaskInboxItems(): List<AssistantWorkQueueItem> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                val json = optJSONObject(index) ?: continue
                val type =
                    runCatching {
                        AssistantWorkQueueItemType.valueOf(json.optString("type"))
                    }.getOrNull() ?: continue
                add(
                    AssistantWorkQueueItem(
                        id = json.optString("id"),
                        type = type,
                        title = json.optString("title"),
                        summary = json.optString("summary"),
                        sessionId = json.optString("session_id"),
                        statusCode = json.optString("status_code"),
                        source = json.optString("source"),
                        createdAtMs = json.optLong("created_at_ms"),
                    ),
                )
            }
        }
    }

    private fun JSONArray?.toSessionCards(): List<AssistantSessionCard> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                val json = optJSONObject(index) ?: continue
                add(json.toSessionCard())
            }
        }
    }

    private fun JSONObject?.toSessionCard(): AssistantSessionCard {
        if (this == null) {
            return AssistantSessionCard(
                sessionId = "",
                task = "",
                summary = "",
            )
        }
        return AssistantSessionCard(
            sessionId = optString("session_id"),
            statusSnapshot = optJSONObject("status_snapshot").toAgentUiStatusSnapshot(optString("status_code", AgentUiStatus.IDLE)),
            task = optString("task"),
            summary = optString("summary"),
            routeReason = optString("route_reason"),
            entrySource = optString("entry_source"),
            targetPackageName = optString("target_package_name"),
            turn = optInt("turn"),
            resumable = optBoolean("resumable"),
            awaitingConfirmation = optBoolean("awaiting_confirmation"),
            waitingForExternal = optBoolean("waiting_for_external"),
            updatedAtMs = optLong("updated_at_ms"),
        )
    }

    private fun JSONObject?.toHealthSnapshot(): AssistantHealthSnapshot {
        if (this == null || length() == 0) return AssistantHealthSnapshot()
        return AssistantHealthSnapshot(
            status = optString("status", "idle"),
            summary = optString("summary"),
            activeSessionId = optString("active_session_id"),
            staleRuntime = optBoolean("stale_runtime"),
            backlogCount = optInt("backlog_count"),
            resumableSessionCount = optInt("resumable_session_count"),
            failedSessionCount = optInt("failed_session_count"),
            approvalSessionCount = optInt("approval_session_count"),
            dueTriggerCount = optInt("due_trigger_count"),
            lastProjectionAtMs = optLong("last_projection_at_ms"),
        )
    }

    private fun AssistantSessionCard.toJson(): JSONObject =
        JSONObject().apply {
            put("session_id", sessionId)
            put("status_code", statusCode)
            put("status_snapshot", statusSnapshot.toJson())
            put("task", task)
            put("summary", summary)
            put("route_reason", routeReason)
            put("entry_source", entrySource)
            put("target_package_name", targetPackageName)
            put("turn", turn)
            put("resumable", resumable)
            put("awaiting_confirmation", awaitingConfirmation)
            put("waiting_for_external", waitingForExternal)
            put("updated_at_ms", updatedAtMs)
        }
}
