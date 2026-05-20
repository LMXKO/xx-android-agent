package com.lmx.xiaoxuanagent.runtime

import org.json.JSONArray
import org.json.JSONObject

internal fun SessionWorkerRecord.toJson(): JSONObject =
    JSONObject().apply {
        put("worker_id", workerId)
        put("agent_id", agentId)
        put("parent_session_id", parentSessionId)
        put("parent_worker_id", parentWorkerId)
        put("parent_agent_id", parentAgentId)
        put("root_session_id", rootSessionId)
        put("coordinator_session_id", coordinatorSessionId)
        put("session_id", sessionId)
        put("depth", depth)
        put("child_worker_ids", JSONArray(childWorkerIds))
        put("child_session_ids", JSONArray(childSessionIds))
        put("mission_type", missionType.name)
        put("escalation_policy", escalationPolicy.name)
        put("join_expectation", joinExpectation.name)
        put("mission_label", missionLabel)
        put("mission_summary", missionSummary)
        put("task", task)
        put("entry_source", entrySource)
        put("status", status.name)
        put("priority", priority)
        put("retry_count", retryCount)
        put("max_retries", maxRetries)
        put("next_eligible_at_ms", nextEligibleAtMs)
        put("blocked_reason", blockedReason)
        put("join_policy", joinPolicy.name)
        put("summary", summary)
        put("completion_status", completionStatus)
        put("result_summary", resultSummary)
        put("source", source)
        put("lease_owner", leaseOwner)
        put("lease_token", leaseToken)
        put("lease_acquired_at_ms", leaseAcquiredAtMs)
        put("lease_expires_at_ms", leaseExpiresAtMs)
        put("created_at_ms", createdAtMs)
        put("updated_at_ms", updatedAtMs)
        put("last_attempt_at_ms", lastAttemptAtMs)
        put("metadata", JSONObject().apply { metadata.forEach { (key, value) -> put(key, value) } })
    }

internal fun SessionWorkerGraphSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("roots", JSONArray().apply { roots.forEach { put(it.toJson()) } })
        put(
            "scheduler",
            JSONObject().apply {
                put("max_concurrent_workers", scheduler.maxConcurrentWorkers)
                put("queued_count", scheduler.queuedCount)
                put("active_count", scheduler.activeCount)
                put("blocked_count", scheduler.blockedCount)
                put("deferred_count", scheduler.deferredCount)
                put("waiting_children_count", scheduler.waitingChildrenCount)
                put("ready_worker_ids", JSONArray(scheduler.readyWorkerIds))
                put("fairness_mode", scheduler.fairnessMode)
                put("mailbox_priority_mode", scheduler.mailboxPriorityMode)
                put("mission_summary", scheduler.missionSummary)
                put("escalation_summary", scheduler.escalationSummary)
                put("join_summary", scheduler.joinSummary)
                put("blocked_coordinator_ids", JSONArray(scheduler.blockedCoordinatorIds))
                put("coordinator_load_summary", JSONArray(scheduler.coordinatorLoadSummary))
                put(
                    "dispatch_candidates",
                    JSONArray().apply {
                        scheduler.dispatchCandidates.forEach { candidate ->
                            put(
                                JSONObject().apply {
                                    put("worker_id", candidate.workerId)
                                    put("session_id", candidate.sessionId)
                                    put("root_session_id", candidate.rootSessionId)
                                    put("coordinator_session_id", candidate.coordinatorSessionId)
                                    put("mission_type", candidate.missionType)
                                    put("mission_label", candidate.missionLabel)
                                    put("escalation_policy", candidate.escalationPolicy)
                                    put("owner_key", candidate.ownerKey)
                                    put("lease_owner", candidate.leaseOwner)
                                    put("lease_expires_at_ms", candidate.leaseExpiresAtMs)
                                    put("lane", candidate.lane)
                                    put("score", candidate.score)
                                    put("reasons", JSONArray(candidate.reasons))
                                },
                            )
                        }
                    },
                )
            },
        )
        put("active_session_ids", JSONArray(activeSessionIds))
    }

internal fun SessionWorkerTreeNode.toJson(): JSONObject =
    JSONObject().apply {
        put("record", record.toJson())
        put("children", JSONArray().apply { children.forEach { put(it.toJson()) } })
    }

internal fun JSONArray?.toWorkerRecords(): List<SessionWorkerRecord> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val metadata = item.optJSONObject("metadata").toStringMap()
            val joinPolicy =
                runCatching { SessionWorkerJoinPolicy.valueOf(item.optString("join_policy")) }
                    .getOrDefault(SessionWorkerJoinPolicy.WAIT_ALL_CHILDREN)
            val missionProfile =
                deriveSessionWorkerMissionProfile(
                    task = item.optString("task"),
                    entrySource = item.optString("entry_source"),
                    joinPolicy = joinPolicy,
                    metadata = metadata,
                    hasParent = item.optString("parent_worker_id").isNotBlank(),
                )
            add(
                SessionWorkerRecord(
                    workerId = item.optString("worker_id"),
                    agentId = item.optString("agent_id"),
                    parentSessionId = item.optString("parent_session_id"),
                    parentWorkerId = item.optString("parent_worker_id"),
                    parentAgentId = item.optString("parent_agent_id"),
                    rootSessionId = item.optString("root_session_id"),
                    coordinatorSessionId = item.optString("coordinator_session_id"),
                    sessionId = item.optString("session_id"),
                    depth = item.optInt("depth", 0),
                    childWorkerIds = item.optJSONArray("child_worker_ids").toJsonStringList(),
                    childSessionIds = item.optJSONArray("child_session_ids").toJsonStringList(),
                    missionType =
                        runCatching { SessionWorkerMissionType.valueOf(item.optString("mission_type")) }
                            .getOrDefault(missionProfile.missionType),
                    escalationPolicy =
                        runCatching { SessionWorkerEscalationPolicy.valueOf(item.optString("escalation_policy")) }
                            .getOrDefault(missionProfile.escalationPolicy),
                    joinExpectation =
                        runCatching { SessionWorkerJoinExpectation.valueOf(item.optString("join_expectation")) }
                            .getOrDefault(missionProfile.joinExpectation),
                    missionLabel = item.optString("mission_label").ifBlank { missionProfile.missionLabel },
                    missionSummary = item.optString("mission_summary").ifBlank { missionProfile.missionSummary },
                    task = item.optString("task"),
                    entrySource = item.optString("entry_source"),
                    status =
                        runCatching { SessionWorkerStatus.valueOf(item.optString("status")) }
                            .getOrDefault(SessionWorkerStatus.QUEUED),
                    priority = item.optInt("priority", 0),
                    retryCount = item.optInt("retry_count", 0),
                    maxRetries = item.optInt("max_retries", 2),
                    nextEligibleAtMs = item.optLong("next_eligible_at_ms"),
                    blockedReason = item.optString("blocked_reason"),
                    joinPolicy = joinPolicy,
                    summary = item.optString("summary"),
                    completionStatus = item.optString("completion_status"),
                    resultSummary = item.optString("result_summary"),
                    source = item.optString("source"),
                    leaseOwner = item.optString("lease_owner"),
                    leaseToken = item.optString("lease_token"),
                    leaseAcquiredAtMs = item.optLong("lease_acquired_at_ms"),
                    leaseExpiresAtMs = item.optLong("lease_expires_at_ms"),
                    createdAtMs = item.optLong("created_at_ms"),
                    updatedAtMs = item.optLong("updated_at_ms"),
                    lastAttemptAtMs = item.optLong("last_attempt_at_ms"),
                    metadata = metadata,
                ),
            )
        }
    }
}
