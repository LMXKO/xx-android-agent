package com.lmx.xiaoxuanagent.safety

import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.ScreenObservation

object ScreenPromptInjectionGuard {
    fun review(
        observation: ScreenObservation,
        action: AgentAction,
        semanticText: String,
    ): ScreenContentRiskSignal {
        val contentRisk = ScreenContentRiskClassifier.analyze(observation, semanticText)
        if (contentRisk.behavior != "allow") {
            return contentRisk
        }
        val mutationAction =
            action is AgentAction.SetText ||
                action is AgentAction.PopulatePrimaryInput ||
                action is AgentAction.ShareText ||
                action is AgentAction.ReplyNotification
        if (!mutationAction) {
            return ScreenContentRiskSignal()
        }
        val combined = (observation.topTexts + observation.elements.take(12).map { it.text }).joinToString(" ")
        return if (
            combined.contains("assistant", ignoreCase = true) ||
            combined.contains("模型", ignoreCase = true) ||
            combined.contains("prompt", ignoreCase = true)
        ) {
            ScreenContentRiskSignal(
                behavior = "ask",
                summary = "screen_model_target_context",
                detailLines = listOf("screen_risk=model_target_context"),
            )
        } else {
            ScreenContentRiskSignal()
        }
    }
}
