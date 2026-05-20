package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class RemoteBridgeRequestStatus {
    PENDING,
    PROCESSED,
    FAILED,
}

data class RemoteBridgeRequest(
    val requestId: String,
    val capability: SessionCapabilityKey,
    val sessionId: String = "",
    val task: String = "",
    val query: String = "",
    val entrySource: String = "remote_bridge",
    val userCorrection: String = "",
    val payload: Map<String, String> = emptyMap(),
    val status: RemoteBridgeRequestStatus = RemoteBridgeRequestStatus.PENDING,
    val summary: String = "",
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

data class RemoteBridgeOutboundEvent(
    val timestamp: Long,
    val sessionId: String,
    val eventType: String,
    val summary: String,
    val payload: Map<String, String> = emptyMap(),
)

data class RemoteBridgeSnapshot(
    val pendingRequests: List<RemoteBridgeRequest> = emptyList(),
    val recentRequests: List<RemoteBridgeRequest> = emptyList(),
    val recentEvents: List<RemoteBridgeOutboundEvent> = emptyList(),
)

object RemoteBridgeStore {
    private const val REMOTE_DIR = "runtime"
    private const val REQUEST_FILE = "remote_bridge_requests.json"
    private const val EVENT_FILE = "remote_bridge_events.json"
    private const val MAX_REQUESTS = 120
    private const val MAX_EVENTS = 240
    private val lock = Any()
    private val requests = ArrayDeque<RemoteBridgeRequest>()
    private val events = ArrayDeque<RemoteBridgeOutboundEvent>()
    private var hydrated = false

    fun enqueueRequest(
        capability: SessionCapabilityKey,
        sessionId: String = "",
        task: String = "",
        query: String = "",
        entrySource: String = "remote_bridge",
        userCorrection: String = "",
        payload: Map<String, String> = emptyMap(),
        summary: String = "",
    ): RemoteBridgeRequest {
        val now = System.currentTimeMillis()
        val request =
            RemoteBridgeRequest(
                requestId = "remote_${now}_${capability.name.lowercase()}",
                capability = capability,
                sessionId = sessionId,
                task = task.trim(),
                query = query.trim(),
                entrySource = entrySource,
                userCorrection = userCorrection,
                payload = payload,
                summary = summary.ifBlank { task.ifBlank { query.ifBlank { capability.name.lowercase() } } },
                createdAtMs = now,
                updatedAtMs = now,
            )
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            requests.addFirst(request)
            trimRequestsUnlocked()
            persistRequestsUnlocked()
        }
        return request
    }

    fun readPendingRequests(
        limit: Int = 8,
    ): List<RemoteBridgeRequest> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            requests.filter { it.status == RemoteBridgeRequestStatus.PENDING }.sortedBy { it.createdAtMs }.take(limit)
        }

    fun readSnapshot(
        requestLimit: Int = 8,
        eventLimit: Int = 8,
    ): RemoteBridgeSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            RemoteBridgeSnapshot(
                pendingRequests = requests.filter { it.status == RemoteBridgeRequestStatus.PENDING }.sortedBy { it.createdAtMs }.take(requestLimit),
                recentRequests = requests.take(requestLimit).toList(),
                recentEvents = events.take(eventLimit).toList(),
            )
        }

    fun importSnapshot(
        snapshot: RemoteBridgeSnapshot,
    ) {
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val mergedRequests =
                (requests + snapshot.recentRequests + snapshot.pendingRequests)
                    .distinctBy { it.requestId }
                    .sortedByDescending { it.updatedAtMs }
                    .take(MAX_REQUESTS)
            val mergedEvents =
                (events + snapshot.recentEvents)
                    .distinctBy { "${it.timestamp}|${it.eventType}|${it.sessionId}|${it.summary}" }
                    .sortedByDescending { it.timestamp }
                    .take(MAX_EVENTS)
            requests.clear()
            mergedRequests.forEach(requests::addLast)
            events.clear()
            mergedEvents.forEach(events::addLast)
            persistRequestsUnlocked()
            persistEventsUnlocked()
        }
    }

    fun markProcessed(
        requestId: String,
        summary: String,
        payloadLines: List<String> = emptyList(),
    ) {
        mutateRequest(requestId) { current ->
            current.copy(
                status = RemoteBridgeRequestStatus.PROCESSED,
                summary = summary.ifBlank { current.summary },
                updatedAtMs = System.currentTimeMillis(),
            )
        }
        appendOutbound(
            eventType = "capability_processed",
            sessionId = "",
            summary = summary,
            payload = payloadLines.mapIndexed { index, line -> "line_$index" to line }.toMap(),
        )
        PlatformTraceStore.record(
            category = "remote_request_processed",
            summary = summary,
            metadata = mapOf("request_id" to requestId),
        )
    }

    fun markFailed(
        requestId: String,
        summary: String,
    ) {
        mutateRequest(requestId) { current ->
            current.copy(
                status = RemoteBridgeRequestStatus.FAILED,
                summary = summary.ifBlank { current.summary },
                updatedAtMs = System.currentTimeMillis(),
            )
        }
        appendOutbound(
            eventType = "capability_failed",
            sessionId = "",
            summary = summary,
        )
        PlatformTraceStore.record(
            category = "remote_request_failed",
            summary = summary,
            metadata = mapOf("request_id" to requestId),
        )
    }

    fun appendOutbound(
        eventType: String,
        sessionId: String,
        summary: String,
        payload: Map<String, String> = emptyMap(),
    ) {
        val event =
            RemoteBridgeOutboundEvent(
                timestamp = System.currentTimeMillis(),
                sessionId = sessionId,
                eventType = eventType,
                summary = summary,
                payload = payload,
            )
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            events.addFirst(event)
            trimEventsUnlocked()
            persistEventsUnlocked()
        }
    }

    private fun mutateRequest(
        requestId: String,
        reducer: (RemoteBridgeRequest) -> RemoteBridgeRequest,
    ) {
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val index = requests.indexOfFirst { it.requestId == requestId }
            if (index < 0) return
            val next = reducer(requests.elementAt(index))
            requests.removeAt(index)
            requests.add(index, next)
            persistRequestsUnlocked()
        }
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        requestFile()?.takeIf(File::exists)?.let { file ->
            runCatching {
                val array = JSONObject(file.readText()).optJSONArray("requests") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    requests.addLast(json.toRemoteBridgeRequest())
                }
            }
        }
        eventFile()?.takeIf(File::exists)?.let { file ->
            runCatching {
                val array = JSONObject(file.readText()).optJSONArray("events") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    events.addLast(json.toRemoteBridgeEvent())
                }
            }
        }
    }

    private fun persistRequestsUnlocked() {
        val file = requestFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "requests",
                    JSONArray().apply {
                        requests.forEach { request ->
                            put(request.toJson())
                        }
                    },
                )
            }.toString(2),
        )
    }

    private fun persistEventsUnlocked() {
        val file = eventFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "events",
                    JSONArray().apply {
                        events.forEach { event ->
                            put(event.toJson())
                        }
                    },
                )
            }.toString(2),
        )
    }

    private fun trimRequestsUnlocked() {
        while (requests.size > MAX_REQUESTS) {
            requests.removeLast()
        }
    }

    private fun trimEventsUnlocked() {
        while (events.size > MAX_EVENTS) {
            events.removeLast()
        }
    }

    private fun requestFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, REMOTE_DIR), REQUEST_FILE)
    }

    private fun eventFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, REMOTE_DIR), EVENT_FILE)
    }

    private fun RemoteBridgeRequest.toJson(): JSONObject =
        JSONObject().apply {
            put("request_id", requestId)
            put("capability", capability.name)
            put("session_id", sessionId)
            put("task", task)
            put("query", query)
            put("entry_source", entrySource)
            put("user_correction", userCorrection)
            put("status", status.name)
            put("summary", summary)
            put("created_at_ms", createdAtMs)
            put("updated_at_ms", updatedAtMs)
            put(
                "payload",
                JSONObject().apply {
                    payload.forEach { (key, value) ->
                        put(key, value)
                    }
                },
            )
        }

    private fun JSONObject.toRemoteBridgeRequest(): RemoteBridgeRequest =
        RemoteBridgeRequest(
            requestId = optString("request_id"),
            capability =
                runCatching {
                    SessionCapabilityKey.valueOf(optString("capability"))
                }.getOrDefault(SessionCapabilityKey.START_SESSION),
            sessionId = optString("session_id"),
            task = optString("task"),
            query = optString("query"),
            entrySource = optString("entry_source", "remote_bridge"),
            userCorrection = optString("user_correction"),
            payload = optJSONObject("payload").toStringMap(),
            status =
                runCatching {
                    RemoteBridgeRequestStatus.valueOf(optString("status"))
                }.getOrDefault(RemoteBridgeRequestStatus.PENDING),
            summary = optString("summary"),
            createdAtMs = optLong("created_at_ms"),
            updatedAtMs = optLong("updated_at_ms"),
        )

    private fun RemoteBridgeOutboundEvent.toJson(): JSONObject =
        JSONObject().apply {
            put("timestamp", timestamp)
            put("session_id", sessionId)
            put("event_type", eventType)
            put("summary", summary)
            put(
                "payload",
                JSONObject().apply {
                    payload.forEach { (key, value) ->
                        put(key, value)
                    }
                },
            )
        }

    private fun JSONObject.toRemoteBridgeEvent(): RemoteBridgeOutboundEvent =
        RemoteBridgeOutboundEvent(
            timestamp = optLong("timestamp"),
            sessionId = optString("session_id"),
            eventType = optString("event_type"),
            summary = optString("summary"),
            payload = optJSONObject("payload").toStringMap(),
        )

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap {
            keys().forEach { key ->
                put(key, optString(key))
            }
        }
    }
}

