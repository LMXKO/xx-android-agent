package com.lmx.xiaoxuanagent.agent

import android.graphics.Rect
import java.security.MessageDigest

object ObservationAugmenter {
    private const val MAX_VISUAL_TARGETS = 14
    private const val MAX_VISUAL_TOP_TEXTS = 6
    private const val SELF_PACKAGE = "com.lmx.xiaoxuanagent"
    private val priorityActionKeywords = listOf("搜索", "查找", "放大镜", "联系人", "添加朋友")

    private val selfUiKeywords =
        listOf(
            "小轩",
            "移动端个人Agent",
            "开始执行",
            "停止任务",
            "暂停任务",
            "继续执行",
            "告诉它要做什么",
            "暂无个人记忆",
            "暂无最近任务",
            "暂无技能调用",
            "Overview",
            "Context",
            "Agent",
            "Result",
            "Planner",
            "Logs",
            "query=",
            "profile=",
            "skills=",
            "route=",
            "planType=",
            "planStage=",
            "nextObjective=",
            "sessionId=",
            "replay=",
            "targetPkg=",
            "foreground=",
            "pageState=",
            "lastEvent=",
            "observation=",
            "elements=",
            "screenshot=",
            "agentRunning=",
            "agentStatus=",
            "agentTurn=",
            "lastAction=",
            "lastPlannerAction=",
            "hint=",
            "lastResult=",
            "lastError=",
            "plannerMode=",
        )

    fun augmentObservation(
        observation: ScreenObservation,
        visualContext: VisualPerceptionContext,
        blockedTexts: List<String> = emptyList(),
    ): ScreenObservation {
        val enrichedElements =
            enrichElementsWithParserRegions(
                enrichElementsWithVisualObjects(observation.elements, visualContext.visualObjects),
                visualContext.parserRegions,
            )
        val enrichedObservation = observation.copy(elements = enrichedElements)
        val visualTopTexts = buildVisualTopTexts(enrichedObservation, visualContext, blockedTexts)
        val targets = buildVirtualTargets(enrichedObservation, visualContext, blockedTexts)
        if (observation.elements.isNotEmpty() && visualTopTexts == observation.topTexts && targets.isEmpty()) {
            return enrichedObservation
        }
        if (targets.isEmpty() && visualTopTexts == observation.topTexts) {
            return enrichedObservation
        }
        val mixedTargets = targets.filter { target -> shouldInjectVisualTarget(enrichedObservation, target) }
        val elements = mergeVisualTargetsIntoElements(enrichedObservation, mixedTargets)
        val structureHints =
            (observation.structureHints + mixedTargets.take(3).map { target ->
                "visual_target:${target.id}:${target.text.take(18)}:${target.source}:${target.candidateTier}"
            }).distinct().take(8)
        val screenSummary =
            buildString {
                append(observation.screenSummary)
                if (mixedTargets.isNotEmpty()) {
                    append(", mixedVisualTargets=").append(mixedTargets.size)
                    append(", visualHead=").append(mixedTargets.take(3).joinToString(" / ") { it.text })
                }
                if (visualTopTexts.isNotEmpty() && visualTopTexts != observation.topTexts) {
                    append(", visualHints=").append(visualTopTexts.take(3).joinToString(" / "))
                }
            }
        val signature = buildVisualSignature(observation, visualTopTexts, targets)
        return enrichedObservation.copy(
            signature = signature,
            screenSummary = screenSummary,
            topTexts = visualTopTexts,
            elements = elements,
            structureHints = structureHints,
        )
    }

    fun augmentIndexedObservation(
        indexedObservation: IndexedScreenObservation,
        visualContext: VisualPerceptionContext,
        blockedTexts: List<String> = emptyList(),
    ): IndexedScreenObservation {
        val observation = augmentObservation(indexedObservation.observation, visualContext, blockedTexts)
        val targets = buildVirtualTargets(observation, visualContext, blockedTexts)
        val searchElementsById =
            enrichElementsWithParserRegions(
                enrichElementsWithVisualObjects(
                    indexedObservation.searchElementsById.values.toList(),
                    visualContext.visualObjects,
                ),
                visualContext.parserRegions,
            ).associateBy { it.id }
        if (
            observation == indexedObservation.observation &&
            targets.isEmpty() &&
            searchElementsById == indexedObservation.searchElementsById
        ) {
            return indexedObservation
        }
        return indexedObservation.copy(
            observation = observation,
            virtualTargetsById =
                if (targets.isEmpty()) {
                    indexedObservation.virtualTargetsById
                } else {
                    (indexedObservation.virtualTargetsById + targets.associateBy { it.id })
                },
            searchElementsById = searchElementsById,
        )
    }

