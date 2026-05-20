package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.PlannerProviderBackend
import com.lmx.xiaoxuanagent.agent.PlannerProviderCostTier
import com.lmx.xiaoxuanagent.agent.PlannerProviderKey
import com.lmx.xiaoxuanagent.agent.PlannerProviderLatencyTier
import com.lmx.xiaoxuanagent.agent.PlannerProviderModality
import com.lmx.xiaoxuanagent.agent.PlannerProviderRegistryStore
import com.lmx.xiaoxuanagent.assistantos.PersistentAssistantEngine
import com.lmx.xiaoxuanagent.memory.MemoryRecallIndexStore

internal suspend fun dispatchRuntimeCapability(
    request: SessionCapabilityRequest,
): SessionCapabilityResult =
    when (request.key) {
        SessionCapabilityKey.REFRESH_ASSISTANT_OS -> {
            PersistentAssistantEngine.start(
                reason = request.entrySource.ifBlank { "capability_refresh_assistant_os" },
            )
            SessionCapabilityResult(
                success = true,
                summary = "assistant OS 已刷新。",
                sessionId = SessionRuntime.State.runtimeState().session.sessionId,
                payloadLines =
                    listOf(
                        "active_session=${SessionRuntime.State.runtimeState().session.sessionId.ifBlank { "-" }}",
                        "pending_remote=${SessionPlatformFacade.readRemoteTransportSnapshot().pendingInbound.size}",
                        "pending_workers=${SessionWorkerStore.readDispatchableWorkers(limit = 8).size}",
                    ),
            )
        }

        SessionCapabilityKey.START_SESSION -> {
            val result =
                SessionRuntimeKernel.startTask(
                    task = request.task,
                    entrySource = request.entrySource,
                    sessionId = request.payload["session_id"].orEmpty().ifBlank { "session_${System.currentTimeMillis()}" },
                    metadata = request.payload,
                )
            SessionCapabilityResult(
                success = result.success,
                summary = result.summary,
                sessionId = result.sessionId,
                workerId = result.workerId,
                payloadLines = result.lines,
            )
        }

        SessionCapabilityKey.RESUME_SESSION -> {
            val result =
                SessionRuntimeKernel.resumeSession(
                    sessionId = request.sessionId,
                    userCorrection = request.userCorrection,
                )
            SessionCapabilityResult(
                success = result.success,
                summary = result.summary,
                sessionId = result.sessionId,
            )
        }

        SessionCapabilityKey.STOP_SESSION -> {
            val result =
                SessionRuntimeKernel.stopSession(
                    sessionId = request.sessionId,
                    reason = request.payload["reason"].orEmpty().ifBlank { "已通过 capability bus 停止任务。" },
                )
            SessionCapabilityResult(
                success = result.success,
                summary = result.summary,
                sessionId = result.sessionId,
            )
        }

        SessionCapabilityKey.APPROVE_SAFETY -> {
            val result =
                SessionRuntimeKernel.approveSafety(
                    sessionId = request.sessionId,
                    userCorrection = request.userCorrection,
                )
            SessionCapabilityResult(
                success = result.success,
                summary = result.summary,
                sessionId = result.sessionId,
            )
        }

        SessionCapabilityKey.REJECT_SAFETY -> {
            val result = SessionRuntimeKernel.rejectSafety(request.sessionId)
            SessionCapabilityResult(
                success = result.success,
                summary = result.summary,
                sessionId = result.sessionId,
            )
        }

        SessionCapabilityKey.FORK_WORKER -> {
            val result =
                SessionRuntimeKernel.enqueueWorkerFork(
                    parentSessionId = request.sessionId,
                    task = request.task,
                    source = request.entrySource,
                    summary = request.payload["summary"].orEmpty(),
                    metadata = request.payload,
                )
            SessionCapabilityResult(
                success = result.success,
                summary = result.summary,
                workerId = result.workerId,
                payloadLines = result.lines,
            )
        }

        else -> SessionCapabilityResult(false, "unsupported_runtime_capability:${request.key.name.lowercase()}")
    }

