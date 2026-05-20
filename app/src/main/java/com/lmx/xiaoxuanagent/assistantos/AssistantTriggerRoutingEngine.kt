package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.memory.PersonalAssistantUserModelSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey
import com.lmx.xiaoxuanagent.taskprofile.TaskRegistry

private data class AssistantTriggerRouteContext(
    val signal: AssistantExternalSignal,
    val activeSessionId: String,
    val providerState: AssistantSignalProviderState?,
    val providerId: String,
    val profileId: String,
    val profileDisplayName: String,
    val policySnapshot: AssistantTriggerRoutePolicySnapshot,
    val adaptivePolicy: AssistantAdaptivePolicySnapshot,
    val userModel: PersonalAssistantUserModelSnapshot,
    val autonomyPlan: AssistantAutonomyPlanSnapshot,
)

private data class AssistantTriggerRoutePolicy(
    val id: String,
    val lane: String,
    val priority: Int,
    val matches: (AssistantTriggerRouteContext) -> Boolean,
    val route: (AssistantTriggerRouteContext) -> AssistantTriggerRoutingDecision,
)

internal object AssistantTriggerRoutingEngine {
    fun route(
        signal: AssistantExternalSignal,
        activeSessionId: String = "",
        providerState: AssistantSignalProviderState? = null,
        adaptivePolicy: AssistantAdaptivePolicySnapshot = AssistantAdaptivePolicySnapshot(),
        userModel: PersonalAssistantUserModelSnapshot = PersonalAssistantUserModelSnapshot(),
        autonomyPlan: AssistantAutonomyPlanSnapshot = AssistantAutonomyPlanSnapshot(),
    ): AssistantTriggerRoutingDecision {
        val packageName = signal.payload["package_name"].orEmpty()
        val profile = TaskRegistry.allProfiles().firstOrNull { it.packageName == packageName }
        val context =
            AssistantTriggerRouteContext(
                signal = signal,
                activeSessionId = activeSessionId,
                providerState = providerState,
                providerId = providerState?.providerId.orEmpty(),
                profileId = profile?.id.orEmpty(),
                profileDisplayName = profile?.displayName.orEmpty(),
                policySnapshot = AssistantTriggerRoutePolicyStore.read(),
                adaptivePolicy = adaptivePolicy,
                userModel = userModel,
                autonomyPlan = autonomyPlan,
            )
        return providerGuard(context)
            ?: autonomyGuard(context)
            ?: activeSessionGuard(context)
            ?: explicitOverride(context)
            ?: policyMatch(context)
            ?: refreshOnlyDecision(context, "fallback_refresh_only")
    }

    private fun providerGuard(
        context: AssistantTriggerRouteContext,
    ): AssistantTriggerRoutingDecision? {
        val providerState = context.providerState ?: return null
        return when {
            !providerState.supports(context.signal.capability) ->
                refreshOnlyDecision(context, "provider_capability_not_supported")

            !providerState.supports(context.signal.type) ->
                refreshOnlyDecision(context, "provider_signal_type_not_supported")

            providerState.trustLevel == AssistantSignalProviderTrustLevel.LOW &&
                context.signal.capability == SessionCapabilityKey.START_SESSION ->
                refreshOnlyDecision(context, "low_trust_provider_refresh_only")

            else -> null
        }
    }

    private fun autonomyGuard(
        context: AssistantTriggerRouteContext,
    ): AssistantTriggerRoutingDecision? {
        if (
            context.autonomyPlan.mode == "onboarding_guard" &&
            context.signal.capability == SessionCapabilityKey.START_SESSION
        ) {
            return refreshOnlyDecision(context, "autonomy_onboarding_guard")
        }
        if (
            context.autonomyPlan.mode == "approval_guard" &&
            context.signal.capability == SessionCapabilityKey.START_SESSION &&
            context.signal.type != AssistantExternalSignalType.NOTIFICATION
        ) {
            return refreshOnlyDecision(context, "autonomy_approval_guard")
        }
        if (
            "voice" in context.adaptivePolicy.blockedEntrySurfaces &&
            context.signal.source.contains("voice", ignoreCase = true)
        ) {
            return refreshOnlyDecision(context, "adaptive_surface_blocked")
        }
        if (
            context.userModel.topProfileIds.isNotEmpty() &&
            context.signal.capability == SessionCapabilityKey.START_SESSION &&
            context.profileId.isBlank() &&
            context.signal.type != AssistantExternalSignalType.MESSAGE
        ) {
            return refreshOnlyDecision(context, "unknown_profile_refresh_only")
        }
        return null
    }