    private fun buildVisualSignature(
        observation: ScreenObservation,
        visualTopTexts: List<String>,
        targets: List<VirtualUiTarget>,
    ): String {
        val seed =
            buildString {
                append(observation.signature)
                append("|top=")
                append(visualTopTexts.take(MAX_VISUAL_TOP_TEXTS).joinToString("||") { normalizeText(it) })
                append("|targets=")
                append(
                    targets.take(6).joinToString("||") { target ->
                        "${normalizeText(target.text)}@${target.bounds}"
                    },
                )
            }
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(12)
    }

    private fun buildVirtualTargets(
        observation: ScreenObservation,
        visualContext: VisualPerceptionContext,
        blockedTexts: List<String>,
    ): List<VirtualUiTarget> {
        val parserCandidates =
            visualContext.parserRegions
                .asSequence()
                .mapNotNull { region ->
                    val rect = parseBounds(region.bounds) ?: return@mapNotNull null
                    VisualCandidate(
                        text = region.label,
                        bounds = region.bounds,
                        source = region.source.ifBlank { "screen_parser" },
                        confidence = region.confidence,
                        rect = rect,
                        matchedElementId = region.matchedElementIds.firstOrNull(),
                        matchedContainerId = region.matchedContainerIds.firstOrNull().orEmpty(),
                        roleHint = region.roleHint,
                        contextHints = region.contextHints,
                        objectType = region.type,
                        descriptorTokens = region.descriptorTokens,
                        textFree = region.textFree,
                        candidateTier = region.searchTier,
                        tapPattern = region.tapPattern,
                        tapHotspotX = region.tapHotspotX,
                        tapHotspotY = region.tapHotspotY,
                        interactionRegions = region.interactionRegions,
                        visualSignature = region.visualSignature,
                        spatialSignature = region.spatialSignature,
                        accessibilityUniqueId = region.accessibilityUniqueIds.firstOrNull().orEmpty(),
                        accessibilityLabel = region.accessibilityLabels.firstOrNull().orEmpty(),
                        collectionPosition = "",
                        parserRegionId = region.id,
                        searchScopeId = if (region.parserLayer == "scope" || region.searchTier == "scope" || region.searchTier == "band" || region.searchTier == "local") region.id else region.matchedContainerIds.firstOrNull().orEmpty(),
                    )
                }
        val groundedCandidates =
            visualContext.groundedTexts
                .asSequence()
                .filter { it.text.isNotBlank() }
                .mapNotNull { item ->
                    val rect = parseBounds(item.bounds) ?: return@mapNotNull null
                    VisualCandidate(
                        text = item.text,
                        bounds = item.bounds,
                        source = if (item.matchedElementId.isNullOrBlank()) "grounded" else "grounded_matched",
                        confidence = (0.5f + item.overlapScore).coerceAtMost(1f),
                        rect = rect,
                        matchedElementId = item.matchedElementId,
                        matchedContainerId = item.matchedContainerId,
                        roleHint = inferRoleHint(item.text),
                        contextHints = emptyList(),
                        objectType = "",
                        tapHotspotX = 0.5f,
                        tapHotspotY = 0.5f,
                    )
                }
        val ocrCandidates =
            visualContext.ocrTexts
                .asSequence()
                .filter { it.text.isNotBlank() }
                .mapNotNull { item ->
                    val rect = parseBounds(item.bounds) ?: return@mapNotNull null
                    VisualCandidate(
                        text = item.text,
                        bounds = item.bounds,
                        source = "ocr",
                        confidence = item.confidence,
                        rect = rect,
                        roleHint = inferRoleHint(item.text),
                        contextHints = emptyList(),
                        objectType = "",
                        tapHotspotX = 0.5f,
                        tapHotspotY = 0.5f,
                    )
                }
        val visualObjectCandidates =
            visualContext.visualObjects
                .asSequence()
                .mapNotNull { item ->
                    val rect = parseBounds(item.bounds) ?: return@mapNotNull null
                    val candidateText = item.label
                    VisualCandidate(
                        text = candidateText,
                        bounds = item.bounds,
                        source = item.source.ifBlank { "visual_object" },
                        confidence = item.confidence,
                        rect = rect,
                        matchedElementId = item.matchedElementId,
                        matchedContainerId = item.matchedContainerId,
                        roleHint = item.roleHint,
                        contextHints = item.contextHints,
                        objectType = item.type,
                    descriptorTokens = item.descriptorTokens,
                    textFree = item.textFree,
                    candidateTier = item.candidateTier,
                    tapPattern = item.tapPattern,
                    tapHotspotX = item.tapHotspotX,
                    tapHotspotY = item.tapHotspotY,
                    interactionRegions = item.interactionRegions,
                    visualSignature = item.visualSignature,
                    spatialSignature = item.spatialSignature,
                    accessibilityUniqueId = item.accessibilityUniqueId,
                    accessibilityLabel = item.accessibilityLabel,
                    collectionPosition = item.collectionPosition,
                    parserRegionId = "",
                    searchScopeId = item.matchedContainerId,
                )
                }
        return (parserCandidates + groundedCandidates + ocrCandidates + visualObjectCandidates)
            .filterNot { candidate -> textLooksLikeNoise(candidate.text) && candidate.descriptorTokens.isEmpty() }
            .filterNot { looksLikeSelfUiText(it.text) }
            .filterNot { looksLikeTaskEcho(it.text, blockedTexts) }
            .filterNot { isLikelySystemChrome(it.rect) }
            .sortedWith(
                compareByDescending<VisualCandidate> { it.score }
                    .thenBy { it.rect.top }
                    .thenBy { it.rect.left },
            )
            .distinctBy { "${it.text}|${it.bounds}|${it.source}|${it.objectType}" }
            .take(MAX_VISUAL_TARGETS)
            .mapIndexed { index, block ->
                val elementOffset = observation.elements.size + index + 1
                VirtualUiTarget(
                    id = "e${elementOffset.toString().padStart(2, '0')}",
                    text = block.text,
                    bounds = block.bounds,
                    source = block.source,
                    candidateTier = block.candidateTier,
                    objectType = block.objectType,
                    matchedElementId = block.matchedElementId,
                    matchedContainerId = block.matchedContainerId,
                    confidence = block.confidence,
                    roleHint = block.roleHint,
                    contextHints = block.contextHints,
                    descriptorTokens = block.descriptorTokens,
                    textFree = block.textFree,
                    tapPattern = block.tapPattern,
                    tapHotspotX = block.tapHotspotX,
                    tapHotspotY = block.tapHotspotY,
                    interactionRegions = block.interactionRegions,
                    visualSignature = block.visualSignature,
                    spatialSignature = block.spatialSignature,
                    accessibilityUniqueId = block.accessibilityUniqueId,
                    accessibilityLabel = block.accessibilityLabel,
                    collectionPosition = block.collectionPosition,
                    parserRegionId = block.parserRegionId,
                    searchScopeId = block.searchScopeId,
                )
            }
            .toList()
    }

