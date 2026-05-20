package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.memory.PersonalAssistantUserModelSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey

data class AssistantTriggerRoutingDecision(
    val capability: SessionCapabilityKey,
    val task: String = "",
    val query: String = "",
    val userCorrection: String = "",
    val payload: Map<String, String> = emptyMap(),
    val summary: String = "",
    val shouldConsume: Boolean = true,
)

internal object AssistantTriggerRoutingPolicy {
    fun route(
        signal: AssistantExternalSignal,
        activeSessionId: String = "",
        providerState: AssistantSignalProviderState? = null,
        adaptivePolicy: AssistantAdaptivePolicySnapshot = AssistantAdaptivePolicyStore.read(),
        userModel: PersonalAssistantUserModelSnapshot = AssistantProductShellStore.read().userModel,
        autonomyPlan: AssistantAutonomyPlanSnapshot = AssistantProductShellStore.read().autonomyPlan,
    ): AssistantTriggerRoutingDecision {
        val shell = AssistantProductShellStore.read()
        val effectiveUserModel = if (userModel.summary.isNotBlank()) userModel else shell.userModel
        val effectiveAutonomyPlan = if (autonomyPlan.summary.isNotBlank()) autonomyPlan else shell.autonomyPlan
        return AssistantTriggerRoutingEngine.route(
            signal = signal,
            activeSessionId = activeSessionId,
            providerState = providerState,
            adaptivePolicy = adaptivePolicy,
            userModel = effectiveUserModel,
            autonomyPlan = effectiveAutonomyPlan,
        )
    }
}