internal suspend fun dispatchInspectorCapability(
    request: SessionCapabilityRequest,
): SessionCapabilityResult =
    when (request.key) {
        SessionCapabilityKey.SEARCH_ARTIFACTS -> {
            val hits =
                SessionPlatformFacade.searchArtifacts(
                    query = request.query,
                    sessionId = request.sessionId,
                    limit = request.payload["limit"]?.toIntOrNull() ?: 6,
                )
            SessionCapabilityResult(
                success = hits.isNotEmpty(),
                summary = if (hits.isEmpty()) "没有命中 artifact。" else "artifact 检索完成。",
                sessionId = request.sessionId,
                payloadLines =
                    hits.map { hit ->
                        "${hit.record.type} | ${hit.sessionId.ifBlank { "-" }} | ${hit.record.summary.ifBlank { hit.record.artifactId }}"
                    },
            )
        }

        SessionCapabilityKey.RECALL_MEMORY -> {
            val hits =
                MemoryRecallIndexStore.search(
                    query = request.query.ifBlank { request.task },
                    profileId = request.payload["profile_id"].orEmpty(),
                    limit = request.payload["limit"]?.toIntOrNull() ?: 6,
                )
            SessionCapabilityResult(
                success = hits.isNotEmpty(),
                summary = if (hits.isEmpty()) "没有命中长期记忆。" else "长期记忆检索完成。",
                sessionId = request.sessionId,
                payloadLines = hits.map { "${it.type} | ${it.preview}" },
            )
        }

        SessionCapabilityKey.COMPARE_SESSIONS -> {
            val leftSessionId = request.sessionId.ifBlank { request.payload["left_session_id"].orEmpty() }
            val rightSessionId = request.payload["right_session_id"].orEmpty()
            val diff =
                SessionPlatformFacade.compareSessions(
                    leftSessionId = leftSessionId,
                    rightSessionId = rightSessionId,
                )
            SessionCapabilityResult(
                success = diff.lines.isNotEmpty(),
                summary = diff.summary,
                sessionId = leftSessionId,
                payloadLines = diff.lines,
            )
        }

        SessionCapabilityKey.BATCH_REPLAY -> {
            val sessionIds =
                buildList {
                    request.sessionId.takeIf { it.isNotBlank() }?.let(::add)
                    request.payload["session_ids"]
                        ?.split(",")
                        ?.map(String::trim)
                        ?.filter(String::isNotBlank)
                        ?.forEach(::add)
                }.distinct()
            val report =
                SessionPlatformFacade.runBatchDeterministicReplay(
                    sessionIds = if (sessionIds.isEmpty()) ReplayStore.listSessionIds(limit = 4) else sessionIds,
                )
            SessionCapabilityResult(
                success = report.totalSessions > 0,
                summary = "batch replay: matched=${report.matchedSessions} mismatched=${report.mismatchedSessions}",
                payloadLines = report.lines,
            )
        }

        SessionCapabilityKey.INSPECT_REPLAY -> {
            val commandIndex = request.payload["command_index"]?.toIntOrNull() ?: 0
            val report =
                SessionPlatformFacade.inspectReplayCommand(
                    sessionId = request.sessionId,
                    commandIndex = commandIndex,
                )
            SessionCapabilityResult(
                success = report.totalCommands > 0,
                summary = report.summary,
                sessionId = request.sessionId,
                payloadLines = report.lines,
            )
        }

        SessionCapabilityKey.INSPECT_REPLAY_STATE -> {
            val commandIndex = request.payload["command_index"]?.toIntOrNull() ?: 0
            val report =
                SessionPlatformFacade.readReplayInspectState(
                    sessionId = request.sessionId,
                    commandIndex = commandIndex,
                )
            SessionCapabilityResult(
                success = report.totalCommands > 0,
                summary = report.summary,
                sessionId = request.sessionId,
                payloadLines = report.lines,
            )
        }

        SessionCapabilityKey.INSPECT_REPLAY_ARTIFACTS -> {
            val commandIndex = request.payload["command_index"]?.toIntOrNull() ?: 0
            val report =
                SessionPlatformFacade.readReplayArtifactsForCommand(
                    sessionId = request.sessionId,
                    commandIndex = commandIndex,
                )
            SessionCapabilityResult(
                success = report.artifacts.isNotEmpty(),
                summary = report.summary,
                sessionId = request.sessionId,
                payloadLines = report.lines,
            )
        }

        SessionCapabilityKey.COMPARE_REPLAY_STEPS -> {
            val leftCommandIndex = request.payload["left_command_index"]?.toIntOrNull() ?: 0
            val rightCommandIndex = request.payload["right_command_index"]?.toIntOrNull() ?: 0
            val report =
                SessionPlatformFacade.compareReplaySteps(
                    sessionId = request.sessionId,
                    leftCommandIndex = leftCommandIndex,
                    rightCommandIndex = rightCommandIndex,
                )
            SessionCapabilityResult(
                success = report.totalCommands > 0,
                summary = report.summary,
                sessionId = request.sessionId,
                payloadLines = report.lines,
            )
        }

        SessionCapabilityKey.INSPECT_REPLAY_BREAKPOINT -> {
            val commandIndex = request.payload["command_index"]?.toIntOrNull() ?: 0
            val report =
                SessionPlatformFacade.inspectReplayBreakpoint(
                    sessionId = request.sessionId,
                    commandIndex = commandIndex,
                )
            SessionCapabilityResult(
                success = report.totalCommands > 0,
                summary = report.summary,
                sessionId = request.sessionId,
                payloadLines =
                    listOf(
                        "command_type=${report.commandType.ifBlank { "-" }}",
                        "status=${report.statusCode.ifBlank { "-" }}",
                        "turn=${report.turn}",
                        "swarm_mode=${report.swarmMode.ifBlank { "-" }}",
                        "swarm_summary=${report.swarmSummary.ifBlank { "-" }}",
                        "graph=${report.graphSummary.ifBlank { "-" }}",
                        "mailbox=${report.mailboxSummary.ifBlank { "-" }}",
                        "scheduler=${report.schedulerSummary.ifBlank { "-" }}",
                        "providers=${report.providerSummary.ifBlank { "-" }}",
                        "signals=${report.signalSummary.ifBlank { "-" }}",
                        "approvals=${report.approvalSummary.ifBlank { "-" }}",
                        "artifact_lifecycle=${report.artifactLifecycleSummary.ifBlank { "-" }}",
                        "trace_summary=${report.traceSummary.ifBlank { "-" }}",
                        "trace_count=${report.traceCount}",
                        "artifact_count=${report.artifactCount}",
                    ) + report.lines,
            )
        }

        else -> SessionCapabilityResult(false, "unsupported_inspector_capability:${request.key.name.lowercase()}")
    }

