package com.lmx.xiaoxuanagent.runtime

import org.json.JSONArray
import org.json.JSONObject

internal fun SessionExecutionLease.isExpired(
    now: Long,
): Boolean = expiresAtMs > 0L && now >= expiresAtMs

internal fun SessionExecutionSchedulerSnapshot.toCoordinatorJson(): JSONObject =
    JSONObject().apply {
        put("active_session_id", activeSessionId)
        put("lease", lease?.toCoordinatorJson() ?: JSONObject())
        put(
            "active_leases",
            JSONArray().apply {
                activeLeases.forEach { currentLease ->
                    put(currentLease.toCoordinatorJson())
                }
            },
        )
        put("last_dispatch_session_id", lastDispatchSessionId)
        put("last_dispatch_reason", lastDispatchReason)
        put("last_observed_package_name", lastObservedPackageName)
        put("parallelism_budget", parallelismBudget)
        put("fairness_summary", fairnessSummary)
        put("owner_queue_summary", JSONArray(ownerQueueSummary))
        put("runnable_session_ids", JSONArray(runnableSessionIds))
        put("blocked_session_ids", JSONArray(blockedSessionIds))
        put("runnable_worker_ids", JSONArray(runnableWorkerIds))
        put(
            "dispatch_plan",
            JSONArray().apply {
                dispatchPlan.forEach { item ->
                    put(item.toCoordinatorJson())
                }
            },
        )
        put(
            "candidates",
            JSONArray().apply {
                candidates.forEach { candidate ->
                    put(candidate.toCoordinatorJson())
                }
            },
        )
        put("updated_at_ms", updatedAtMs)
    }

internal fun SessionExecutionLease.toCoordinatorJson(): JSONObject =
    JSONObject().apply {
        put("lease_id", leaseId)
        put("session_id", sessionId)
        put("owner", owner)
        put("reason", reason)
        put("acquired_at_ms", acquiredAtMs)
        put("expires_at_ms", expiresAtMs)
    }

internal fun SessionExecutionDispatchPlanItem.toCoordinatorJson(): JSONObject =
    JSONObject().apply {
        put("session_id", sessionId)
        put("owner_key", ownerKey)
        put("lane", lane)
        put("target_package_name", targetPackageName)
        put("score", score)
        put("reason", reason)
    }

internal fun SessionExecutionCandidate.toCoordinatorJson(): JSONObject =
    JSONObject().apply {
        put("session_id", sessionId)
        put("root_session_id", rootSessionId)
        put("parent_session_id", parentSessionId)
        put("coordinator_session_id", coordinatorSessionId)
        put("worker_id", workerId)
        put("agent_id", agentId)
        put("task", task)
        put("entry_source", entrySource)
        put("target_package_name", targetPackageName)
        put("status", status)
        put("source", source.name)
        put("owner_key", ownerKey)
        put("lane", lane)
        put("priority", priority)
        put("runnable", runnable)
        put("blocked_reason", blockedReason)
        put("sort_score", sortScore)
        put("updated_at_ms", updatedAtMs)
    }

internal fun JSONObject.toCoordinatorSchedulerSnapshot(): SessionExecutionSchedulerSnapshot =
    SessionExecutionSchedulerSnapshot(
        activeSessionId = optString("active_session_id"),
        lease = optJSONObject("lease").toCoordinatorLease(),
        activeLeases =
            optJSONArray("active_leases").toCoordinatorLeases().ifEmpty {
                listOfNotNull(optJSONObject("lease").toCoordinatorLease())
            },
        lastDispatchSessionId = optString("last_dispatch_session_id"),
        lastDispatchReason = optString("last_dispatch_reason"),
        lastObservedPackageName = optString("last_observed_package_name"),
        parallelismBudget = optInt("parallelism_budget", 1),
        fairnessSummary = optString("fairness_summary"),
        ownerQueueSummary = optJSONArray("owner_queue_summary").toCoordinatorStringList(),
        runnableSessionIds = optJSONArray("runnable_session_ids").toCoordinatorStringList(),
        blockedSessionIds = optJSONArray("blocked_session_ids").toCoordinatorStringList(),
        runnableWorkerIds = optJSONArray("runnable_worker_ids").toCoordinatorStringList(),
        dispatchPlan = optJSONArray("dispatch_plan").toCoordinatorDispatchPlan(),
        candidates = optJSONArray("candidates").toCoordinatorExecutionCandidates(),
        updatedAtMs = optLong("updated_at_ms"),
    )

internal fun JSONObject?.toCoordinatorLease(): SessionExecutionLease? {
    if (this == null || length() == 0) return null
    val sessionId = optString("session_id")
    if (sessionId.isBlank()) return null
    return SessionExecutionLease(
        leaseId = optString("lease_id"),
        sessionId = sessionId,
        owner = optString("owner"),
        reason = optString("reason"),
        acquiredAtMs = optLong("acquired_at_ms"),
        expiresAtMs = optLong("expires_at_ms"),
    )
}

internal fun JSONArray?.toCoordinatorLeases(): List<SessionExecutionLease> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            optJSONObject(index).toCoordinatorLease()?.let(::add)
        }
    }
}

internal fun JSONArray?.toCoordinatorExecutionCandidates(): List<SessionExecutionCandidate> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(
                SessionExecutionCandidate(
                    sessionId = item.optString("session_id"),
                    rootSessionId = item.optString("root_session_id"),
                    parentSessionId = item.optString("parent_session_id"),
                    coordinatorSessionId = item.optString("coordinator_session_id"),
                    workerId = item.optString("worker_id"),
                    agentId = item.optString("agent_id"),
                    task = item.optString("task"),
                    entrySource = item.optString("entry_source"),
                    targetPackageName = item.optString("target_package_name"),
                    status = item.optString("status"),
                    source =
                        runCatching {
                            SessionExecutionCandidateSource.valueOf(item.optString("source", SessionExecutionCandidateSource.RESUMABLE_ROOT.name))
                        }.getOrDefault(SessionExecutionCandidateSource.RESUMABLE_ROOT),
                    ownerKey = item.optString("owner_key"),
                    lane = item.optString("lane"),
                    priority = item.optInt("priority", 0),
                    runnable = item.optBoolean("runnable", false),
                    blockedReason = item.optString("blocked_reason"),
                    sortScore = item.optInt("sort_score", 0),
                    updatedAtMs = item.optLong("updated_at_ms"),
                ),
            )
        }
    }
}

internal fun JSONArray?.toCoordinatorDispatchPlan(): List<SessionExecutionDispatchPlanItem> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(
                SessionExecutionDispatchPlanItem(
                    sessionId = item.optString("session_id"),
                    ownerKey = item.optString("owner_key"),
                    lane = item.optString("lane"),
                    targetPackageName = item.optString("target_package_name"),
                    score = item.optLong("score"),
                    reason = item.optString("reason"),
                ),
            )
        }
    }
}

internal fun JSONArray?.toCoordinatorStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}
