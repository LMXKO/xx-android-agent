package com.lmx.xiaoxuanagent.agent

import android.graphics.Rect

internal data class VisualGroundingVerificationResult(
    val verified: Boolean = false,
    val bestCandidate: UiElementObservation? = null,
    val bestScore: Int = 0,
    val message: String = "",
)

internal object VisualGroundingVerifier {
    private const val MAX_VISUAL_VERIFIER_SEEDS = 4

    fun verifyFrame(
        frame: GroundingVerificationFrame,
        executionResult: AgentExecutionResult?,
        descriptor: GroundingEntityDescriptor,
        targetText: String,
        targetContextHints: List<String>,
        preferredScopes: List<String>,
    ): VisualGroundingVerificationResult {
        val screenshot = frame.screenshot ?: return VisualGroundingVerificationResult()
        val visualContext = frame.visualContext ?: frame.indexedObservation.visualContext
        if (!visualContext.captureAvailable) return VisualGroundingVerificationResult()

        val effectiveIndexedObservation =
            frame.indexedObservation.copy(
                screenshot = screenshot,
                visualContext = visualContext,
            )
        val queryCandidates =
            QueryConditionedGroundingSearchEngine.search(
                indexedObservation = effectiveIndexedObservation,
                targetText = targetText,
                roleHint = executionResult?.targetObjectType.orEmpty(),
                additionalKeywords = (descriptor.anchors + descriptor.contextTokens + descriptor.visualTokens + targetContextHints).distinct(),
                preferredScopeIds = preferredScopes,
            )
        val focusBounds =
            buildList {
                executionResult?.resolvedTargetBounds?.takeIf { it.isNotBlank() }?.let(::add)
                queryCandidates.take(2).mapTo(this) { it.bounds }
                visualContext.parserRegions
                    .filter { region ->
                        preferredScopes.contains(region.id) ||
                            region.matchedContainerIds.any { preferredScopes.contains(it) }
                    }.take(2)
                    .mapTo(this) { it.bounds }
            }.distinct()
                .take(MAX_VISUAL_VERIFIER_SEEDS)

        val cropCandidates =
            focusBounds.flatMap { bounds ->
                val crop =
                    VisualPerceptionEngine.reanalyzeCrop(
                        screenshot = screenshot,
                        observation = frame.indexedObservation.observation,
                        baseVisualContext = visualContext,
                        focusBounds = bounds,
                        focusLabel = descriptor.type.ifBlank { executionResult?.targetObjectType.orEmpty() },
                    )
                cropToCandidates(crop)
            }
        val bestCandidate =
            (queryCandidates + cropCandidates)
                .distinctBy { "${normalizeBoundsKey(it.bounds)}|${it.text}|${it.source}" }
                .maxByOrNull {
                    scoreCandidate(
                        candidate = it,
                        descriptor = descriptor,
                        expectedBounds = executionResult?.resolvedTargetBounds.orEmpty(),
                        preferredScopes = preferredScopes,
                        targetText = targetText,
                    )
                }
        val bestScore =
            bestCandidate?.let {
                scoreCandidate(
                    candidate = it,
                    descriptor = descriptor,
                    expectedBounds = executionResult?.resolvedTargetBounds.orEmpty(),
                    preferredScopes = preferredScopes,
                    targetText = targetText,
                )
            } ?: 0
        val verified =
            when {
                bestScore >= 11 -> true
                bestScore >= 8 && descriptor.stableIds.isNotEmpty() -> true
                bestScore >= 8 && descriptor.visualSignatures.isNotEmpty() -> true
                else -> false
            }
        return VisualGroundingVerificationResult(
            verified = verified,
            bestCandidate = bestCandidate,
            bestScore = bestScore,
            message =
                when {
                    verified && bestCandidate != null -> "独立视觉 verifier 已重新锁定 ${bestCandidate.id}。"
                    bestCandidate != null -> "独立视觉 verifier 发现候选 ${bestCandidate.id}，但置信度不足。"
                    else -> "独立视觉 verifier 未找到可靠候选。"
                },
        )
    }

    private fun cropToCandidates(
        cropResult: CropReanalysisResult,
    ): List<UiElementObservation> =
        (
            cropResult.parserRegions.map { region ->
                UiElementObservation(
                    id = "visual_verify_${region.id}",
                    text = region.label,
                    viewId = "visual_verify:${region.source}",
                    className = "VisualVerifierParser:${region.type}",
                    bounds = region.bounds,
                    clickable = true,
                    editable = false,
                    scrollable = false,
                    enabled = true,
                    focused = false,
                    selected = false,
                    containerId = region.matchedContainerIds.firstOrNull().orEmpty(),
                    accessibilityLabel = region.accessibilityLabels.firstOrNull().orEmpty(),
                    accessibilityUniqueId = region.accessibilityUniqueIds.firstOrNull().orEmpty(),
                    descriptorTokens = region.descriptorTokens,
                    visualDescriptorTokens = region.descriptorTokens,
                    interactionRegions = region.interactionRegions,
                    visualSignature = region.visualSignature,
                    spatialSignature = region.spatialSignature,
                    roleHint = region.roleHint,
                    neighborTexts = region.contextHints,
                    source = "visual_verifier_parser",
                    searchScopeId = region.id,
                )
            } +
                cropResult.visualObjects.map { visualObject ->
                    UiElementObservation(
                        id = "visual_verify_${visualObject.id}",
                        text = visualObject.label,
                        viewId = "visual_verify:${visualObject.source}",
                        className = "VisualVerifierObject:${visualObject.type}",
                        bounds = visualObject.bounds,
                        clickable = true,
                        editable = false,
                        scrollable = false,
                        enabled = true,
                        focused = false,
                        selected = false,
                        parentId = visualObject.matchedElementId.orEmpty(),
                        containerId = visualObject.matchedContainerId,
                        collectionPosition = visualObject.collectionPosition,
                        accessibilityLabel = visualObject.accessibilityLabel,
                        accessibilityUniqueId = visualObject.accessibilityUniqueId,
                        descriptorTokens = visualObject.descriptorTokens,
                        visualDescriptorTokens = visualObject.descriptorTokens,
                        interactionRegions = visualObject.interactionRegions,
                        visualSignature = visualObject.visualSignature,
                        spatialSignature = visualObject.spatialSignature,
                        roleHint = visualObject.roleHint,
                        neighborTexts = visualObject.contextHints,
                        source = "visual_verifier_object",
                        searchScopeId = visualObject.matchedContainerId.ifBlank { visualObject.matchedElementId.orEmpty() },
                    )
                }
            ).distinctBy { it.id }

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
        val stableBoost = descriptor.stableIds.count { id -> id.isNotBlank() && id.equals(candidate.accessibilityUniqueId, ignoreCase = true) } * 5
        val labelBoost = descriptor.accessibilityLabels.count { label -> label.length >= 2 && semantics.contains(label, ignoreCase = true) } * 2
        val anchorBoost = descriptor.anchors.count { anchor -> anchor.length >= 2 && semantics.contains(anchor, ignoreCase = true) } * 3
        val contextBoost = descriptor.contextTokens.count { token -> token.length >= 2 && semantics.contains(token, ignoreCase = true) }
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
        val targetBoost = if (targetText.isNotBlank() && semantics.contains(targetText, ignoreCase = true)) 4 else 0
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

    private fun normalizeBoundsKey(bounds: String): String {
        val values = Regex("""-?\d+""").findAll(bounds).map { it.value }.toList()
        return if (values.size >= 4) values.take(4).joinToString(",") else bounds
    }
}