    private fun shouldInjectVisualTarget(
        observation: ScreenObservation,
        target: VirtualUiTarget,
    ): Boolean {
        if (observation.elements.isEmpty()) return true
        if (target.matchedElementId.isNullOrBlank()) return true
        if (target.source.contains("screen_parser", ignoreCase = true) && target.confidence >= 0.62f) return true
        if (target.objectType.isNotBlank() && target.confidence >= 0.75f) return true
        if (target.textFree && target.confidence >= 0.68f) return true
        if (target.source == "ocr" && target.confidence >= 0.9f && target.roleHint.isNotBlank()) return true
        if (priorityActionKeywords.any { keyword -> target.text.contains(keyword, ignoreCase = true) }) return true
        val matchedElement = observation.elements.firstOrNull { it.id == target.matchedElementId }
        if (matchedElement == null) return true
        return matchedElement.text.isBlank() ||
            (target.accessibilityUniqueId.takeIf { it.isNotBlank() }?.let { matchedElement.accessibilityUniqueId != it } == true) ||
            !matchedElement.text.contains(target.text, ignoreCase = true) ||
            target.contextHints.any { hint -> matchedElement.neighborTexts.none { it.contains(hint, ignoreCase = true) } } ||
            target.descriptorTokens.any { token ->
                (matchedElement.descriptorTokens + matchedElement.visualDescriptorTokens).none {
                    it.contains(token, ignoreCase = true)
                }
            }
    }

