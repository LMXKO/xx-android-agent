package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.PlannerProviderPolicyStore
import com.lmx.xiaoxuanagent.assistantos.AssistantAnalyticsStore
import com.lmx.xiaoxuanagent.assistantos.AssistantExternalSignalStore
import com.lmx.xiaoxuanagent.assistantos.AssistantProductShellStore
import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskStore
import com.lmx.xiaoxuanagent.assistantos.AssistantSignalProviderStore
import com.lmx.xiaoxuanagent.assistantos.AssistantVoiceInteractionStore
import com.lmx.xiaoxuanagent.memory.MemoryWorkspaceGovernance
import com.lmx.xiaoxuanagent.memory.PersonalMemoryInsightSnapshot
import com.lmx.xiaoxuanagent.memory.PersonalMemoryStore
import org.json.JSONArray
import org.json.JSONObject

internal object SessionPlatformBundleOps {
    fun exportSessionBundle(
        sessionId: String,
        eventLimit: Int = 20,
    ): JSONObject {
        val snapshot = SessionPlatformFacade.readSessionSnapshot(sessionId = sessionId, eventLimit = eventLimit)
        PlatformTraceStore.record(
            category = "bundle_export",
            sessionId = sessionId,
            summary = "bundle schema=${SessionPlatformFacade.BUNDLE_SCHEMA_VERSION} exported",
        )
        return JSONObject().apply {
            put("schema_version", SessionPlatformFacade.BUNDLE_SCHEMA_VERSION)
            put("exported_at_ms", System.currentTimeMillis())
            put("session_id", snapshot.sessionId)
            put("bridge_snapshot", snapshot.bridgeSnapshot.toJson())
            put("runtime_state", snapshot.state.toJson())
            put("metrics_snapshot", snapshot.metricsSnapshot.toJson())
            put("health_summary", snapshot.healthSummary.toJson())
            put("resume_snapshot", SessionResumeStore.readSessionJson(snapshot.sessionId) ?: snapshot.resumeSnapshot?.toJson() ?: JSONObject())
            put("session_snapshot", ReplayStore.readSessionJson(snapshot.sessionId) ?: snapshot.sessionSnapshot?.toJson() ?: JSONObject())
            put(
                "bridge_events",
                JSONArray().apply {
                    snapshot.recentBridgeEvents.forEach { event ->
                        put(event.toJson())
                    }
                },
            )
            put(
                "runtime_events",
                JSONArray().apply {
                    snapshot.recentRuntimeEvents.forEach { event ->
                        put(event.toJson())
                    }
                },
            )
            put(
                "artifacts",
                JSONArray().apply {
                    ArtifactStore.exportArtifacts(sessionId = snapshot.sessionId, limit = eventLimit).forEach(::put)
                },
            )
            put("remote_bridge_snapshot", RemoteBridgeStore.readSnapshot(requestLimit = eventLimit, eventLimit = eventLimit).toJson())
            put(
                "worker_queue",
                JSONArray().apply {
                    SessionWorkerStore.readAll(eventLimit).forEach { worker ->
                        put(worker.toJson())
                    }
                },
            )
            put(
                "worker_graph",
                SessionWorkerStore.readGraphSnapshot(limit = eventLimit).toJson(),
            )
            put(
                "session_graph_nodes",
                JSONArray().apply {
                    SessionSessionGraphStore.readAll(limit = eventLimit, rootSessionId = snapshot.sessionId).forEach { node ->
                        put(node.toJson())
                    }
                },
            )
            put(
                "execution_scheduler_snapshot",
                SessionExecutionCoordinatorStore.exportJson(),
            )
            put(
                "worker_mailbox",
                SessionWorkerMailboxStore.exportJson(eventLimit),
            )
            put(
                "proactive_tasks",
                JSONArray().apply {
                    AssistantProactiveTaskStore.readAll(eventLimit).forEach { task ->
                        put(task.toJson())
                    }
                },
            )
            put(
                "platform_traces",
                JSONArray().apply {
                    PlatformTraceStore.readRecent(sessionId = snapshot.sessionId, limit = eventLimit).forEach { trace ->
                        put(trace.toJson())
                    }
                },
            )
            put("remote_transport_snapshot", RemoteTransportStore.readSnapshot(inboundLimit = eventLimit, outboundLimit = eventLimit).toJson())
            put("background_memory_queue", BackgroundMemoryExtractor.exportJson(eventLimit))
            put("artifact_retention_policy", ArtifactRetentionPolicyStore.exportJson())
            put("product_shell_snapshot", AssistantProductShellStore.exportJson())
            put("assistant_analytics", AssistantAnalyticsStore.exportJson())
            put("memory_workspace_snapshot", MemoryWorkspaceGovernance.exportJson())
            put("session_history_snapshot", SessionHistoryService.readHistory(limit = eventLimit).toJson())
            put("product_diagnostics_snapshot", SessionPlatformFacade.readProductDiagnosticsSnapshot().toJson())
            put("planner_provider_policy", PlannerProviderPolicyStore.exportJson())
            put("conversation_compacts", SessionConversationCompactStore.exportJson(limit = eventLimit))
            put("voice_interaction_snapshot", AssistantVoiceInteractionStore.exportJson())
            put("memory_insight_snapshot", PersonalMemoryStore.readInsightSnapshot(limit = 4).toJson())
            put(
                "external_signals",
                JSONArray().apply {
                    AssistantExternalSignalStore.readAll(limit = eventLimit).forEach { signal ->
                        put(signal.toJson())
                    }
                },
            )
            put("agent_sidechain_events", SessionAgentSidechainStore.exportJson(eventLimit))
            put("signal_providers", AssistantSignalProviderStore.exportJson(eventLimit))
        }
    }

