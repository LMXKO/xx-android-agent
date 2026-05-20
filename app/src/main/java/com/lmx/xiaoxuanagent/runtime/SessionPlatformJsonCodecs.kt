package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.assistantos.AssistantExternalSignal
import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTask
import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskType
import com.lmx.xiaoxuanagent.assistantos.AssistantExternalSignalType
import org.json.JSONArray
import org.json.JSONObject

internal fun RuntimeEventEntry.toJson(): JSONObject =
    JSONObject().apply {
        put("timestamp", timestamp)
        put("session_id", sessionId)
        put("command_type", commandType)
        put("command_payload", commandPayload)
        put("transition", transition)
        put("status_code", statusCode)
        put("turn", turn)
        put("summary", summary)
        put(
            "metadata",
            JSONObject().apply {
                metadata.forEach { (key, value) ->
                    put(key, value)
                }
            },
        )
    }

internal fun SessionBridgeProtocolEntry.toJson(): JSONObject =
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

internal fun SessionBridgeSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("session_id", sessionId)
        put("task", task)
        put("entry_source", entrySource)
        put("target_package_name", targetPackageName)
        put("profile_id", profileId)
        put("turn", turn)
        put("status_code", statusCode)
        put("status_category", statusCategory.name)
        put("status_blocked_reason", statusBlockedReason.name)
        put("status_execution_phase", statusExecutionPhase.name)
        put("status_takeover_reason", statusTakeoverReason.name)
        put("status_terminal_reason", statusTerminalReason.name)
        put("resumable", resumable)
        put("target_app_return_eligible", targetAppReturnEligible)
        put("route_reason", routeReason)
        put("result_summary", resultSummary)
        put("error_summary", errorSummary)
        put("takeover_summary", takeoverSummary)
        put("pending_safety_confirmation", pendingSafetyConfirmation)
    }

internal fun RuntimeMetricsSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put(
            "command_counters",
            JSONArray().apply {
                commandCounters.forEach { counter ->
                    put(JSONObject().apply {
                        put("key", counter.key)
                        put("count", counter.count)
                    })
                }
            },
        )
        put(
            "bridge_event_counters",
            JSONArray().apply {
                bridgeEventCounters.forEach { counter ->
                    put(JSONObject().apply {
                        put("key", counter.key)
                        put("count", counter.count)
                    })
                }
            },
        )
        put(
            "hook_counters",
            JSONArray().apply {
                hookCounters.forEach { counter ->
                    put(JSONObject().apply {
                        put("key", counter.key)
                        put("count", counter.count)
                    })
                }
            },
        )
        put("last_updated_at_ms", lastUpdatedAtMs)
    }

internal fun SessionPlatformHealthSummary.toJson(): JSONObject =
    JSONObject().apply {
        put("status", status)
        put("summary", summary)
        put("deterministic_replay_ready", deterministicReplayReady)
        put("resumable_snapshot_present", resumableSnapshotPresent)
        put("bridge_feed_present", bridgeFeedPresent)
        put("runtime_ledger_present", runtimeLedgerPresent)
        put("pending_approval_count", pendingApprovalCount)
        put("remote_pending_count", remotePendingCount)
        put("worker_queue_count", workerQueueCount)
        put("mailbox_pending_count", mailboxPendingCount)
        put("memory_queue_pending_count", memoryQueuePendingCount)
        put("proactive_task_count", proactiveTaskCount)
        put("stale_runtime", staleRuntime)
    }

internal fun SessionRuntimeState.toJson(): JSONObject =
    JSONObject().apply {
        put("session_id", session.sessionId)
        put("status", session.status)
        put("task", session.task)
        put("turns", session.turns)
        put("last_transition", lastTransition)
        put("updated_at_ms", updatedAtMs)
        put("route_reason", routeSnapshot?.reason.orEmpty())
        put("result_summary", resultSnapshot?.summary.orEmpty())
        put("last_error", resultSnapshot?.lastError.orEmpty())
        put("takeover_reason", takeoverSnapshot?.latestTakeoverReason.orEmpty())
    }

internal fun SessionResumeSnapshot.toJson(): JSONObject =
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
        put("last_transition", lastTransition)
        put("updated_at_ms", updatedAtMs)
    }

internal fun ReplaySessionSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("session_id", sessionId)
        put("profile_id", profileId)
        put("target_package_name", targetPackageName)
        put("task", task)
        put("entry_source", entrySource)
        put("status", status)
        put("status_snapshot", statusSnapshot.toJson())
        put("route_reason", routeReason)
        put("started_at", startedAt)
        put("updated_at", updatedAt)
        put("finished_at", finishedAt)
        put("turn_count", turnCount)
        put("final_message", finalMessage)
        put("pending_safety_confirmation", pendingSafetyConfirmation)
    }