    private fun mergeVisualTargetsIntoElements(
        observation: ScreenObservation,
        targets: List<VirtualUiTarget>,
    ): List<UiElementObservation> {
        if (targets.isEmpty()) return observation.elements
        val appended =
            targets.map { target ->
                UiElementObservation(
                    id = target.id,
                    text = target.text,
                    viewId = "visual:${target.id}",
                    className = "VisualTextTarget:${target.source}",
                    bounds = target.bounds,
                    clickable = true,
                    editable = false,
                    scrollable = false,
                    enabled = true,
                    focused = false,
                    selected = false,
                    parentId = target.matchedElementId.orEmpty(),
                    containerId = target.matchedContainerId,
                    depth = 99,
                    collectionPosition = target.collectionPosition,
                    accessibilityLabel = target.accessibilityLabel.ifBlank { target.text.takeIf { !target.textFree }.orEmpty() },
                    accessibilityUniqueId = target.accessibilityUniqueId,
                    descriptorTokens = target.descriptorTokens.take(8),
                    visualDescriptorTokens = target.descriptorTokens.take(8),
                    interactionRegions = target.interactionRegions,
                    visualSignature = target.visualSignature,
                    spatialSignature = target.spatialSignature,
                    roleHint = target.roleHint,
                    neighborTexts = target.contextHints.take(4),
                    source = "visual",
                    searchScopeId = target.searchScopeId,
                )
            }
        return (observation.elements + appended).distinctBy { it.id }
    }

    private fun enrichElementsWithParserRegions(
        elements: List<UiElementObservation>,
        parserRegions: List<ScreenParserRegion>,
    ): List<UiElementObservation> {
        if (elements.isEmpty() || parserRegions.isEmpty()) return elements
        return elements.map { element ->
            val matchedRegions =
                parserRegions.filter { region ->
                    when {
                        region.matchedElementIds.contains(element.id) -> true
                        region.accessibilityUniqueIds.any { it.isNotBlank() && it == element.accessibilityUniqueId } -> true
                        else -> boundsOverlap(element.bounds, region.bounds) >= 0.52f
                    }
                }
            if (matchedRegions.isEmpty()) {
                element
            } else {
                val strongestRegion = matchedRegions.maxByOrNull { it.confidence } ?: matchedRegions.first()
                element.copy(
                    descriptorTokens =
                        (element.descriptorTokens + matchedRegions.flatMap { it.descriptorTokens }).distinct().take(12),
                    visualDescriptorTokens =
                        (element.visualDescriptorTokens + matchedRegions.flatMap { it.descriptorTokens }).distinct().take(12),
                    interactionRegions =
                        (element.interactionRegions + matchedRegions.flatMap { it.interactionRegions })
                            .distinctBy { "${it.leftFraction}|${it.topFraction}|${it.rightFraction}|${it.bottomFraction}|${it.label}" }
                            .sortedByDescending { it.score }
                            .take(6),
                    visualSignature =
                        (listOf(element.visualSignature) + matchedRegions.map { it.visualSignature })
                            .filter { it.isNotBlank() }
                            .distinct()
                            .joinToString("||")
                            .take(180),
                    spatialSignature =
                        strongestRegion.spatialSignature.ifBlank { element.spatialSignature },
                    searchScopeId =
                        element.searchScopeId.ifBlank {
                            strongestRegion.matchedContainerIds.firstOrNull()
                                ?: strongestRegion.id
                        },
                )
            }
        }
    }

