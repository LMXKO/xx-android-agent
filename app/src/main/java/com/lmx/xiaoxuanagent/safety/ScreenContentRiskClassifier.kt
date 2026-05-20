package com.lmx.xiaoxuanagent.safety

import com.lmx.xiaoxuanagent.agent.ScreenObservation

data class ScreenContentRiskSignal(
    val behavior: String = "allow",
    val summary: String = "",
    val detailLines: List<String> = emptyList(),
)

object ScreenContentRiskClassifier {
    fun analyze(
        observation: ScreenObservation,
        semanticText: String,
    ): ScreenContentRiskSignal {
        val profileId =
            when (observation.packageName) {
                "com.tencent.mm" -> "wechat_assistant"
                "com.sankuai.meituan" -> "meituan_assistant"
                "com.autonavi.minimap" -> "amap_assistant"
                else -> ""
            }
        val screenText =
            buildString {
                append(observation.screenSummary).append(' ')
                append(observation.topTexts.joinToString(" "))
                append(' ')
                append(observation.elements.take(16).joinToString(" ") { it.text })
            }
        val matchedInjection = ScreenContentPatternSupport.firstMatchedKeyword(screenText, ScreenContentPatternSupport.injectionKeywords)
        if (matchedInjection != null && semanticText.isNotBlank()) {
            return ScreenContentRiskSignal(
                behavior = "ask",
                summary = "screen_prompt_injection",
                detailLines = listOf("screen_risk=injection", "matched=$matchedInjection"),
            )
        }
        val matchedDeceptive =
            ScreenContentPatternSupport.firstMatchedKeyword(
                screenText,
                ScreenContentPatternSupport.deceptiveKeywordsForProfile(profileId),
            )
        if (matchedDeceptive != null) {
            return ScreenContentRiskSignal(
                behavior = "ask",
                summary = "screen_deceptive_copy",
                detailLines = listOf("screen_risk=deceptive", "matched=$matchedDeceptive", "profile=${profileId.ifBlank { "generic" }}"),
            )
        }
        val matchedCaptcha = ScreenContentPatternSupport.firstMatchedKeyword(screenText, ScreenContentPatternSupport.captchaKeywords)
        if (matchedCaptcha != null) {
            return ScreenContentRiskSignal(
                behavior = "ask",
                summary = "screen_captcha_flow",
                detailLines = listOf("screen_risk=captcha", "matched=$matchedCaptcha"),
            )
        }
        val matchedSensitive =
            ScreenContentPatternSupport.firstMatchedKeyword(
                screenText,
                ScreenContentPatternSupport.sensitiveKeywordsForProfile(profileId),
            )
        if (matchedSensitive != null && semanticText.contains(matchedSensitive, ignoreCase = true)) {
            return ScreenContentRiskSignal(
                behavior = "deny",
                summary = "screen_sensitive_input_flow",
                detailLines = listOf("screen_risk=sensitive_input", "matched=$matchedSensitive", "profile=${profileId.ifBlank { "generic" }}"),
            )
        }
        return ScreenContentRiskSignal()
    }
}
