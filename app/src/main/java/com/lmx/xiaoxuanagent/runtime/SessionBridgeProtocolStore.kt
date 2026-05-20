package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionBridgeProtocolEntry(
    val timestamp: Long,
    val sessionId: String,
    val eventType: String,
    val summary: String,
    val payload: Map<String, String> = emptyMap(),
)

object SessionBridgeProtocolStore {
    private const val MAX_PROTOCOL_ENTRIES = 240
    private const val PROTOCOL_DIR = "runtime"
    private const val PROTOCOL_FILE = "session_bridge_feed.json"
    private val lock = Any()
    private val entries = ArrayDeque<SessionBridgeProtocolEntry>()
    private var hydrated = false

    fun append(
        event: SessionBridgeEvent,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        val entry =
            SessionBridgeProtocolEntry(
                timestamp = timestamp,
                sessionId = event.sessionIdOrBlank(),
                eventType = event.protocolEventType(),
                summary = event.protocolSummary(),
                payload = event.protocolPayload(),
            )
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            entries.addFirst(entry)
            while (entries.size > MAX_PROTOCOL_ENTRIES) {
                entries.removeLast()
            }
            persistUnlocked()
            RuntimeMetricsStore.recordBridgeEvent(entry.eventType)
        }
    }

    fun readRecent(
        sessionId: String = "",
        limit: Int = 12,
    ): List<SessionBridgeProtocolEntry> {
        if (limit <= 0) return emptyList()
        return synchronized(lock) {
            hydrateIfNeededUnlocked()
            entries
                .asSequence()
                .filter { sessionId.isBlank() || it.sessionId == sessionId }
                .take(limit)
                .toList()
        }
    }

    fun importEntries(
        importedEntries: List<SessionBridgeProtocolEntry>,
    ) {
        if (importedEntries.isEmpty()) return
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val merged =
                (entries + importedEntries)
                    .distinctBy { "${it.timestamp}|${it.sessionId}|${it.eventType}|${it.summary}" }
                    .sortedByDescending { it.timestamp }
                    .take(MAX_PROTOCOL_ENTRIES)
            entries.clear()
            merged.forEach(entries::addLast)
            persistUnlocked()
            RuntimeMetricsStore.recordPlatformEvent("import_bridge_events")
        }
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = protocolFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("entries") ?: JSONArray()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                entries.addLast(
                    SessionBridgeProtocolEntry(
                        timestamp = json.optLong("timestamp"),
                        sessionId = json.optString("session_id"),
                        eventType = json.optString("event_type"),
                        summary = json.optString("summary"),
                        payload = json.optJSONObject("payload").toStringMap(),
                    ),
                )
            }
        }
    }

    private fun persistUnlocked() {
        val file = protocolFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "entries",
                    JSONArray().apply {
                        entries.forEach { entry ->
                            put(
                                JSONObject().apply {
                                    put("timestamp", entry.timestamp)
                                    put("session_id", entry.sessionId)
                                    put("event_type", entry.eventType)
                                    put("summary", entry.summary)
                                    put(
                                        "payload",
                                        JSONObject().apply {
                                            entry.payload.forEach { (key, value) ->
                                                put(key, value)
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }.toString(2),
        )
    }

    private fun protocolFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, PROTOCOL_DIR), PROTOCOL_FILE)
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap {
            keys().forEach { key ->
                put(key, optString(key))
            }
        }
    }
}

object SessionBridgeProtocolBridge : SessionBridge {
    override fun publish(event: SessionBridgeEvent) {
        SessionBridgeProtocolStore.append(event)
    }
}

class CompositeSessionBridge(
    private vararg val delegates: SessionBridge,
) : SessionBridge {
    override fun publish(event: SessionBridgeEvent) {
        delegates.forEach { delegate ->
            delegate.publish(event)
        }
    }
}

private fun SessionBridgeEvent.sessionIdOrBlank(): String =
    when (this) {
        is SessionBridgeEvent.AgentPaused -> sessionId
        is SessionBridgeEvent.AgentResumed -> sessionId
        is SessionBridgeEvent.AgentStarted -> sessionId
        is SessionBridgeEvent.AgentStopped -> sessionId
        is SessionBridgeEvent.AppUnavailable -> sessionId
        is SessionBridgeEvent.ArtifactRecorded -> sessionId
        is SessionBridgeEvent.ErrorRecorded -> sessionId
        is SessionBridgeEvent.ExternalWaitEntered -> sessionId
        is SessionBridgeEvent.ExternalWaitResolved -> sessionId
        is SessionBridgeEvent.PlannerDecisionRecorded -> sessionId
        is SessionBridgeEvent.PlanningContextAcquired -> sessionId
        is SessionBridgeEvent.RouteResolved -> sessionId
        is SessionBridgeEvent.RoutingDiagnostics -> sessionId
        is SessionBridgeEvent.RoutingFailed -> sessionId
        is SessionBridgeEvent.RoutingStarted -> sessionId
        is SessionBridgeEvent.RuntimeStateChanged -> sessionId
        is SessionBridgeEvent.SafetyConfirmationApproved -> sessionId
        is SessionBridgeEvent.SafetyConfirmationRequested -> sessionId
        is SessionBridgeEvent.TurnCompleted -> sessionId
        is SessionBridgeEvent.LogMessage -> ""
    }

private fun SessionBridgeEvent.protocolEventType(): String =
    when (this) {
        is SessionBridgeEvent.AgentPaused -> "agent_paused"
        is SessionBridgeEvent.AgentResumed -> "agent_resumed"
        is SessionBridgeEvent.AgentStarted -> "agent_started"
        is SessionBridgeEvent.AgentStopped -> "agent_stopped"
        is SessionBridgeEvent.AppUnavailable -> "app_unavailable"
        is SessionBridgeEvent.ArtifactRecorded -> "artifact_recorded"
        is SessionBridgeEvent.ErrorRecorded -> "error_recorded"
        is SessionBridgeEvent.ExternalWaitEntered -> "external_wait_entered"
        is SessionBridgeEvent.ExternalWaitResolved -> "external_wait_resolved"
        is SessionBridgeEvent.PlannerDecisionRecorded -> "planner_decision_recorded"
        is SessionBridgeEvent.PlanningContextAcquired -> "planning_context_acquired"
        is SessionBridgeEvent.RouteResolved -> "route_resolved"
        is SessionBridgeEvent.RoutingDiagnostics -> "routing_diagnostics"
        is SessionBridgeEvent.RoutingFailed -> "routing_failed"
        is SessionBridgeEvent.RoutingStarted -> "routing_started"
        is SessionBridgeEvent.RuntimeStateChanged -> "runtime_state_changed"
        is SessionBridgeEvent.SafetyConfirmationApproved -> "safety_confirmation_approved"
        is SessionBridgeEvent.SafetyConfirmationRequested -> "safety_confirmation_requested"
        is SessionBridgeEvent.TurnCompleted -> "turn_completed"
        is SessionBridgeEvent.LogMessage -> "log_message"
    }

private fun SessionBridgeEvent.protocolSummary(): String =
    when (this) {
        is SessionBridgeEvent.AgentPaused -> "session=$sessionId paused"
        is SessionBridgeEvent.AgentResumed -> "session=$sessionId resumed by $resumeSource"
        is SessionBridgeEvent.AgentStarted -> "session=$sessionId started for ${task.take(48)}"
        is SessionBridgeEvent.AgentStopped -> "session=$sessionId stopped: ${reason.take(64)}"
        is SessionBridgeEvent.AppUnavailable -> "session=$sessionId app unavailable ${targetPackageName.ifBlank { profileDisplayName }}"
        is SessionBridgeEvent.ArtifactRecorded -> "session=$sessionId turn=$turn artifact=$type"
        is SessionBridgeEvent.ErrorRecorded -> "session=$sessionId error=${error.take(72)}"
        is SessionBridgeEvent.ExternalWaitEntered -> "session=$sessionId waiting ${waitingEvent.ifBlank { suspendReason }}"
        is SessionBridgeEvent.ExternalWaitResolved -> "session=$sessionId wait resolved ${previousEvent.ifBlank { resumeHint }}"
        is SessionBridgeEvent.PlannerDecisionRecorded -> "session=$sessionId planner=${actionLabel}"
        is SessionBridgeEvent.PlanningContextAcquired -> "session=$sessionId turn=$turn observation=$observationSignature"
        is SessionBridgeEvent.RouteResolved -> "session=$sessionId route=${routeReason.ifBlank { targetPackageName }}"
        is SessionBridgeEvent.RoutingDiagnostics -> "session=$sessionId routing diagnostics ${messages.size}"
        is SessionBridgeEvent.RoutingFailed -> "session=$sessionId route failed ${routeReason.take(64)}"
        is SessionBridgeEvent.RoutingStarted -> "session=$sessionId routing started"
        is SessionBridgeEvent.RuntimeStateChanged -> "session=$sessionId state=${bridgeSnapshot?.statusCode ?: state.session.status}"
        is SessionBridgeEvent.SafetyConfirmationApproved -> "session=$sessionId safety approved ${confirmation.actionLabel}"
        is SessionBridgeEvent.SafetyConfirmationRequested -> "session=$sessionId safety requested ${confirmation.actionLabel}"
        is SessionBridgeEvent.TurnCompleted -> "session=$sessionId turn=$turn action=$actionLabel status=$finalStatus"
        is SessionBridgeEvent.LogMessage -> message.take(120)
    }

private fun SessionBridgeEvent.protocolPayload(): Map<String, String> =
    when (this) {
        is SessionBridgeEvent.AgentPaused ->
            mapOf("session_id" to sessionId)

        is SessionBridgeEvent.AgentResumed ->
            mapOf(
                "session_id" to sessionId,
                "resume_hint" to resumeHint,
                "resume_source" to resumeSource,
                "user_correction" to userCorrection,
            )

        is SessionBridgeEvent.AgentStarted ->
            mapOf(
                "session_id" to sessionId,
                "task" to task,
                "profile_display_name" to profileDisplayName,
                "target_package_name" to targetPackageName,
                "route_reason" to routeReason,
                "entry_source" to entrySource,
            )

        is SessionBridgeEvent.AgentStopped ->
            mapOf(
                "session_id" to sessionId,
                "reason" to reason,
                "status" to status,
            )

        is SessionBridgeEvent.AppUnavailable ->
            mapOf(
                "session_id" to sessionId,
                "task" to task,
                "entry_source" to entrySource,
                "profile_display_name" to profileDisplayName,
                "target_package_name" to targetPackageName,
                "route_reason" to routeReason,
            )

        is SessionBridgeEvent.ArtifactRecorded ->
            mapOf(
                "session_id" to sessionId,
                "turn" to turn.toString(),
                "artifact_id" to artifactId,
                "type" to type,
                "summary" to summary,
            )

        is SessionBridgeEvent.ErrorRecorded ->
            mapOf(
                "session_id" to sessionId,
                "error" to error,
                "keep_running" to keepRunning.toString(),
            )

        is SessionBridgeEvent.ExternalWaitEntered ->
            mapOf(
                "session_id" to sessionId,
                "waiting_event" to waitingEvent,
                "suspend_reason" to suspendReason,
                "observation_signature" to observationSignature,
            )

        is SessionBridgeEvent.ExternalWaitResolved ->
            mapOf(
                "session_id" to sessionId,
                "previous_event" to previousEvent,
                "resume_hint" to resumeHint,
                "user_correction" to userCorrection,
                "observation_signature" to observationSignature,
            )

        is SessionBridgeEvent.PlannerDecisionRecorded ->
            mapOf(
                "session_id" to sessionId,
                "action_label" to actionLabel,
                "reason" to reason,
            )

        is SessionBridgeEvent.PlanningContextAcquired ->
            mapOf(
                "session_id" to sessionId,
                "turn" to turn.toString(),
                "observation_signature" to observationSignature,
                "page_state" to pageState,
                "top_texts_preview" to topTextsPreview,
            )

        is SessionBridgeEvent.RouteResolved ->
            mapOf(
                "session_id" to sessionId,
                "task" to task,
                "entry_source" to entrySource,
                "profile_display_name" to profileDisplayName,
                "target_package_name" to targetPackageName,
                "route_reason" to routeReason,
            )

        is SessionBridgeEvent.RoutingDiagnostics ->
            mapOf(
                "session_id" to sessionId,
                "messages" to messages.joinToString(" | "),
            )

        is SessionBridgeEvent.RoutingFailed ->
            mapOf(
                "session_id" to sessionId,
                "task" to task,
                "entry_source" to entrySource,
                "route_reason" to routeReason,
            )

        is SessionBridgeEvent.RoutingStarted ->
            mapOf(
                "session_id" to sessionId,
                "task" to task,
                "entry_source" to entrySource,
            )

        is SessionBridgeEvent.RuntimeStateChanged ->
            mapOf(
                "session_id" to sessionId,
                "status_code" to (bridgeSnapshot?.statusCode ?: state.session.status),
                "turn" to state.session.turns.toString(),
                "last_transition" to state.lastTransition,
            )

        is SessionBridgeEvent.SafetyConfirmationApproved ->
            mapOf(
                "session_id" to sessionId,
                "title" to confirmation.title,
                "summary" to confirmation.summary,
                "action_label" to confirmation.actionLabel,
                "resume_hint" to resumeHint,
                "user_correction" to userCorrection,
            )

        is SessionBridgeEvent.SafetyConfirmationRequested ->
            mapOf(
                "session_id" to sessionId,
                "title" to confirmation.title,
                "summary" to confirmation.summary,
                "action_label" to confirmation.actionLabel,
            )

        is SessionBridgeEvent.TurnCompleted ->
            mapOf(
                "session_id" to sessionId,
                "turn" to turn.toString(),
                "action_label" to actionLabel,
                "final_status" to finalStatus,
                "final_result" to finalResult,
                "keep_running" to keepRunning.toString(),
                "loop_detected" to loopDetected.toString(),
            )

        is SessionBridgeEvent.LogMessage ->
            mapOf("message" to message)
    }