    private fun enrichElementsWithVisualObjects(
        elements: List<UiElementObservation>,
        visualObjects: List<VisualObjectObservation>,
    ): List<UiElementObservation> {
        if (elements.isEmpty() || visualObjects.isEmpty()) return elements
        return elements.map { element ->
            val matchedObjects =
                visualObjects.filter { visualObject ->
                    when {
                        visualObject.matchedElementId == element.id -> true
                        visualObject.accessibilityUniqueId.isNotBlank() &&
                            visualObject.accessibilityUniqueId == element.accessibilityUniqueId -> true
                        else -> boundsOverlap(element.bounds, visualObject.bounds) >= 0.52f
                    }
                }
            if (matchedObjects.isEmpty()) {
                element
            } else {
                val strongestObject = matchedObjects.maxByOrNull { it.confidence } ?: matchedObjects.first()
                element.copy(
                    accessibilityLabel =
                        element.accessibilityLabel.ifBlank {
                            strongestObject.accessibilityLabel.ifBlank {
                                strongestObject.label.takeIf { !strongestObject.textFree }.orEmpty()
                            }
                        },
                    descriptorTokens =
                        (element.descriptorTokens + matchedObjects.flatMap { it.descriptorTokens }).distinct().take(10),
                    visualDescriptorTokens =
                        (element.visualDescriptorTokens + matchedObjects.flatMap { it.descriptorTokens }).distinct().take(10),
                    interactionRegions =
                        (matchedObjects.flatMap { it.interactionRegions })
                            .distinctBy { "${it.leftFraction}|${it.topFraction}|${it.rightFraction}|${it.bottomFraction}|${it.label}" }
                            .take(5)
                            .ifEmpty { element.interactionRegions },
                    visualSignature =
                        (listOf(element.visualSignature) + matchedObjects.map { it.visualSignature })
                            .filter { it.isNotBlank() }
                            .distinct()
                            .joinToString("||")
                            .take(160),
                    spatialSignature =
                        strongestObject.spatialSignature.ifBlank { element.spatialSignature },
                )
            }
        }
    }

    private fun buildVisualTopTexts(
        observation: ScreenObservation,
        visualContext: VisualPerceptionContext,
        blockedTexts: List<String>,
    ): List<String> {
        val merged =
            (observation.topTexts.asSequence() +
                visualContext.groundedTexts.asSequence().map { it.text } +
                visualContext.parserRegions.asSequence().map { it.label } +
                visualContext.parserRegions.asSequence().flatMap { it.descriptorTokens.asSequence() } +
                visualContext.visualObjects.asSequence().map { it.label } +
                visualContext.visualObjects.asSequence().flatMap { it.descriptorTokens.asSequence() } +
                visualContext.visualHints.asSequence() +
                visualContext.ocrTexts.asSequence().map { it.text })
                .map(::normalizeText)
                .filter { it.isNotBlank() }
                .filterNot(::textLooksLikeNoise)
                .filterNot(::looksLikeSelfUiText)
                .filterNot { looksLikeTaskEcho(it, blockedTexts) }
                .distinct()
                .take(MAX_VISUAL_TOP_TEXTS)
                .toList()
        return if (merged.isEmpty()) observation.topTexts else merged
    }

    private fun textLooksLikeNoise(text: String): Boolean {
        val compact = normalizeText(text)
        if (compact.isBlank()) return true
        if (compact.length == 1 && compact.first().isDigit()) return true
        if (compact.length <= 2 && compact.all { !it.isLetterOrDigit() && !it.isWhitespace() }) return true
        if (Regex("""^\d{1,2}:\d{2}$""").matches(compact)) return true
        if (Regex("""^\d+%$""").matches(compact)) return true
        return false
    }

    private fun normalizeText(text: String): String =
        text.replace("\\s+".toRegex(), " ").trim()

    private fun looksLikeSelfUiText(text: String): Boolean {
        if (text.isBlank()) return false
        return selfUiKeywords.any { keyword -> text.contains(keyword, ignoreCase = true) }
    }

    private fun looksLikeTaskEcho(
        text: String,
        blockedTexts: List<String>,
    ): Boolean {
        val candidate = normalizeForEchoCheck(text)
        if (candidate.length < 6) return false
        return blockedTexts
            .asSequence()
            .map(::normalizeForEchoCheck)
            .filter { it.length >= 6 }
            .any { blocked ->
                blocked.contains(candidate) ||
                    candidate.contains(blocked) ||
                    normalizedEditDistance(candidate, blocked) <= 0.22f
            }
    }

    private fun normalizeForEchoCheck(text: String): String =
        text.lowercase()
            .replace(Regex("""[\s，。,.!！?？:：;；'"“”‘’、】【\[\]\-_/\\|]+"""), "")

    private fun normalizedEditDistance(
        left: String,
        right: String,
    ): Float {
        if (left == right) return 0f
        val maxLen = maxOf(left.length, right.length)
        if (maxLen == 0) return 0f
        if (kotlin.math.abs(left.length - right.length) > maxLen / 2) return 1f

        val prev = IntArray(right.length + 1) { it }
        val curr = IntArray(right.length + 1)
        for (i in left.indices) {
            curr[0] = i + 1
            for (j in right.indices) {
                val cost = if (left[i] == right[j]) 0 else 1
                curr[j + 1] = minOf(
                    curr[j] + 1,
                    prev[j + 1] + 1,
                    prev[j] + cost,
                )
            }
            for (j in prev.indices) {
                prev[j] = curr[j]
            }
        }
        return prev[right.length].toFloat() / maxLen.toFloat()
    }

