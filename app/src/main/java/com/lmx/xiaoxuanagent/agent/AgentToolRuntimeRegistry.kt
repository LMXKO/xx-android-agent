package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.runtime.SessionRuntime
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyGrantStore
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyPolicyBehavior
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyPolicyStore

data class AgentToolRuntimeContract(
    val toolName: String,
    val permissionFamily: String = "",
    val readOnly: Boolean = false,
    val concurrencySafe: Boolean = false,
    val requiresUserInteraction: Boolean = false,
    val shouldDefer: Boolean = false,
    val alwaysLoad: Boolean = false,
    val interruptBehavior: String = "",
    val inputContract: String = "",
    val resultContract: String = "",
    val progressLabel: String = "",
    val activityDescription: String = "",
    val permissionPreview: String = "",
    val protocolPrompt: String = "",
    val protocolSummary: String = "",
    val detailLines: List<String> = emptyList(),
)

object AgentToolRuntimeRegistry {
    fun resolve(
        action: AgentAction,
        observation: ScreenObservation,
    ): AgentToolRuntimeContract {
        val protocol = AgentToolRuntimeProtocol.evaluate(action = action, observation = observation)
        val descriptor = protocol.descriptor ?: AgentToolCatalog.find(action.toolName)
        return resolveDescriptor(
            descriptor = descriptor,
            observation = observation,
            actionLabel = action.label,
            protocol = protocol,
        )
    }

    fun resolveDescriptor(
        descriptor: AgentToolDescriptor?,
        observation: ScreenObservation? = null,
        actionLabel: String = "",
        protocol: AgentToolRuntimeEnvelope? = null,
    ): AgentToolRuntimeContract {
        val permissionFamily = descriptor?.permissionFamily.orEmpty().ifBlank { "general" }
        val matchedRule =
            observation?.let {
                RuntimeSafetyPolicyStore.resolveRule(
                    actionFamily = permissionFamily,
                    targetPackageName = it.packageName,
                    toolName = descriptor?.name.orEmpty(),
                    pageState = it.pageState,
                    targetText = actionLabel,
                )
            }
        val matchedGrant =
            observation?.let {
                RuntimeSafetyGrantStore.resolveGrant(
                    packageName = it.packageName,
                    actionFamily = permissionFamily,
                    sessionId = SessionRuntime.State.runtimeState().session.sessionId,
                )
            }
        val permissionPreview =
            when {
                matchedRule?.behavior == RuntimeSafetyPolicyBehavior.DENY -> "deny_rule"
                matchedRule?.behavior == RuntimeSafetyPolicyBehavior.ASK -> "ask_rule"
                matchedRule?.behavior == RuntimeSafetyPolicyBehavior.ALLOW -> "allow_rule"
                matchedGrant != null -> "runtime_grant:${matchedGrant.scope}"
                else -> "runtime_review"
            }
        return AgentToolRuntimeContract(
            toolName = descriptor?.name.orEmpty(),
            permissionFamily = permissionFamily,
            readOnly = protocol?.readOnly ?: !((descriptor?.irreversible) ?: false),
            concurrencySafe = descriptor?.concurrencySafe ?: false,
            requiresUserInteraction = descriptor?.requiresUserInteraction ?: false,
            shouldDefer = descriptor?.shouldDefer ?: false,
            alwaysLoad = descriptor?.alwaysLoad ?: false,
            interruptBehavior = descriptor?.interruptBehavior.orEmpty().ifBlank { "cancel" },
            inputContract = descriptor?.inputContract.orEmpty(),
            resultContract = descriptor?.resultContract.orEmpty(),
            progressLabel = descriptor?.progressLabel.orEmpty(),
            activityDescription = descriptor?.progressLabel.orEmpty().ifBlank { descriptor?.summary.orEmpty().ifBlank { actionLabel } },
            permissionPreview = permissionPreview,
            protocolPrompt = protocol?.prompt ?: descriptor?.summary.orEmpty(),
            protocolSummary = protocol?.summary ?: descriptor?.summary.orEmpty(),
            detailLines =
                buildList {
                    addAll(protocol?.detailLines.orEmpty())
                    add("permission_preview=$permissionPreview")
                    descriptor?.fallbackTools?.takeIf { it.isNotEmpty() }?.let {
                        add("fallback_chain=${it.joinToString("->")}")
                    }
                    descriptor?.inputContract?.takeIf { it.isNotBlank() }?.let {
                        add("input_contract=$it")
                    }
                    matchedRule?.ruleId?.takeIf { it.isNotBlank() }?.let { add("policy_rule=$it") }
                    matchedGrant?.grantId?.takeIf { it.isNotBlank() }?.let { add("grant_id=$it") }
                }.distinct().take(10),
        )
    }

    fun catalog(): List<AgentToolRuntimeContract> =
        AgentToolCatalog.descriptors().map { descriptor ->
            resolveDescriptor(descriptor = descriptor, actionLabel = descriptor.summary)
        }
}
