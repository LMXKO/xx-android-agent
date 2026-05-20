package com.lmx.xiaoxuanagent.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class CropReanalysisResult(
    val parserRegions: List<ScreenParserRegion> = emptyList(),
    val visualObjects: List<VisualObjectObservation> = emptyList(),
    val cropBounds: String = "",
)

private data class CropFocusSeed(
    val bounds: String,
    val label: String,
    val depth: Int,
)

object VisualPerceptionEngine {
    private const val MAX_OCR_BLOCKS = 18
    private const val MAX_VISUAL_OBJECTS = 20
    private const val MAX_CROP_REFINEMENT_SEEDS = 4
    private const val MAX_CROP_REFINEMENT_DEPTH = 3
    private const val MAX_CROP_REFINEMENT_BRANCHES = 3
    private val actionLikeKeywords = listOf("关闭", "跳过", "取消", "继续", "发送", "提交", "确认", "搜索", "路线")

    suspend fun analyze(
        screenshot: ScreenshotPayload?,
        observation: ScreenObservation,
    ): VisualPerceptionContext {
        if (screenshot == null) {
            return VisualPerceptionContext(
                captureAvailable = false,
                summary = "未获取到截图，当前仅使用无障碍节点。",
            )
        }

        val bitmap = decodeBitmapOrNull(screenshot) ?: return VisualPerceptionContext(
            captureAvailable = true,
            summary = "截图已获取，但无法解码为位图。",
        )

        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val text = recognizer.process(image).processAwait()
            val nodeTexts = observation.elements.map { it.text }.filter { it.isNotBlank() } + observation.topTexts
            val blocks =
                text.textBlocks.mapNotNull { block ->
                    val blockText = block.text.trim()
                    if (blockText.isBlank()) return@mapNotNull null
                    val bounds = block.boundingBox ?: Rect()
                    VisualTextObservation(
                        text = blockText,
                        bounds = "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]",
                        confidence = estimateConfidence(blockText, bounds),
                    )
                }.distinctBy { it.text }
                    .take(MAX_OCR_BLOCKS)

            val visualHints =
                blocks.map { it.text }
                    .filterNot { blockText: String ->
                        nodeTexts.any { nodeText: String ->
                            nodeText.contains(blockText) || blockText.contains(nodeText)
                        }
                    }
                    .take(8)
            val groundedTexts = buildGroundings(blocks, observation)
            val baseVisualObjects = buildVisualObjects(bitmap, blocks, groundedTexts, observation)
            val baseParserRegions = ScreenParserEngine.parse(bitmap, observation, blocks, groundedTexts, baseVisualObjects)
            val cropRefinement =
                refineParserRegionsWithLocalCrops(
                    bitmap = bitmap,
                    observation = observation,
                    blocks = blocks,
                    groundedTexts = groundedTexts,
                    visualObjects = baseVisualObjects,
                    parserRegions = baseParserRegions,
                )
            val visualObjects =
                (baseVisualObjects + cropRefinement.visualObjects)
                    .sortedByDescending { it.confidence }
                    .distinctBy { "${it.type}|${it.label}|${it.bounds}|${it.source}|${it.matchedElementId}|${it.matchedContainerId}" }
                    .take(MAX_VISUAL_OBJECTS + 8)
            val parserRegions =
                (baseParserRegions + cropRefinement.parserRegions)
                    .sortedByDescending { it.confidence }
                    .distinctBy {
                        listOf(
                            it.type,
                            it.label,
                            it.bounds,
                            it.source,
                            it.parserLayer,
                            it.searchTier,
                            it.matchedElementIds.sorted().joinToString(","),
                        ).joinToString("|")
                    }
                    .take(36)

            VisualPerceptionContext(
                captureAvailable = true,
                ocrTexts = blocks,
                groundedTexts = groundedTexts,
                visualObjects = visualObjects,
                parserRegions = parserRegions,
                visualHints = visualHints,
                summary = buildSummary(blocks, groundedTexts, visualHints, visualObjects, parserRegions),
            )
        } catch (error: Throwable) {
            VisualPerceptionContext(
                captureAvailable = true,
                summary = "OCR 失败: ${error.javaClass.simpleName}: ${error.message}",
            )
        } finally {
            bitmap.recycle()
            recognizer.close()
        }
    }

    internal fun reanalyzeCrop(
        screenshot: ScreenshotPayload?,
        observation: ScreenObservation,
        baseVisualContext: VisualPerceptionContext,
        focusBounds: String,
        focusLabel: String = "",
    ): CropReanalysisResult {
        if (screenshot == null) return CropReanalysisResult()
        val bitmap = decodeBitmapOrNull(screenshot) ?: return CropReanalysisResult()
        return try {
            val queue = ArrayDeque<CropFocusSeed>()
            val visited = linkedSetOf<String>()
            val parserResults = mutableListOf<ScreenParserRegion>()
            val visualResults = mutableListOf<VisualObjectObservation>()
            queue.add(CropFocusSeed(bounds = focusBounds, label = focusLabel.ifBlank { "crop" }, depth = 1))
            while (queue.isNotEmpty()) {
                val seed = queue.removeFirst()
                if (!visited.add("${seed.depth}:${normalizeBoundsKey(seed.bounds)}")) continue
                val passResult =
                    reanalyzeCropPass(
                        bitmap = bitmap,
                        observation = observation,
                        blocks = baseVisualContext.ocrTexts,
                        groundedTexts = baseVisualContext.groundedTexts,
                        visualObjects = baseVisualContext.visualObjects.filterNot { it.source.contains("crop", ignoreCase = true) },
                        focusBounds = seed.bounds,
                        focusLabel = seed.label,
                    )
                parserResults += passResult.parserRegions
                visualResults += passResult.visualObjects
                if (seed.depth < MAX_CROP_REFINEMENT_DEPTH) {
                    deriveNextCropSeeds(passResult, seed.depth + 1)
                        .take(MAX_CROP_REFINEMENT_BRANCHES)
                        .forEach(queue::addLast)
                }
            }
            CropReanalysisResult(
                parserRegions =
                    parserResults
                        .sortedByDescending { it.confidence }
                        .distinctBy { "${it.type}|${it.bounds}|${it.source}|${it.parserLayer}|${it.searchTier}" }
                        .take(28),
                visualObjects =
                    visualResults
                        .sortedByDescending { it.confidence }
                        .distinctBy { "${it.type}|${it.bounds}|${it.source}|${it.candidateTier}|${it.matchedElementId}|${it.matchedContainerId}" }
                        .take(24),
                cropBounds = focusBounds,
            )
        } finally {
            bitmap.recycle()
        }
    }

    internal fun decodeBitmapOrNull(
        screenshot: ScreenshotPayload,
    ) = runCatching {
        val bytes = Base64.decode(screenshot.base64Data, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun buildSummary(
        blocks: List<VisualTextObservation>,
        groundedTexts: List<VisualGroundingObservation>,
        visualHints: List<String>,
        visualObjects: List<VisualObjectObservation>,
        parserRegions: List<ScreenParserRegion>,
    ): String {
        if (blocks.isEmpty() && visualObjects.isEmpty() && parserRegions.isEmpty()) {
            return "截图已获取，但 OCR 未识别到有效文本，也没有提取出可用视觉对象或解析区域。"
        }
        val head =
            if (blocks.isNotEmpty()) {
                blocks.take(4).joinToString(" / ") { it.text }
            } else if (parserRegions.isNotEmpty()) {
                parserRegions.take(4).joinToString(" / ") { it.label.ifBlank { it.type } }
            } else {
                visualObjects.take(4).joinToString(" / ") { it.label.ifBlank { it.type } }
            }
        val alignedCount = groundedTexts.count { !it.matchedElementId.isNullOrBlank() }
        val objectCount = visualObjects.size
        val parserCount = parserRegions.size
        val textFreeCount = visualObjects.count { it.textFree }
        return if (visualHints.isEmpty()) {
            "OCR 文本 ${blocks.size} 条，对齐节点 $alignedCount 条，视觉对象 $objectCount 个（无字对象 $textFreeCount 个），解析区域 $parserCount 个，前几项: $head"
        } else {
            "OCR 文本 ${blocks.size} 条，对齐节点 $alignedCount 条，视觉对象 $objectCount 个（无字对象 $textFreeCount 个），解析区域 $parserCount 个，补充视觉提示 ${visualHints.size} 条: ${visualHints.take(4).joinToString(" / ")}"
        }
    }

    private fun buildGroundings(
        blocks: List<VisualTextObservation>,
        observation: ScreenObservation,
    ): List<VisualGroundingObservation> =
        blocks.map { block ->
            val blockRect = parseBounds(block.bounds)
            val matched =
                observation.elements.mapNotNull { element ->
                    val elementRect = parseBounds(element.bounds) ?: return@mapNotNull null
                    val overlap = overlapScore(blockRect, elementRect)
                    val distanceScore = centerDistanceScore(blockRect, elementRect)
                    val textScore =
                        when {
                            element.text.isBlank() -> 0.05f
                            element.text.contains(block.text) || block.text.contains(element.text) -> 1f
                            else -> 0f
                        }
                    val score = overlap * 0.5f + textScore * 0.35f + distanceScore * 0.15f
                    if (score <= 0.08f) {
                        null
                    } else {
                        element to score
                    }
                }.maxByOrNull { it.second }

            VisualGroundingObservation(
                text = block.text,
                bounds = block.bounds,
                matchedElementId = matched?.first?.id,
                matchedElementText = matched?.first?.text.orEmpty(),
                overlapScore = matched?.second ?: 0f,
                matchedContainerId = matched?.first?.containerId.orEmpty(),
            )
        }.take(MAX_OCR_BLOCKS)

    private fun buildVisualObjects(
        bitmap: Bitmap,
        blocks: List<VisualTextObservation>,
        groundedTexts: List<VisualGroundingObservation>,
        observation: ScreenObservation,
    ): List<VisualObjectObservation> {
        val ocrObjects =
            blocks.mapIndexedNotNull { index, block ->
                val rect = parseBounds(block.bounds) ?: return@mapIndexedNotNull null
                val grounding =
                    groundedTexts.firstOrNull { it.text == block.text && it.bounds == block.bounds }
                val type =
                    when {
                        rect.width() >= 260 && rect.height() >= 120 -> "card_like"
                        rect.width() in 60..240 && rect.height() in 28..120 && block.text.length <= 12 -> "button_like"
                        rect.width() <= 120 && rect.height() <= 120 && block.text.length <= 6 -> "chip_like"
                        grounding?.matchedElementId.isNullOrBlank() == true -> "unmatched_text"
                        else -> "text_object"
                    }
                val label =
                    grounding?.matchedElementText
                        ?.takeIf { it.isNotBlank() }
                        ?: block.text
                val confidence =
                    when (type) {
                        "card_like" -> (block.confidence + 0.12f).coerceAtMost(0.96f)
                        "button_like" -> (block.confidence + 0.18f).coerceAtMost(0.97f)
                        "chip_like" -> (block.confidence + 0.1f).coerceAtMost(0.95f)
                        else -> block.confidence
                    }
                val matchedElement =
                    grounding?.matchedElementId?.let { matchedId ->
                        observation.elements.firstOrNull { it.id == matchedId }
                    } ?: observation.elements.firstOrNull { element ->
                        element.bounds == block.bounds || element.text.contains(block.text) || block.text.contains(element.text)
                    }
                val roleHint = matchedElement?.roleHint ?: inferVisualRoleHint(label, type)
                val descriptorTokens =
                    (
                        matchedElement?.descriptorTokens.orEmpty() +
                            listOf(label, block.text, type, roleHint)
                    ).filter { it.isNotBlank() }.distinct().take(8)
                VisualObjectObservation(
                    id = "vo_ocr_${index + 1}",
                    type = type,
                    label = label,
                    bounds = block.bounds,
                    confidence = confidence,
                    source = "ocr_object",
                    candidateTier = "primary",
                    roleHint = roleHint,
                    contextHints = matchedElement?.neighborTexts.orEmpty().take(4),
                    descriptorTokens = descriptorTokens,
                    textFree = false,
                    tapPattern = tapPatternFor(type, roleHint, matchedElement?.source.orEmpty()),
                    tapHotspotX = hotspotXFor(type, roleHint),
                    tapHotspotY = hotspotYFor(type, roleHint),
                    interactionRegions = defaultInteractionRegions(type, roleHint),
                    visualSignature = buildVisualSignature(source = "ocr_object", type = type, descriptorTokens = descriptorTokens),
                    spatialSignature = buildSpatialSignature(rect, bitmap.width, bitmap.height),
                    matchedElementId = matchedElement?.id,
                    matchedContainerId = grounding?.matchedContainerId.orEmpty().ifBlank { matchedElement?.containerId.orEmpty() },
                    accessibilityUniqueId = matchedElement?.accessibilityUniqueId.orEmpty(),
                    accessibilityLabel = matchedElement?.accessibilityLabel.orEmpty(),
                    collectionPosition = matchedElement?.collectionPosition.orEmpty(),
                )
            }
        val treeDerivedObjects =
            observation.elements.mapIndexedNotNull { index, element ->
                if (!shouldPromoteTreeElementToVisualObject(element)) return@mapIndexedNotNull null
                val type = inferTreeVisualType(element)
                val label = element.text.ifBlank { inferSyntheticLabel(element, type) }
                VisualObjectObservation(
                    id = "vo_tree_${index + 1}",
                    type = type,
                    label = label,
                    bounds = element.bounds,
                    confidence = when (type) {
                        "icon_button" -> 0.84f
                        "toolbar_icon" -> 0.8f
                        "visual_card" -> 0.8f
                        else -> 0.74f
                    },
                    source = element.source.ifBlank { "tree_shape" },
                    candidateTier =
                        when (element.source) {
                            "overflow_tree" -> "secondary"
                            "overflow_recall" -> "recall"
                            else -> "primary"
                        },
                    roleHint = element.roleHint.ifBlank { inferVisualRoleHint(label, type) },
                    contextHints = element.neighborTexts.take(4),
                    descriptorTokens = buildObjectDescriptorTokens(label, element, type),
                    textFree = element.text.isBlank() && element.accessibilityLabel.isBlank(),
                    tapPattern = tapPatternFor(type, element.roleHint, element.source),
                    tapHotspotX = hotspotXFor(type, element.roleHint),
                    tapHotspotY = hotspotYFor(type, element.roleHint),
                    interactionRegions = defaultInteractionRegions(type, element.roleHint),
                    visualSignature = buildVisualSignature(source = element.source, type = type, descriptorTokens = buildObjectDescriptorTokens(label, element, type)),
                    spatialSignature = buildSpatialSignature(parseBounds(element.bounds), bitmap.width, bitmap.height),
                    matchedElementId = element.id,
                    matchedContainerId = element.containerId,
                    accessibilityUniqueId = element.accessibilityUniqueId,
                    accessibilityLabel = element.accessibilityLabel,
                    collectionPosition = element.collectionPosition,
                )
            }
        val containerObjects =
            observation.elements
                .groupBy { it.containerId }
                .entries
                .mapIndexedNotNull { index, entry ->
                    val containerId = entry.key
                    if (containerId.isBlank()) return@mapIndexedNotNull null
                    val members = entry.value
                    if (members.size < 2) return@mapIndexedNotNull null
                    val clickableMembers = members.count { it.clickable }
                    if (clickableMembers == 0 && members.none { it.roleHint == "detail" || it.roleHint == "conversation" }) {
                        return@mapIndexedNotNull null
                    }
                    val union = unionBounds(members.mapNotNull { parseBounds(it.bounds) })
                    val label =
                        members.asSequence()
                            .map { it.text.ifBlank { it.accessibilityLabel }.ifBlank { it.containerTitle } }
                            .firstOrNull { it.isNotBlank() }
                            ?: members.flatMap { it.neighborTexts }.firstOrNull { it.isNotBlank() }
                            ?: "容器对象"
                    val descriptorTokens =
                        members.flatMap { it.descriptorTokens + it.neighborTexts }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .take(10)
                    VisualObjectObservation(
                        id = "vo_container_${index + 1}",
                        type = if (members.any { it.roleHint == "conversation" }) "conversation_row" else "container_region",
                        label = label,
                        bounds = union,
                        confidence = 0.72f,
                        source = "container_region",
                        candidateTier = "secondary",
                        roleHint = members.firstOrNull { it.roleHint.isNotBlank() }?.roleHint.orEmpty(),
                        contextHints = members.flatMap { it.neighborTexts }.distinct().take(4),
                        descriptorTokens = descriptorTokens,
                        textFree = descriptorTokens.isEmpty(),
                        tapPattern = "container_body",
                        tapHotspotX = 0.46f,
                        tapHotspotY = 0.42f,
                        interactionRegions = defaultInteractionRegions("container_region", members.firstOrNull { it.roleHint.isNotBlank() }?.roleHint.orEmpty()),
                        visualSignature = buildVisualSignature(source = "container_region", type = "container_region", descriptorTokens = descriptorTokens),
                        spatialSignature = buildSpatialSignature(parseBounds(union), bitmap.width, bitmap.height),
                        matchedElementId = members.firstOrNull { it.clickable }?.id,
                        matchedContainerId = containerId,
                        accessibilityUniqueId = members.firstNotNullOfOrNull { it.accessibilityUniqueId.takeIf(String::isNotBlank) }.orEmpty(),
                        accessibilityLabel = members.firstNotNullOfOrNull { it.accessibilityLabel.takeIf(String::isNotBlank) }.orEmpty(),
                        collectionPosition = members.firstNotNullOfOrNull { it.collectionPosition.takeIf(String::isNotBlank) }.orEmpty(),
                    )
                }
        val detectorObjects = detectBitmapRegionObjects(bitmap, blocks, observation)
        return (detectorObjects + ocrObjects + treeDerivedObjects + containerObjects)
            .sortedByDescending { it.confidence }
            .distinctBy { "${it.type}|${it.label}|${it.bounds}|${it.source}|${it.matchedElementId}|${it.matchedContainerId}" }
            .take(MAX_VISUAL_OBJECTS)
    }

    private fun refineParserRegionsWithLocalCrops(
        bitmap: Bitmap,
        observation: ScreenObservation,
        blocks: List<VisualTextObservation>,
        groundedTexts: List<VisualGroundingObservation>,
        visualObjects: List<VisualObjectObservation>,
        parserRegions: List<ScreenParserRegion>,
    ): CropReanalysisResult {
        val seedRegions =
            (parserRegions
                .filter { region ->
                    region.confidence >= 0.58f &&
                        (
                            region.parserLayer in setOf("learned", "scope", "crop_scope") ||
                                region.searchTier in setOf("scope", "local") ||
                                region.type.contains("scope", ignoreCase = true) ||
                                region.type.contains("card", ignoreCase = true) ||
                                region.type.contains("route", ignoreCase = true) ||
                                region.type.contains("conversation", ignoreCase = true)
                        )
                }
                .map { region -> region.id to region.bounds } +
                visualObjects
                    .filter { visualObject ->
                        visualObject.confidence >= 0.66f &&
                            (
                                visualObject.candidateTier in setOf("primary", "detector", "secondary") ||
                                    visualObject.type.contains("card", ignoreCase = true) ||
                                    visualObject.type.contains("route", ignoreCase = true) ||
                                    visualObject.type.contains("map", ignoreCase = true) ||
                                    visualObject.type.contains("conversation", ignoreCase = true)
                            )
                    }.map { visualObject -> visualObject.id to visualObject.bounds })
                .distinctBy { it.second }
                .take(MAX_CROP_REFINEMENT_SEEDS)
        if (seedRegions.isEmpty()) return CropReanalysisResult()
        val refinements =
            seedRegions.map { (seedId, bounds) ->
                reanalyzeCropPass(
                    bitmap = bitmap,
                    observation = observation,
                    blocks = blocks,
                    groundedTexts = groundedTexts,
                    visualObjects = visualObjects,
                    focusBounds = bounds,
                    focusLabel = seedId,
                )
            }
        return CropReanalysisResult(
            parserRegions = refinements.flatMap { it.parserRegions },
            visualObjects = refinements.flatMap { it.visualObjects },
        )
    }

    private fun reanalyzeCropPass(
        bitmap: Bitmap,
        observation: ScreenObservation,
        blocks: List<VisualTextObservation>,
        groundedTexts: List<VisualGroundingObservation>,
        visualObjects: List<VisualObjectObservation>,
        focusBounds: String,
        focusLabel: String,
    ): CropReanalysisResult {
        val focusRect = parseBounds(focusBounds) ?: return CropReanalysisResult()
        val cropRect = expandCropRect(focusRect, bitmap.width, bitmap.height)
        if (cropRect.width() < 48 || cropRect.height() < 48) return CropReanalysisResult()
        val cropBitmap =
            runCatching {
                Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
            }.getOrNull() ?: return CropReanalysisResult()
        return try {
            val localObservation = buildCropObservation(observation, cropRect) ?: return CropReanalysisResult()
            val localBlocks =
                blocks.mapNotNull { translateVisualTextIntoCrop(it, cropRect) }
                    .take(MAX_OCR_BLOCKS)
            val localGroundedTexts =
                groundedTexts.mapNotNull { translateGroundingIntoCrop(it, cropRect) }
                    .take(MAX_OCR_BLOCKS)
            val localBaseObjects =
                visualObjects
                    .filterNot { it.source.contains("crop", ignoreCase = true) }
                    .mapNotNull { translateVisualObjectIntoCrop(it, cropRect) }
            val localDetectorObjects = detectBitmapRegionObjects(cropBitmap, localBlocks, localObservation)
            val localVisualObjects =
                (localBaseObjects + localDetectorObjects)
                    .sortedByDescending { it.confidence }
                    .distinctBy { "${it.type}|${it.label}|${it.bounds}|${it.source}|${it.matchedElementId}|${it.matchedContainerId}" }
                    .take(MAX_VISUAL_OBJECTS)
            val localParserRegions =
                ScreenParserEngine.parse(
                    bitmap = cropBitmap,
                    observation = localObservation,
                    ocrTexts = localBlocks,
                    groundedTexts = localGroundedTexts,
                    visualObjects = localVisualObjects,
                    allowLearnedRegions = false,
                )
            CropReanalysisResult(
                parserRegions =
                    localParserRegions.mapIndexed { index, region ->
                        translateParserRegionFromCrop(
                            region = region,
                            cropRect = cropRect,
                            bitmapWidth = bitmap.width,
                            bitmapHeight = bitmap.height,
                            seedLabel = focusLabel.ifBlank { "crop" },
                            index = index,
                        )
                    },
                visualObjects =
                    localVisualObjects.mapIndexed { index, visualObject ->
                        translateVisualObjectFromCrop(
                            visualObject = visualObject,
                            cropRect = cropRect,
                            bitmapWidth = bitmap.width,
                            bitmapHeight = bitmap.height,
                            seedLabel = focusLabel.ifBlank { "crop" },
                            index = index,
                        )
                    },
                cropBounds = rectToBounds(cropRect),
            )
        } finally {
            cropBitmap.recycle()
        }
    }

    private fun deriveNextCropSeeds(
        cropResult: CropReanalysisResult,
        nextDepth: Int,
    ): List<CropFocusSeed> =
        (
            cropResult.parserRegions
                .filter { region ->
                    region.confidence >= 0.62f &&
                        (
                            region.parserLayer.contains("scope", ignoreCase = true) ||
                                region.searchTier in setOf("local", "local_refine", "scope", "model") ||
                                region.type.contains("scope", ignoreCase = true) ||
                                region.type.contains("card", ignoreCase = true) ||
                                region.type.contains("route", ignoreCase = true) ||
                                region.type.contains("conversation", ignoreCase = true)
                        )
                }.map { region ->
                    CropFocusSeed(
                        bounds = region.bounds,
                        label = region.id,
                        depth = nextDepth,
                    )
                } +
                cropResult.visualObjects
                    .filter { visualObject ->
                        visualObject.confidence >= 0.68f &&
                            (
                                visualObject.candidateTier in setOf("local_refine", "crop", "primary", "secondary") ||
                                    visualObject.type.contains("card", ignoreCase = true) ||
                                    visualObject.type.contains("route", ignoreCase = true) ||
                                    visualObject.type.contains("conversation", ignoreCase = true) ||
                                    visualObject.type.contains("map", ignoreCase = true)
                            )
                    }.map { visualObject ->
                        CropFocusSeed(
                            bounds = visualObject.bounds,
                            label = visualObject.id,
                            depth = nextDepth,
                        )
                    }
            ).distinctBy { normalizeBoundsKey(it.bounds) }
            .sortedByDescending { seed ->
                val regionConfidence = cropResult.parserRegions.firstOrNull { normalizeBoundsKey(it.bounds) == normalizeBoundsKey(seed.bounds) }?.confidence ?: 0f
                val objectConfidence = cropResult.visualObjects.firstOrNull { normalizeBoundsKey(it.bounds) == normalizeBoundsKey(seed.bounds) }?.confidence ?: 0f
                maxOf(regionConfidence, objectConfidence)
            }

    private fun normalizeBoundsKey(bounds: String): String =
        Regex("""-?\d+""").findAll(bounds).joinToString(",") { it.value }

    private fun buildCropObservation(
        observation: ScreenObservation,
        cropRect: Rect,
    ): ScreenObservation? {
        val localElements =
            observation.elements.mapNotNull { element ->
                translateElementIntoCrop(element, cropRect)
            }
        if (localElements.isEmpty()) return null
        val localIds = localElements.map { it.id }.toSet()
        return observation.copy(
            signature = "${observation.signature}:crop:${cropRect.left},${cropRect.top},${cropRect.right},${cropRect.bottom}",
            topTexts =
                (
                    localElements.mapNotNull { it.text.takeIf(String::isNotBlank) } +
                        localElements.mapNotNull { it.accessibilityLabel.takeIf(String::isNotBlank) } +
                        observation.topTexts
                ).distinct().take(10),
            primaryEditableId = observation.primaryEditableId?.takeIf { localIds.contains(it) },
            focusedElementId = observation.focusedElementId?.takeIf { localIds.contains(it) },
            defaultScrollableId = observation.defaultScrollableId?.takeIf { localIds.contains(it) },
            primaryInterruptActionId = observation.primaryInterruptActionId?.takeIf { localIds.contains(it) },
            interruptiveHints = observation.interruptiveHints.filter { localIds.contains(it.elementId) },
            elements = localElements,
            structureHints = (observation.structureHints + "crop_focus=${rectToBounds(cropRect)}").distinct().take(10),
        )
    }

    private fun translateElementIntoCrop(
        element: UiElementObservation,
        cropRect: Rect,
    ): UiElementObservation? {
        val rect = parseBounds(element.bounds) ?: return null
        if (overlapScore(cropRect, rect) < 0.12f && !cropRect.contains(rect)) return null
        return element.copy(
            bounds = rectToBounds(shiftRect(rect, -cropRect.left, -cropRect.top)),
        )
    }

    private fun translateVisualTextIntoCrop(
        text: VisualTextObservation,
        cropRect: Rect,
    ): VisualTextObservation? {
        val rect = parseBounds(text.bounds) ?: return null
        if (overlapScore(cropRect, rect) < 0.12f && !cropRect.contains(rect)) return null
        return text.copy(bounds = rectToBounds(shiftRect(rect, -cropRect.left, -cropRect.top)))
    }

    private fun translateGroundingIntoCrop(
        grounding: VisualGroundingObservation,
        cropRect: Rect,
    ): VisualGroundingObservation? {
        val rect = parseBounds(grounding.bounds) ?: return null
        if (overlapScore(cropRect, rect) < 0.12f && !cropRect.contains(rect)) return null
        return grounding.copy(bounds = rectToBounds(shiftRect(rect, -cropRect.left, -cropRect.top)))
    }

    private fun translateVisualObjectIntoCrop(
        visualObject: VisualObjectObservation,
        cropRect: Rect,
    ): VisualObjectObservation? {
        val rect = parseBounds(visualObject.bounds) ?: return null
        if (overlapScore(cropRect, rect) < 0.12f && !cropRect.contains(rect)) return null
        return visualObject.copy(
            bounds = rectToBounds(shiftRect(rect, -cropRect.left, -cropRect.top)),
        )
    }

    private fun translateParserRegionFromCrop(
        region: ScreenParserRegion,
        cropRect: Rect,
        bitmapWidth: Int,
        bitmapHeight: Int,
        seedLabel: String,
        index: Int,
    ): ScreenParserRegion {
        val localRect = parseBounds(region.bounds) ?: Rect()
        val globalRect = shiftRect(localRect, cropRect.left, cropRect.top)
        return region.copy(
            id = "parser_crop_${seedLabel.hashCode().toUInt().toString(16)}_${index + 1}_${region.id.takeLast(6)}",
            bounds = rectToBounds(globalRect),
            confidence = (region.confidence + 0.04f).coerceAtMost(0.97f),
            source = "${region.source.ifBlank { "screen_parser" }}_crop",
            parserLayer = "crop_${region.parserLayer.ifBlank { "local" }}",
            searchTier = "local_refine",
            visualSignature =
                buildVisualSignature(
                    source = "screen_parser_crop",
                    type = region.type,
                    descriptorTokens = region.descriptorTokens,
                ),
            spatialSignature = buildSpatialSignature(globalRect, bitmapWidth, bitmapHeight),
        )
    }

    private fun translateVisualObjectFromCrop(
        visualObject: VisualObjectObservation,
        cropRect: Rect,
        bitmapWidth: Int,
        bitmapHeight: Int,
        seedLabel: String,
        index: Int,
    ): VisualObjectObservation {
        val localRect = parseBounds(visualObject.bounds) ?: Rect()
        val globalRect = shiftRect(localRect, cropRect.left, cropRect.top)
        return visualObject.copy(
            id = "vo_crop_${seedLabel.hashCode().toUInt().toString(16)}_${index + 1}_${visualObject.id.takeLast(6)}",
            bounds = rectToBounds(globalRect),
            confidence = (visualObject.confidence + 0.03f).coerceAtMost(0.96f),
            source = "${visualObject.source.ifBlank { "visual_object" }}_crop",
            candidateTier = "local_refine",
            visualSignature =
                buildVisualSignature(
                    source = "visual_crop",
                    type = visualObject.type,
                    descriptorTokens = visualObject.descriptorTokens,
                ),
            spatialSignature = buildSpatialSignature(globalRect, bitmapWidth, bitmapHeight),
        )
    }

    private fun expandCropRect(
        rect: Rect,
        maxWidth: Int,
        maxHeight: Int,
    ): Rect {
        val horizontal = (rect.width() * 0.42f).toInt().coerceAtLeast(56)
        val vertical = (rect.height() * 0.42f).toInt().coerceAtLeast(56)
        return Rect(
            (rect.left - horizontal).coerceAtLeast(0),
            (rect.top - vertical).coerceAtLeast(0),
            (rect.right + horizontal).coerceAtMost(maxWidth),
            (rect.bottom + vertical).coerceAtMost(maxHeight),
        )
    }

    private fun shiftRect(
        rect: Rect,
        dx: Int,
        dy: Int,
    ): Rect = Rect(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy)

    private fun detectBitmapRegionObjects(
        bitmap: Bitmap,
        blocks: List<VisualTextObservation>,
        observation: ScreenObservation,
    ): List<VisualObjectObservation> {
        val blockRects = blocks.mapNotNull { parseBounds(it.bounds) }
        val cells = buildSaliencyCells(bitmap, blockRects)
        if (cells.isEmpty()) return emptyList()
        val proposals = mergeCellsToProposals(cells, bitmap.width, bitmap.height)
        return proposals.mapIndexedNotNull { index, proposal ->
            val matchedElement =
                observation.elements
                    .mapNotNull { element ->
                        val rect = parseBounds(element.bounds) ?: return@mapNotNull null
                        val overlap = overlapScore(proposal.bounds, rect)
                        val distance = centerDistanceScore(proposal.bounds, rect)
                        val score = overlap * 0.65f + distance * 0.35f
                        if (score <= 0.18f) null else element to score
                    }.maxByOrNull { it.second }
                    ?.first
            val type = classifyProposalType(proposal, bitmap.width, bitmap.height)
            val label = detectorLabelForProposal(proposal, type, bitmap.width, bitmap.height)
            val descriptorTokens = buildProposalDescriptorTokens(proposal, type, label)
            if (descriptorTokens.isEmpty() && label.isBlank()) return@mapIndexedNotNull null
            VisualObjectObservation(
                id = "vo_detector_${index + 1}",
                type = type,
                label = label,
                bounds = rectToBounds(proposal.bounds),
                confidence = proposal.score.coerceIn(0.45f, 0.93f),
                source = "visual_detector",
                candidateTier = if (proposal.textOverlap == 0) "detector" else "secondary",
                roleHint = matchedElement?.roleHint ?: inferVisualRoleHint(label, type),
                contextHints =
                    (
                        matchedElement?.neighborTexts.orEmpty() +
                            descriptorTokens.take(3)
                    ).distinct().take(5),
                descriptorTokens = descriptorTokens,
                textFree = proposal.textOverlap == 0,
                tapPattern = if (proposal.interactionRegions.size > 1) "detector_regions" else tapPatternFor(type, matchedElement?.roleHint.orEmpty(), "visual_detector"),
                tapHotspotX = hotspotXFor(type, matchedElement?.roleHint.orEmpty()),
                tapHotspotY = hotspotYFor(type, matchedElement?.roleHint.orEmpty()),
                interactionRegions = proposal.interactionRegions,
                visualSignature = buildVisualSignature(source = "visual_detector", type = type, descriptorTokens = descriptorTokens),
                spatialSignature = buildSpatialSignature(proposal.bounds, bitmap.width, bitmap.height),
                matchedElementId = matchedElement?.id,
                matchedContainerId = matchedElement?.containerId.orEmpty(),
                accessibilityUniqueId = matchedElement?.accessibilityUniqueId.orEmpty(),
                accessibilityLabel = matchedElement?.accessibilityLabel.orEmpty(),
                collectionPosition = matchedElement?.collectionPosition.orEmpty(),
            )
        }
    }

    private fun parseBounds(
        bounds: String,
    ): Rect? {
        val values = Regex("""-?\d+""").findAll(bounds).map { it.value.toInt() }.toList()
        if (values.size < 4) return null
        return Rect(values[0], values[1], values[2], values[3])
    }

    private fun overlapScore(
        a: Rect?,
        b: Rect,
    ): Float {
        if (a == null) return 0f
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val width = (right - left).coerceAtLeast(0)
        val height = (bottom - top).coerceAtLeast(0)
        val interArea = width * height
        if (interArea <= 0) return 0f
        val baseArea = (a.width() * a.height()).coerceAtLeast(1)
        return interArea.toFloat() / baseArea.toFloat()
    }

    private fun centerDistanceScore(
        a: Rect?,
        b: Rect,
    ): Float {
        if (a == null) return 0f
        val dx = a.exactCenterX() - b.exactCenterX()
        val dy = a.exactCenterY() - b.exactCenterY()
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        val maxDistance = maxOf(a.width(), a.height(), b.width(), b.height()).coerceAtLeast(1)
        return (1f - (distance / (maxDistance * 3f))).coerceIn(0f, 1f)
    }

    private fun shouldPromoteTreeElementToVisualObject(
        element: UiElementObservation,
    ): Boolean {
        if (!element.clickable && !element.editable) return false
        if (element.bounds.isBlank()) return false
        if (element.source == "visual") return false
        return element.text.isBlank() ||
            element.accessibilityLabel.isNotBlank() ||
            element.className.contains("Image", ignoreCase = true) ||
            element.className.contains("Button", ignoreCase = true) ||
            (element.roleHint in setOf("submit", "dismiss", "entry", "conversation", "detail"))
    }

    private fun inferTreeVisualType(
        element: UiElementObservation,
    ): String =
        when {
            element.className.contains("ImageButton", ignoreCase = true) -> "icon_button"
            element.className.contains("Image", ignoreCase = true) -> "icon_button"
            element.source == "overflow_recall" && element.text.isBlank() -> "toolbar_icon"
            element.className.contains("Button", ignoreCase = true) -> "button_like"
            element.clickable && element.containerId.isNotBlank() && element.text.isBlank() -> "visual_card"
            else -> "tree_visual_target"
        }

    private fun inferSyntheticLabel(
        element: UiElementObservation,
        type: String,
    ): String =
        element.accessibilityLabel.takeIf { it.isNotBlank() }
            ?: element.containerTitle.takeIf { it.isNotBlank() }
            ?: element.paneTitle.takeIf { it.isNotBlank() }
            ?: element.neighborTexts.firstOrNull { it.isNotBlank() }
            ?: element.descriptorTokens.firstOrNull()
            ?: element.roleHint.ifBlank { type }

    private fun inferVisualRoleHint(
        label: String,
        type: String,
    ): String =
        when {
            actionLikeKeywords.any { label.contains(it, ignoreCase = true) } -> "system_action"
            type.contains("button", ignoreCase = true) || type.contains("icon", ignoreCase = true) -> "entry"
            type.contains("card", ignoreCase = true) -> "detail"
            else -> ""
        }

    private fun tapPatternFor(
        type: String,
        roleHint: String,
        source: String,
    ): String =
        when {
            roleHint == "dismiss" -> "corner_action"
            type.contains("icon", ignoreCase = true) -> "icon_cluster"
            type.contains("card", ignoreCase = true) || type.contains("container", ignoreCase = true) -> "card_body"
            source == "overflow_recall" -> "list_row"
            else -> "center_bias"
        }

    private fun buildObjectDescriptorTokens(
        label: String,
        element: UiElementObservation,
        type: String,
    ): List<String> =
        (
            listOf(
                label,
                element.accessibilityLabel,
                element.containerTitle,
                element.paneTitle,
                element.stateDescription,
                element.collectionPosition,
                element.roleHint,
                type,
            ) +
                element.neighborTexts +
                element.descriptorTokens
        ).map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(10)

    private fun unionBounds(
        rects: List<Rect>,
    ): String {
        if (rects.isEmpty()) return "[0,0][0,0]"
        val left = rects.minOf { it.left }
        val top = rects.minOf { it.top }
        val right = rects.maxOf { it.right }
        val bottom = rects.maxOf { it.bottom }
        return "[$left,$top][$right,$bottom]"
    }

    private fun hotspotXFor(
        type: String,
        roleHint: String,
    ): Float =
        when {
            roleHint == "dismiss" -> 0.82f
            type == "chip_like" -> 0.5f
            type == "visual_card" || type == "card_like" -> 0.45f
            else -> 0.5f
        }

    private fun hotspotYFor(
        type: String,
        roleHint: String,
    ): Float =
        when {
            roleHint == "dismiss" -> 0.22f
            type == "chip_like" -> 0.5f
            type == "visual_card" || type == "card_like" -> 0.38f
            else -> 0.5f
        }

    private fun estimateConfidence(
        text: String,
        bounds: Rect,
    ): Float {
        val area = bounds.width().coerceAtLeast(0) * bounds.height().coerceAtLeast(0)
        val base = if (text.length >= 4) 0.78f else 0.64f
        return (base + (area.coerceAtMost(80_000) / 80_000f) * 0.18f).coerceAtMost(0.96f)
    }

    private fun buildSaliencyCells(
        bitmap: Bitmap,
        textRects: List<Rect>,
    ): List<SaliencyCell> {
        val columns = when {
            bitmap.width >= 1440 -> 14
            bitmap.width >= 1080 -> 12
            else -> 10
        }
        val cellSize = (bitmap.width / columns).coerceAtLeast(72)
        val rows = (bitmap.height / cellSize).coerceAtLeast(8)
        val actualCellWidth = (bitmap.width / columns.toFloat()).coerceAtLeast(48f)
        val actualCellHeight = (bitmap.height / rows.toFloat()).coerceAtLeast(48f)
        val cells = mutableListOf<SaliencyCell>()
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val left = (column * actualCellWidth).toInt()
                val top = (row * actualCellHeight).toInt()
                val right = if (column == columns - 1) bitmap.width else ((column + 1) * actualCellWidth).toInt()
                val bottom = if (row == rows - 1) bitmap.height else ((row + 1) * actualCellHeight).toInt()
                val rect = Rect(left, top, right, bottom)
                if (rect.width() < 24 || rect.height() < 24) continue
                val features = sampleBitmapFeatures(bitmap, rect)
                val textOverlap = textRects.count { overlapScore(rect, it) >= 0.15f }
                val saliency =
                    (
                        features.edgeDensity * 0.42f +
                            features.variance * 0.34f +
                            features.saturation * 0.16f +
                            if (textOverlap == 0) 0.08f else 0f
                        ).coerceIn(0f, 1f)
                if (saliency < 0.16f && features.edgeDensity < 0.22f && features.variance < 0.08f) continue
                cells +=
                    SaliencyCell(
                        row = row,
                        column = column,
                        bounds = rect,
                        saliency = saliency,
                        edgeDensity = features.edgeDensity,
                        variance = features.variance,
                        saturation = features.saturation,
                        textOverlap = textOverlap,
                    )
            }
        }
        return cells
    }

    private fun sampleBitmapFeatures(
        bitmap: Bitmap,
        rect: Rect,
    ): BitmapFeatures {
        val stepX = (rect.width() / 6).coerceAtLeast(4)
        val stepY = (rect.height() / 6).coerceAtLeast(4)
        var samples = 0
        var luminanceSum = 0f
        var luminanceSquareSum = 0f
        var saturationSum = 0f
        var edgeSum = 0f
        var edgeComparisons = 0
        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                val color = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
                val luminance = 0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)
                val maxChannel = maxOf(Color.red(color), Color.green(color), Color.blue(color)).toFloat()
                val minChannel = minOf(Color.red(color), Color.green(color), Color.blue(color)).toFloat()
                val saturation = if (maxChannel <= 0f) 0f else (maxChannel - minChannel) / maxChannel
                luminanceSum += luminance
                luminanceSquareSum += luminance * luminance
                saturationSum += saturation
                samples += 1
                if (x + stepX < rect.right) {
                    val neighbor = bitmap.getPixel((x + stepX).coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
                    val neighborLum = 0.299f * Color.red(neighbor) + 0.587f * Color.green(neighbor) + 0.114f * Color.blue(neighbor)
                    edgeSum += kotlin.math.abs(luminance - neighborLum)
                    edgeComparisons += 1
                }
                if (y + stepY < rect.bottom) {
                    val neighbor = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), (y + stepY).coerceIn(0, bitmap.height - 1))
                    val neighborLum = 0.299f * Color.red(neighbor) + 0.587f * Color.green(neighbor) + 0.114f * Color.blue(neighbor)
                    edgeSum += kotlin.math.abs(luminance - neighborLum)
                    edgeComparisons += 1
                }
                x += stepX
            }
            y += stepY
        }
        if (samples == 0) return BitmapFeatures()
        val mean = luminanceSum / samples
        val variance = ((luminanceSquareSum / samples) - mean * mean).coerceAtLeast(0f) / (255f * 255f)
        val edgeDensity = if (edgeComparisons == 0) 0f else (edgeSum / edgeComparisons) / 255f
        return BitmapFeatures(
            variance = variance.coerceIn(0f, 1f),
            edgeDensity = edgeDensity.coerceIn(0f, 1f),
            saturation = (saturationSum / samples).coerceIn(0f, 1f),
        )
    }

    private fun mergeCellsToProposals(
        cells: List<SaliencyCell>,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): List<VisualRegionProposal> {
        if (cells.isEmpty()) return emptyList()
        val byCoordinate = cells.associateBy { it.row to it.column }
        val visited = mutableSetOf<Pair<Int, Int>>()
        val proposals = mutableListOf<VisualRegionProposal>()
        cells.sortedByDescending { it.saliency }.forEach { seed ->
            val key = seed.row to seed.column
            if (!visited.add(key)) return@forEach
            val queue = ArrayDeque<SaliencyCell>()
            queue.add(seed)
            val cluster = mutableListOf<SaliencyCell>()
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                cluster += current
                listOf(
                    current.row - 1 to current.column,
                    current.row + 1 to current.column,
                    current.row to current.column - 1,
                    current.row to current.column + 1,
                ).forEach neighborLoop@{ neighborKey ->
                    val neighbor = byCoordinate[neighborKey] ?: return@neighborLoop
                    if (visited.add(neighborKey)) {
                        if (neighbor.saliency >= 0.14f || neighbor.edgeDensity >= 0.18f) {
                            queue.add(neighbor)
                        }
                    }
                }
            }
            val rect = unionBoundsRect(cluster.map { it.bounds })
            val areaFraction = rect.width().toFloat() * rect.height().toFloat() / (bitmapWidth.toFloat() * bitmapHeight.toFloat()).coerceAtLeast(1f)
            if (rect.width() < 36 || rect.height() < 36) return@forEach
            if (areaFraction < 0.0025f || areaFraction > 0.42f) return@forEach
            val interactionRegions =
                cluster
                    .sortedByDescending { it.saliency }
                    .take(4)
                    .mapNotNull { cell ->
                        relativeInteractionRegion(parent = rect, child = cell.bounds, label = "detector_cell", score = cell.saliency)
                    }
            proposals +=
                VisualRegionProposal(
                    bounds = rect,
                    score = cluster.map { it.saliency }.average().toFloat().coerceIn(0f, 1f),
                    edgeDensity = cluster.map { it.edgeDensity }.average().toFloat().coerceIn(0f, 1f),
                    variance = cluster.map { it.variance }.average().toFloat().coerceIn(0f, 1f),
                    saturation = cluster.map { it.saturation }.average().toFloat().coerceIn(0f, 1f),
                    textOverlap = cluster.sumOf { it.textOverlap },
                    interactionRegions = interactionRegions.ifEmpty { defaultInteractionRegions("visual_region", "") },
                )
        }
        return proposals
            .sortedByDescending { it.score }
            .distinctBy { rectToBounds(it.bounds) }
            .take(8)
    }

    private fun classifyProposalType(
        proposal: VisualRegionProposal,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): String {
        val widthFraction = proposal.bounds.width().toFloat() / bitmapWidth.toFloat().coerceAtLeast(1f)
        val heightFraction = proposal.bounds.height().toFloat() / bitmapHeight.toFloat().coerceAtLeast(1f)
        val nearTop = proposal.bounds.top <= bitmapHeight * 0.18f
        val nearBottom = proposal.bounds.bottom >= bitmapHeight * 0.82f
        return when {
            widthFraction >= 0.62f && heightFraction <= 0.16f && (nearTop || nearBottom) -> "toolbar_strip"
            widthFraction <= 0.2f && heightFraction <= 0.16f && proposal.edgeDensity >= 0.22f -> "icon_button"
            widthFraction >= 0.38f && heightFraction >= 0.12f && proposal.saturation >= 0.18f && proposal.textOverlap == 0 -> "media_like"
            widthFraction >= 0.34f && heightFraction >= 0.14f && proposal.saturation < 0.16f && proposal.edgeDensity >= 0.18f -> "map_like"
            widthFraction >= 0.28f && heightFraction >= 0.1f -> "card_like"
            else -> "visual_region"
        }
    }

    private fun detectorLabelForProposal(
        proposal: VisualRegionProposal,
        type: String,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): String {
        val horizontal =
            when {
                proposal.bounds.exactCenterX() <= bitmapWidth * 0.33f -> "左侧"
                proposal.bounds.exactCenterX() >= bitmapWidth * 0.66f -> "右侧"
                else -> "中部"
            }
        val vertical =
            when {
                proposal.bounds.exactCenterY() <= bitmapHeight * 0.22f -> "顶部"
                proposal.bounds.exactCenterY() >= bitmapHeight * 0.78f -> "底部"
                else -> "中部"
            }
        val typeLabel =
            when (type) {
                "toolbar_strip" -> "工具栏区域"
                "icon_button" -> "图标按钮"
                "media_like" -> "媒体区域"
                "map_like" -> "地图区域"
                "card_like" -> "内容卡片"
                else -> "视觉区域"
            }
        return "$vertical$horizontal$typeLabel"
    }

    private fun buildProposalDescriptorTokens(
        proposal: VisualRegionProposal,
        type: String,
        label: String,
    ): List<String> =
        listOf(
            label,
            type,
            if (proposal.textOverlap == 0) "无字目标" else "含文本区域",
            if (proposal.edgeDensity >= 0.22f) "高边缘" else "低边缘",
            if (proposal.saturation >= 0.18f) "彩色区域" else "低饱和区域",
            if (proposal.variance >= 0.12f) "纹理明显" else "纹理平滑",
        ).filter { it.isNotBlank() }
            .distinct()
            .take(8)

    private fun buildVisualSignature(
        source: String,
        type: String,
        descriptorTokens: List<String>,
    ): String =
        listOf(source, type)
            .plus(descriptorTokens.take(3))
            .filter { it.isNotBlank() }
            .joinToString(":")

    private fun buildSpatialSignature(
        rect: Rect?,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): String {
        if (rect == null || bitmapWidth <= 0 || bitmapHeight <= 0) return ""
        val horizontal =
            when {
                rect.exactCenterX() <= bitmapWidth * 0.33f -> "left"
                rect.exactCenterX() >= bitmapWidth * 0.66f -> "right"
                else -> "center"
            }
        val vertical =
            when {
                rect.exactCenterY() <= bitmapHeight * 0.2f -> "top"
                rect.exactCenterY() >= bitmapHeight * 0.8f -> "bottom"
                else -> "middle"
            }
        val areaFraction = rect.width().toFloat() * rect.height().toFloat() / (bitmapWidth.toFloat() * bitmapHeight.toFloat()).coerceAtLeast(1f)
        val sizeBucket =
            when {
                areaFraction >= 0.12f -> "large"
                areaFraction >= 0.04f -> "medium"
                else -> "small"
            }
        return "$vertical:$horizontal:$sizeBucket"
    }

    private fun defaultInteractionRegions(
        type: String,
        roleHint: String,
    ): List<InteractionRegion> =
        when {
            roleHint == "dismiss" ->
                listOf(
                    InteractionRegion(0.62f, 0.08f, 0.94f, 0.38f, score = 0.92f, label = "dismiss_corner"),
                    InteractionRegion(0.42f, 0.18f, 0.78f, 0.52f, score = 0.74f, label = "dismiss_inner"),
                )

            type.contains("icon", ignoreCase = true) ->
                listOf(
                    InteractionRegion(0.22f, 0.18f, 0.78f, 0.78f, score = 0.88f, label = "icon_core"),
                    InteractionRegion(0.12f, 0.12f, 0.9f, 0.9f, score = 0.7f, label = "icon_safe_zone"),
                )

            type.contains("card", ignoreCase = true) || type.contains("container", ignoreCase = true) ->
                listOf(
                    InteractionRegion(0.2f, 0.18f, 0.86f, 0.62f, score = 0.86f, label = "card_body"),
                    InteractionRegion(0.3f, 0.28f, 0.92f, 0.84f, score = 0.72f, label = "card_secondary"),
                )

            else ->
                listOf(
                    InteractionRegion(0.24f, 0.18f, 0.82f, 0.78f, score = 0.82f, label = "object_core"),
                    InteractionRegion(0.12f, 0.12f, 0.9f, 0.9f, score = 0.64f, label = "object_safe_zone"),
                )
        }

    private fun relativeInteractionRegion(
        parent: Rect,
        child: Rect,
        label: String,
        score: Float,
    ): InteractionRegion? {
        if (parent.width() <= 0 || parent.height() <= 0) return null
        val left = ((child.left - parent.left).toFloat() / parent.width().toFloat()).coerceIn(0f, 1f)
        val top = ((child.top - parent.top).toFloat() / parent.height().toFloat()).coerceIn(0f, 1f)
        val right = ((child.right - parent.left).toFloat() / parent.width().toFloat()).coerceIn(0f, 1f)
        val bottom = ((child.bottom - parent.top).toFloat() / parent.height().toFloat()).coerceIn(0f, 1f)
        if (right - left < 0.08f || bottom - top < 0.08f) return null
        return InteractionRegion(left, top, right, bottom, score = score, label = label)
    }

    private fun unionBoundsRect(
        rects: List<Rect>,
    ): Rect {
        val left = rects.minOf { it.left }
        val top = rects.minOf { it.top }
        val right = rects.maxOf { it.right }
        val bottom = rects.maxOf { it.bottom }
        return Rect(left, top, right, bottom)
    }

    private fun rectToBounds(
        rect: Rect,
    ): String = "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]"

    private data class BitmapFeatures(
        val variance: Float = 0f,
        val edgeDensity: Float = 0f,
        val saturation: Float = 0f,
    )

    private data class SaliencyCell(
        val row: Int,
        val column: Int,
        val bounds: Rect,
        val saliency: Float,
        val edgeDensity: Float,
        val variance: Float,
        val saturation: Float,
        val textOverlap: Int,
    )

    private data class VisualRegionProposal(
        val bounds: Rect,
        val score: Float,
        val edgeDensity: Float,
        val variance: Float,
        val saturation: Float,
        val textOverlap: Int,
        val interactionRegions: List<InteractionRegion>,
    )

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.processAwait(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
            addOnFailureListener { error ->
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }
        }
}