internal fun RemoteBridgeSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("pending_requests", JSONArray().apply { pendingRequests.forEach { put(it.toJson()) } })
        put("recent_requests", JSONArray().apply { recentRequests.forEach { put(it.toJson()) } })
        put("recent_events", JSONArray().apply { recentEvents.forEach { put(it.toJson()) } })
    }

internal fun RemoteBridgeRequest.toJson(): JSONObject =
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
        put("payload", JSONObject().apply { payload.forEach { (key, value) -> put(key, value) } })
    }

internal fun RemoteBridgeOutboundEvent.toJson(): JSONObject =
    JSONObject().apply {
        put("timestamp", timestamp)
        put("session_id", sessionId)
        put("event_type", eventType)
        put("summary", summary)
        put("payload", JSONObject().apply { payload.forEach { (key, value) -> put(key, value) } })
    }

internal fun AssistantProactiveTask.toJson(): JSONObject =
    JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("capability", capability.name)
        put("title", title)
        put("summary", summary)
        put("task", task)
        put("session_id", sessionId)
        put("parent_session_id", parentSessionId)
        put("fire_at_ms", fireAtMs)
        put("enabled", enabled)
        put("source", source)
        put("priority", priority)
        put("deadline_at_ms", deadlineAtMs)
        put("defer_count", deferCount)
        put("recommended_command", recommendedCommand)
        put("created_at_ms", createdAtMs)
        put("updated_at_ms", updatedAtMs)
        put("metadata", JSONObject().apply { metadata.forEach { (key, value) -> put(key, value) } })
    }

internal fun PlatformTraceEntry.toJson(): JSONObject =
    JSONObject().apply {
        put("trace_id", traceId)
        put("timestamp", timestamp)
        put("category", category)
        put("session_id", sessionId)
        put("summary", summary)
        put("metadata", JSONObject().apply { metadata.forEach { (key, value) -> put(key, value) } })
    }

internal fun RemoteTransportSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("pending_inbound", JSONArray().apply { pendingInbound.forEach { put(it.toJson()) } })
        put("pending_outbound", JSONArray().apply { pendingOutbound.forEach { put(it.toJson()) } })
        put("recent_inbound", JSONArray().apply { recentInbound.forEach { put(it.toJson()) } })
        put("recent_outbound", JSONArray().apply { recentOutbound.forEach { put(it.toJson()) } })
    }

internal fun SessionGraphNode.toJson(): JSONObject =
    JSONObject().apply {
        put("session_id", sessionId)
        put("parent_session_id", parentSessionId)
        put("worker_id", workerId)
        put("parent_worker_id", parentWorkerId)
        put("agent_id", agentId)
        put("parent_agent_id", parentAgentId)
        put("root_session_id", rootSessionId)
        put("coordinator_session_id", coordinatorSessionId)
        put("task", task)
        put("entry_source", entrySource)
        put("status", status)
        put("depth", depth)
        put("blocked_reason", blockedReason)
        put("child_session_ids", JSONArray(childSessionIds))
        put("pending_child_session_ids", JSONArray(pendingChildSessionIds))
        put("pending_approval_count", pendingApprovalCount)
        put("mailbox_pending_count", mailboxPendingCount)
        put("descendant_session_count", descendantSessionCount)
        put("latest_result_summary", latestResultSummary)
        put("latest_error_summary", latestErrorSummary)
        put("created_at_ms", createdAtMs)
        put("updated_at_ms", updatedAtMs)
        put("metadata", JSONObject().apply { metadata.forEach { (key, value) -> put(key, value) } })
    }

internal fun RemoteTransportEnvelope.toJson(): JSONObject =
    JSONObject().apply {
        put("envelope_id", envelopeId)
        put("direction", direction.name)
        put("type", type.name)
        put("session_id", sessionId)
        put("summary", summary)
        put("capability", capability?.name.orEmpty())
        put("task", task)
        put("query", query)
        put("entry_source", entrySource)
        put("user_correction", userCorrection)
        put("channel", channel)
        put("body", body)
        put("status", status.name)
        put("created_at_ms", createdAtMs)
        put("updated_at_ms", updatedAtMs)
        put("payload", JSONObject().apply { payload.forEach { (key, value) -> put(key, value) } })
    }

