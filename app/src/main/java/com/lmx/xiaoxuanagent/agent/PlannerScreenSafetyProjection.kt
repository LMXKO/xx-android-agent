package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.safety.ScreenContentPatternSupport

data class PlannerScreenSafetyProjectionResult(
    val topTexts: List<String>,
    val elements: List<UiElementObservation>,
    val ocrTexts: List<VisualTextObservation>,
    val groundedTexts: List<VisualGroundingObservation>,
    val visualHints: List<String>,
    val lines: List<String> = emptyList(),
)

object PlannerScreenSafetyProjection {
    fun project(
        topTexts: List<String>,
        elements: List<UiElementObservation>,
        ocrTexts: List<VisualTextObservation>,
        groundedTexts: List<VisualGroundingObservation>,
        visualHints: List<String>,
        profileId: String = "",
    ): PlannerScreenSafetyProjectionResult {
        val lines = linkedSetOf<String>()
        val deceptiveKeywords = ScreenContentPatternSupport.deceptiveKeywordsForProfile(profileId)
        val sensitiveKeywords = ScreenContentPatternSupport.sensitiveKeywordsForProfile(profileId)

        fun sanitize(
            value: String,
        ): String {
            val normalized = ScreenContentPatternSupport.redactSensitivePatterns(value.trim())
            if (normalized.isBlank()) return normalized
            val suspicious = ScreenContentPatternSupport.firstMatchedKeyword(normalized, ScreenContentPatternSupport.injectionKeywords)
            if (suspicious != null) {
                lines.add("screen_projection=suspicious:$suspicious")
                return "[screen-content-filtered:suspicious]"
            }
            val deceptive = ScreenContentPatternSupport.firstMatchedKeyword(normalized, deceptiveKeywords)
            if (deceptive != null) {
                if (profileId.isNotBlank()) lines.add("screen_projection=profile=$profileId")
                lines.add("screen_projection=deceptive:$deceptive")
                return "[screen-content-filtered:deceptive]"
            }
            val captcha = ScreenContentPatternSupport.firstMatchedKeyword(normalized, ScreenContentPatternSupport.captchaKeywords)
            if (captcha != null) {
                lines.add("screen_projection=captcha:$captcha")
                return "[screen-content-filtered:captcha]"
            }
            val sensitive = ScreenContentPatternSupport.firstMatchedKeyword(normalized, sensitiveKeywords)
            if (sensitive != null) {
                if (profileId.isNotBlank()) lines.add("screen_projection=profile_sensitive:$profileId:$sensitive")
                lines.add("screen_projection=sensitive:$sensitive")
                return normalized.replace(sensitive, "[redacted]", ignoreCase = true)
            }
            return normalized
        }

        return PlannerScreenSafetyProjectionResult(
            topTexts = topTexts.map(::sanitize),
            elements = elements.map { it.copy(text = sanitize(it.text), neighborTexts = it.neighborTexts.map(::sanitize)) },
            ocrTexts = ocrTexts.map { it.copy(text = sanitize(it.text)) },
            groundedTexts = groundedTexts.map { it.copy(text = sanitize(it.text), matchedElementText = sanitize(it.matchedElementText)) },
            visualHints = visualHints.map(::sanitize),
            lines =
                (lines + listOfNotNull(
                    lines.takeIf { it.isNotEmpty() }?.let { "screen_projection=guarded_context" },
                )).toList(),
        )
    }
}