    private fun isLikelySystemChrome(rect: Rect): Boolean {
        val width = rect.width().coerceAtLeast(0)
        val height = rect.height().coerceAtLeast(0)
        if (width <= 0 || height <= 0) return true
        return (rect.top in 0..70 && height <= 90) || (rect.bottom >= 1220 && height <= 120)
    }

    private fun parseBounds(bounds: String): Rect? {
        val values = Regex("""-?\d+""").findAll(bounds).map { it.value.toInt() }.toList()
        if (values.size < 4) return null
        return Rect(values[0], values[1], values[2], values[3])
    }

    private fun boundsOverlap(
        leftBounds: String,
        rightBounds: String,
    ): Float {
        val leftRect = parseBounds(leftBounds) ?: return 0f
        val rightRect = parseBounds(rightBounds) ?: return 0f
        val left = maxOf(leftRect.left, rightRect.left)
        val top = maxOf(leftRect.top, rightRect.top)
        val right = minOf(leftRect.right, rightRect.right)
        val bottom = minOf(leftRect.bottom, rightRect.bottom)
        val width = (right - left).coerceAtLeast(0)
        val height = (bottom - top).coerceAtLeast(0)
        val interArea = width * height
        if (interArea <= 0) return 0f
        val baseArea = (leftRect.width() * leftRect.height()).coerceAtLeast(1)
        return interArea.toFloat() / baseArea.toFloat()
    }

    private fun inferRoleHint(
        text: String,
    ): String =
        when {
            listOf("搜索", "查找", "联系人", "添加朋友").any { text.contains(it, ignoreCase = true) } -> "entry"
            listOf("发送", "确认", "完成", "打开", "进入").any { text.contains(it, ignoreCase = true) } -> "submit"
            listOf("关闭", "跳过", "取消", "知道了", "稍后").any { text.contains(it, ignoreCase = true) } -> "dismiss"
            else -> ""
        }

    private data class VisualCandidate(
        val text: String,
        val bounds: String,
        val source: String,
        val confidence: Float,
        val rect: Rect,
        val matchedElementId: String? = null,
        val matchedContainerId: String = "",
        val roleHint: String = "",
        val contextHints: List<String> = emptyList(),
        val objectType: String = "",
        val descriptorTokens: List<String> = emptyList(),
        val textFree: Boolean = false,
        val candidateTier: String = "primary",
        val tapPattern: String = "",
        val tapHotspotX: Float = 0.5f,
        val tapHotspotY: Float = 0.5f,
        val interactionRegions: List<InteractionRegion> = emptyList(),
        val visualSignature: String = "",
        val spatialSignature: String = "",
        val accessibilityUniqueId: String = "",
        val accessibilityLabel: String = "",
        val collectionPosition: String = "",
        val parserRegionId: String = "",
        val searchScopeId: String = "",
    ) {
        val score: Float
            get() {
                var value = confidence
                val compactLength = text.replace("\\s+".toRegex(), "").length
                if (compactLength in 2..12) value += 0.35f
                if (rect.width() in 36..420 && rect.height() in 24..220) value += 0.2f
                if (source == "grounded_matched") value += 0.5f
                if (source == "grounded") value += 0.25f
                if (objectType.isNotBlank()) value += 0.18f
                if (contextHints.isNotEmpty()) value += 0.12f
                if (descriptorTokens.isNotEmpty()) value += 0.1f
                if (visualSignature.isNotBlank()) value += 0.08f
                if (interactionRegions.isNotEmpty()) value += 0.08f
                if (source.contains("screen_parser")) value += 0.18f
                if (parserRegionId.isNotBlank()) value += 0.1f
                if (searchScopeId.isNotBlank()) value += 0.08f
                if (candidateTier == "recall") value += 0.08f
                if (candidateTier == "scope" || candidateTier == "band" || candidateTier == "local" || candidateTier == "local_refine" || candidateTier == "crop") value += 0.16f
                if (candidateTier == "detector") value += 0.14f
                if (textFree) value += 0.06f
                if (priorityActionKeywords.any { text.contains(it, ignoreCase = true) }) value += 0.9f
                return value
            }
    }
}
