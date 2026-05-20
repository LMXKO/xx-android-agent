package com.lmx.xiaoxuanagent.safety

enum class RiskLevel {
    LOW,
    CONFIRM,
    BLOCK,
}

data class SafetyReview(
    val level: RiskLevel,
    val title: String = "",
    val summary: String = "",
    val actionLabel: String = "",
    val approvalKey: String = "",
    val targetPackageName: String = "",
    val actionFamily: String = "",
    val riskLevelLabel: String = "",
    val grantScopeHint: String = "",
)

data class PendingSafetyConfirmation(
    val approvalKey: String,
    val title: String,
    val summary: String,
    val actionLabel: String,
    val planStage: String = "",
    val subgoalId: String = "",
    val nextObjective: String = "",
    val targetPackageName: String = "",
    val actionFamily: String = "",
    val riskLevelLabel: String = "",
    val grantScopeHint: String = "",
    val handoffReason: String = "",
    val completionSummary: String = "",
    val finalUserStep: String = "",
)
