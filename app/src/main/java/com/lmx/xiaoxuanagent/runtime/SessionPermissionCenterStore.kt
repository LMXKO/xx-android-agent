package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.safety.RuntimeSafetyGrantStore
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyPolicyBehavior
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyPolicyStore
import com.lmx.xiaoxuanagent.safety.behaviorSummary
import com.lmx.xiaoxuanagent.safety.scopeSummary

data class SessionPermissionCenterSnapshot(
    val sessionId: String = "",
    val pendingSummary: String = "",
    val policyCount: Int = 0,
    val allowCount: Int = 0,
    val askCount: Int = 0,
    val denyCount: Int = 0,
    val activeGrantCount: Int = 0,
    val decisionCount: Int = 0,
    val summary: String = "",
    val lines: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
)

object SessionPermissionCenterStore {
    fun readSnapshot(
        sessionId: String = "",
        limit: Int = 12,
    ): SessionPermissionCenterSnapshot {
        val liveState = SessionRuntime.State.runtimeState()
        val pending =
            when {
                sessionId.isBlank() || liveState.session.sessionId == sessionId -> liveState.safety.pendingConfirmation
                else -> SessionPlatformFacade.readSessionSnapshot(sessionId).resumeSnapshot?.safety?.pendingConfirmation
            }
        val grants = RuntimeSafetyGrantStore.readGrants(limit = limit, includeInactive = false, sessionId = sessionId)
        val policies = RuntimeSafetyPolicyStore.readRules(limit = maxOf(limit, 24))
        val decisions = SessionSafetyDecisionStore.readRecent(sessionId = sessionId, limit = limit)
        val allowCount = policies.count { it.behavior == RuntimeSafetyPolicyBehavior.ALLOW }
        val askCount = policies.count { it.behavior == RuntimeSafetyPolicyBehavior.ASK }
        val denyCount = policies.count { it.behavior == RuntimeSafetyPolicyBehavior.DENY }
        val summary =
            buildString {
                append("pending=").append(if (pending != null) 1 else 0)
                append(" allow=").append(allowCount)
                append(" ask=").append(askCount)
                append(" deny=").append(denyCount)
                append(" policies=").append(policies.size)
                append(" grants=").append(grants.size)
                append(" decisions=").append(decisions.size)
            }
        return SessionPermissionCenterSnapshot(
            sessionId = sessionId,
            pendingSummary = pending?.summary.orEmpty(),
            policyCount = policies.size,
            allowCount = allowCount,
            askCount = askCount,
            denyCount = denyCount,
            activeGrantCount = grants.size,
            decisionCount = decisions.size,
            summary = summary,
            lines =
                buildList {
                    pending?.let {
                        add("pending | ${it.actionLabel.ifBlank { "-" }} | ${it.summary.take(88)}")
                    }
                    add("policy_summary | allow=$allowCount | ask=$askCount | deny=$denyCount")
                    policies.take(4).forEach {
                        add(
                            "policy | ${it.ruleId} | ${it.behavior.name.lowercase()} | ${it.scopeSummary()}",
                        )
                        it.sourceTag.takeIf { tag -> tag.isNotBlank() }?.let { tag -> add("policy_source | ${it.ruleId} | $tag") }
                        it.surfaceHint.takeIf { hint -> hint.isNotBlank() }?.let { hint -> add("policy_surface | ${it.ruleId} | $hint") }
                        add("policy_explain | ${it.ruleId} | ${it.behaviorSummary().take(120)}")
                    }
                    grants.take(3).forEach { add("grant | ${it.scope} | ${it.actionFamily.ifBlank { "-" }} | ${it.targetPackageName.ifBlank { "*" }}") }
                    decisions.take(4).forEach { add("decision | ${it.outcome} | ${it.actionFamily.ifBlank { "-" }} | ${it.summary.take(88)}") }
                },
            recommendedCommands =
                listOfNotNull(
                    if (pending != null && sessionId.isNotBlank()) "/approve --session-id $sessionId" else null,
                    if (pending != null && sessionId.isNotBlank()) "/reject --session-id $sessionId" else null,
                    if (pending != null && pending.actionFamily.isNotBlank()) {
                        "/set-safety-policy --behavior ask --action-family ${pending.actionFamily}" +
                            pending.targetPackageName.takeIf { it.isNotBlank() }?.let { " --target-package-name $it" }.orEmpty()
                    } else {
                        null
                    },
                    if (allowCount > 0) "/safety-policies --behavior allow" else null,
                    if (askCount > 0) "/safety-policies --behavior ask" else null,
                    if (denyCount > 0) "/safety-policies --behavior deny" else null,
                    "/permission-center${sessionId.takeIf { it.isNotBlank() }?.let { " --session-id $it" }.orEmpty()}",
                    "/safety-center",
                    "/safety-policies",
                    "/safety-decisions",
                ).distinct(),
        )
    }
}
