package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.safety.RuntimeSafetyGrant
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyPolicyBehavior
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyPolicyRule

data class AppPermissionPresentationSnapshot(
    val lines: List<String> = emptyList(),
    val cards: List<SessionPermissionProductCard> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
)

object AppPermissionPresentationStore {
    fun build(
        rules: List<RuntimeSafetyPolicyRule>,
        grants: List<RuntimeSafetyGrant>,
        sessionId: String,
    ): AppPermissionPresentationSnapshot {
        val packageCards =
            rules
                .groupBy { it.targetPackageName.ifBlank { "*" } }
                .entries
                .sortedByDescending { it.value.size }
                .take(4)
                .map { (pkg, pkgRules) ->
                    val allow = pkgRules.count { it.behavior == RuntimeSafetyPolicyBehavior.ALLOW }
                    val ask = pkgRules.count { it.behavior == RuntimeSafetyPolicyBehavior.ASK }
                    val deny = pkgRules.count { it.behavior == RuntimeSafetyPolicyBehavior.DENY }
                    val dominantBehavior =
                        when {
                            deny > 0 -> RuntimeSafetyPolicyBehavior.DENY
                            ask > 0 -> RuntimeSafetyPolicyBehavior.ASK
                            else -> RuntimeSafetyPolicyBehavior.ALLOW
                        }
                    SessionPermissionProductCard(
                        ruleId = "app_permission_$pkg",
                        cardType = "app_permission",
                        behavior = dominantBehavior.name.lowercase(),
                        title = AppPermissionSemanticSupport.packageLabel(pkg),
                        subtitle = AppPermissionSemanticSupport.behaviorLabel(dominantBehavior),
                        scope = pkg,
                        sourceTag = "presentation",
                        surfaceHint = "app_permission",
                        explanation =
                            "授权=${AppPermissionSemanticSupport.behaviorLabel(dominantBehavior)} | " +
                                "已授权能力=${grants.count { it.targetPackageName == pkg }} | " +
                                "allow=$allow ask=$ask deny=$deny",
                        matchSummary =
                            pkgRules
                                .take(3)
                                .joinToString(" | ") { AppPermissionSemanticSupport.actionFamilyLabel(it.actionFamily) },
                        primaryCommand = "/permission-product${sessionId.takeIf { it.isNotBlank() }?.let { " --session-id $it" }.orEmpty()} --tab ask",
                    )
                }
        val lines =
            packageCards.map { card ->
                "app_permission | ${card.title} | ${card.subtitle} | ${card.explanation}"
            }
        return AppPermissionPresentationSnapshot(
            lines = lines,
            cards = packageCards,
            recommendedCommands =
                buildList {
                    if (packageCards.isNotEmpty()) {
                        add("/permission-center${sessionId.takeIf { it.isNotBlank() }?.let { " --session-id $it" }.orEmpty()}")
                    }
                },
        )
    }
}