internal suspend fun dispatchGovernanceCapability(
    request: SessionCapabilityRequest,
    dispatchApproved: suspend (SessionCapabilityRequest) -> SessionCapabilityResult,
): SessionCapabilityResult {
    return when (request.key) {
        SessionCapabilityKey.READ_CAPABILITY_APPROVALS -> {
            val approvals =
                PlatformCapabilityApprovalStore.readPending(
                    limit = request.payload["limit"]?.toIntOrNull() ?: 12,
                    family = request.payload["family"].orEmpty(),
                    sessionId = request.payload["session_id"].orEmpty(),
                )
            SessionCapabilityResult(
                success = approvals.isNotEmpty(),
                summary = if (approvals.isEmpty()) "当前没有待审批 capability 请求。" else "待审批 capability 请求已读取。",
                payloadLines =
                    approvals.map { approval ->
                        "${approval.approvalId} | ${approval.permissionFamily.ifBlank { "general" }} | ${approval.riskLevel} | ${approval.capability.name.lowercase()} | ${approval.entrySource} | suggested_grant=${suggestedGrantScopeFor(approval)} | ${approval.summary} | ${approval.explanation.take(80)}"
                    },
            )
        }

        SessionCapabilityKey.APPROVE_CAPABILITY_REQUEST -> {
            val approvalId = request.payload["approval_id"].orEmpty()
            val approval =
                PlatformCapabilityApprovalStore.markApproved(approvalId)
                    ?: return SessionCapabilityResult(false, "未找到待审批请求：$approvalId")
            val grant =
                PlatformCapabilityApprovalStore.issueGrant(
                    approval = approval,
                    scope = request.payload["grant_scope"].orEmpty(),
                    ttlMinutes = request.payload["ttl_minutes"]?.toIntOrNull() ?: 0,
                    note = request.payload["note"].orEmpty(),
                )
            val result =
                dispatchApproved(
                SessionCapabilityRequest(
                    key = approval.capability,
                    sessionId = approval.sessionId,
                    task = approval.task,
                    query = approval.query,
                    entrySource = "approved_capability_request:${approval.entrySource}",
                    userCorrection = approval.userCorrection,
                    payload = approval.payload,
                ),
            )
            result.copy(
                payloadLines =
                    buildList {
                        add("approval=${approval.approvalId}")
                        grant?.let {
                            add("grant=${it.grantId} | scope=${it.scope} | family=${it.permissionFamily.ifBlank { "general" }} | expires=${it.expiresAtMs}")
                        }
                        addAll(result.payloadLines)
                    },
            )
        }

        SessionCapabilityKey.REJECT_CAPABILITY_REQUEST -> {
            val approvalId = request.payload["approval_id"].orEmpty()
            val approval =
                PlatformCapabilityApprovalStore.markRejected(approvalId)
                    ?: return SessionCapabilityResult(false, "未找到待审批请求：$approvalId")
            SessionCapabilityResult(
                success = true,
                summary = "已拒绝能力请求：${approval.approvalId}",
                sessionId = approval.sessionId,
            )
        }

        SessionCapabilityKey.READ_CAPABILITY_POLICIES -> {
            val diagnostics = SessionCapabilityPolicyStore.readDiagnostics()
            SessionCapabilityResult(
                success = diagnostics.totalRules > 0,
                summary = "capability policy 规则已读取。",
                payloadLines = diagnostics.lines,
            )
        }

        SessionCapabilityKey.READ_CAPABILITY_GRANTS -> {
            val grants =
                PlatformCapabilityApprovalStore.readGrants(
                    limit = request.payload["limit"]?.toIntOrNull() ?: 12,
                    includeInactive = request.payload["include_inactive"]?.toBooleanStrictOrNull() ?: false,
                    family = request.payload["family"].orEmpty(),
                    sessionId = request.payload["session_id"].orEmpty(),
                )
            SessionCapabilityResult(
                success = grants.isNotEmpty(),
                summary = if (grants.isEmpty()) "当前没有 capability grant。" else "capability grant 已读取。",
                payloadLines =
                    grants.map { grant ->
                        "${grant.grantId} | ${grant.scope} | ${grant.permissionFamily.ifBlank { "general" }} | ${grant.capability?.name?.lowercase() ?: "*"} | status=${grant.status.name.lowercase()} | expires=${grant.expiresAtMs} | ${grant.note.take(72)}"
                    },
            )
        }

        SessionCapabilityKey.REVOKE_CAPABILITY_GRANT -> {
            val grantId = request.payload["grant_id"].orEmpty()
            val grant =
                PlatformCapabilityApprovalStore.revokeGrant(
                    grantId = grantId,
                    note = request.payload["note"].orEmpty(),
                ) ?: return SessionCapabilityResult(false, "未找到 capability grant：$grantId")
            SessionCapabilityResult(
                success = true,
                summary = "capability grant 已撤销：${grant.grantId}",
                payloadLines =
                    listOf(
                        "scope=${grant.scope}",
                        "family=${grant.permissionFamily.ifBlank { "general" }}",
                        "capability=${grant.capability?.name?.lowercase() ?: "*"}",
                        "status=${grant.status.name.lowercase()}",
                    ),
            )
        }

        SessionCapabilityKey.UPSERT_CAPABILITY_POLICY -> {
            val decision =
                runCatching {
                    SessionCapabilityPolicyDecision.valueOf(
                        request.payload["decision"].orEmpty().uppercase(),
                    )
                }.getOrDefault(SessionCapabilityPolicyDecision.ALLOW)
            val capability =
                request.payload["capability"]
                    ?.takeIf { it.isNotBlank() && it != "*" }
                    ?.let { runCatching { SessionCapabilityKey.valueOf(it.uppercase()) }.getOrNull() }
            val rule =
                SessionCapabilityPolicyStore.upsertRule(
                    SessionCapabilityPolicyRule(
                        ruleId = request.payload["rule_id"].orEmpty(),
                        entrySourcePrefix = request.payload["entry_source_prefix"].orEmpty(),
                        capability = capability,
                        permissionFamily = request.payload["permission_family"].orEmpty(),
                        taskContains = request.payload["task_contains"].orEmpty(),
                        queryContains = request.payload["query_contains"].orEmpty(),
                        payloadKey = request.payload["payload_key"].orEmpty(),
                        payloadContains = request.payload["payload_contains"].orEmpty(),
                        decision = decision,
                        reason = request.payload["reason"].orEmpty().ifBlank { "manual_upsert" },
                        explanation = request.payload["explanation"].orEmpty(),
                        riskLevel = request.payload["risk_level"].orEmpty().ifBlank { capability?.let(::capabilityRiskLevel).orEmpty().ifBlank { "medium" } },
                        surfaceHint = request.payload["surface_hint"].orEmpty(),
                        priority = request.payload["priority"]?.toIntOrNull() ?: 0,
                        enabled = request.payload["enabled"]?.toBooleanStrictOrNull() ?: true,
                    ),
                )
            SessionCapabilityResult(
                success = true,
                summary = "capability policy 已写入：${rule.ruleId}",
                payloadLines =
                    listOf(
                        "entry=${rule.entrySourcePrefix.ifBlank { "*" }}",
                        "capability=${rule.capability?.name?.lowercase() ?: "*"}",
                        "family=${rule.permissionFamily.ifBlank { rule.capability?.let(::capabilityPermissionFamily).orEmpty().ifBlank { "general" } }}",
                        "decision=${rule.decision.name.lowercase()}",
                        "risk=${rule.riskLevel}",
                        "priority=${rule.priority}",
                    ),
            )
        }

        SessionCapabilityKey.DELETE_CAPABILITY_POLICY -> {
            val ruleId = request.payload["rule_id"].orEmpty()
            val removed = SessionCapabilityPolicyStore.removeRule(ruleId)
            SessionCapabilityResult(
                success = removed,
                summary = if (removed) "capability policy 已删除：$ruleId" else "未找到 capability policy：$ruleId",
            )
        }

        SessionCapabilityKey.READ_PROVIDER_POLICY -> {
            val policy = SessionPlatformFacade.readPlannerProviderPolicy()
            val providerFailures = com.lmx.xiaoxuanagent.agent.PlannerProviderPolicyStore.recentFailureCountsByProvider(limit = 24)
            val registry = PlannerProviderRegistryStore.read()
            SessionCapabilityResult(
                success = true,
                summary = "planner provider policy 已读取。",
                payloadLines =
                    buildList {
                        add("enabled=${policy.enabled}")
                        add("prefer_text_on_artifact_heavy_stage=${policy.preferTextOnArtifactHeavyStage}")
                        add("prefer_text_on_resume=${policy.preferTextOnResume}")
                        add("sparse_node_gap=${policy.sparseNodeVisionFailureFallbackGap}")
                        add("visual_page_gap=${policy.visualPageVisionFailureFallbackGap}")
                        add("text_failure_gap=${policy.textFailureVisionFallbackGap}")
                        add("provider_failures=${providerFailures.entries.joinToString(",") { "${it.key}:${it.value}" }.ifBlank { "-" }}")
                        add("stage_overrides=${policy.stageOverrides.entries.joinToString(",") { "${it.key}:${it.value}" }.ifBlank { "-" }}")
                        add("package_overrides=${policy.packageOverrides.entries.joinToString(",") { "${it.key}:${it.value}" }.ifBlank { "-" }}")
                        registry.forEach { provider ->
                            add(
                                "registry ${provider.providerId} | ${provider.backendId} | ${provider.modality.name.lowercase()} | enabled=${provider.enabled} | priority=${provider.priority}",
                            )
                        }
                    },
            )
        }

        SessionCapabilityKey.UPSERT_PROVIDER_POLICY -> {
            val current = SessionPlatformFacade.readPlannerProviderPolicy()
            val updated =
                SessionPlatformFacade.updatePlannerProviderPolicy(
                    current.copy(
                        enabled = request.payload["enabled"]?.toBooleanStrictOrNull() ?: current.enabled,
                        preferTextOnArtifactHeavyStage =
                            request.payload["prefer_text_on_artifact_heavy_stage"]?.toBooleanStrictOrNull()
                                ?: current.preferTextOnArtifactHeavyStage,
                        preferTextOnResume =
                            request.payload["prefer_text_on_resume"]?.toBooleanStrictOrNull()
                                ?: current.preferTextOnResume,
                        sparseNodeVisionFailureFallbackGap =
                            request.payload["sparse_node_gap"]?.toIntOrNull()
                                ?: current.sparseNodeVisionFailureFallbackGap,
                        visualPageVisionFailureFallbackGap =
                            request.payload["visual_page_gap"]?.toIntOrNull()
                                ?: current.visualPageVisionFailureFallbackGap,
                        textFailureVisionFallbackGap =
                            request.payload["text_failure_gap"]?.toIntOrNull()
                                ?: current.textFailureVisionFallbackGap,
                        stageOverrides =
                            parseProviderOverrides(
                                request.payload["stage_overrides"],
                                current.stageOverrides,
                            ),
                        packageOverrides =
                            parseProviderOverrides(
                                request.payload["package_overrides"],
                                current.packageOverrides,
                            ),
                    ),
                )
            SessionCapabilityResult(
                success = true,
                summary = "planner provider policy 已更新。",
                payloadLines =
                    listOf(
                        "enabled=${updated.enabled}",
                        "stage_overrides=${updated.stageOverrides.entries.joinToString(",") { "${it.key}:${it.value}" }.ifBlank { "-" }}",
                        "package_overrides=${updated.packageOverrides.entries.joinToString(",") { "${it.key}:${it.value}" }.ifBlank { "-" }}",
                    ),
            )
        }

        SessionCapabilityKey.READ_PROVIDER_REGISTRY -> {
            val providers = PlannerProviderRegistryStore.read()
            SessionCapabilityResult(
                success = providers.isNotEmpty(),
                summary = "planner provider registry 已读取。",
                payloadLines =
                    providers.map { provider ->
                        buildString {
                            append(provider.providerId)
                            append(" | backend=").append(provider.backendId)
                            append(" | modality=").append(provider.modality.name.lowercase())
                            append(" | route=").append(provider.routeLabel)
                            append(" | priority=").append(provider.priority)
                            append(" | latency=").append(provider.latencyTier.name.lowercase())
                            append(" | cost=").append(provider.costTier.name.lowercase())
                            append(" | enabled=").append(provider.enabled)
                            append(" | screenshot=").append(provider.supportsScreenshot)
                            append(" | structured=").append(provider.supportsStructuredOutput)
                            append(" | capabilities=").append(provider.capabilities.joinToString(",").ifBlank { "-" })
                        }
                    },
            )
        }

        SessionCapabilityKey.UPSERT_PROVIDER_REGISTRY -> {
            val providerId =
                PlannerProviderKey.resolveProviderId(request.payload["provider_id"].orEmpty())
                    ?: request.payload["provider_id"].orEmpty().trim().lowercase()
            if (providerId.isBlank()) {
                return SessionCapabilityResult(false, "更新 provider registry 需要提供 provider_id。")
            }
            val updated =
                PlannerProviderRegistryStore.upsert(providerId) { current ->
                    current.copy(
                        modality =
                            request.payload["modality"]
                                ?.uppercase()
                                ?.let { runCatching { PlannerProviderModality.valueOf(it) }.getOrNull() }
                                ?: current.modality,
                        routeLabel = request.payload["route_label"].orEmpty().ifBlank { current.routeLabel },
                        backendId =
                            PlannerProviderBackend.resolveBackendId(
                                request.payload["backend_id"].orEmpty().ifBlank {
                                    request.payload["backend"].orEmpty()
                                },
                            ) ?: current.backendId,
                        priority = request.payload["priority"]?.toIntOrNull() ?: current.priority,
                        latencyTier =
                            request.payload["latency_tier"]
                                ?.uppercase()
                                ?.let { runCatching { PlannerProviderLatencyTier.valueOf(it) }.getOrNull() }
                                ?: current.latencyTier,
                        costTier =
                            request.payload["cost_tier"]
                                ?.uppercase()
                                ?.let { runCatching { PlannerProviderCostTier.valueOf(it) }.getOrNull() }
                                ?: current.costTier,
                        supportsScreenshot =
                            request.payload["supports_screenshot"]?.toBooleanStrictOrNull()
                                ?: current.supportsScreenshot,
                        supportsStructuredOutput =
                            request.payload["supports_structured_output"]?.toBooleanStrictOrNull()
                                ?: current.supportsStructuredOutput,
                        enabled = request.payload["enabled"]?.toBooleanStrictOrNull() ?: current.enabled,
                        tags =
                            request.payload["tags"]
                                ?.split(",", "|")
                                ?.mapNotNull { it.trim().takeIf(String::isNotBlank) }
                                ?.toSet()
                                ?.takeIf { it.isNotEmpty() }
                                ?: current.tags,
                        capabilities =
                            request.payload["capabilities"]
                                ?.split(",", "|")
                                ?.mapNotNull { it.trim().takeIf(String::isNotBlank) }
                                ?.toSet()
                                ?.takeIf { it.isNotEmpty() }
                                ?: current.capabilities,
                    )
                }
            val provider = updated.firstOrNull { it.providerId == providerId }
            SessionCapabilityResult(
                success = provider != null,
                summary = "planner provider registry 已更新。",
                payloadLines =
                    provider?.let {
                        listOf(
                            "provider_id=${it.providerId}",
                            "backend=${it.backendId}",
                            "modality=${it.modality.name.lowercase()}",
                            "route_label=${it.routeLabel}",
                            "priority=${it.priority}",
                            "latency=${it.latencyTier.name.lowercase()}",
                            "cost=${it.costTier.name.lowercase()}",
                            "enabled=${it.enabled}",
                            "capabilities=${it.capabilities.joinToString(",").ifBlank { "-" }}",
                        )
                    }.orEmpty(),
            )
        }

        else -> SessionCapabilityResult(false, "unsupported_governance_capability:${request.key.name.lowercase()}")
    }
}

