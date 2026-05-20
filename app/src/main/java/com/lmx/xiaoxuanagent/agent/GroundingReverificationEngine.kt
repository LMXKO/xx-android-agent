package com.lmx.xiaoxuanagent.agent

import android.graphics.Rect

internal data class GroundingReverificationResult(
    val verified: Boolean = false,
    val matchScore: Int = 0,
    val candidate: UiElementObservation? = null,
    val entityMatch: GroundingEntityMatch = GroundingEntityMatch(),
    val message: String = "",
)

data class GroundingVerificationFrame(
    val indexedObservation: IndexedScreenObservation,
    val screenshot: ScreenshotPayload? = null,
    val visualContext: VisualPerceptionContext? = null,
    val frameTag: String = "",
)

internal object GroundingReverificationEngine {
    fun reverify(
        afterIndexedObservation: IndexedScreenObservation?,
        executionResult: AgentExecutionResult?,
        descriptor: GroundingEntityDescriptor,
        expectedTargetText: String,
        targetContextHints: List<String>,
        afterScreenshot: ScreenshotPayload? = null,
        afterVisualContext: VisualPerceptionContext? = null,
        afterFrames: List<GroundingVerificationFrame> = emptyList(),
    ): GroundingReverificationResult {
        val targetText = expectedTargetText.ifBlank { descriptor.primary }.trim()
        val preferredScopes =
            listOf(
                executionResult?.resolvedSearchScopeId.orEmpty(),
                executionResult?.resolvedParserRegionId.orEmpty(),
                executionResult?.resolvedContainerId.orEmpty(),
            ).filter { it.isNotBlank() }.distinct()
        val frames =
            afterFrames.ifEmpty {
                afterIndexedObservation?.let {
                    listOf(
                        GroundingVerificationFrame(
                            indexedObservation = it,
                            screenshot = afterScreenshot,
                            visualContext = afterVisualContext,
                            frameTag = "current",
                        ),
                    )
                }.orEmpty()
            }
        if (frames.isEmpty()) return GroundingReverificationResult()
        return frames.fold(GroundingReverificationResult()) { best, frame ->
            val current = evaluateFrame(
                frame = frame,
                executionResult = executionResult,
                descriptor = descriptor,
                targetText = targetText,
                targetContextHints = targetContextHints,
                preferredScopes = preferredScopes,
            )
            when {
                current.verified && !best.verified -> current
                current.verified && best.verified && current.matchScore > best.matchScore -> current
                !best.verified && current.matchScore > best.matchScore -> current
                else -> best
            }
        }
    }

    private fun evaluateFrame(
        frame: GroundingVerificationFrame,
        executionResult: AgentExecutionResult?,
        descriptor: GroundingEntityDescriptor,
        targetText: String,
        targetContextHints: List<String>,
        preferredScopes: List<String>,
    ): GroundingReverificationResult {
        val indexedObservation = frame.indexedObservation
        val afterObservation = indexedObservation.observation
        val candidate =
            CascadedGroundingSearch.search(
                indexedObservation = indexedObservation,
                targetText = targetText,
                roleHint = executionResult?.targetObjectType.orEmpty(),
                additionalKeywords = (descriptor.anchors + descriptor.contextTokens + descriptor.visualTokens + targetContextHints).distinct(),
                preferredScopeIds = preferredScopes,
            )
        val entityMatch = GroundingEntityFingerprint.matchObservation(afterObservation, descriptor)
        val candidateScore =
            candidate?.let {
                scoreCandidate(
                    candidate = it,
                    descriptor = descriptor,
                    expectedBounds = executionResult?.resolvedTargetBounds.orEmpty(),
                    preferredScopes = preferredScopes,
                    targetText = targetText,
                )
            } ?: 0
        val visualVerification =
            VisualGroundingVerifier.verifyFrame(
                frame = frame,
                executionResult = executionResult,
                descriptor = descriptor,
                targetText = targetText,
                targetContextHints = targetContextHints,
                preferredScopes = preferredScopes,
            )
        val verified =
            when {
                visualVerification.verified -> true
                candidateScore >= 9 -> true
                visualVerification.bestScore >= 7 && entityMatch.verified -> true
                candidateScore >= 6 && entityMatch.verified -> true
                entityMatch.verified && preferredScopes.isNotEmpty() -> true
                else -> false
            }
        val framePrefix = frame.frameTag.takeIf { it.isNotBlank() }?.let { "[$it] " }.orEmpty()
        return GroundingReverificationResult(
            verified = verified,
            matchScore = maxOf(candidateScore, visualVerification.bestScore),
            candidate = visualVerification.bestCandidate ?: candidate,
            entityMatch = entityMatch,
            message =
                when {
                    verified && visualVerification.bestCandidate != null ->
                        "${framePrefix}${visualVerification.message}"
                    verified && candidate != null ->
                        "${framePrefix}已通过局部重 grounding 重新命中目标候选 ${candidate.id}。"
                    verified && entityMatch.verified ->
                        "${framePrefix}已通过页面级实体指纹重 grounding 确认目标仍然成立。"
                    visualVerification.bestCandidate != null ->
                        "${framePrefix}${visualVerification.message}"
                    candidate != null ->
                        "${framePrefix}发现局部候选 ${candidate.id}，但重 grounding 置信度不足。"
                    else ->
                        "${framePrefix}未在局部重 grounding 中重新定位到可靠目标。"
                },
        )
    }