internal fun AssistantExternalSignal.toJson(): JSONObject =
    JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("capability", capability.name)
        put("title", title)
        put("summary", summary)
        put("task", task)
        put("session_id", sessionId)
        put("query", query)
        put("fire_at_ms", fireAtMs)
        put("enabled", enabled)
        put("source", source)
        put("created_at_ms", createdAtMs)
        put("updated_at_ms", updatedAtMs)
        put("payload", JSONObject().apply { payload.forEach { (key, value) -> put(key, value) } })
    }

internal fun JSONArray?.toRuntimeEventEntries(): List<RuntimeEventEntry> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(
                RuntimeEventEntry(
                    timestamp = item.optLong("timestamp"),
                    sessionId = item.optString("session_id"),
                    commandType = item.optString("command_type"),
                    commandPayload = item.optString("command_payload"),
                    transition = item.optString("transition"),
                    statusCode = item.optString("status_code"),
                    turn = item.optInt("turn"),
                    summary = item.optString("summary"),
                    metadata =
                        buildMap {
                            item.optJSONObject("metadata")?.keys()?.forEach { key ->
                                put(key, item.optJSONObject("metadata")?.optString(key).orEmpty())
                            }
                        },
                ),
            )
        }
    }
}

internal fun JSONArray?.toBridgeProtocolEntries(): List<SessionBridgeProtocolEntry> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(
                SessionBridgeProtocolEntry(
                    timestamp = item.optLong("timestamp"),
                    sessionId = item.optString("session_id"),
                    eventType = item.optString("event_type"),
                    summary = item.optString("summary"),
                    payload =
                        buildMap {
                            item.optJSONObject("payload")?.keys()?.forEach { key ->
                                put(key, item.optJSONObject("payload")?.optString(key).orEmpty())
                            }
                        },
                ),
            )
        }
    }
}

internal fun JSONArray?.toJsonObjects(): List<JSONObject> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(::add)
        }
    }
}

internal fun JSONObject?.toRemoteBridgeSnapshot(): RemoteBridgeSnapshot {
    if (this == null || length() == 0) return RemoteBridgeSnapshot()
    return RemoteBridgeSnapshot(
        pendingRequests = optJSONArray("pending_requests").toRemoteRequests(),
        recentRequests = optJSONArray("recent_requests").toRemoteRequests(),
        recentEvents = optJSONArray("recent_events").toRemoteEvents(),
    )
}

internal fun JSONObject?.toRemoteTransportSnapshot(): RemoteTransportSnapshot {
    if (this == null || length() == 0) return RemoteTransportSnapshot()
    return RemoteTransportSnapshot(
        pendingInbound = optJSONArray("pending_inbound").toRemoteTransportEnvelopes(),
        pendingOutbound = optJSONArray("pending_outbound").toRemoteTransportEnvelopes(),
        recentInbound = optJSONArray("recent_inbound").toRemoteTransportEnvelopes(),
        recentOutbound = optJSONArray("recent_outbound").toRemoteTransportEnvelopes(),
    )
}

internal fun JSONArray?.toRemoteRequests(): List<RemoteBridgeRequest> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(
                RemoteBridgeRequest(
                    requestId = item.optString("request_id"),
                    capability =
                        runCatching { SessionCapabilityKey.valueOf(item.optString("capability")) }
                            .getOrDefault(SessionCapabilityKey.START_SESSION),
                    sessionId = item.optString("session_id"),
                    task = item.optString("task"),
                    query = item.optString("query"),
                    entrySource = item.optString("entry_source", "remote_bridge"),
                    userCorrection = item.optString("user_correction"),
                    payload = item.optJSONObject("payload").toStringMap(),
                    status =
                        runCatching { RemoteBridgeRequestStatus.valueOf(item.optString("status")) }
                            .getOrDefault(RemoteBridgeRequestStatus.PENDING),
                    summary = item.optString("summary"),
                    createdAtMs = item.optLong("created_at_ms"),
                    updatedAtMs = item.optLong("updated_at_ms"),
                ),
            )
        }
    }
}

internal fun JSONArray?.toRemoteEvents(): List<RemoteBridgeOutboundEvent> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(
                RemoteBridgeOutboundEvent(
                    timestamp = item.optLong("timestamp"),
                    sessionId = item.optString("session_id"),
                    eventType = item.optString("event_type"),
                    summary = item.optString("summary"),
                    payload = item.optJSONObject("payload").toStringMap(),
                ),
            )
        }
    }
}

