package com.lmx.xiaoxuanagent.safety

import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.ScreenObservation

object CrossAppDataLeakPolicy {
    private val sensitiveKeywords = listOf("密码", "验证码", "token", "secret", "银行卡", "身份证")

    fun review(
        observation: ScreenObservation,
        action: AgentAction,
        semanticText: String,
        targetPackageName: String,
        declaredHandoffFields: Set<String> = emptySet(),
    ): ScreenContentRiskSignal {
        val crossApp = targetPackageName.isNotBlank() && observation.packageName.isNotBlank() && observation.packageName != targetPackageName
        val exportAction =
            action is AgentAction.ShareText ||
                action is AgentAction.PasteClipboard ||
                action is AgentAction.PopulatePrimaryInput ||
                action is AgentAction.SetText ||
                action is AgentAction.ReplyNotification
        if (!crossApp || !exportAction) {
            return ScreenContentRiskSignal()
        }
        val matched = sensitiveKeywords.firstOrNull { semanticText.contains(it, ignoreCase = true) }
        return when {
            // 敏感内容（密码/验证码等）永不放行，即便在 mission 内。
            matched != null ->
                ScreenContentRiskSignal(
                    behavior = "deny",
                    summary = "cross_app_sensitive_leak",
                    detailLines = listOf("screen_risk=cross_app_leak", "matched=$matched", "from=${observation.packageName}", "to=$targetPackageName"),
                )
            // mission 声明了允许跨 App 携带的字段（如 query/price）：非敏感的跨 App 变更直接放行，不打断。
            declaredHandoffFields.isNotEmpty() ->
                ScreenContentRiskSignal()
            else ->
                ScreenContentRiskSignal(
                    behavior = "ask",
                    summary = "cross_app_mutation_review",
                    detailLines = listOf("screen_risk=cross_app_mutation", "from=${observation.packageName}", "to=$targetPackageName"),
                )
        }
    }
}