    private fun scoreCandidate(
        candidate: UiElementObservation,
        descriptor: GroundingEntityDescriptor,
        expectedBounds: String,
        preferredScopes: List<String>,
        targetText: String,
    ): Int {
        val semantics =
            buildString {
                append(candidate.text).append(' ')
                append(candidate.accessibilityLabel).append(' ')
                append(candidate.accessibilityUniqueId).append(' ')
                append(candidate.visualSignature).append(' ')
                append(candidate.spatialSignature).append(' ')
                append(candidate.collectionPosition).append(' ')
                append(candidate.roleHint).append(' ')
                append(candidate.descriptorTokens.joinToString(" ")).append(' ')
                append(candidate.visualDescriptorTokens.joinToString(" ")).append(' ')
                append(candidate.neighborTexts.joinToString(" "))
            }
        val stableBoost =
            descriptor.stableIds.count { id -> id.isNotBlank() && id.equals(candidate.accessibilityUniqueId, ignoreCase = true) } * 5
        val labelBoost =
            descriptor.accessibilityLabels.count { label -> label.length >= 2 && semantics.contains(label, ignoreCase = true) } * 2
        val anchorBoost =
            descriptor.anchors.count { anchor -> anchor.length >= 2 && semantics.contains(anchor, ignoreCase = true) } * 3
        val contextBoost =
            descriptor.contextTokens.count { token -> token.length >= 2 && semantics.contains(token, ignoreCase = true) }
        val visualBoost =
            descriptor.visualSignatures.count { signature -> signature.isNotBlank() && signature == candidate.visualSignature } * 3 +
                descriptor.visualTokens.count { token -> token.length >= 2 && semantics.contains(token, ignoreCase = true) }
        val spatialBoost =
            descriptor.spatialSignatures.count { signature -> signature.isNotBlank() && signature == candidate.spatialSignature } * 2
        val scopeBoost =
            when {
                preferredScopes.isEmpty() -> 0
                candidate.searchScopeId.isNotBlank() && preferredScopes.contains(candidate.searchScopeId) -> 4
                candidate.containerId.isNotBlank() && preferredScopes.contains(candidate.containerId) -> 3
                else -> 0
            }
        val boundsBoost =
            parseBounds(expectedBounds)?.let { expected ->
                parseBounds(candidate.bounds)?.let { actual ->
                    when {
                        overlapScore(expected, actual) >= 0.58f -> 4
                        overlapScore(expected, actual) >= 0.32f -> 2
                        else -> 0
                    }
                }
            } ?: 0
        val targetBoost =
            if (targetText.isNotBlank() && semantics.contains(targetText, ignoreCase = true)) {
                4
            } else {
                0
            }
        return stableBoost + labelBoost + anchorBoost + contextBoost + visualBoost + spatialBoost + scopeBoost + boundsBoost + targetBoost
    }

    private fun parseBounds(bounds: String): Rect? {
        val values = Regex("""-?\d+""").findAll(bounds).map { it.value.toInt() }.toList()
        if (values.size < 4) return null
        return Rect(values[0], values[1], values[2], values[3])
    }

    private fun overlapScore(
        a: Rect,
        b: Rect,
    ): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val width = (right - left).coerceAtLeast(0)
        val height = (bottom - top).coerceAtLeast(0)
        val area = width * height
        if (area <= 0) return 0f
        val base = minOf(a.width() * a.height(), b.width() * b.height()).coerceAtLeast(1)
        return area.toFloat() / base.toFloat()
    }
}