internal fun JSONArray?.toRemoteTransportEnvelopes(): List<RemoteTransportEnvelope> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(
                RemoteTransportEnvelope(
                    envelopeId = item.optString("envelope_id"),
                    direction =
                        runCatching { RemoteTransportDirection.valueOf(item.optString("direction")) }
                            .getOrDefault(RemoteTransportDirection.INBOUND),
                    type =
                        runCatching { RemoteTransportEnvelopeType.valueOf(item.optString("type")) }
                            .getOrDefault(RemoteTransportEnvelopeType.CAPABILITY_REQUEST),
                    sessionId = item.optString("session_id"),
                    summary = item.optString("summary"),
                    capability =
                        item.optString("capability")
                            .takeIf { it.isNotBlank() }
                            ?.let { runCatching { SessionCapabilityKey.valueOf(it) }.getOrNull() },
                    task = item.optString("task"),
                    query = item.optString("query"),
                    entrySource = item.optString("entry_source"),
                    userCorrection = item.optString("user_correction"),
                    channel = item.optString("channel"),
                    body = item.optString("body"),
                    payload = item.optJSONObject("payload").toStringMap(),
                    status =
                        runCatching { RemoteTransportEnvelopeStatus.valueOf(item.optString("status")) }
                            .getOrDefault(RemoteTransportEnvelopeStatus.PENDING),
                    createdAtMs = item.optLong("created_at_ms"),
                    updatedAtMs = item.optLong("updated_at_ms"),
                ),
            )
        }
    }
}

internal fun JSONArray?.toProactiveTasks(): List<AssistantProactiveTask> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(
                AssistantProactiveTask(
                    id = item.optString("id"),
                    type =
                        runCatching { AssistantProactiveTaskType.valueOf(item.optString("type")) }
                            .getOrDefault(AssistantProactiveTaskType.SCHEDULED_TASK),
                    capability =
                        runCatching { SessionCapabilityKey.valueOf(item.optString("capability")) }
                            .getOrDefault(SessionCapabilityKey.START_SESSION),
                    title = item.optString("title"),
                    summary = item.optString("summary"),
                    task = item.optString("task"),
                    sessionId = item.optString("session_id"),
                    parentSessionId = item.optString("parent_session_id"),
                    fireAtMs = item.optLong("fire_at_ms"),
                    enabled = item.optBoolean("enabled", true),
                    source = item.optString("source"),
                    priority = item.optInt("priority"),
                    deadlineAtMs = item.optLong("deadline_at_ms"),
                    deferCount = item.optInt("defer_count"),
                    recommendedCommand = item.optString("recommended_command"),
                    createdAtMs = item.optLong("created_at_ms"),
                    updatedAtMs = item.optLong("updated_at_ms"),
                    metadata = item.optJSONObject("metadata").toStringMap(),
                ),
            )
        }
    }
}

internal fun JSONArray?.toExternalSignals(): List<AssistantExternalSignal> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(
                AssistantExternalSignal(
                    id = item.optString("id"),
                    type =
                        runCatching { AssistantExternalSignalType.valueOf(item.optString("type")) }
                            .getOrDefault(AssistantExternalSignalType.SYSTEM_EVENT),
                    capability =
                        runCatching { SessionCapabilityKey.valueOf(item.optString("capability")) }
                            .getOrDefault(SessionCapabilityKey.START_SESSION),
                    title = item.optString("title"),
                    summary = item.optString("summary"),
                    task = item.optString("task"),
                    sessionId = item.optString("session_id"),
                    query = item.optString("query"),
                    fireAtMs = item.optLong("fire_at_ms"),
                    enabled = item.optBoolean("enabled", true),
                    source = item.optString("source"),
                    payload = item.optJSONObject("payload").toStringMap(),
                    createdAtMs = item.optLong("created_at_ms"),
                    updatedAtMs = item.optLong("updated_at_ms"),
                ),
            )
        }
    }
}

internal fun JSONArray?.toTraceEntries(): List<PlatformTraceEntry> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(
                PlatformTraceEntry(
                    traceId = item.optString("trace_id"),
                    timestamp = item.optLong("timestamp"),
                    category = item.optString("category"),
                    sessionId = item.optString("session_id"),
                    summary = item.optString("summary"),
                    metadata = item.optJSONObject("metadata").toStringMap(),
                ),
            )
        }
    }
}

internal fun JSONObject?.toStringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    return buildMap {
        keys().forEach { key ->
            put(key, optString(key))
        }
    }
}

internal fun JSONArray?.toJsonStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}