    fun exportRemoteViewerBundle(
        sessionId: String,
        eventLimit: Int = 20,
    ): JSONObject =
        exportSessionBundle(sessionId = sessionId, eventLimit = eventLimit).apply {
            PlatformTraceStore.record(
                category = "remote_viewer_export",
                sessionId = sessionId,
                summary = "remote viewer bundle exported",
            )
            put("viewer_mode", "remote_viewer")
            put(
                "session_diff_candidates",
                JSONArray(
                    ReplayStore.listSessionIds(limit = 8)
                        .filter { it.isNotBlank() && it != sessionId },
                ),
            )
        }

    fun importSessionBundle(
        bundle: JSONObject,
        bootstrapImportedResume: Boolean = false,
    ): SessionPlatformSnapshot? {
        val migratedBundle = migrateBundle(bundle)
        val sessionId = migratedBundle.optString("session_id")
        if (sessionId.isBlank()) return null
        migratedBundle.optJSONObject("session_snapshot")
            ?.takeIf { it.length() > 0 }
            ?.let { ReplayStore.importSessionSnapshotJson(sessionId, it) }
        migratedBundle.optJSONObject("resume_snapshot")
            ?.takeIf { it.length() > 0 }
            ?.let { json -> SessionResumeStore.importSnapshotJson(json, markLatest = bootstrapImportedResume) }
        RuntimeEventStore.importEntries(
            migratedBundle.optJSONArray("runtime_events").toRuntimeEventEntries(),
        )
        SessionBridgeProtocolStore.importEntries(
            migratedBundle.optJSONArray("bridge_events").toBridgeProtocolEntries(),
        )
        ArtifactStore.importArtifacts(
            sessionId = sessionId,
            artifacts = migratedBundle.optJSONArray("artifacts").toJsonObjects(),
        )
        RemoteBridgeStore.importSnapshot(migratedBundle.optJSONObject("remote_bridge_snapshot").toRemoteBridgeSnapshot())
        SessionWorkerStore.importRecords(migratedBundle.optJSONArray("worker_queue").toWorkerRecords())
        SessionSessionGraphStore.importJson(migratedBundle.optJSONArray("session_graph_nodes"))
        SessionExecutionCoordinatorStore.importJson(migratedBundle.optJSONObject("execution_scheduler_snapshot"))
        SessionWorkerMailboxStore.importJson(migratedBundle.optJSONArray("worker_mailbox"))
        AssistantProactiveTaskStore.importTasks(migratedBundle.optJSONArray("proactive_tasks").toProactiveTasks())
        PlatformTraceStore.importEntries(migratedBundle.optJSONArray("platform_traces").toTraceEntries())
        RemoteTransportStore.importSnapshot(migratedBundle.optJSONObject("remote_transport_snapshot").toRemoteTransportSnapshot())
        BackgroundMemoryExtractor.importJson(migratedBundle.optJSONArray("background_memory_queue"))
        ArtifactRetentionPolicyStore.importJson(migratedBundle.optJSONObject("artifact_retention_policy"))
        AssistantProductShellStore.importJson(migratedBundle.optJSONObject("product_shell_snapshot"))
        AssistantAnalyticsStore.importJson(migratedBundle.optJSONObject("assistant_analytics"))
        MemoryWorkspaceGovernance.importJson(migratedBundle.optJSONObject("memory_workspace_snapshot"))
        PlannerProviderPolicyStore.importJson(migratedBundle.optJSONObject("planner_provider_policy"))
        SessionConversationCompactStore.importJson(migratedBundle.optJSONObject("conversation_compacts"))
        AssistantVoiceInteractionStore.importJson(migratedBundle.optJSONObject("voice_interaction_snapshot"))
        val importedMemoryInsight = migratedBundle.optJSONObject("memory_insight_snapshot").toMemoryInsightSnapshot()
        PersonalMemoryStore.importInsightSnapshot(importedMemoryInsight)
        AssistantProductShellStore.update { current ->
            current.copy(
                memoryInsight =
                    if (importedMemoryInsight.summary.isNotBlank() || importedMemoryInsight.consolidationSummary.isNotBlank()) {
                        PersonalMemoryStore.readInsightSnapshot(limit = 4)
                    } else {
                        current.memoryInsight
                    },
                voiceInteraction = AssistantVoiceInteractionStore.read(),
            )
        }
        AssistantExternalSignalStore.importSignals(migratedBundle.optJSONArray("external_signals").toExternalSignals())
        SessionAgentSidechainStore.importJson(migratedBundle.optJSONArray("agent_sidechain_events"))
        AssistantSignalProviderStore.importJson(migratedBundle.optJSONArray("signal_providers"))
        RuntimeMetricsStore.recordPlatformEvent("import_session_bundle")
        PlatformTraceStore.record(
            category = "bundle_import",
            sessionId = sessionId,
            summary = "bundle schema=${migratedBundle.optInt("schema_version", 1)} imported",
        )
        if (bootstrapImportedResume) {
            SessionRuntime.bootstrapFromResumeSnapshot(sessionId)
        }
        return SessionPlatformFacade.readSessionSnapshot(sessionId = sessionId)
    }