    private fun activeSessionGuard(
        context: AssistantTriggerRouteContext,
    ): AssistantTriggerRoutingDecision? {
        if (
            context.activeSessionId.isNotBlank() &&
            context.signal.sessionId.isNotBlank() &&
            context.signal.sessionId == context.activeSessionId
        ) {
            return enrichDecision(
                context = context,
                policyId = "active_session_exact_match",
                lane = "active_session",
                decision =
                    AssistantTriggerRoutingDecision(
                        capability = context.signal.capability,
                        task = context.signal.task,
                        query = context.signal.query,
                        summary = context.signal.summary.ifBlank { context.signal.title },
                    ),
            )
        }
        if (context.activeSessionId.isNotBlank()) {
            return refreshOnlyDecision(context, "active_session_present")
        }
        return null
    }

    private fun explicitOverride(
        context: AssistantTriggerRouteContext,
    ): AssistantTriggerRoutingDecision? {
        val signalTypeKey = context.signal.type.name.lowercase()
        val packageName = context.signal.payload["package_name"].orEmpty()
        val capabilityOverride =
            context.policySnapshot.packageCapabilityOverrides[packageName]
                ?: context.policySnapshot.signalTypeCapabilityOverrides[signalTypeKey]
                ?: return null
        return enrichDecision(
            context = context,
            policyId = "policy_override",
            lane = "override",
            decision =
                AssistantTriggerRoutingDecision(
                    capability = capabilityOverride,
                    task = deriveOverrideTask(context, capabilityOverride),
                    query = context.signal.query,
                    summary = context.signal.summary.ifBlank { context.signal.title },
                    payload = buildMap {
                        packageName.takeIf { it.isNotBlank() }?.let { put("package_name", it) }
                        context.profileId.takeIf { it.isNotBlank() }?.let { put("profile_id", it) }
                    },
                ),
        )
    }

    private fun policyMatch(
        context: AssistantTriggerRouteContext,
    ): AssistantTriggerRoutingDecision? {
        val disabled = context.policySnapshot.disabledPolicyIds
        val matchedPolicy =
            policies()
                .filterNot { it.id in disabled }
                .sortedWith(compareByDescending<AssistantTriggerRoutePolicy> { it.priority })
                .firstOrNull { it.matches(context) }
                ?: return null
        return enrichDecision(
            context = context,
            policyId = matchedPolicy.id,
            lane = matchedPolicy.lane,
            decision = matchedPolicy.route(context),
        )
    }

    private fun policies(): List<AssistantTriggerRoutePolicy> =
        listOf(
            AssistantTriggerRoutePolicy(
                id = "notification_resume_existing_session",
                lane = "resume",
                priority = 120,
                matches = { it.signal.type == AssistantExternalSignalType.NOTIFICATION && it.signal.sessionId.isNotBlank() },
                route = { context ->
                    AssistantTriggerRoutingDecision(
                        capability = SessionCapabilityKey.RESUME_SESSION,
                        summary = context.signal.summary.ifBlank { "通知驱动恢复任务" },
                    )
                },
            ),
            AssistantTriggerRoutePolicy(
                id = "notification_start_known_profile",
                lane = "engagement",
                priority = 110,
                matches = { it.signal.type == AssistantExternalSignalType.NOTIFICATION && it.profileId.isNotBlank() },
                route = { context ->
                    AssistantTriggerRoutingDecision(
                        capability = SessionCapabilityKey.START_SESSION,
                        task =
                            context.signal.task.ifBlank {
                                buildString {
                                    append("处理来自 ").append(context.profileDisplayName).append(" 的通知")
                                    context.signal.payload["title"]?.takeIf { it.isNotBlank() }?.let { append("：").append(it) }
                                    context.signal.payload["text"]?.takeIf { it.isNotBlank() }?.let { append(" ").append(it.take(80)) }
                                }
                            },
                        summary = context.signal.summary.ifBlank { "通知命中已知应用任务" },
                    )
                },
            ),
            AssistantTriggerRoutePolicy(
                id = "message_passthrough",
                lane = "conversation",
                priority = 100,
                matches = { it.signal.type == AssistantExternalSignalType.MESSAGE },
                route = { context ->
                    AssistantTriggerRoutingDecision(
                        capability = context.signal.capability,
                        task =
                            context.signal.task.ifBlank {
                                context.signal.query.takeIf { it.isNotBlank() }?.let { query -> "处理消息线索：$query" }.orEmpty()
                            },
                        query = context.signal.query,
                        summary = context.signal.summary.ifBlank { context.signal.title },
                    )
                },
            ),
            AssistantTriggerRoutePolicy(
                id = "relationship_follow_up",
                lane = "relationship",
                priority = 96,
                matches = {
                    it.signal.type == AssistantExternalSignalType.CONTACT ||
                        it.signal.type == AssistantExternalSignalType.CALL_LOG
                },
                route = { context ->
                    AssistantTriggerRoutingDecision(
                        capability = context.signal.capability,
                        task =
                            context.signal.task.ifBlank {
                                "跟进联系人线索：${context.signal.summary.ifBlank { context.signal.title }}"
                            },
                        query = context.signal.query,
                        summary = context.signal.summary.ifBlank { context.signal.title },
                    )
                },
            ),
            AssistantTriggerRoutePolicy(
                id = "contextual_signal_follow_up",
                lane = "context",
                priority = 90,
                matches = {
                    it.signal.type == AssistantExternalSignalType.CALENDAR ||
                        it.signal.type == AssistantExternalSignalType.LOCATION
                },
                route = { context ->
                    AssistantTriggerRoutingDecision(
                        capability = context.signal.capability,
                        task =
                            context.signal.task.ifBlank {
                                listOf(context.signal.title, context.signal.summary, context.signal.query)
                                    .firstOrNull { it.isNotBlank() }
                                    ?.let { summary -> "根据外部线索继续跟进：$summary" }
                                    .orEmpty()
                            },
                        query = context.signal.query,
                        summary = context.signal.summary.ifBlank { context.signal.title },
                    )
                },
            ),
            AssistantTriggerRoutePolicy(
                id = "foreground_start_known_profile",
                lane = "foreground_takeover",
                priority = 85,
                matches = { it.signal.type == AssistantExternalSignalType.APP_FOREGROUND && it.profileId.isNotBlank() },
                route = { context ->
                    AssistantTriggerRoutingDecision(
                        capability = SessionCapabilityKey.START_SESSION,
                        task =
                            context.signal.task.ifBlank {
                                "观察并接管 ${context.profileDisplayName} 当前前台任务"
                            },
                        query = context.signal.query,
                        summary = context.signal.summary.ifBlank { context.profileDisplayName },
                        payload = mapOf("profile_id" to context.profileId),
                    )
                },
            ),
            AssistantTriggerRoutePolicy(
                id = "clipboard_follow_up",
                lane = "capture",
                priority = 70,
                matches = { it.signal.type == AssistantExternalSignalType.CLIPBOARD },
                route = { context ->
                    AssistantTriggerRoutingDecision(
                        capability = context.signal.capability,
                        task =
                            context.signal.task.ifBlank {
                                context.signal.query.takeIf { it.isNotBlank() }?.let { query -> "根据剪贴板内容跟进：$query" }.orEmpty()
                            },
                        query = context.signal.query,
                        summary = context.signal.summary.ifBlank { context.signal.title },
                    )
                },
            ),
            AssistantTriggerRoutePolicy(
                id = "system_refresh_or_passthrough",
                lane = "system",
                priority = 60,
                matches = {
                    it.signal.type == AssistantExternalSignalType.SYSTEM_EVENT ||
                        it.signal.type == AssistantExternalSignalType.APP_FOREGROUND
                },
                route = { context ->
                    AssistantTriggerRoutingDecision(
                        capability = context.signal.capability,
                        task = context.signal.task,
                        query = context.signal.query,
                        summary = context.signal.summary.ifBlank { context.signal.title },
                    )
                },
            ),
        )