internal suspend fun dispatchPlatformControlCapability(
    request: SessionCapabilityRequest,
): SessionCapabilityResult =
    when (request.key) {
        SessionCapabilityKey.READ_AGENT_SIDECHAINS -> {
            val snapshots =
                SessionPlatformFacade.readAgentSidechainSnapshots(
                    rootSessionId = request.sessionId,
                    limit = request.payload["limit"]?.toIntOrNull() ?: 8,
                )
            SessionCapabilityResult(
                success = snapshots.isNotEmpty(),
                summary = if (snapshots.isEmpty()) "没有可读的 sidechain。" else "sidechain 快照已读取。",
                sessionId = request.sessionId,
                payloadLines =
                    snapshots.map { snapshot ->
                        "${snapshot.agentId} | ${snapshot.latestStage.name.lowercase()} | ${snapshot.latestSummary.ifBlank { snapshot.workerId }}"
                    },
            )
        }

        SessionCapabilityKey.READ_SIGNAL_PROVIDERS,
        SessionCapabilityKey.READ_WORKER_MAILBOX,
        SessionCapabilityKey.POST_WORKER_MESSAGE,
        SessionCapabilityKey.ACK_WORKER_MESSAGE,
        ->
            dispatchPlatformWorkerCapability(request)

        SessionCapabilityKey.READ_MEMORY_QUEUE,
        SessionCapabilityKey.RETRY_MEMORY_TASK,
        SessionCapabilityKey.READ_MEMORY_WORKSPACE,
        SessionCapabilityKey.READ_MEMORY_GOVERNANCE,
        SessionCapabilityKey.UPSERT_MEMORY_ENTRY,
        SessionCapabilityKey.DELETE_MEMORY_ENTRY,
        SessionCapabilityKey.READ_SESSION_HISTORY,
        SessionCapabilityKey.SEARCH_SESSION_HISTORY,
        SessionCapabilityKey.READ_SESSION_COMPENSATIONS,
        SessionCapabilityKey.RUN_SESSION_COMPENSATION,
        SessionCapabilityKey.READ_PRODUCT_SHELL,
        SessionCapabilityKey.READ_PRODUCT_DIAGNOSTICS,
        SessionCapabilityKey.READ_CURRENT_SCREEN,
        SessionCapabilityKey.READ_REGRESSION_PLAN,
        SessionCapabilityKey.RUN_REGRESSION_PLAN,
        SessionCapabilityKey.REFRESH_PRODUCT_SHELL,
        SessionCapabilityKey.ACK_PRODUCT_SHELL_TIP,
            SessionCapabilityKey.UPDATE_PRODUCT_ONBOARDING,
            SessionCapabilityKey.READ_COMMAND_CENTER,
            SessionCapabilityKey.READ_SESSION_EXPLANATION_LOG,
            SessionCapabilityKey.READ_SESSION_MEMORY_NOTEBOOK,
            SessionCapabilityKey.READ_TOOL_CATALOG,
            SessionCapabilityKey.READ_FOLLOW_UP_QUEUE,
            SessionCapabilityKey.COMPLETE_FOLLOW_UP,
            SessionCapabilityKey.DEFER_FOLLOW_UP,
            SessionCapabilityKey.READ_SAFETY_CENTER,
            SessionCapabilityKey.READ_SAFETY_POLICIES,
            SessionCapabilityKey.UPSERT_SAFETY_POLICY,
            SessionCapabilityKey.DELETE_SAFETY_POLICY,
            SessionCapabilityKey.REVOKE_RUNTIME_SAFETY_GRANT,
            SessionCapabilityKey.READ_ARTIFACT_RETENTION,
            SessionCapabilityKey.RUN_ARTIFACT_RETENTION,
        SessionCapabilityKey.UPSERT_ARTIFACT_RETENTION,
        SessionCapabilityKey.READ_OPERATOR_CONSOLE,
        ->
            dispatchPlatformAssistantCapability(request)

        else -> SessionCapabilityResult(false, "unsupported_platform_capability:${request.key.name.lowercase()}")
    }

private fun parseProviderOverrides(
    raw: String?,
    current: Map<String, String>,
): Map<String, String> {
    if (raw == null) return current
    if (raw.isBlank()) return emptyMap()
    return raw
        .split(",")
        .mapNotNull { token ->
            val trimmed = token.trim()
            if (trimmed.isBlank()) {
                null
            } else {
                val separatorIndex = trimmed.indexOf('=').takeIf { it >= 0 } ?: trimmed.indexOf(':')
                if (separatorIndex <= 0 || separatorIndex >= trimmed.lastIndex) {
                    null
                } else {
                    val key = trimmed.substring(0, separatorIndex).trim()
                    val providerId =
                        com.lmx.xiaoxuanagent.agent.PlannerProviderKey.resolveProviderId(
                            trimmed.substring(separatorIndex + 1).trim(),
                        )
                    if (key.isBlank() || providerId.isNullOrBlank()) null else key to providerId
                }
            }
        }.toMap()
}