    fun migrateBundle(
        bundle: JSONObject,
    ): JSONObject {
        val steps =
            listOf<(JSONObject) -> JSONObject>(
                { json ->
                    if (json.optInt("schema_version", 1) >= 2) {
                        json
                    } else {
                        json.apply {
                            put("schema_version", 2)
                            if (!has("metrics_snapshot")) put("metrics_snapshot", JSONObject())
                            if (!has("health_summary")) put("health_summary", JSONObject())
                        }
                    }
                },
                { json ->
                    if (json.optInt("schema_version", 1) >= 3) {
                        json
                    } else {
                        json.apply {
                            put("schema_version", 3)
                            if (!has("remote_bridge_snapshot")) put("remote_bridge_snapshot", JSONObject())
                            if (!has("worker_queue")) put("worker_queue", JSONArray())
                            if (!has("proactive_tasks")) put("proactive_tasks", JSONArray())
                            if (!has("platform_traces")) put("platform_traces", JSONArray())
                        }
                    }
                },
                { json ->
                    if (json.optInt("schema_version", 1) >= 4) {
                        json
                    } else {
                        json.apply {
                            put("schema_version", 4)
                            if (!has("remote_transport_snapshot")) put("remote_transport_snapshot", JSONObject())
                            if (!has("external_signals")) put("external_signals", JSONArray())
                        }
                    }
                },
                { json ->
                    if (json.optInt("schema_version", 1) >= 5) {
                        json
                    } else {
                        json.apply {
                            put("schema_version", 5)
                            if (!has("agent_sidechain_events")) put("agent_sidechain_events", JSONArray())
                            if (!has("signal_providers")) put("signal_providers", JSONArray())
                        }
                    }
                },
                { json ->
                    if (json.optInt("schema_version", 1) >= 6) {
                        json
                    } else {
                        json.apply {
                            put("schema_version", 6)
                            if (!has("session_graph_nodes")) put("session_graph_nodes", JSONArray())
                        }
                    }
                },
                { json ->
                    if (json.optInt("schema_version", 1) >= 7) {
                        json
                    } else {
                        json.apply {
                            put("schema_version", 7)
                            if (!has("execution_scheduler_snapshot")) put("execution_scheduler_snapshot", JSONObject())
                        }
                    }
                },
                { json ->
                    if (json.optInt("schema_version", 1) >= 8) {
                        json
                    } else {
                        json.apply {
                            put("schema_version", 8)
                            if (!has("worker_mailbox")) put("worker_mailbox", JSONArray())
                            if (!has("background_memory_queue")) put("background_memory_queue", JSONArray())
                            if (!has("artifact_retention_policy")) put("artifact_retention_policy", JSONObject())
                        }
                    }
                },
                { json ->
                    if (json.optInt("schema_version", 1) >= 9) {
                        json
                    } else {
                        json.apply {
                            put("schema_version", 9)
                            if (!has("product_shell_snapshot")) put("product_shell_snapshot", JSONObject())
                        }
                    }
                },
                { json ->
                    if (json.optInt("schema_version", 1) >= 10) {
                        json
                    } else {
                        json.apply {
                            put("schema_version", 10)
                            if (!has("planner_provider_policy")) put("planner_provider_policy", JSONObject())
                        }
                    }
                },
                { json ->
                    if (json.optInt("schema_version", 1) >= 14) {
                        json
                    } else {
                        json.apply {
                            put("schema_version", 14)
                            if (!has("assistant_analytics")) put("assistant_analytics", JSONObject())
                            if (!has("memory_workspace_snapshot")) put("memory_workspace_snapshot", JSONObject())
                            if (!has("session_history_snapshot")) put("session_history_snapshot", JSONObject())
                            if (!has("product_diagnostics_snapshot")) put("product_diagnostics_snapshot", JSONObject())
                        }
                    }
                },
                { json ->
                    if (json.optInt("schema_version", 1) >= 15) {
                        json
                    } else {
                        json.apply {
                            put("schema_version", 15)
                            if (!has("conversation_compacts")) put("conversation_compacts", JSONObject())
                            if (!has("voice_interaction_snapshot")) put("voice_interaction_snapshot", JSONObject())
                            if (!has("memory_insight_snapshot")) put("memory_insight_snapshot", JSONObject())
                        }
                    }
                },
            )
        val migrated = JSONObject(bundle.toString())
        steps.forEach { step -> step(migrated) }
        return migrated.apply {
            put("schema_version", SessionPlatformFacade.BUNDLE_SCHEMA_VERSION)
        }
    }
}

