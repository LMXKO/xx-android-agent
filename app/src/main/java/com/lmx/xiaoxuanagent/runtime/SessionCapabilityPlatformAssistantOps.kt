package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentToolCatalog
import com.lmx.xiaoxuanagent.agent.ConnectedAppCatalog
import com.lmx.xiaoxuanagent.agent.ConnectedAppExecutionReceiptStore
import com.lmx.xiaoxuanagent.agent.ConnectedAppGoldenPathRegistry
import com.lmx.xiaoxuanagent.agent.PlannerProviderRegistryStore
import com.lmx.xiaoxuanagent.accessibility.AgentAccessibilityService
import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskStore
import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskType
import com.lmx.xiaoxuanagent.harness.HarnessStore
import com.lmx.xiaoxuanagent.harness.RegressionRunner
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyPolicyBehavior
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyPolicyStore
import com.lmx.xiaoxuanagent.safety.behaviorSummary
import com.lmx.xiaoxuanagent.safety.scopeSummary

internal suspend fun dispatchPlatformAssistantCapability(
    request: SessionCapabilityRequest,
): SessionCapabilityResult {
    return when (request.key) {
        SessionCapabilityKey.READ_MEMORY_QUEUE -> {
            val tasks =
                SessionPlatformFacade.readBackgroundMemoryQueue(
                    includeCompleted = request.payload["include_completed"]?.toBooleanStrictOrNull() ?: false,
                    limit = request.payload["limit"]?.toIntOrNull() ?: 12,
                )
            SessionCapabilityResult(
                success = tasks.isNotEmpty(),
                summary = if (tasks.isEmpty()) "后台记忆队列为空。" else "后台记忆队列已读取。",
                payloadLines =
                    tasks.map { task ->
                        "${task.taskId} | ${task.type.name.lowercase()} | ${task.status.name.lowercase()} | retry=${task.retryCount}/${task.maxRetries} | ${task.task.ifBlank { task.profileId }}"
                    },
            )
        }

        SessionCapabilityKey.RETRY_MEMORY_TASK -> {
            val task =
                SessionPlatformFacade.retryBackgroundMemoryTask(
                    taskId = request.payload["task_id"].orEmpty(),
                )
            SessionCapabilityResult(
                success = task != null,
                summary = if (task == null) "未找到后台记忆任务。" else "后台记忆任务已重新排队：${task.taskId}",
            )
        }

        SessionCapabilityKey.READ_MEMORY_WORKSPACE -> {
            val snapshot = SessionPlatformFacade.readMemoryWorkspaceSnapshot()
            SessionCapabilityResult(
                success = snapshot.totalFiles > 0,
                summary = if (snapshot.totalFiles <= 0) "memory workspace 为空。" else "memory workspace 已读取。",
                payloadLines =
                    buildList {
                        add("summary=${snapshot.summary}")
                        add("root=${snapshot.rootPath.ifBlank { "-" }}")
                        add("core=${snapshot.coreMemoryPath.ifBlank { "-" }}")
                        add("digest=${snapshot.digestPath.ifBlank { "-" }}")
                        snapshot.entries.take(8).forEach { entry ->
                            add("entry ${entry.type.name.lowercase()} | ${entry.ageBucket} | ${entry.name} | ${entry.summary.take(72)}")
                        }
                    },
            )
        }

        SessionCapabilityKey.READ_MEMORY_GOVERNANCE -> {
            val snapshot =
                SessionPlatformFacade.readMemoryGovernanceSnapshot(
                    limit = request.payload["limit"]?.toIntOrNull() ?: 16,
                    auditLimit = request.payload["audit_limit"]?.toIntOrNull() ?: 8,
                )
            SessionCapabilityResult(
                success = snapshot.totalEntries > 0,
                summary = if (snapshot.totalEntries <= 0) "长期记忆治理面为空。" else "长期记忆治理面已读取。",
                payloadLines =
                    buildList {
                        add("summary=${snapshot.summary}")
                        add("workspace=${snapshot.workspaceSummary.ifBlank { "-" }}")
                        snapshot.entries.forEach { entry ->
                            add(
                                "memory ${entry.type.wireName} | ${entry.title.take(48)} | ${entry.summary.take(72)} | id=${entry.entryId}",
                            )
                        }
                        snapshot.auditTrail.forEach { audit ->
                            add("audit ${audit.action} | ${audit.type.wireName} | ${audit.summary.take(80)}")
                        }
                    },
            )
        }

        SessionCapabilityKey.UPSERT_MEMORY_ENTRY -> {
            val snapshot =
                SessionPlatformFacade.upsertMemoryGovernanceEntry(
                    typeWire = request.payload["type"].orEmpty(),
                    primary = request.payload["primary"].orEmpty().ifBlank { request.query.ifBlank { request.task } },
                    secondary = request.payload["secondary"].orEmpty(),
                    note = request.payload["note"].orEmpty(),
                    profileId = request.payload["profile_id"].orEmpty(),
                )
            SessionCapabilityResult(
                success = request.payload["type"].orEmpty().isNotBlank(),
                summary = "长期记忆条目已写入。",
                payloadLines =
                    buildList {
                        add("summary=${snapshot.summary}")
                        snapshot.entries.take(8).forEach { entry ->
                            add("memory ${entry.type.wireName} | ${entry.title.take(48)} | ${entry.summary.take(72)}")
                        }
                    },
            )
        }

        SessionCapabilityKey.DELETE_MEMORY_ENTRY -> {
            val snapshot =
                SessionPlatformFacade.deleteMemoryGovernanceEntry(
                    typeWire = request.payload["type"].orEmpty(),
                    entryRef = request.payload["entry_ref"].orEmpty().ifBlank { request.query.ifBlank { request.task } },
                )
            SessionCapabilityResult(
                success = request.payload["type"].orEmpty().isNotBlank(),
                summary = "长期记忆条目删除已处理。",
                payloadLines =
                    buildList {
                        add("summary=${snapshot.summary}")
                        snapshot.auditTrail.take(6).forEach { audit ->
                            add("audit ${audit.action} | ${audit.type.wireName} | ${audit.summary.take(80)}")
                        }
                    },
            )
        }

        SessionCapabilityKey.READ_SESSION_HISTORY -> {
            val history = SessionPlatformFacade.readSessionHistory(limit = request.payload["limit"]?.toIntOrNull() ?: 12)
            SessionCapabilityResult(
                success = history.entries.isNotEmpty(),
                summary = "session history 已读取。",
                payloadLines =
                    buildList {
                        add(history.summary)
                        history.entries.forEach { entry ->
                            add("${entry.sessionId} | ${entry.statusCode} | ${entry.title} | ${entry.summary.take(80)}")
                        }
                    },
            )
        }

        SessionCapabilityKey.SEARCH_SESSION_HISTORY -> {
            val result =
                SessionPlatformFacade.searchSessionHistory(
                    query = request.query.ifBlank { request.payload["query"].orEmpty() },
                    limit = request.payload["limit"]?.toIntOrNull() ?: 8,
                )
            SessionCapabilityResult(
                success = result.matches.isNotEmpty(),
                summary = result.summary,
                payloadLines =
                    buildList {
                        add("query=${result.query}")
                        result.matches.forEach { entry ->
                            add("${entry.sessionId} | ${entry.statusCode} | ${entry.title} | ${entry.lastActivitySummary.take(64)}")
                        }
                    },
            )
        }

        SessionCapabilityKey.READ_SESSION_COMPENSATIONS -> {
            val plans =
                SessionCompensationStore.readSessionPlans(
                    sessionId = request.sessionId,
                    limit = request.payload["limit"]?.toIntOrNull() ?: 8,
                )
            SessionCapabilityResult(
                success = plans.isNotEmpty(),
                summary = if (plans.isEmpty()) "当前 session 没有补偿计划。" else "session 补偿计划已读取。",
                sessionId = request.sessionId,
                payloadLines =
                    buildList {
                        plans.forEach { plan ->
                            add("plan turn=${plan.turn} | ${plan.status} | ${plan.summary}")
                            plan.steps.forEach { step ->
                                add("step ${step.stepId} | ${step.status} | ${step.title} | ${step.summary.ifBlank { step.action.label }}")
                            }
                        }
                    },
            )
        }

        SessionCapabilityKey.RUN_SESSION_COMPENSATION -> {
            val turn = request.payload["turn"]?.toIntOrNull() ?: request.payload["turn_index"]?.toIntOrNull() ?: -1
            if (request.sessionId.isBlank() || turn < 0) {
                return SessionCapabilityResult(false, "执行补偿需要提供 session_id 与 turn。")
            }
            val service = AgentAccessibilityService.current()
                ?: return SessionCapabilityResult(false, "当前无障碍服务未连接，无法执行真实补偿动作。", sessionId = request.sessionId)
            val execution =
                SessionCompensationStore.runCompensation(
                    sessionId = request.sessionId,
                    turn = turn,
                    stepId = request.payload["step_id"].orEmpty(),
                    allowHighRisk = request.payload["allow_high_risk"] == "true",
                ) { action ->
                    service.executeExternalAction(
                        action = action,
                        preferredPackageName = request.payload["preferred_package"].orEmpty(),
                    )
                }
            SessionCapabilityResult(
                success = execution.success,
                summary = execution.summary,
                sessionId = request.sessionId,
                payloadLines = execution.stepResults,
            )
        }

        SessionCapabilityKey.READ_COMMAND_CATALOG -> {
            val commandName = request.payload["command"].orEmpty()
            val prefix = request.payload["prefix"].orEmpty()
            val category =
                request.payload["category"]
                    ?.uppercase()
                    ?.let { runCatching { SessionPlatformCommandCategory.valueOf(it) }.getOrNull() }
            val lines =
                when {
                    commandName.isNotBlank() -> SessionPlatformCommandRegistry.helpLines(commandName)
                    prefix.isNotBlank() -> SessionPlatformCommandRegistry.suggest(prefix)
                    else -> SessionPlatformCommandRegistry.catalogLines(category, request.payload["limit"]?.toIntOrNull() ?: 18)
                }
            SessionCapabilityResult(
                success = lines.isNotEmpty(),
                summary =
                    when {
                        commandName.isNotBlank() -> "命令帮助已读取。"
                        prefix.isNotBlank() -> "命令建议已生成。"
                        else -> "命令目录已读取。"
                    },
                payloadLines = lines,
            )
        }

        SessionCapabilityKey.READ_PRODUCT_SHELL -> {
            val snapshot = SessionPlatformFacade.readProductShellSnapshot()
            val focusSessionId = snapshot.viewerShell.focusSessionId.ifBlank { snapshot.swarmStrategy.focusSessionIds.firstOrNull().orEmpty() }
            val swarmCoordination =
                SessionPlatformFacade.readSwarmCoordinationSnapshot(
                    activeSessionId = focusSessionId,
                    traceSessionId = focusSessionId,
                    workerGraphLimit = 12,
                    mailboxLimit = 24,
                    traceLimit = 12,
                )
            val section = request.payload["section"].orEmpty()
            val payloadLines =
                when (section) {
                    "today" ->
                        buildList {
                            add("routine=${snapshot.routineShell.mode}")
                            add("routine_summary=${snapshot.routineShell.summary.ifBlank { "-" }}")
                            add("routine_next_window=${snapshot.routineShell.nextWindowSummary.ifBlank { "-" }}")
                            snapshot.routineShell.checklistLines.forEach { add("checklist | $it") }
                            snapshot.routineShell.followUpLines.forEach { add("follow_up | $it") }
                            add("digest=${snapshot.digestShell.mode}")
                            add("digest_summary=${snapshot.digestShell.summary.ifBlank { "-" }}")
                            snapshot.digestShell.highlightLines.forEach { add("digest | $it") }
                            add("interrupt=${snapshot.interruptBudget.mode}")
                            add("interrupt_summary=${snapshot.interruptBudget.summary.ifBlank { "-" }}")
                            add("interrupt_remaining=${snapshot.interruptBudget.remainingBudget}/${snapshot.interruptBudget.totalBudget}")
                            add("memory_insight=${snapshot.memoryInsight.summary.ifBlank { "-" }}")
                            add("voice=${snapshot.voiceInteraction.state}")
                            add("voice_summary=${snapshot.voiceInteraction.summary.ifBlank { "-" }}")
                        }

                    "inbox" ->
                        buildList {
                            add("onboarding=${snapshot.onboarding.status}")
                            snapshot.onboarding.steps.forEach { step ->
                                add("step ${step.id} | ${step.status} | ${step.title}")
                            }
                            add("tips_visible=${snapshot.tips.size}")
                            snapshot.tips.forEach { tip ->
                                add("tip ${tip.id} | ${tip.status} | ${tip.title}")
                            }
                        }

                    "entry" ->
                        buildList {
                            add("interrupt=${snapshot.interruptBudget.mode}")
                            add("allowed_sources=${snapshot.interruptBudget.allowedSources.joinToString(",").ifBlank { "-" }}")
                            add("blocked_sources=${snapshot.interruptBudget.blockedSources.joinToString(",").ifBlank { "-" }}")
                            add("digest_action=${snapshot.digestShell.actionCommand.ifBlank { "-" }}")
                            add("voice_state=${snapshot.voiceInteraction.state}")
                            add("voice_availability=${snapshot.voiceInteraction.availabilitySummary.ifBlank { "-" }}")
                            add("voice_summary=${snapshot.voiceInteraction.summary.ifBlank { "-" }}")
                            add("app_preflight=${snapshot.diagnostics.appPreflightSummary.ifBlank { "-" }}")
                            snapshot.diagnostics.appPreflightLines.take(8).forEach { add("app_preflight | $it") }
                            snapshot.diagnostics.appPreflightRecommendedCommands.take(4).forEach { add("app_preflight_command | $it") }
                        }

                    "digest" ->
                        buildList {
                            add("digest=${snapshot.digestShell.mode}")
                            add("digest_title=${snapshot.digestShell.title.ifBlank { "-" }}")
                            add("digest_summary=${snapshot.digestShell.summary.ifBlank { "-" }}")
                            snapshot.digestShell.highlightLines.forEach { add("digest | $it") }
                            add("digest_action=${snapshot.digestShell.actionCommand.ifBlank { "-" }}")
                        }

                    "interrupt" ->
                        buildList {
                            add("interrupt=${snapshot.interruptBudget.mode}")
                            add("interrupt_summary=${snapshot.interruptBudget.summary.ifBlank { "-" }}")
                            add("interrupt_remaining=${snapshot.interruptBudget.remainingBudget}/${snapshot.interruptBudget.totalBudget}")
                            add("interrupt_hard_block=${snapshot.interruptBudget.hardBlock}")
                            add("interrupt_cooldown=${snapshot.interruptBudget.cooldownSummary.ifBlank { "-" }}")
                            add("allowed_sources=${snapshot.interruptBudget.allowedSources.joinToString(",").ifBlank { "-" }}")
                            add("blocked_sources=${snapshot.interruptBudget.blockedSources.joinToString(",").ifBlank { "-" }}")
                        }

                    "viewer" ->
                        buildList {
                            add("viewer=${snapshot.viewerShell.mode}")
                            add("focus_session=${snapshot.viewerShell.focusSessionId.ifBlank { "-" }}")
                            add("focus_task=${snapshot.viewerShell.focusTask.ifBlank { "-" }}")
                            add("detail_summary=${snapshot.viewerShell.detailSummary.ifBlank { "-" }}")
                            add("action_lane=${snapshot.viewerShell.actionLaneSummary.ifBlank { "-" }}")
                            snapshot.viewerShell.detailLines.forEach { add("detail | $it") }
                            snapshot.viewerShell.timelineLines.take(4).forEach { add("timeline | $it") }
                            snapshot.viewerShell.graphLines.take(4).forEach { add("graph | $it") }
                            snapshot.viewerShell.traceLines.take(4).forEach { add("trace | $it") }
                            snapshot.viewerShell.actionLines.take(4).forEach { add("action | $it") }
                        }

                    "approvals" ->
                        buildList {
                            add("approval_summary=${snapshot.viewerShell.approvalSummary.ifBlank { "-" }}")
                            snapshot.viewerShell.approvalLines.forEach { add("approval | $it") }
                            PlatformCapabilityApprovalStore.readGrants(limit = 4).forEach { grant ->
                                add(
                                    "grant | ${grant.grantId} | ${grant.scope} | ${grant.permissionFamily.ifBlank { "general" }} | ${grant.status.name.lowercase()}",
                                )
                            }
                            snapshot.governanceShell.controlLines.take(4).forEach { add("governance | $it") }
                        }

                    "timeline" ->
                        buildList {
                            add("timeline_summary=${snapshot.viewerShell.timelineSummary.ifBlank { "-" }}")
                            snapshot.viewerShell.timelineLines.forEach { add("timeline | $it") }
                            snapshot.viewerShell.traceLines.forEach { add("trace | $it") }
                        }

                    "graph" ->
                        buildList {
                            add("graph_summary=${snapshot.viewerShell.graphSummary.ifBlank { "-" }}")
                            snapshot.viewerShell.graphLines.forEach { add("graph | $it") }
                            snapshot.viewerShell.detailLines.take(3).forEach { add("detail | $it") }
                        }

                    "governance" ->
                        buildList {
                            add("governance=${snapshot.governanceShell.mode}")
                            add("governance_summary=${snapshot.governanceShell.summary.ifBlank { "-" }}")
                            add("consent=${snapshot.governanceShell.consentSummary.ifBlank { "-" }}")
                            add("privacy=${snapshot.governanceShell.privacySummary.ifBlank { "-" }}")
                            add("history=${snapshot.governanceShell.historySummary.ifBlank { "-" }}")
                            add("retention=${snapshot.governanceShell.retentionSummary.ifBlank { "-" }}")
                            add("autonomy=${snapshot.governanceShell.autonomySummary.ifBlank { "-" }}")
                            snapshot.governanceShell.explanationLines.forEach { add("explain | $it") }
                            snapshot.governanceShell.controlLines.forEach { add("control | $it") }
                            snapshot.governanceShell.actionLines.forEach { add("action | $it") }
                        }

                    "autonomy" ->
                        buildList {
                            add("mode=${snapshot.autonomyPlan.mode}")
                            add("summary=${snapshot.autonomyPlan.summary.ifBlank { "-" }}")
                            add("trigger_policy=${snapshot.autonomyPlan.triggerPolicyMode}")
                            add("restore_mode=${snapshot.autonomyPlan.restoreMode}")
                            add("proactive_mode=${snapshot.autonomyPlan.proactiveMode}")
                            add("entry_mode=${snapshot.autonomyPlan.entrySurfaceMode}")
                            add("event_priority=${snapshot.autonomyPlan.eventPrioritySummary.ifBlank { "-" }}")
                            add("user_model=${snapshot.autonomyPlan.userModelSummary.ifBlank { "-" }}")
                            snapshot.autonomyPlan.enginePhaseOrder.forEach { add("phase | $it") }
                            snapshot.autonomyPlan.recommendedCommands.forEach { add("command | $it") }
                        }

                    "routine_center" ->
                        buildList {
                            add("routine_policy=${snapshot.routinePolicy.summary.ifBlank { "-" }}")
                            add("digest_policy=${snapshot.digestPolicy.summary.ifBlank { "-" }}")
                            add("quiet_hours=${snapshot.quietHours.summary.ifBlank { "-" }}")
                            add("interrupt_policy=${snapshot.interruptPolicy.summary.ifBlank { "-" }}")
                            snapshot.routineShell.checklistLines.forEach { add("routine | $it") }
                            snapshot.routineShell.followUpLines.forEach { add("follow | $it") }
                        }

                    "swarm" ->
                        buildList {
                            add("swarm=${snapshot.swarmStrategy.mode}")
                            add("swarm_summary=${snapshot.swarmStrategy.summary.ifBlank { "-" }}")
                            add("coordination=${swarmCoordination.mode}")
                            add("coordination_summary=${swarmCoordination.summary.ifBlank { "-" }}")
                            add("coordination_parallelism=${swarmCoordination.parallelismPressureSummary.ifBlank { "-" }}")
                            add("coordination_mailbox=${swarmCoordination.mailboxCoordinationSummary.ifBlank { "-" }}")
                            add("coordination_fairness=${swarmCoordination.ownerFairnessSummary.ifBlank { "-" }}")
                            add("coordination_trace=${swarmCoordination.traceAttentionSummary.ifBlank { "-" }}")
                            swarmCoordination.dispatchCandidates.forEach { add("swarm_candidate | $it") }
                            swarmCoordination.recommendedActions.forEach { add("swarm_command | $it") }
                            swarmCoordination.recentLines.forEach { add("swarm_recent | $it") }
                        }

                    else ->
                        buildList {
                            add("onboarding=${snapshot.onboarding.status}")
                            add("onboarding_pending=${snapshot.onboarding.pendingSteps.size}")
                            add("tips_visible=${snapshot.tips.size}")
                            add("swarm=${snapshot.swarmStrategy.mode}")
                            add("swarm_summary=${snapshot.swarmStrategy.summary.ifBlank { "-" }}")
                            add("swarm_fairness=${swarmCoordination.ownerFairnessSummary.ifBlank { snapshot.swarmStrategy.fairnessMode }}")
                            add("swarm_lease_policy=${snapshot.swarmStrategy.leasePolicyMode}")
                            add("swarm_coordination=${swarmCoordination.coordinationMode.ifBlank { snapshot.swarmStrategy.coordinationMode }}")
                            add("swarm_lease_pressure=${swarmCoordination.parallelismPressureSummary.ifBlank { snapshot.swarmStrategy.leasePressureSummary.ifBlank { "-" } }}")
                            add("swarm_dispatch=${swarmCoordination.workerDispatchSummary.ifBlank { snapshot.swarmStrategy.dispatchSummary.ifBlank { "-" } }}")
                            add("swarm_mailbox=${swarmCoordination.mailboxCoordinationSummary.ifBlank { "-" }}")
                            add("swarm_trace=${swarmCoordination.traceAttentionSummary.ifBlank { "-" }}")
                            add("swarm_missions=${snapshot.swarmStrategy.missionSummary.ifBlank { "-" }}")
                            add("swarm_escalations=${snapshot.swarmStrategy.escalationSummary.ifBlank { "-" }}")
                            add("swarm_joins=${snapshot.swarmStrategy.joinSummary.ifBlank { "-" }}")
                            add("operator=${snapshot.operatorShell.mode}")
                            add("operator_summary=${snapshot.operatorShell.summary.ifBlank { "-" }}")
                            add("operator_provider_diagnostics=${snapshot.operatorShell.providerDiagnosticsSummary.ifBlank { "-" }}")
                            add("operator_worker_missions=${snapshot.operatorShell.workerMissionSummary.ifBlank { "-" }}")
                            add("operator_worker_lease=${snapshot.operatorShell.workerLeaseSummary.ifBlank { "-" }}")
                            add("operator_swarm_policy=${snapshot.operatorShell.swarmPolicySummary.ifBlank { "-" }}")
                            add("operator_replay=${snapshot.operatorShell.replayHealthSummary.ifBlank { "-" }}")
                            add("autonomy=${snapshot.autonomyPlan.mode}")
                            add("autonomy_summary=${snapshot.autonomyPlan.summary.ifBlank { "-" }}")
                            add("user_model=${snapshot.userModel.summary.ifBlank { "-" }}")
                            add("user_identity=${snapshot.userModel.identitySummary.ifBlank { "-" }}")
                            add("user_preferences=${snapshot.userModel.preferenceSummary.ifBlank { "-" }}")
                            add("agenda=${snapshot.agendaShell.mode}")
                            add("agenda_summary=${snapshot.agendaShell.summary.ifBlank { "-" }}")
                            add("focus=${snapshot.personalFocus.mode}")
                            add("focus_summary=${snapshot.personalFocus.summary.ifBlank { "-" }}")
                            add("rhythm=${snapshot.dailyRhythm.mode}")
                            add("rhythm_summary=${snapshot.dailyRhythm.summary.ifBlank { "-" }}")
                            add("routine=${snapshot.routineShell.mode}")
                            add("routine_summary=${snapshot.routineShell.summary.ifBlank { "-" }}")
                            add("routine_policy=${snapshot.routinePolicy.summary.ifBlank { "-" }}")
                            add("digest=${snapshot.digestShell.mode}")
                            add("digest_summary=${snapshot.digestShell.summary.ifBlank { "-" }}")
                            add("digest_policy=${snapshot.digestPolicy.summary.ifBlank { "-" }}")
                            add("quiet_hours=${snapshot.quietHours.summary.ifBlank { "-" }}")
                            add("interrupt=${snapshot.interruptBudget.mode}")
                            add("interrupt_remaining=${snapshot.interruptBudget.remainingBudget}/${snapshot.interruptBudget.totalBudget}")
                            add("interrupt_policy=${snapshot.interruptPolicy.summary.ifBlank { "-" }}")
                            add("viewer=${snapshot.viewerShell.mode}")
                            add("governance=${snapshot.governanceShell.mode}")
                            add("diagnostics=${snapshot.diagnostics.status}")
                            add("diagnostics_summary=${snapshot.diagnostics.summary.ifBlank { "-" }}")
                            add("app_preflight=${snapshot.diagnostics.appPreflightSummary.ifBlank { "-" }}")
                            add("app_preflight_status=${snapshot.diagnostics.appPreflightStatus.ifBlank { "-" }}")
                            add("analytics_total=${snapshot.analytics.totalEvents}")
                            add("analytics_top=${snapshot.analytics.topCounters.joinToString(",").ifBlank { "-" }}")
                            swarmCoordination.recommendedActions.forEach { command ->
                                add("swarm_command | $command")
                            }
                            swarmCoordination.dispatchCandidates.forEach { candidate ->
                                add("swarm_candidate | $candidate")
                            }
                            snapshot.onboarding.steps.forEach { step ->
                                add("step ${step.id} | ${step.status} | ${step.title}")
                            }
                            snapshot.tips.forEach { tip ->
                                add("tip ${tip.id} | ${tip.status} | ${tip.title}")
                            }
                            snapshot.operatorShell.urgentLines.forEach { line ->
                                add("operator | $line")
                            }
                            snapshot.autonomyPlan.lines.forEach { line ->
                                add("autonomy | $line")
                            }
                            snapshot.userModel.lines.forEach { line ->
                                add("user_model | $line")
                            }
                        }
                }
            SessionCapabilityResult(
                success = snapshot.onboarding.steps.isNotEmpty() || snapshot.tips.isNotEmpty(),
                summary = "product shell 快照已读取。",
                payloadLines = payloadLines,
            )
        }

        SessionCapabilityKey.READ_PRODUCT_DIAGNOSTICS -> {
            val diagnostics = SessionPlatformFacade.readProductDiagnosticsSnapshot()
            SessionCapabilityResult(
                success = true,
                summary = "product diagnostics 已读取。",
                payloadLines =
                    buildList {
                        add("diagnostics=${diagnostics.productShell.diagnostics.status}")
                        add("summary=${diagnostics.productShell.diagnostics.summary.ifBlank { "-" }}")
                        add("app_preflight=${diagnostics.productShell.diagnostics.appPreflightSummary.ifBlank { "-" }}")
                        diagnostics.productShell.diagnostics.lines.forEach { add(it) }
                        diagnostics.productShell.diagnostics.appPreflightRecommendedCommands.forEach { add("app_preflight_command | $it") }
                        add("analytics_total=${diagnostics.analytics.totalEvents}")
                        add("analytics_top=${diagnostics.analytics.topCounters.joinToString(",").ifBlank { "-" }}")
                        diagnostics.analytics.recentEvents.forEach { add("analytics | $it") }
                    },
            )
        }

        SessionCapabilityKey.READ_REGRESSION_PLAN -> {
            val aggregate = HarnessStore.readAggregateSnapshot() ?: HarnessStore.AggregateSnapshot()
            val snapshot =
                RegressionRunner.buildPlan(
                    aggregate = aggregate,
                    limit = request.payload["limit"]?.toIntOrNull() ?: 4,
                )
            SessionCapabilityResult(
                success = snapshot.plannedItems.isNotEmpty(),
                summary = if (snapshot.plannedItems.isEmpty()) "暂无 regression plan。" else "regression plan 已读取。",
                payloadLines =
                    buildList {
                        add("generated_at_ms=${snapshot.generatedAtMs}")
                        snapshot.plannedItems.forEach { item ->
                            add(
                                "plan ${item.scenario.id} | ${item.scenario.title} | urgency=${item.urgencyScore} | matched=${item.matchedSessionId.ifBlank { "-" }} | ${item.reason}",
                            )
                        }
                        snapshot.results.take(4).forEach { result ->
                            add("last_run ${result.scenarioId} | ${result.status} | ${result.summary}")
                        }
                    },
            )
        }

        SessionCapabilityKey.RUN_REGRESSION_PLAN -> {
            val aggregate = HarnessStore.readAggregateSnapshot() ?: HarnessStore.AggregateSnapshot()
            val snapshot =
                RegressionRunner.runPlan(
                    aggregate = aggregate,
                    limit = request.payload["limit"]?.toIntOrNull() ?: 4,
                )
            SessionCapabilityResult(
                success = snapshot.results.any { it.status != "unmatched" },
                summary = "regression plan 已执行。",
                payloadLines =
                    buildList {
                        add("generated_at_ms=${snapshot.generatedAtMs}")
                        snapshot.results.forEach { result ->
                            add(
                                "result ${result.scenarioId} | ${result.status} | session=${result.sessionId.ifBlank { "-" }} | ${result.summary}",
                            )
                            result.mismatches.take(3).forEach { mismatch ->
                                add("mismatch | $mismatch")
                            }
                        }
                    },
            )
        }

        SessionCapabilityKey.READ_PRODUCT_POLICY -> {
            val snapshot = SessionPlatformFacade.readProductShellSnapshot()
            val policyType = request.payload["policy_type"].orEmpty()
            SessionCapabilityResult(
                success = true,
                summary = if (policyType.isBlank()) "产品策略已读取。" else "产品策略已读取：$policyType",
                payloadLines =
                    when (policyType) {
                        "routine" ->
                            buildList {
                                add("routine_policy=${snapshot.routinePolicy.summary.ifBlank { "-" }}")
                                add("focus_theme=${snapshot.routinePolicy.focusTheme}")
                                add("review_window=${snapshot.routinePolicy.reviewWindow}")
                                add("follow_up_window=${snapshot.routinePolicy.followUpWindow}")
                                add("checklists=${snapshot.routinePolicy.checklistTemplates.joinToString(" | ").ifBlank { "-" }}")
                                add("preferred_surfaces=${snapshot.routinePolicy.preferredSurfaces.joinToString(",").ifBlank { "-" }}")
                            }

                        "digest" ->
                            buildList {
                                add("digest_policy=${snapshot.digestPolicy.summary.ifBlank { "-" }}")
                                add("enabled=${snapshot.digestPolicy.enabled}")
                                add("cadence=${snapshot.digestPolicy.cadence}")
                                add("max_highlights=${snapshot.digestPolicy.maxHighlights}")
                                add("delivery_surfaces=${snapshot.digestPolicy.deliverySurfaces.joinToString(",").ifBlank { "-" }}")
                            }

                        "quiet_hours" ->
                            buildList {
                                add("quiet_hours=${snapshot.quietHours.summary.ifBlank { "-" }}")
                                add("enabled=${snapshot.quietHours.enabled}")
                                add("start_local_time=${snapshot.quietHours.startLocalTime}")
                                add("end_local_time=${snapshot.quietHours.endLocalTime}")
                                add("active_now=${snapshot.quietHours.activeNow}")
                            }

                        "interrupt" ->
                            buildList {
                                add("interrupt_policy=${snapshot.interruptPolicy.summary.ifBlank { "-" }}")
                                add("base_budget=${snapshot.interruptPolicy.baseBudget}")
                                add("focus_budget=${snapshot.interruptPolicy.focusBudget}")
                                add("hard_block_in_quiet_hours=${snapshot.interruptPolicy.hardBlockInQuietHours}")
                                add("preferred_sources=${snapshot.interruptPolicy.preferredSources.joinToString(",").ifBlank { "-" }}")
                                add("blocked_sources=${snapshot.interruptPolicy.blockedSources.joinToString(",").ifBlank { "-" }}")
                            }

                        "connected_apps" ->
                            buildList {
                                ConnectedAppCatalog.descriptors().forEach { descriptor ->
                                    val lifecycle = ConnectedAppLifecycleStore.snapshot(descriptor.appId)
                                    val receipt = ConnectedAppExecutionReceiptStore.latest(descriptor.appId)
                                    add("connected_app=${descriptor.appId}")
                                    add("status=${if (lifecycle.connected) "connected" else "disconnected"}")
                                    add("confirm=${lifecycle.confirmationMode}")
                                    add("support=${descriptor.supportCeiling.ifBlank { descriptor.summary }}")
                                    ConnectedAppGoldenPathRegistry.paths(descriptor.appId).take(3).forEach { path ->
                                        add("golden_path | ${path.operation} | state=${path.targetState} | risk=${path.riskLevel} | handoff=${path.handoffPoint}")
                                    }
                                    receipt?.summary?.takeIf { it.isNotBlank() }?.let { add("last=${it.take(120)}") }
                                    add("command=/set-connected-app --app-id ${descriptor.appId} --connected ${(!lifecycle.connected)}")
                                }
                            }

                        "utilities" ->
                            buildList {
                                UtilityGovernanceStore.lines(limit = 16).forEach(::add)
                                UtilityGovernanceStore.toolNames().forEach { toolName ->
                                    val lifecycle = UtilityLifecycleStore.snapshot(toolName)
                                    add("command=/set-utility --tool-name $toolName --enabled ${(!lifecycle.enabled)}")
                                }
                            }

                        else ->
                            buildList {
                                add("routine_policy=${snapshot.routinePolicy.summary.ifBlank { "-" }}")
                                add("digest_policy=${snapshot.digestPolicy.summary.ifBlank { "-" }}")
                                add("quiet_hours=${snapshot.quietHours.summary.ifBlank { "-" }}")
                                add("interrupt_policy=${snapshot.interruptPolicy.summary.ifBlank { "-" }}")
                                add("routine_checklists=${snapshot.routinePolicy.checklistTemplates.joinToString(" | ").ifBlank { "-" }}")
                                add("digest_surfaces=${snapshot.digestPolicy.deliverySurfaces.joinToString(",").ifBlank { "-" }}")
                                add("interrupt_sources=${snapshot.interruptPolicy.preferredSources.joinToString(",").ifBlank { "-" }}")
                            }
                    },
            )
        }

        SessionCapabilityKey.UPSERT_PRODUCT_POLICY -> {
            val policyType = request.payload["policy_type"].orEmpty()
            val snapshot =
                when (policyType) {
                    "routine" ->
                        SessionPlatformFacade.updateRoutinePolicy { current ->
                            current.copy(
                                focusTheme = request.payload["focus_theme"].orEmpty().ifBlank { current.focusTheme },
                                reviewWindow = request.payload["review_window"].orEmpty().ifBlank { current.reviewWindow },
                                followUpWindow = request.payload["follow_up_window"].orEmpty().ifBlank { current.followUpWindow },
                                checklistTemplates =
                                    parseCsvOrPipeList(request.payload["checklist_templates"]).ifEmpty { current.checklistTemplates },
                                preferredSurfaces =
                                    parseCsvOrPipeList(request.payload["preferred_surfaces"]).ifEmpty { current.preferredSurfaces },
                            )
                        }

                    "digest" ->
                        SessionPlatformFacade.updateDigestPolicy { current ->
                            current.copy(
                                enabled = request.payload["enabled"]?.toBooleanStrictOrNull() ?: current.enabled,
                                cadence = request.payload["cadence"].orEmpty().ifBlank { current.cadence },
                                maxHighlights = request.payload["max_highlights"]?.toIntOrNull()?.coerceAtLeast(1) ?: current.maxHighlights,
                                deliverySurfaces =
                                    parseCsvOrPipeList(request.payload["delivery_surfaces"]).ifEmpty { current.deliverySurfaces },
                            )
                        }

                    "quiet_hours" ->
                        SessionPlatformFacade.updateQuietHours { current ->
                            current.copy(
                                enabled = request.payload["enabled"]?.toBooleanStrictOrNull() ?: current.enabled,
                                startLocalTime = request.payload["start_local_time"].orEmpty().ifBlank { current.startLocalTime },
                                endLocalTime = request.payload["end_local_time"].orEmpty().ifBlank { current.endLocalTime },
                            )
                        }

                    "connected_apps" -> {
                        val snapshot =
                            ConnectedAppLifecycleStore.update(
                                appId = request.payload["app_id"].orEmpty(),
                                connected = request.payload["connected"]?.toBooleanStrictOrNull(),
                                confirmationMode = request.payload["confirmation_mode"],
                            )
                        return SessionCapabilityResult(
                            success = snapshot.appId.isNotBlank(),
                            summary = if (snapshot.appId.isBlank()) "更新 connected app 需要提供 app_id。" else "connected app 生命周期已更新：${snapshot.appId}",
                            payloadLines =
                                listOf(
                                    "app_id=${snapshot.appId}",
                                    "connected=${snapshot.connected}",
                                    "confirmation_mode=${snapshot.confirmationMode}",
                                ),
                        )
                    }

                    "utilities" -> {
                        val toolName = request.payload["tool_name"].orEmpty()
                        val enabled = request.payload["enabled"]?.toBooleanStrictOrNull()
                        if (toolName.isBlank() || enabled == null) {
                            return SessionCapabilityResult(false, "更新 utility 需要提供 tool_name 和 enabled。")
                        }
                        val snapshot = UtilityLifecycleStore.update(toolName = toolName, enabled = enabled)
                        return SessionCapabilityResult(
                            success = true,
                            summary = "utility 生命周期已更新：${snapshot.toolName}",
                            payloadLines =
                                listOf(
                                    "tool_name=${snapshot.toolName}",
                                    "enabled=${snapshot.enabled}",
                                ),
                        )
                    }

                    else ->
                        SessionPlatformFacade.updateInterruptPolicy { current ->
                            current.copy(
                                baseBudget = request.payload["base_budget"]?.toIntOrNull()?.coerceAtLeast(1) ?: current.baseBudget,
                                focusBudget = request.payload["focus_budget"]?.toIntOrNull()?.coerceAtLeast(1) ?: current.focusBudget,
                                hardBlockInQuietHours =
                                    request.payload["hard_block_in_quiet_hours"]?.toBooleanStrictOrNull()
                                        ?: current.hardBlockInQuietHours,
                                preferredSources =
                                    parseCsvOrPipeList(request.payload["preferred_sources"]).ifEmpty { current.preferredSources },
                                blockedSources =
                                    parseCsvOrPipeList(request.payload["blocked_sources"]).ifEmpty { current.blockedSources },
                            )
                        }
                }
            SessionCapabilityResult(
                success = true,
                summary = "产品策略已更新：${policyType.ifBlank { "interrupt" }}",
                payloadLines =
                    listOf(
                        "routine_policy=${snapshot.routinePolicy.summary.ifBlank { "-" }}",
                        "digest_policy=${snapshot.digestPolicy.summary.ifBlank { "-" }}",
                        "quiet_hours=${snapshot.quietHours.summary.ifBlank { "-" }}",
                        "interrupt_policy=${snapshot.interruptPolicy.summary.ifBlank { "-" }}",
                    ),
            )
        }

        SessionCapabilityKey.REFRESH_PRODUCT_SHELL -> {
            val snapshot =
                SessionPlatformFacade.refreshProductShellSnapshot(
                    reason = request.payload["reason"].orEmpty().ifBlank { request.entrySource },
                )
            SessionCapabilityResult(
                success = true,
                summary = "product shell 已刷新。",
                payloadLines =
                    buildList {
                        add("onboarding=${snapshot.onboarding.status}")
                        add("tips_visible=${snapshot.tips.size}")
                        add("swarm=${snapshot.swarmStrategy.mode}")
                        add("swarm_lease_pressure=${snapshot.swarmStrategy.leasePressureSummary.ifBlank { "-" }}")
                        add("swarm_dispatch=${snapshot.swarmStrategy.dispatchSummary.ifBlank { "-" }}")
                        add("operator=${snapshot.operatorShell.mode}")
                        add("agenda=${snapshot.agendaShell.mode}")
                        add("focus=${snapshot.personalFocus.mode}")
                        add("rhythm=${snapshot.dailyRhythm.mode}")
                        add("routine=${snapshot.routineShell.mode}")
                        add("digest=${snapshot.digestShell.mode}")
                        add("interrupt=${snapshot.interruptBudget.mode}")
                    },
            )
        }

        SessionCapabilityKey.ACK_PRODUCT_SHELL_TIP -> {
            val snapshot =
                SessionPlatformFacade.acknowledgeProductShellTip(
                    tipId = request.payload["tip_id"].orEmpty(),
                    action = request.payload["action"].orEmpty(),
                    note = request.payload["note"].orEmpty(),
                )
            SessionCapabilityResult(
                success = request.payload["tip_id"].orEmpty().isNotBlank(),
                summary = "product shell tip 已更新。",
                payloadLines =
                    buildList {
                        add("tips_visible=${snapshot.tips.size}")
                        snapshot.tips.forEach { tip ->
                            add("tip ${tip.id} | ${tip.status} | ${tip.title}")
                        }
                    },
            )
        }

        SessionCapabilityKey.UPDATE_PRODUCT_ONBOARDING -> {
            val snapshot =
                SessionPlatformFacade.updateProductOnboardingStep(
                    stepId = request.payload["step_id"].orEmpty(),
                    action = request.payload["action"].orEmpty(),
                    note = request.payload["note"].orEmpty(),
                )
            SessionCapabilityResult(
                success = request.payload["step_id"].orEmpty().isNotBlank(),
                summary = "product shell onboarding 已更新。",
                payloadLines =
                    buildList {
                        add("onboarding=${snapshot.onboarding.status}")
                        snapshot.onboarding.steps.forEach { step ->
                            add("step ${step.id} | ${step.status} | ${step.title}")
                        }
                    },
            )
        }

        SessionCapabilityKey.READ_COMMAND_CENTER -> {
            val limit = request.payload["limit"]?.toIntOrNull() ?: 8
            val sessionId = request.sessionId.ifBlank { request.payload["session_id"].orEmpty() }
            val snapshot = SessionCommandCenterStore.readSnapshot(limit = limit)
            val receipts =
                if (sessionId.isBlank()) {
                    SessionCommandCenterStore.readRecent(limit = limit)
                } else {
                    SessionCommandCenterStore.readRecentForSession(sessionId = sessionId, limit = limit)
                }
            SessionCapabilityResult(
                success = receipts.isNotEmpty() || snapshot.totalCount > 0,
                summary = if (receipts.isEmpty()) "最近没有命令回执。" else "命令中心摘要已读取。",
                sessionId = sessionId,
                payloadLines =
                    buildList {
                        add("total=${snapshot.totalCount}")
                        add("running=${snapshot.runningCount}")
                        add("failed=${snapshot.failedCount}")
                        add("attention=${snapshot.attentionSummary.ifBlank { "-" }}")
                        snapshot.attentionLines.take(3).forEach { add("attention_line | $it") }
                        receipts.forEach { receipt ->
                            add(
                                "${receipt.receiptId} | ${receipt.status.name.lowercase()} | ${receipt.capability.ifBlank { "-" }} | ${receipt.summary.take(96)}",
                            )
                            receipt.lines.take(3).forEach { line ->
                                add("receipt_line | ${receipt.receiptId} | $line")
                            }
                        }
                    },
            )
        }

        SessionCapabilityKey.READ_SESSION_EXPLANATION_LOG -> {
            val sessionId = request.sessionId.ifBlank { request.payload["session_id"].orEmpty() }
            val limit = request.payload["limit"]?.toIntOrNull() ?: 8
            val entries = SessionExplanationStore.readRecent(sessionId = sessionId, limit = limit)
            SessionCapabilityResult(
                success = entries.isNotEmpty(),
                summary = if (entries.isEmpty()) "当前没有 explanation 日志。" else "session explanation 已读取。",
                sessionId = sessionId,
                payloadLines =
                    buildList {
                        entries.forEach { entry ->
                            add(
                                "#${entry.turn} | ${entry.phase} | ${entry.title.ifBlank { "-" }} | ${entry.summary.take(96)}",
                            )
                            entry.detailLines.take(3).forEach { line ->
                                add("detail | ${entry.phase} | $line")
                            }
                        }
                    },
            )
        }

        SessionCapabilityKey.READ_SESSION_MEMORY_NOTEBOOK -> {
            val sessionId = request.sessionId.ifBlank { request.payload["session_id"].orEmpty() }
            val snapshot = SessionMemoryNotebookStore.readSnapshot(sessionId)
            SessionCapabilityResult(
                success = snapshot != null,
                summary = if (snapshot == null) "当前没有 session notebook。" else "session notebook 已读取。",
                sessionId = sessionId,
                payloadLines =
                    snapshot?.let {
                        buildList {
                            add("updated_at_ms=${it.updatedAtMs}")
                            add("path=${it.markdownPath.ifBlank { "-" }}")
                            add("headline=${it.headline.ifBlank { "-" }}")
                            add("focus=${it.focusSummary.ifBlank { "-" }}")
                            addAll(it.previewLines.take(request.payload["limit"]?.toIntOrNull() ?: 18))
                        }
                    }.orEmpty(),
            )
        }

        SessionCapabilityKey.READ_SESSION_ACTION_LIFECYCLE -> {
            val sessionId =
                request.sessionId
                    .ifBlank { request.payload["session_id"].orEmpty() }
                    .ifBlank { SessionRuntime.State.runtimeState().session.sessionId }
            val snapshot =
                sessionId.takeIf { it.isNotBlank() }?.let {
                    SessionPlatformFacade.readSessionActionLifecycle(
                        sessionId = it,
                        limit = request.payload["limit"]?.toIntOrNull() ?: 8,
                    )
                }
            SessionCapabilityResult(
                success = snapshot != null && snapshot.totalCount > 0,
                summary = if (snapshot == null || snapshot.totalCount <= 0) "当前没有动作生命周期记录。" else "session action lifecycle 已读取。",
                sessionId = sessionId,
                payloadLines =
                    snapshot?.let {
                        buildList {
                            add("summary=${it.attentionSummary.ifBlank { "-" }}")
                            it.lines.forEach(::add)
                            it.recommendedCommands.forEach { command -> add("command | $command") }
                        }
                    }.orEmpty(),
            )
        }

        SessionCapabilityKey.READ_MAIN_LOOP -> {
            val sessionId =
                request.sessionId
                    .ifBlank { request.payload["session_id"].orEmpty() }
                    .ifBlank { SessionRuntime.State.runtimeState().session.sessionId }
            val snapshot = sessionId.takeIf { it.isNotBlank() }?.let(SessionPlatformFacade::readSessionMainLoop)
            SessionCapabilityResult(
                success = snapshot != null,
                summary = if (snapshot == null) "当前没有主循环快照。" else "主循环快照已读取。",
                sessionId = sessionId,
                payloadLines =
                    snapshot?.let {
                        buildList {
                            add("turn=${it.turn}")
                            add("phase=${it.phase.ifBlank { "-" }}")
                            add("summary=${it.summary.ifBlank { "-" }}")
                            add("queue=${it.queueSummary.ifBlank { "-" }}")
                            add("memory=${it.memorySummary.ifBlank { "-" }}")
                            add("tool=${it.toolSummary.ifBlank { "-" }}")
                            add("permission=${it.permissionSummary.ifBlank { "-" }}")
                            add("grounding=${it.groundingSummary.ifBlank { "-" }}")
                            it.recommendedCommands.forEach { command -> add("command | $command") }
                        }
                    }.orEmpty(),
            )
        }

        SessionCapabilityKey.READ_LOOP_RUNTIME -> {
            val sessionId =
                request.sessionId
                    .ifBlank { request.payload["session_id"].orEmpty() }
                    .ifBlank { SessionRuntime.State.runtimeState().session.sessionId }
            val snapshot =
                sessionId.takeIf { it.isNotBlank() }?.let {
                    SessionLoopRuntimeStore.refresh(sessionId = it, phase = "capability_read")
                    SessionPlatformFacade.readSessionLoopRuntime(it)
                }
            SessionCapabilityResult(
                success = snapshot != null,
                summary = if (snapshot == null) "当前没有 loop runtime 快照。" else "loop runtime 已读取。",
                sessionId = sessionId,
                payloadLines =
                    snapshot?.let {
                        buildList {
                            add("summary=${it.summary.ifBlank { "-" }}")
                            add("turn=${it.turn}")
                            add("phase=${it.phase.ifBlank { "-" }}")
                            add("queue_drain=${it.queueDrainCount}")
                            add("attachments=${it.attachmentCount}")
                            add("prefetch=${it.prefetchCount}")
                            add("task_signals=${it.taskSignalCount}")
                            add("tool_catalog=${it.toolCatalogCount}")
                            it.lines.forEach(::add)
                            it.recommendedCommands.forEach { command -> add("command | $command") }
                        }
                    }.orEmpty(),
            )
        }

        SessionCapabilityKey.READ_LOOP_INBOX -> {
            val sessionId =
                request.sessionId
                    .ifBlank { request.payload["session_id"].orEmpty() }
                    .ifBlank { SessionRuntime.State.runtimeState().session.sessionId }
            val snapshot =
                sessionId.takeIf { it.isNotBlank() }?.let {
                    SessionLoopInboxStore.refresh(sessionId = it)
                    SessionPlatformFacade.readSessionLoopInbox(it)
                }
            SessionCapabilityResult(
                success = snapshot != null && snapshot.totalCount > 0,
                summary = if (snapshot == null || snapshot.totalCount <= 0) "当前没有 loop inbox 项。" else "loop inbox 已读取。",
                sessionId = sessionId,
                payloadLines =
                    snapshot?.let {
                        buildList {
                            add("summary=${it.summary.ifBlank { "-" }}")
                            it.lines.forEach(::add)
                            it.recommendedCommands.forEach { command -> add("command | $command") }
                        }
                    }.orEmpty(),
            )
        }

        SessionCapabilityKey.READ_MEMORY_POLICY -> {
            val sessionId =
                request.sessionId
                    .ifBlank { request.payload["session_id"].orEmpty() }
                    .ifBlank { SessionRuntime.State.runtimeState().session.sessionId }
            val snapshot = sessionId.takeIf { it.isNotBlank() }?.let(SessionPlatformFacade::readSessionMemoryPolicy)
            SessionCapabilityResult(
                success = snapshot != null,
                summary = if (snapshot == null) "当前没有 memory policy 记录。" else "session memory policy 已读取。",
                sessionId = sessionId,
                payloadLines =
                    snapshot?.let {
                        buildList {
                            add("latest_turn=${it.latestTurn}")
                            add("last_notebook_turn=${it.lastNotebookTurn}")
                            add("summary=${it.summary.ifBlank { "-" }}")
                            it.lastReason.takeIf { reason -> reason.isNotBlank() }?.let { add("reason=$it") }
                            it.recommendedCommands.forEach { command -> add("command | $command") }
                        }
                    }.orEmpty(),
            )
        }

        SessionCapabilityKey.READ_MEMORY_MAINTENANCE -> {
            val snapshot = SessionPlatformFacade.readSessionMemoryMaintenance()
            SessionCapabilityResult(
                success = snapshot.pendingCount > 0 || snapshot.deferredCount > 0 || snapshot.failedCount > 0 || snapshot.completedCount > 0,
                summary = if (snapshot.lastSummary.isBlank()) "当前还没有后台记忆维护记录。" else "后台记忆维护状态已读取。",
                payloadLines =
                    buildList {
                        add("summary=pending=${snapshot.pendingCount} running=${snapshot.runningCount} deferred=${snapshot.deferredCount} failed=${snapshot.failedCount} completed=${snapshot.completedCount}")
                        add("notebook_updates=${snapshot.notebookUpdates}")
                        add("last_task=${snapshot.lastTaskType.ifBlank { "-" }} | ${snapshot.lastStatus.ifBlank { "-" }} | ${snapshot.lastTaskId.ifBlank { "-" }}")
                        add("last_updated_at_ms=${snapshot.lastUpdatedAtMs}")
                        snapshot.lastSummary.takeIf { it.isNotBlank() }?.let { add("last_summary=${it.take(160)}") }
                    },
            )
        }

        SessionCapabilityKey.READ_MEMORY_CURATOR -> {
            val sessionId =
                request.sessionId
                    .ifBlank { request.payload["session_id"].orEmpty() }
                    .ifBlank { SessionRuntime.State.runtimeState().session.sessionId }
            val snapshot = sessionId.takeIf { it.isNotBlank() }?.let(SessionPlatformFacade::readSessionMemoryCurator)
            SessionCapabilityResult(
                success = snapshot != null,
                summary = if (snapshot == null) "当前没有 memory curator 记录。" else "session memory curator 已读取。",
                sessionId = sessionId,
                payloadLines =
                    snapshot?.let {
                        buildList {
                            add("status=${it.status.ifBlank { "-" }}")
                            add("summary=${it.summary.ifBlank { "-" }}")
                            add("policy_reason=${it.policyReason.ifBlank { "-" }}")
                            add("task_id=${it.lastTaskId.ifBlank { "-" }}")
                            add("notebook_path=${it.notebookPath.ifBlank { "-" }}")
                            addAll(it.lines.take(4))
                            it.recommendedCommands.forEach { command -> add("command | $command") }
                        }
                    }.orEmpty(),
            )
        }

        SessionCapabilityKey.READ_MEMORY_FORK -> {
            val sessionId =
                request.sessionId
                    .ifBlank { request.payload["session_id"].orEmpty() }
                    .ifBlank { SessionRuntime.State.runtimeState().session.sessionId }
            val snapshot =
                sessionId.takeIf { it.isNotBlank() }?.let {
                    SessionMemoryForkRuntimeStore.refresh(it)
                    SessionPlatformFacade.readSessionMemoryFork(it)
                }
            SessionCapabilityResult(
                success = snapshot != null,
                summary = if (snapshot == null) "当前没有 memory fork runtime。" else "memory fork runtime 已读取。",
                sessionId = sessionId,
                payloadLines =
                    snapshot?.let {
                        buildList {
                            add("summary=${it.summary.ifBlank { "-" }}")
                            add("worker_id=${it.workerId.ifBlank { "-" }}")
                            add("worker_status=${it.workerStatus.ifBlank { "-" }}")
                            add("blocked_reason=${it.blockedReason.ifBlank { "-" }}")
                            add("policy_reason=${it.policyReason.ifBlank { "-" }}")
                            add("notebook_path=${it.notebookPath.ifBlank { "-" }}")
                            it.lines.forEach(::add)
                            it.recommendedCommands.forEach { command -> add("command | $command") }
                        }
                    }.orEmpty(),
            )
        }

        SessionCapabilityKey.READ_SESSION_TURN_LOOP -> {
            val sessionId =
                request.sessionId
                    .ifBlank { request.payload["session_id"].orEmpty() }
                    .ifBlank { SessionRuntime.State.runtimeState().session.sessionId }
            val snapshot = sessionId.takeIf { it.isNotBlank() }?.let(SessionPlatformFacade::readSessionTurnLoop)
            SessionCapabilityResult(
                success = snapshot != null,
                summary = if (snapshot == null) "未找到 turn loop 快照。" else "turn loop 已读取。",
                sessionId = sessionId,
                payloadLines =
                    snapshot?.let {
                        buildList {
                            add("turn=${it.turn}")
                            add("phase=${it.phase.ifBlank { "-" }}")
                            add("summary=${it.summary.ifBlank { "-" }}")
                            it.blockerLines.forEach { line -> add("blocker | $line") }
                            it.agendaLines.forEach { line -> add("agenda | $line") }
                            it.recommendedCommands.forEach { command -> add("command | $command") }
                        }
                    }.orEmpty(),
            )
        }

        SessionCapabilityKey.READ_TOOL_LEDGER -> {
            val sessionId =
                request.sessionId
                    .ifBlank { request.payload["session_id"].orEmpty() }
                    .ifBlank { SessionRuntime.State.runtimeState().session.sessionId }
            val limit = request.payload["limit"]?.toIntOrNull() ?: 8
            val entries =
                sessionId.takeIf { it.isNotBlank() }
                    ?.let { SessionPlatformFacade.readSessionToolLedger(sessionId = it, limit = limit) }
                    .orEmpty()
            SessionCapabilityResult(
                success = entries.isNotEmpty(),
                summary = if (entries.isEmpty()) "当前没有工具调用记录。" else "tool ledger 已读取。",
                sessionId = sessionId,
                payloadLines =
                    buildList {
                        add(SessionToolUseLedgerStore.summary(sessionId = sessionId, limit = limit))
                        entries.forEach { entry ->
                            add("tool ${entry.turn} | ${entry.status.name.lowercase()} | ${entry.toolName} | ${entry.durationMs}ms | ${entry.summary.take(88)}")
                            entry.detailLines.forEach { line -> add("tool_detail | ${entry.entryId} | $line") }
                        }
                    },
            )
        }

        SessionCapabilityKey.READ_TOOL_RUNTIME -> {
            val sessionId =
                request.sessionId
                    .ifBlank { request.payload["session_id"].orEmpty() }
                    .ifBlank { SessionRuntime.State.runtimeState().session.sessionId }
            val snapshot =
                sessionId.takeIf { it.isNotBlank() }?.let {
                    SessionPlatformFacade.readSessionToolRuntime(
                        sessionId = it,
                        limit = request.payload["limit"]?.toIntOrNull() ?: 12,
                    )
                }
            SessionCapabilityResult(
                success = snapshot != null && snapshot.totalCount > 0,
                summary = if (snapshot == null || snapshot.totalCount <= 0) "当前没有工具运行时记录。" else "工具运行时快照已读取。",
                sessionId = sessionId,
                payloadLines =
                    snapshot?.let {
                        buildList {
                            add("summary=${it.summary}")
                            if (it.permissionOutcomes.isNotEmpty()) add("permission_outcomes=${it.permissionOutcomes.joinToString(",")}")
                            it.lines.take(8).forEach(::add)
                        }
                    }.orEmpty(),
            )
        }

        SessionCapabilityKey.READ_TOOL_CATALOG -> {
            val descriptors = AgentToolCatalog.descriptors()
            SessionCapabilityResult(
                success = descriptors.isNotEmpty(),
                summary = "tool catalog 已读取。",
                payloadLines =
                    buildList {
                        descriptors.forEach { descriptor ->
                            add(
                                "${descriptor.name} | ${descriptor.type.name.lowercase()} | perm=${descriptor.permissionFamily} | irreversible=${descriptor.irreversible} | concurrent=${descriptor.concurrencySafe} | defer=${descriptor.shouldDefer} | always_load=${descriptor.alwaysLoad} | ${descriptor.summary}",
                            )
                            descriptor.inputContract.takeIf { it.isNotBlank() }?.let { add("tool_input | ${descriptor.name} | $it") }
                            descriptor.resultContract.takeIf { it.isNotBlank() }?.let { add("tool_result | ${descriptor.name} | $it") }
                            descriptor.progressLabel.takeIf { it.isNotBlank() }?.let { add("tool_progress | ${descriptor.name} | $it") }
                            if (descriptor.requiresUserInteraction) {
                                add("tool_interaction | ${descriptor.name} | user_interaction")
                            }
                            if (descriptor.fallbackTools.isNotEmpty()) {
                                add("tool_fallback | ${descriptor.name} | ${descriptor.fallbackTools.joinToString(",")}")
                            }
                        }
                    },
            )
        }

        SessionCapabilityKey.READ_TOOL_CONTRACTS -> {
            val snapshot = SessionPlatformFacade.readToolContracts(limit = request.payload["limit"]?.toIntOrNull() ?: 12)
            SessionCapabilityResult(
                success = snapshot.totalCount > 0,
                summary = if (snapshot.totalCount <= 0) "当前没有 tool runtime contract。" else "tool runtime contract 已读取。",
                payloadLines =
                    buildList {
                        add("summary=${snapshot.summary.ifBlank { "-" }}")
                        add("interactive=${snapshot.interactiveCount}")
                        add("deferred=${snapshot.deferredCount}")
                        add("always_load=${snapshot.alwaysLoadCount}")
                        add("concurrent=${snapshot.concurrentCount}")
                        snapshot.lines.forEach(::add)
                        ConnectedAppGoldenPathRegistry.lines(limit = 18).forEach(::add)
                    },
            )
        }

        SessionCapabilityKey.READ_FOLLOW_UP_QUEUE -> {
            val limit = request.payload["limit"]?.toIntOrNull() ?: 12
            val proactiveTasks = SessionPlatformFacade.readProactiveTasks(limit = limit)
            val externalSignals = SessionPlatformFacade.readExternalSignals(limit = 6)
            val enabledTasks = proactiveTasks.filter { it.enabled }
            val dueTasks = enabledTasks.filter { it.fireAtMs in 1..System.currentTimeMillis() }
            val approvalFollowUps = enabledTasks.filter { it.type == AssistantProactiveTaskType.APPROVAL_FOLLOW_UP }
            SessionCapabilityResult(
                success = enabledTasks.isNotEmpty() || externalSignals.isNotEmpty(),
                summary = if (enabledTasks.isEmpty() && externalSignals.isEmpty()) "当前没有待跟进队列。" else "跟进队列已读取。",
                payloadLines =
                    buildList {
                        add("enabled_tasks=${enabledTasks.size}")
                        add("due_tasks=${dueTasks.size}")
                        add("approval_follow_ups=${approvalFollowUps.size}")
                        add("external_signals=${externalSignals.count { it.enabled }}")
                        approvalFollowUps.take(4).forEach { task ->
                            add("approval | ${task.id} | ${task.summary.ifBlank { task.title }} | session=${task.sessionId.ifBlank { "-" }}")
                        }
                        enabledTasks.take(limit).forEach { task ->
                            add(
                                "task ${task.id} | ${task.type.name.lowercase()} | p=${task.priority} | deadline=${task.deadlineAtMs.takeIf { it > 0L } ?: "-"} | defer=${task.deferCount} | ${task.title} | ${task.summary.take(88)}",
                            )
                            task.recommendedCommand.takeIf { it.isNotBlank() }?.let { add("task_command | ${task.id} | $it") }
                        }
                        externalSignals.filter { it.enabled }.take(4).forEach { signal ->
                            add("signal ${signal.id} | ${signal.source.ifBlank { signal.type.name.lowercase() }} | ${signal.title} | ${signal.summary.take(88)}")
                        }
                    },
            )
        }

        SessionCapabilityKey.READ_FOLLOW_UP_HEALTH -> {
            val snapshot = SessionPlatformFacade.readFollowUpHealth(limit = request.payload["limit"]?.toIntOrNull() ?: 24)
            SessionCapabilityResult(
                success = snapshot.totalEnabled > 0 || snapshot.overdueCount > 0 || snapshot.deferredCount > 0,
                summary = if (snapshot.totalEnabled <= 0) "当前没有启用中的跟进项。" else "跟进健康度已读取。",
                payloadLines =
                    buildList {
                        add("summary=${snapshot.summary.ifBlank { "-" }}")
                        add("enabled=${snapshot.totalEnabled}")
                        add("overdue=${snapshot.overdueCount}")
                        add("deferred=${snapshot.deferredCount}")
                        add("approvals=${snapshot.approvalCount}")
                        add("scheduled=${snapshot.scheduledCount}")
                        snapshot.topLines.forEach { add(it) }
                    },
            )
        }

        SessionCapabilityKey.READ_TASK_OS -> {
            val snapshot = SessionPlatformFacade.readTaskOs(limit = request.payload["limit"]?.toIntOrNull() ?: 24)
            SessionCapabilityResult(
                success = snapshot.enabledTaskCount > 0 || snapshot.overdueCount > 0,
                summary = if (snapshot.enabledTaskCount <= 0) "当前 task OS 没有启用任务。" else "task OS 状态已读取。",
                payloadLines =
                    buildList {
                        add("summary=${snapshot.summary.ifBlank { "-" }}")
                        add("active_session=${snapshot.activeSessionId.ifBlank { "-" }}")
                        snapshot.lines.forEach(::add)
                    },
            )
        }

        SessionCapabilityKey.COMPLETE_FOLLOW_UP -> {
            val taskId = request.payload["task_id"].orEmpty().ifBlank { request.query.ifBlank { request.task } }
            val task = SessionPlatformFacade.readProactiveTasks(limit = 64).firstOrNull { it.id == taskId }
            if (task == null) {
                SessionCapabilityResult(
                    success = false,
                    summary = "未找到跟进项：$taskId",
                )
            } else {
                AssistantProactiveTaskStore.markCompleted(task.id)
                SessionCapabilityResult(
                    success = true,
                    summary = "跟进项已完成：${task.title}",
                    sessionId = task.sessionId,
                    payloadLines = listOf("task_id=${task.id}", "type=${task.type.name.lowercase()}", "summary=${task.summary.ifBlank { task.title }}"),
                )
            }
        }

        SessionCapabilityKey.DEFER_FOLLOW_UP -> {
            val taskId = request.payload["task_id"].orEmpty().ifBlank { request.query.ifBlank { request.task } }
            val deferByMs =
                request.payload["defer_by_ms"]?.toLongOrNull()
                    ?: ((request.payload["minutes"]?.toLongOrNull() ?: 30L).coerceAtLeast(1L) * 60_000L)
            val task = SessionPlatformFacade.readProactiveTasks(limit = 64).firstOrNull { it.id == taskId }
            if (task == null) {
                SessionCapabilityResult(
                    success = false,
                    summary = "未找到跟进项：$taskId",
                )
            } else {
                AssistantProactiveTaskStore.defer(task.id, deferByMs = deferByMs)
                SessionCapabilityResult(
                    success = true,
                    summary = "跟进项已延后：${task.title}",
                    sessionId = task.sessionId,
                    payloadLines = listOf("task_id=${task.id}", "defer_by_ms=$deferByMs"),
                )
            }
        }

        SessionCapabilityKey.READ_SAFETY_CENTER -> {
            val sessionId = request.sessionId.ifBlank { request.payload["session_id"].orEmpty() }
            val liveState = SessionRuntime.State.runtimeState()
            val pending =
                when {
                    sessionId.isBlank() || liveState.session.sessionId == sessionId -> liveState.safety.pendingConfirmation
                    else -> SessionPlatformFacade.readSessionSnapshot(sessionId).resumeSnapshot?.safety?.pendingConfirmation
                }
            val grants =
                SessionPlatformFacade.readRuntimeSafetyGrants(
                    limit = request.payload["limit"]?.toIntOrNull() ?: 12,
                    includeInactive = request.payload["include_inactive"]?.toBooleanStrictOrNull() ?: false,
                    sessionId = sessionId,
                    targetPackageName = request.payload["target_package_name"].orEmpty(),
                    actionFamily = request.payload["action_family"].orEmpty(),
                )
            val rules =
                RuntimeSafetyPolicyStore.readRules(
                    limit = 8,
                    actionFamily = request.payload["action_family"].orEmpty(),
                    targetPackageName = request.payload["target_package_name"].orEmpty(),
                    toolName = request.payload["tool_name"].orEmpty(),
                    pageState = request.payload["page_state"].orEmpty(),
                    behavior =
                        request.payload["behavior"]
                            ?.takeIf { it.isNotBlank() }
                            ?.let { value -> runCatching { RuntimeSafetyPolicyBehavior.valueOf(value.uppercase()) }.getOrNull() },
                )
            val decisions = SessionPlatformFacade.readSessionSafetyDecisions(sessionId = sessionId, limit = 4)
            val userModel = com.lmx.xiaoxuanagent.memory.PersonalMemoryStore.readUserModelSnapshot(limit = 4)
            SessionCapabilityResult(
                success = pending != null || grants.isNotEmpty() || rules.isNotEmpty() || decisions.isNotEmpty(),
                summary =
                    when {
                        pending != null -> "当前有待处理的风险确认。"
                        rules.isNotEmpty() -> "安全中心已读取，含显式安全规则。"
                        grants.isNotEmpty() -> "安全中心已读取。"
                        decisions.isNotEmpty() -> "安全中心已读取，含安全决策历史。"
                        else -> "当前没有待确认动作，也没有运行时安全授权。"
                    },
                sessionId = sessionId,
                payloadLines =
                    buildList {
                        pending?.let { confirmation ->
                            add("pending_action=${confirmation.actionLabel.ifBlank { "-" }}")
                            add("pending_summary=${confirmation.summary.ifBlank { "-" }}")
                            add("pending_scope_hint=${confirmation.grantScopeHint.ifBlank { "-" }}")
                            add("pending_family=${confirmation.actionFamily.ifBlank { "-" }}")
                        } ?: add("pending_action=-")
                        add("grants=${grants.size}")
                        add("policies=${rules.size}")
                        add("decisions=${decisions.size}")
                        add("user_model_safety=${userModel.safetySummary.ifBlank { "-" }}")
                        rules.forEach { rule ->
                            add(
                                "policy ${rule.ruleId} | ${rule.behavior.name.lowercase()} | ${rule.scopeSummary()}",
                            )
                            rule.sourceTag.takeIf { it.isNotBlank() }?.let { add("policy_source ${rule.ruleId} | $it") }
                            rule.surfaceHint.takeIf { it.isNotBlank() }?.let { add("policy_surface ${rule.ruleId} | $it") }
                            add("policy_explain ${rule.ruleId} | ${rule.behaviorSummary().take(120)}")
                            rule.note.takeIf { it.isNotBlank() }?.let { add("policy_note ${rule.ruleId} | ${it.take(88)}") }
                        }
                        grants.forEach { grant ->
                            add(
                                "${grant.grantId} | ${grant.status.name.lowercase()} | ${grant.scope} | ${grant.actionFamily.ifBlank { "-" }} | app=${grant.targetPackageName.ifBlank { "*" }} | ${grant.note.take(88)}",
                            )
                        }
                        decisions.forEach { entry ->
                            add("decision ${entry.outcome} | ${entry.actionFamily.ifBlank { "-" }} | ${entry.summary.take(88)}")
                        }
                    },
            )
        }

        SessionCapabilityKey.READ_PERMISSION_CENTER -> {
            val sessionId =
                request.sessionId
                    .ifBlank { request.payload["session_id"].orEmpty() }
                    .ifBlank { SessionRuntime.State.runtimeState().session.sessionId }
            val snapshot =
                SessionPlatformFacade.readPermissionCenter(
                    sessionId = sessionId,
                    limit = request.payload["limit"]?.toIntOrNull() ?: 12,
                )
            SessionCapabilityResult(
                success = snapshot.policyCount > 0 || snapshot.activeGrantCount > 0 || snapshot.decisionCount > 0 || snapshot.pendingSummary.isNotBlank(),
                summary = if (snapshot.summary.isBlank()) "当前权限中心为空。" else "权限中心已读取。",
                sessionId = sessionId,
                payloadLines =
                    buildList {
                        add("summary=${snapshot.summary}")
                        snapshot.pendingSummary.takeIf { it.isNotBlank() }?.let { add("pending=$it") }
                        snapshot.lines.forEach(::add)
                        snapshot.recommendedCommands.forEach { add("command | $it") }
                    },
            )
        }

        SessionCapabilityKey.READ_PERMISSION_PRODUCT -> {
            val sessionId =
                request.sessionId
                    .ifBlank { request.payload["session_id"].orEmpty() }
                    .ifBlank { SessionRuntime.State.runtimeState().session.sessionId }
            val tab =
                request.payload["tab"]
                    .orEmpty()
                    .ifBlank { request.payload["behavior"].orEmpty() }
            val query = request.payload["query"].orEmpty()
            val snapshot =
                SessionPlatformFacade.readPermissionProduct(
                    sessionId = sessionId,
                    limit = request.payload["limit"]?.toIntOrNull() ?: 12,
                    behavior = tab,
                    query = query,
                )
            SessionCapabilityResult(
                success = snapshot.tabCount > 0 || snapshot.cardCount > 0 || snapshot.allowCount > 0 || snapshot.askCount > 0 || snapshot.denyCount > 0,
                summary = if (snapshot.summary.isBlank()) "当前没有 permission product 状态。" else "permission product 已读取。",
                sessionId = sessionId,
                payloadLines =
                    buildList {
                        add("summary=${snapshot.summary}")
                        add("tab=${snapshot.activeTab.ifBlank { "-" }}")
                        snapshot.query.takeIf { it.isNotBlank() }?.let { add("query=$it") }
                        add("recent=${snapshot.recentCount}")
                        add("pending=${snapshot.pendingCount}")
                        add("grants=${snapshot.grantCount}")
                        add("allow=${snapshot.allowCount}")
                        add("ask=${snapshot.askCount}")
                        add("deny=${snapshot.denyCount}")
                        add("sources=${snapshot.sourceCount}")
                        snapshot.tabs.forEach { tabItem ->
                            add("tab | ${tabItem.id} | active=${tabItem.active} | count=${tabItem.count} | ${tabItem.summary}")
                        }
                        snapshot.cards.forEach { card ->
                            add("card | ${card.cardType} | ${card.behavior} | ${card.title} | ${card.scope}")
                            card.subtitle.takeIf { it.isNotBlank() }?.let { add("card_subtitle | ${card.ruleId} | ${it.take(120)}") }
                            card.sourceTag.takeIf { it.isNotBlank() }?.let { add("card_source | ${card.ruleId} | $it") }
                            card.surfaceHint.takeIf { it.isNotBlank() }?.let { add("card_surface | ${card.ruleId} | $it") }
                            add("card_explain | ${card.ruleId} | ${card.explanation.take(120)}")
                            card.primaryCommand.takeIf { it.isNotBlank() }?.let { add("card_action | ${card.ruleId} | $it") }
                        }
                        snapshot.lines.forEach(::add)
                        snapshot.recommendedCommands.forEach { add("command | $it") }
                    },
            )
        }

        SessionCapabilityKey.READ_SAFETY_POLICIES -> {
            val rules =
                RuntimeSafetyPolicyStore.readRules(
                    limit = request.payload["limit"]?.toIntOrNull() ?: 16,
                    actionFamily = request.payload["action_family"].orEmpty(),
                    targetPackageName = request.payload["target_package_name"].orEmpty(),
                    toolName = request.payload["tool_name"].orEmpty(),
                    pageState = request.payload["page_state"].orEmpty(),
                    behavior =
                        request.payload["behavior"]
                            ?.takeIf { it.isNotBlank() }
                            ?.let { value -> runCatching { RuntimeSafetyPolicyBehavior.valueOf(value.uppercase()) }.getOrNull() },
                )
            SessionCapabilityResult(
                success = rules.isNotEmpty(),
                summary = if (rules.isEmpty()) "当前没有显式安全规则。" else "显式安全规则已读取。",
                payloadLines =
                    buildList {
                        rules.forEach { rule ->
                            add("${rule.ruleId} | ${rule.behavior.name.lowercase()} | ${rule.scopeSummary()} | ${rule.note.take(96)}")
                            rule.sourceTag.takeIf { it.isNotBlank() }?.let { add("source | ${rule.ruleId} | $it") }
                            rule.surfaceHint.takeIf { it.isNotBlank() }?.let { add("surface | ${rule.ruleId} | $it") }
                            add("explain | ${rule.ruleId} | ${rule.behaviorSummary().take(120)}")
                        }
                    },
            )
        }

        SessionCapabilityKey.READ_SAFETY_DECISIONS -> {
            val sessionId = request.sessionId.ifBlank { request.payload["session_id"].orEmpty() }
            val entries =
                SessionPlatformFacade.readSessionSafetyDecisions(
                    sessionId = sessionId,
                    limit = request.payload["limit"]?.toIntOrNull() ?: 12,
                )
            SessionCapabilityResult(
                success = entries.isNotEmpty(),
                summary = if (entries.isEmpty()) "当前没有安全决策历史。" else "安全决策历史已读取。",
                sessionId = sessionId,
                payloadLines =
                    entries.map { entry ->
                        "${entry.decisionId} | ${entry.outcome} | ${entry.actionFamily.ifBlank { "-" }} | app=${entry.targetPackageName.ifBlank { "*" }} | ${entry.summary.take(96)}"
                    },
            )
        }

        SessionCapabilityKey.UPSERT_SAFETY_POLICY -> {
            val behavior =
                runCatching {
                    RuntimeSafetyPolicyBehavior.valueOf(
                        request.payload["behavior"].orEmpty().uppercase(),
                    )
                }.getOrDefault(RuntimeSafetyPolicyBehavior.ASK)
            val rule =
                RuntimeSafetyPolicyStore.upsertRule(
                    behavior = behavior,
                    actionFamily = request.payload["action_family"].orEmpty(),
                    targetPackageName = request.payload["target_package_name"].orEmpty(),
                    toolName = request.payload["tool_name"].orEmpty(),
                    pageState = request.payload["page_state"].orEmpty(),
                    targetTextContains = request.payload["target_text_contains"].orEmpty(),
                    note = request.payload["note"].orEmpty(),
                    sourceTag = request.payload["source_tag"].orEmpty(),
                    surfaceHint = request.payload["surface_hint"].orEmpty(),
                    explanation = request.payload["explanation"].orEmpty(),
                )
            SessionCapabilityResult(
                success = rule != null,
                summary = if (rule == null) "未能写入安全规则。" else "安全规则已更新：${rule.ruleId}",
                payloadLines =
                    rule?.let {
                        listOf(
                            "behavior=${it.behavior.name.lowercase()}",
                            "action_family=${it.actionFamily}",
                            "target_package_name=${it.targetPackageName.ifBlank { "*" }}",
                            "tool_name=${it.toolName.ifBlank { "*" }}",
                            "page_state=${it.pageState.ifBlank { "*" }}",
                            "target_text_contains=${it.targetTextContains.ifBlank { "*" }}",
                            "source_tag=${it.sourceTag.ifBlank { "-" }}",
                            "surface_hint=${it.surfaceHint.ifBlank { "-" }}",
                            "explanation=${it.behaviorSummary().ifBlank { "-" }}",
                            "note=${it.note.ifBlank { "-" }}",
                        )
                    }.orEmpty(),
            )
        }

        SessionCapabilityKey.DELETE_SAFETY_POLICY -> {
            val removed = RuntimeSafetyPolicyStore.deleteRule(request.payload["rule_id"].orEmpty())
            SessionCapabilityResult(
                success = removed != null,
                summary = if (removed == null) "未找到安全规则。" else "安全规则已删除：${removed.ruleId}",
                payloadLines =
                    removed?.let {
                        listOf(
                            "behavior=${it.behavior.name.lowercase()}",
                            "action_family=${it.actionFamily}",
                            "target_package_name=${it.targetPackageName.ifBlank { "*" }}",
                            "tool_name=${it.toolName.ifBlank { "*" }}",
                            "page_state=${it.pageState.ifBlank { "*" }}",
                            "target_text_contains=${it.targetTextContains.ifBlank { "*" }}",
                        )
                    }.orEmpty(),
            )
        }

        SessionCapabilityKey.REVOKE_RUNTIME_SAFETY_GRANT -> {
            val grantId = request.payload["grant_id"].orEmpty()
            val grant =
                SessionPlatformFacade.revokeRuntimeSafetyGrant(
                    grantId = grantId,
                    note = request.payload["note"].orEmpty(),
                )
            SessionCapabilityResult(
                success = grant != null,
                summary = if (grant == null) "未找到运行时安全授权：$grantId" else "运行时安全授权已撤销：${grant.grantId}",
                sessionId = grant?.sessionId.orEmpty(),
                payloadLines =
                    grant?.let {
                        listOf(
                            "scope=${it.scope}",
                            "action_family=${it.actionFamily.ifBlank { "-" }}",
                            "target_package=${it.targetPackageName.ifBlank { "*" }}",
                            "status=${it.status.name.lowercase()}",
                        )
                    }.orEmpty(),
            )
        }

        SessionCapabilityKey.READ_GROUNDING_HEALTH -> {
            val sessionId =
                request.sessionId
                    .ifBlank { request.payload["session_id"].orEmpty() }
                    .ifBlank { SessionRuntime.State.runtimeState().session.sessionId }
            val snapshot =
                sessionId.takeIf { it.isNotBlank() }?.let {
                    SessionPlatformFacade.readGroundingHealth(
                        sessionId = it,
                        limit = request.payload["limit"]?.toIntOrNull() ?: 12,
                    )
                }
            SessionCapabilityResult(
                success = snapshot != null && snapshot.totalCount > 0,
                summary = if (snapshot == null || snapshot.totalCount <= 0) "当前没有 grounding 健康记录。" else "grounding 健康度已读取。",
                sessionId = sessionId,
                payloadLines =
                    snapshot?.let {
                        buildList {
                            add("summary=${it.summary}")
                            it.lines.forEach(::add)
                        }
                    }.orEmpty(),
            )
        }

        SessionCapabilityKey.READ_ARTIFACT_RETENTION -> {
            val policy = SessionPlatformFacade.readArtifactRetentionPolicy()
            val report = SessionPlatformFacade.previewArtifactRetention(policy)
            SessionCapabilityResult(
                success = true,
                summary = "artifact retention policy 已读取。",
                payloadLines =
                    listOf(
                        "enabled=${policy.enabled}",
                        "max_age_days=${policy.maxAgeMs / (24L * 60L * 60L * 1000L)}",
                        "orphan_session_days=${policy.orphanSessionMaxAgeMs / (24L * 60L * 60L * 1000L)}",
                        "max_artifacts_per_session=${policy.maxArtifactsPerSession}",
                        "max_artifacts_per_type=${policy.maxArtifactsPerType}",
                        "max_artifacts_per_family=${policy.maxArtifactsPerFamily}",
                        "keep_latest_per_family=${policy.keepLatestPerFamily}",
                        "hot_artifact_window_hours=${policy.hotArtifactWindowMs / (60L * 60L * 1000L)}",
                        "pinned_types=${policy.pinnedTypes.joinToString(",").ifBlank { "-" }}",
                        "preview_line_limit=${policy.previewLineLimit}",
                        "preview_deleted_sessions=${report.deletedSessions}",
                        "preview_deleted_artifacts=${report.deletedArtifacts}",
                        "preview_pinned_artifacts=${report.pinnedArtifacts}",
                        "preview_hot_artifacts=${report.hotArtifacts}",
                        "preview_deleted_by_session_cap=${report.deletedBySessionCap}",
                        "preview_deleted_by_type_cap=${report.deletedByTypeCap}",
                        "preview_deleted_by_family_cap=${report.deletedByFamilyCap}",
                        "preview_deleted_by_age=${report.deletedByAge}",
                        "preview_deleted_by_orphan_session=${report.deletedByOrphanSession}",
                    ) + report.lines,
            )
        }

        SessionCapabilityKey.RUN_ARTIFACT_RETENTION -> {
            val report = SessionPlatformFacade.runArtifactRetentionSweep()
            SessionCapabilityResult(
                success = true,
                summary = "artifact retention sweep 完成。",
                payloadLines =
                    listOf(
                        "deleted_sessions=${report.deletedSessions}",
                        "deleted_artifacts=${report.deletedArtifacts}",
                        "kept_artifacts=${report.keptArtifacts}",
                        "pinned_artifacts=${report.pinnedArtifacts}",
                        "hot_artifacts=${report.hotArtifacts}",
                        "deleted_by_session_cap=${report.deletedBySessionCap}",
                        "deleted_by_type_cap=${report.deletedByTypeCap}",
                        "deleted_by_family_cap=${report.deletedByFamilyCap}",
                        "deleted_by_age=${report.deletedByAge}",
                        "deleted_by_orphan_session=${report.deletedByOrphanSession}",
                    ) + report.lines,
            )
        }

        SessionCapabilityKey.UPSERT_ARTIFACT_RETENTION -> {
            val current = SessionPlatformFacade.readArtifactRetentionPolicy()
            val updated =
                SessionPlatformFacade.updateArtifactRetentionPolicy(
                    current.copy(
                        enabled = request.payload["enabled"]?.toBooleanStrictOrNull() ?: current.enabled,
                        maxAgeMs =
                            request.payload["max_age_days"]?.toLongOrNull()?.times(24L * 60L * 60L * 1000L)
                                ?: current.maxAgeMs,
                        orphanSessionMaxAgeMs =
                            request.payload["orphan_session_days"]?.toLongOrNull()?.times(24L * 60L * 60L * 1000L)
                                ?: current.orphanSessionMaxAgeMs,
                        maxArtifactsPerSession =
                            request.payload["max_artifacts_per_session"]?.toIntOrNull()
                                ?: current.maxArtifactsPerSession,
                        maxArtifactsPerType =
                            request.payload["max_artifacts_per_type"]?.toIntOrNull()
                                ?: current.maxArtifactsPerType,
                        maxArtifactsPerFamily =
                            request.payload["max_artifacts_per_family"]?.toIntOrNull()
                                ?: current.maxArtifactsPerFamily,
                        keepLatestPerFamily =
                            request.payload["keep_latest_per_family"]?.toIntOrNull()
                                ?: current.keepLatestPerFamily,
                        hotArtifactWindowMs =
                            request.payload["hot_artifact_window_hours"]?.toLongOrNull()?.times(60L * 60L * 1000L)
                                ?: current.hotArtifactWindowMs,
                        protectActiveSessions =
                            request.payload["protect_active_sessions"]?.toBooleanStrictOrNull()
                                ?: current.protectActiveSessions,
                        pinnedTypes =
                            request.payload["pinned_types"]
                                ?.takeIf { it.isNotBlank() }
                                ?.split(",")
                                ?.mapNotNull { it.trim().takeIf(String::isNotBlank) }
                                ?: current.pinnedTypes,
                        previewLineLimit =
                            request.payload["preview_line_limit"]?.toIntOrNull()
                                ?: current.previewLineLimit,
                    ),
                )
            SessionCapabilityResult(
                success = true,
                summary = "artifact retention policy 已更新。",
                payloadLines =
                    listOf(
                        "enabled=${updated.enabled}",
                        "max_age_days=${updated.maxAgeMs / (24L * 60L * 60L * 1000L)}",
                        "orphan_session_days=${updated.orphanSessionMaxAgeMs / (24L * 60L * 60L * 1000L)}",
                        "max_artifacts_per_session=${updated.maxArtifactsPerSession}",
                        "max_artifacts_per_type=${updated.maxArtifactsPerType}",
                        "max_artifacts_per_family=${updated.maxArtifactsPerFamily}",
                        "keep_latest_per_family=${updated.keepLatestPerFamily}",
                        "hot_artifact_window_hours=${updated.hotArtifactWindowMs / (60L * 60L * 1000L)}",
                        "protect_active_sessions=${updated.protectActiveSessions}",
                        "pinned_types=${updated.pinnedTypes.joinToString(",")}",
                        "preview_line_limit=${updated.previewLineLimit}",
                    ),
            )
        }

        SessionCapabilityKey.READ_OPERATOR_CONSOLE -> {
            val mailboxSnapshot =
                SessionPlatformFacade.readWorkerMailboxSnapshot(
                    target = request.sessionId,
                    limit = 8,
                    priorityMode = "reply_chain_first",
                )
            val memory = SessionPlatformFacade.readBackgroundMemoryQueue(limit = 6)
            val retention = SessionPlatformFacade.previewArtifactRetention()
            val providers = SessionPlatformFacade.readSignalProviders(limit = 6)
            val productShell = SessionPlatformFacade.readProductShellSnapshot()
            val compensations = SessionCompensationStore.readPendingPlans(limit = 6)
            val grants = PlatformCapabilityApprovalStore.readGrants(limit = 6, includeInactive = false)
            val providerPolicy = SessionPlatformFacade.readPlannerProviderPolicy()
            val providerRegistry = PlannerProviderRegistryStore.read()
            val aggregate = HarnessStore.readAggregateSnapshot() ?: HarnessStore.AggregateSnapshot()
            val regression = RegressionRunner.buildPlan(aggregate = aggregate, limit = 3)
            val commandCenter = SessionCommandCenterStore.readSnapshot(limit = 6)
            val workingMemory =
                request.sessionId.takeIf { it.isNotBlank() }?.let(SessionWorkingMemoryStore::readSnapshot)
            val explanationEntries =
                request.sessionId.takeIf { it.isNotBlank() }?.let { SessionExplanationStore.readRecent(it, limit = 4) }.orEmpty()
            val notebook =
                request.sessionId.takeIf { it.isNotBlank() }?.let(SessionMemoryNotebookStore::readSnapshot)
            SessionCapabilityResult(
                success = mailboxSnapshot.totalCount > 0 || memory.isNotEmpty() || providers.isNotEmpty() || commandCenter.totalCount > 0 || workingMemory != null || explanationEntries.isNotEmpty() || notebook != null,
                summary = "operator console 摘要已读取。",
                payloadLines =
                    buildList {
                        add("mailbox_pending=${mailboxSnapshot.pendingCount}")
                        add("mailbox_attention=${mailboxSnapshot.attentionCount}")
                        add("mailbox_categories=${mailboxSnapshot.categorySummary.ifBlank { "-" }}")
                        add("mailbox_coordination=${mailboxSnapshot.coordinationSummary.ifBlank { "-" }}")
                        add("memory_pending=${memory.count { it.status != BackgroundMemoryTaskStatus.COMPLETED }}")
                        add("retention_preview_deleted_artifacts=${retention.deletedArtifacts}")
                        add("pending_compensations=${compensations.size}")
                        add("active_grants=${grants.size}")
                        add("command_attention=${commandCenter.attentionSummary.ifBlank { "-" }}")
                        add("product_onboarding=${productShell.onboarding.status}")
                        add("product_tips=${productShell.tips.size}")
                        add("swarm=${productShell.swarmStrategy.mode}")
                        add("swarm_dispatch=${productShell.swarmStrategy.dispatchSummary.ifBlank { "-" }}")
                        add("swarm_missions=${productShell.swarmStrategy.missionSummary.ifBlank { "-" }}")
                        add("swarm_escalations=${productShell.swarmStrategy.escalationSummary.ifBlank { "-" }}")
                        add("operator=${productShell.operatorShell.mode}")
                        add("operator_summary=${productShell.operatorShell.summary.ifBlank { "-" }}")
                        add("operator_provider_health=${productShell.operatorShell.providerHealthSummary.ifBlank { "-" }}")
                        add("operator_provider_coverage=${productShell.operatorShell.providerCoverageSummary.ifBlank { "-" }}")
                        add("operator_provider_diagnostics=${productShell.operatorShell.providerDiagnosticsSummary.ifBlank { "-" }}")
                        add("operator_graph_health=${productShell.operatorShell.graphHealthSummary.ifBlank { "-" }}")
                        add("operator_artifact_health=${productShell.operatorShell.artifactHealthSummary.ifBlank { "-" }}")
                        add("operator_replay_health=${productShell.operatorShell.replayHealthSummary.ifBlank { "-" }}")
                        add("operator_worker_health=${productShell.operatorShell.workerHealthSummary.ifBlank { "-" }}")
                        add("operator_worker_missions=${productShell.operatorShell.workerMissionSummary.ifBlank { "-" }}")
                        add("operator_worker_lease=${productShell.operatorShell.workerLeaseSummary.ifBlank { "-" }}")
                        add("operator_swarm_policy=${productShell.operatorShell.swarmPolicySummary.ifBlank { "-" }}")
                        add("agenda=${productShell.agendaShell.mode}")
                        add("agenda_summary=${productShell.agendaShell.summary.ifBlank { "-" }}")
                        add("routine=${productShell.routineShell.mode}")
                        add("routine_summary=${productShell.routineShell.summary.ifBlank { "-" }}")
                        add("digest=${productShell.digestShell.mode}")
                        add("digest_summary=${productShell.digestShell.summary.ifBlank { "-" }}")
                        add("memory_insight=${productShell.memoryInsight.summary.ifBlank { "-" }}")
                        add("memory_away=${productShell.memoryInsight.awaySummary.ifBlank { "-" }}")
                        add("voice_state=${productShell.voiceInteraction.state}")
                        add("voice_summary=${productShell.voiceInteraction.summary.ifBlank { "-" }}")
                        add("viewer_working_memory=${workingMemory?.progressSummary.orEmpty().ifBlank { "-" }}")
                        add("viewer_explanations=${explanationEntries.size}")
                        add("viewer_notebook=${notebook?.markdownPath.orEmpty().ifBlank { "-" }}")
                        add("interrupt=${productShell.interruptBudget.mode}")
                        add("interrupt_remaining=${productShell.interruptBudget.remainingBudget}/${productShell.interruptBudget.totalBudget}")
                        add("focus=${productShell.personalFocus.mode}")
                        add("focus_summary=${productShell.personalFocus.summary.ifBlank { "-" }}")
                        add("rhythm=${productShell.dailyRhythm.mode}")
                        add("rhythm_summary=${productShell.dailyRhythm.summary.ifBlank { "-" }}")
                        add("provider_policy_enabled=${providerPolicy.enabled}")
                        add("provider_registry=${providerRegistry.size}")
                        add("regression_plan=${regression.plannedItems.size}")
                        providers.forEach { provider ->
                            add(
                                "provider ${provider.providerId} | health=${provider.healthScore} | failures=${provider.failureCount} | accepted=${provider.acceptedSignals}",
                            )
                        }
                        commandCenter.recentLines.forEach { line ->
                            add("command | $line")
                        }
                        workingMemory?.openLoops?.take(3)?.forEach { line ->
                            add("working_memory | open_loop | $line")
                        }
                        workingMemory?.cautionNotes?.take(2)?.forEach { line ->
                            add("working_memory | caution | $line")
                        }
                        explanationEntries.forEach { entry ->
                            add("explanation | #${entry.turn} | ${entry.phase} | ${entry.summary.take(80)}")
                        }
                        notebook?.previewLines?.take(4)?.forEach { line ->
                            add("notebook | ${line.take(100)}")
                        }
                        providerRegistry.take(3).forEach { provider ->
                            add(
                                "planner_provider ${provider.providerId} | ${provider.backendId} | enabled=${provider.enabled} | priority=${provider.priority}",
                            )
                        }
                        mailboxSnapshot.recentLines.forEach { line ->
                            add("mailbox | $line")
                        }
                        memory.forEach { task ->
                            add("memory ${task.type.name.lowercase()} | ${task.status.name.lowercase()} | ${task.task.ifBlank { task.profileId }}")
                        }
                        compensations.forEach { plan ->
                            add("compensation ${plan.sessionId}#${plan.turn} | ${plan.status} | ${plan.summary.take(64)}")
                        }
                        grants.forEach { grant ->
                            add(
                                "grant ${grant.grantId} | ${grant.scope} | ${grant.permissionFamily.ifBlank { "general" }} | ${grant.capability?.name?.lowercase() ?: "*"}",
                            )
                        }
                        productShell.operatorShell.urgentLines.forEach { line ->
                            add("operator | $line")
                        }
                        productShell.operatorShell.recommendedCommands.forEach { command ->
                            add("operator_command | $command")
                        }
                        regression.results.take(2).forEach { result ->
                            add("regression_result | ${result.scenarioId} | ${result.status} | ${result.summary}")
                        }
                    },
            )
        }

        else -> SessionCapabilityResult(false, "unsupported_platform_assistant_capability:${request.key.name.lowercase()}")
    }
}

private fun parseCsvOrPipeList(
    raw: String?,
): List<String> =
    raw.orEmpty()
        .split(",", "|")
        .mapNotNull { it.trim().takeIf(String::isNotBlank) }