object RemoteBridgeProtocolBridge : SessionBridge {
    override fun publish(event: SessionBridgeEvent) {
        RemoteBridgeStore.appendOutbound(
            eventType = event.remoteEventType(),
            sessionId = event.remoteSessionId(),
            summary = event.remoteSummary(),
            payload = event.remotePayload(),
        )
    }
}

private fun SessionBridgeEvent.remoteSessionId(): String =
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

private fun SessionBridgeEvent.remoteEventType(): String =
    javaClass.simpleName

private fun SessionBridgeEvent.remoteSummary(): String =
    when (this) {
        is SessionBridgeEvent.RuntimeStateChanged -> "runtime=${bridgeSnapshot?.statusCode ?: state.session.status}"
        is SessionBridgeEvent.LogMessage -> message.take(120)
        is SessionBridgeEvent.AgentStarted -> "start ${task.take(64)}"
        is SessionBridgeEvent.AgentStopped -> "stop ${reason.take(64)}"
        is SessionBridgeEvent.ArtifactRecorded -> "artifact=$type turn=$turn"
        is SessionBridgeEvent.ErrorRecorded -> error.take(96)
        is SessionBridgeEvent.PlannerDecisionRecorded -> "${actionLabel} | ${reason.take(64)}"
        is SessionBridgeEvent.SafetyConfirmationRequested -> confirmation.summary.take(96)
        is SessionBridgeEvent.SafetyConfirmationApproved -> confirmation.actionLabel.take(96)
        is SessionBridgeEvent.ExternalWaitEntered -> suspendReason.take(96)
        is SessionBridgeEvent.ExternalWaitResolved -> resumeHint.take(96)
        is SessionBridgeEvent.TurnCompleted -> "$actionLabel -> $finalStatus"
        is SessionBridgeEvent.RoutingDiagnostics -> messages.joinToString(" | ").take(120)
        is SessionBridgeEvent.RoutingFailed -> routeReason.take(96)
        is SessionBridgeEvent.RouteResolved -> routeReason.take(96)
        is SessionBridgeEvent.AppUnavailable -> routeReason.take(96)
        is SessionBridgeEvent.AgentPaused -> "paused"
        is SessionBridgeEvent.AgentResumed -> resumeHint.take(96)
        is SessionBridgeEvent.RoutingStarted -> task.take(96)
        is SessionBridgeEvent.PlanningContextAcquired -> "${pageState} | ${observationSignature.take(32)}"
    }

private fun SessionBridgeEvent.remotePayload(): Map<String, String> =
    when (this) {
        is SessionBridgeEvent.RuntimeStateChanged ->
            mapOf(
                "status" to (bridgeSnapshot?.statusCode ?: state.session.status),
                "task" to state.session.task,
            )
        is SessionBridgeEvent.TurnCompleted ->
            mapOf(
                "turn" to turn.toString(),
                "action" to actionLabel,
                "status" to finalStatus,
                "result" to finalResult.take(120),
            )
        is SessionBridgeEvent.SafetyConfirmationRequested ->
            mapOf(
                "title" to confirmation.title,
                "action_label" to confirmation.actionLabel,
                "summary" to confirmation.summary,
            )
        is SessionBridgeEvent.AgentResumed ->
            mapOf(
                "resume_source" to resumeSource,
                "resume_hint" to resumeHint,
            )
        else -> emptyMap()
    }