    private fun deriveOverrideTask(
        context: AssistantTriggerRouteContext,
        capability: SessionCapabilityKey,
    ): String =
        when (capability) {
            SessionCapabilityKey.START_SESSION ->
                context.signal.task.ifBlank {
                    context.profileDisplayName.takeIf { it.isNotBlank() }?.let { profileName ->
                        "跟进来自 $profileName 的外部线索"
                    }.orEmpty()
                }

            else -> context.signal.task
        }

    private fun refreshOnlyDecision(
        context: AssistantTriggerRouteContext,
        reason: String,
    ): AssistantTriggerRoutingDecision =
        enrichDecision(
            context = context,
            policyId = reason,
            lane = "refresh",
            decision =
                AssistantTriggerRoutingDecision(
                    capability = SessionCapabilityKey.REFRESH_ASSISTANT_OS,
                    summary = context.signal.summary.ifBlank { context.signal.title },
                ),
        )

    private fun enrichDecision(
        context: AssistantTriggerRouteContext,
        policyId: String,
        lane: String,
        decision: AssistantTriggerRoutingDecision,
    ): AssistantTriggerRoutingDecision {
        val providerState = context.providerState
        val metadata =
            buildMap {
                putAll(context.signal.payload)
                put("routing_policy", policyId)
                put("routing_lane", lane)
                put("provider_id", context.providerId)
                providerState?.providerType?.takeIf { it.isNotBlank() }?.let { put("provider_type", it) }
                providerState?.preferredEntrySource?.takeIf { it.isNotBlank() }?.let { put("provider_entry_source", it) }
                providerState?.deliveryMode?.takeIf { it.isNotBlank() }?.let { put("provider_delivery_mode", it) }
                if (providerState?.routingTags?.isNotEmpty() == true) {
                    put("provider_tags", providerState.routingTags.joinToString(","))
                }
                context.profileId.takeIf { it.isNotBlank() }?.let { put("matched_profile_id", it) }
                context.profileDisplayName.takeIf { it.isNotBlank() }?.let { put("matched_profile_name", it) }
            }
        return decision.copy(payload = decision.payload + metadata)
    }
}