private fun SessionHistorySnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("total_sessions", totalSessions)
        put("resumable_sessions", resumableSessions)
        put("failed_sessions", failedSessions)
        put("summary", summary)
        put(
            "entries",
            JSONArray().apply {
                entries.forEach { entry ->
                    put(
                        JSONObject().apply {
                            put("session_id", entry.sessionId)
                            put("title", entry.title)
                            put("task", entry.task)
                            put("status_code", entry.statusCode)
                            put("summary", entry.summary)
                            put("entry_source", entry.entrySource)
                            put("target_package_name", entry.targetPackageName)
                            put("turn_count", entry.turnCount)
                            put("updated_at_ms", entry.updatedAtMs)
                            put("resumable", entry.resumable)
                            put("pending_safety", entry.pendingSafety)
                            put("last_activity_summary", entry.lastActivitySummary)
                        },
                    )
                }
            },
        )
    }

private fun SessionPlatformProductDiagnosticsSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("analytics_total_events", analytics.totalEvents)
        put("analytics_top_counters", JSONArray(analytics.topCounters))
        put("analytics_enabled_sinks", JSONArray(analytics.enabledSinks))
        put("analytics_recent_events", JSONArray(analytics.recentEvents))
        put("diagnostics_status", productShell.diagnostics.status)
        put("diagnostics_summary", productShell.diagnostics.summary)
        put("diagnostics_lines", JSONArray(productShell.diagnostics.lines))
    }

private fun PersonalMemoryInsightSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("summary", summary)
        put("away_summary", awaySummary)
        put("thinkback_summary", thinkbackSummary)
        put("consolidation_summary", consolidationSummary)
        put("dream_summary", dreamSummary)
        put("suggestion_summary", suggestionSummary)
        put("away_lines", JSONArray(awayLines))
        put("thinkback_lines", JSONArray(thinkbackLines))
        put("consolidation_lines", JSONArray(consolidationLines))
        put("dream_lines", JSONArray(dreamLines))
        put("recommended_commands", JSONArray(recommendedCommands))
        put("updated_at_ms", updatedAtMs)
    }

private fun JSONObject?.toMemoryInsightSnapshot(): PersonalMemoryInsightSnapshot {
    if (this == null) return PersonalMemoryInsightSnapshot()
    return PersonalMemoryInsightSnapshot(
        summary = optString("summary"),
        awaySummary = optString("away_summary"),
        thinkbackSummary = optString("thinkback_summary"),
        consolidationSummary = optString("consolidation_summary"),
        dreamSummary = optString("dream_summary"),
        suggestionSummary = optString("suggestion_summary"),
        awayLines = optJSONArray("away_lines").toBundleStringList(),
        thinkbackLines = optJSONArray("thinkback_lines").toBundleStringList(),
        consolidationLines = optJSONArray("consolidation_lines").toBundleStringList(),
        dreamLines = optJSONArray("dream_lines").toBundleStringList(),
        recommendedCommands = optJSONArray("recommended_commands").toBundleStringList(),
        updatedAtMs = optLong("updated_at_ms"),
    )
}

private fun JSONArray?.toBundleStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}
