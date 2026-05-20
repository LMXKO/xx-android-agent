package com.lmx.xiaoxuanagent.agent

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs

internal object ScreenParserEngine {
    private const val MAX_PARSER_REGIONS = 28
    private const val MAX_BAND_SCOPES = 4
    private const val ROW_GROUP_THRESHOLD_PX = 64

    fun parse(
        bitmap: Bitmap,
        observation: ScreenObservation,
        ocrTexts: List<VisualTextObservation>,
        groundedTexts: List<VisualGroundingObservation>,
        visualObjects: List<VisualObjectObservation>,
        allowLearnedRegions: Boolean = true,
    ): List<ScreenParserRegion> {
        val baseObjectRegions = buildObjectRegions(visualObjects)
        val modelRegions =
            ScreenParserModelStore.suggest(
                bitmap = bitmap,
                observation = observation,
                visualObjects = visualObjects,
                parserRegions = baseObjectRegions,
            )
        val learnedRegions =
            if (allowLearnedRegions) {
                ScreenParserLearningStore.suggest(bitmap, observation, visualObjects)
            } else {
                emptyList()
            }
        val containerScopes = buildContainerScopes(bitmap, observation, visualObjects)
        val rowScopes = buildRowScopes(bitmap, observation, visualObjects)
        val bandScopes = buildBitmapBandScopes(bitmap, observation, ocrTexts, groundedTexts, visualObjects)
        return (modelRegions + learnedRegions + containerScopes + rowScopes + bandScopes + baseObjectRegions)
            .sortedByDescending { it.confidence }
            .distinctBy {
                listOf(
                    it.type,
                    it.label,
                    it.bounds,
                    it.parserLayer,
                    it.searchTier,
                    it.matchedElementIds.sorted().joinToString(","),
                ).joinToString("|")
            }
            .take(MAX_PARSER_REGIONS)
    }

    private fun buildObjectRegions(
        visualObjects: List<VisualObjectObservation>,
    ): List<ScreenParserRegion> =
        visualObjects.map { visualObject ->
            ScreenParserRegion(
                id = "parser_${visualObject.id}",
                type = visualObject.type,
                label = visualObject.label,
                bounds = visualObject.bounds,
                confidence = visualObject.confidence,
                source = "screen_parser_object",
                parserLayer = "object",
                searchTier = visualObject.candidateTier,
                roleHint = visualObject.roleHint,
                contextHints = visualObject.contextHints,
                descriptorTokens = visualObject.descriptorTokens,
                textFree = visualObject.textFree,
                tapPattern = visualObject.tapPattern,
                tapHotspotX = visualObject.tapHotspotX,
                tapHotspotY = visualObject.tapHotspotY,
                interactionRegions = visualObject.interactionRegions,
                visualSignature = visualObject.visualSignature,
                spatialSignature = visualObject.spatialSignature,
                matchedElementIds = listOfNotNull(visualObject.matchedElementId),
                matchedContainerIds = listOf(visualObject.matchedContainerId).filter { it.isNotBlank() },
                accessibilityUniqueIds = listOf(visualObject.accessibilityUniqueId).filter { it.isNotBlank() },
                accessibilityLabels = listOf(visualObject.accessibilityLabel).filter { it.isNotBlank() },
            )
        }

    private fun buildContainerScopes(
        bitmap: Bitmap,
        observation: ScreenObservation,
        visualObjects: List<VisualObjectObservation>,
    ): List<ScreenParserRegion> =
        observation.elements
            .groupBy { element ->
                element.containerId.ifBlank {
                    if (element.collectionPosition.isNotBlank()) {
                        "collection:${element.collectionPosition.substringBefore(',')}"
                    } else {
                        ""
                    }
                }
            }
            .filterKeys { it.isNotBlank() }
            .mapNotNull { (containerId, members) ->
                val rects = members.mapNotNull { parseBounds(it.bounds) }
                if (rects.isEmpty()) return@mapNotNull null
                val union = unionRect(rects)
                val label =
                    members.asSequence()
                        .map { it.text.ifBlank { it.accessibilityLabel }.ifBlank { it.containerTitle } }
                        .firstOrNull { it.isNotBlank() }
                        .orEmpty()
                val roleHint = members.firstOrNull { it.roleHint.isNotBlank() }?.roleHint.orEmpty()
                val descriptorTokens =
                    (
                        members.flatMap { member ->
                            member.descriptorTokens +
                                member.visualDescriptorTokens +
                                member.neighborTexts +
                                listOf(
                                    member.accessibilityLabel,
                                    member.containerTitle,
                                    member.collectionPosition,
                                    member.roleHint,
                                )
                        } +
                            visualObjects.filter { it.matchedContainerId == containerId }.flatMap { it.descriptorTokens + it.contextHints }
                    ).map(String::trim)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .take(12)
                val type =
                    when {
                        roleHint == "conversation" -> "conversation_scope"
                        roleHint == "route" -> "route_scope"
                        roleHint == "detail" -> "detail_scope"
                        members.count { it.clickable } >= 3 -> "container_scope"
                        else -> "search_scope"
                    }
                val density = bitmapDensity(bitmap, union)
                val confidence =
                    (
                        0.52f +
                            members.count { it.clickable }.coerceAtMost(5) * 0.05f +
                            density * 0.18f
                    ).coerceIn(0.45f, 0.92f)
                ScreenParserRegion(
                    id = "parser_scope_${containerId.hashCode().toUInt().toString(16)}",
                    type = type,
                    label = label,
                    bounds = rectToBounds(union),
                    confidence = confidence,
                    source = "screen_parser_scope",
                    parserLayer = "scope",
                    searchTier = "scope",
                    roleHint = roleHint,
                    contextHints = members.flatMap { it.neighborTexts }.distinct().take(6),
                    descriptorTokens = descriptorTokens,
                    textFree = label.isBlank(),
                    tapPattern = when {
                        type.contains("conversation") -> "list_row"
                        type.contains("detail") -> "card_body"
                        else -> "container_body"
                    },
                    tapHotspotX = if (type.contains("scope")) 0.42f else 0.5f,
                    tapHotspotY = if (type.contains("scope")) 0.38f else 0.5f,
                    interactionRegions = scopeInteractionRegions(type, members),
                    visualSignature = buildRegionSignature("scope", type, descriptorTokens),
                    spatialSignature = buildSpatialSignature(union, bitmap.width, bitmap.height),
                    matchedElementIds = members.map { it.id },
                    matchedContainerIds = listOf(containerId),
                    accessibilityUniqueIds = members.map { it.accessibilityUniqueId }.filter { it.isNotBlank() }.distinct(),
                    accessibilityLabels = members.map { it.accessibilityLabel }.filter { it.isNotBlank() }.distinct(),
                )
            }

    private fun buildRowScopes(
        bitmap: Bitmap,
        observation: ScreenObservation,
        visualObjects: List<VisualObjectObservation>,
    ): List<ScreenParserRegion> {
        val rowGroups =
            observation.elements
                .filter { it.clickable || it.editable || it.roleHint.isNotBlank() }
                .groupBy { element ->
                    val rect = parseBounds(element.bounds) ?: Rect()
                    rect.top / ROW_GROUP_THRESHOLD_PX
                }
                .values
                .filter { it.size >= 2 }
                .sortedBy { group -> group.minOfOrNull { parseBounds(it.bounds)?.top ?: Int.MAX_VALUE } ?: Int.MAX_VALUE }
                .take(6)
        return rowGroups.mapIndexedNotNull { index, members ->
            val rects = members.mapNotNull { parseBounds(it.bounds) }
            if (rects.isEmpty()) return@mapIndexedNotNull null
            val union = unionRect(rects)
            val descriptorTokens =
                (
                    members.flatMap { it.descriptorTokens + it.visualDescriptorTokens + it.neighborTexts } +
                        visualObjects.filter { objectObservation ->
                            parseBounds(objectObservation.bounds)?.let { objectRect -> overlapScore(union, objectRect) >= 0.18f } == true
                        }.flatMap { it.descriptorTokens + it.contextHints }
                ).map(String::trim)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(10)
            val roleHint = members.firstOrNull { it.roleHint.isNotBlank() }?.roleHint.orEmpty()
            ScreenParserRegion(
                id = "parser_row_${index + 1}",
                type = if (roleHint == "conversation") "conversation_row_scope" else "row_scope",
                label = members.firstOrNull { it.text.isNotBlank() }?.text.orEmpty(),
                bounds = rectToBounds(union),
                confidence = (0.56f + bitmapDensity(bitmap, union) * 0.16f).coerceIn(0.48f, 0.86f),
                source = "screen_parser_row",
                parserLayer = "scope",
                searchTier = "local",
                roleHint = roleHint,
                contextHints = members.flatMap { it.neighborTexts }.distinct().take(5),
                descriptorTokens = descriptorTokens,
                textFree = descriptorTokens.isEmpty(),
                tapPattern = "list_row",
                tapHotspotX = 0.4f,
                tapHotspotY = 0.42f,
                interactionRegions = scopeInteractionRegions("row_scope", members),
                visualSignature = buildRegionSignature("row", roleHint.ifBlank { "row_scope" }, descriptorTokens),
                spatialSignature = buildSpatialSignature(union, bitmap.width, bitmap.height),
                matchedElementIds = members.map { it.id },
                matchedContainerIds = members.map { it.containerId }.filter { it.isNotBlank() }.distinct(),
                accessibilityUniqueIds = members.map { it.accessibilityUniqueId }.filter { it.isNotBlank() }.distinct(),
                accessibilityLabels = members.map { it.accessibilityLabel }.filter { it.isNotBlank() }.distinct(),
            )
        }
    }

    private fun buildBitmapBandScopes(
        bitmap: Bitmap,
        observation: ScreenObservation,
        ocrTexts: List<VisualTextObservation>,
        groundedTexts: List<VisualGroundingObservation>,
        visualObjects: List<VisualObjectObservation>,
    ): List<ScreenParserRegion> {
        val rowStep = maxOf(8, bitmap.height / 48)
        val bandScores =
            (0 until bitmap.height step rowStep).map { top ->
                val bottom = (top + rowStep * 2).coerceAtMost(bitmap.height)
                val rect = Rect(0, top, bitmap.width, bottom)
                val density = bitmapDensity(bitmap, rect)
                val textHits = ocrTexts.count { parseBounds(it.bounds)?.let { overlapScore(rect, it) >= 0.18f } == true }
                val objectHits = visualObjects.count { parseBounds(it.bounds)?.let { overlapScore(rect, it) >= 0.2f } == true }
                val groundedHits = groundedTexts.count { parseBounds(it.bounds)?.let { overlapScore(rect, it) >= 0.2f } == true }
                val score = density + textHits * 0.08f + objectHits * 0.11f + groundedHits * 0.08f
                rect to score
            }
                .sortedByDescending { it.second }
                .distinctBy { pair -> pair.first.top / (rowStep * 2) }
                .take(MAX_BAND_SCOPES)
        return bandScores.mapIndexedNotNull { index, (rect, rawScore) ->
            if (rawScore <= 0.26f) return@mapIndexedNotNull null
            val overlappingElements =
                observation.elements.filter { element ->
                    parseBounds(element.bounds)?.let { overlapScore(rect, it) >= 0.22f } == true
                }
            val descriptorTokens =
                (
                    overlappingElements.flatMap { it.descriptorTokens + it.visualDescriptorTokens + it.neighborTexts } +
                        visualObjects.filter { parseBounds(it.bounds)?.let { overlapScore(rect, it) >= 0.22f } == true }
                            .flatMap { it.descriptorTokens + it.contextHints }
                ).map(String::trim)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(10)
            val roleHint = overlappingElements.firstOrNull { it.roleHint.isNotBlank() }?.roleHint.orEmpty()
            ScreenParserRegion(
                id = "parser_band_${index + 1}",
                type = if (rect.top < bitmap.height * 0.2f) "toolbar_band" else "content_band",
                label = overlappingElements.firstOrNull { it.text.isNotBlank() }?.text.orEmpty(),
                bounds = rectToBounds(rect),
                confidence = rawScore.coerceIn(0.42f, 0.84f),
                source = "screen_parser_band",
                parserLayer = "band",
                searchTier = "band",
                roleHint = roleHint,
                contextHints = overlappingElements.flatMap { it.neighborTexts }.distinct().take(4),
                descriptorTokens = descriptorTokens,
                textFree = descriptorTokens.isEmpty(),
                tapPattern = if (rect.top < bitmap.height * 0.2f) "icon_cluster" else "container_body",
                tapHotspotX = 0.5f,
                tapHotspotY = if (rect.top < bitmap.height * 0.2f) 0.3f else 0.5f,
                interactionRegions =
                    listOf(
                        InteractionRegion(0.08f, 0.18f, 0.92f, 0.78f, score = rawScore, label = "band_primary"),
                        InteractionRegion(0.18f, 0.18f, 0.54f, 0.72f, score = rawScore * 0.82f, label = "band_leading"),
                    ),
                visualSignature = buildRegionSignature("band", roleHint.ifBlank { "content_band" }, descriptorTokens),
                spatialSignature = buildSpatialSignature(rect, bitmap.width, bitmap.height),
                matchedElementIds = overlappingElements.map { it.id },
                matchedContainerIds = overlappingElements.map { it.containerId }.filter { it.isNotBlank() }.distinct(),
                accessibilityUniqueIds = overlappingElements.map { it.accessibilityUniqueId }.filter { it.isNotBlank() }.distinct(),
                accessibilityLabels = overlappingElements.map { it.accessibilityLabel }.filter { it.isNotBlank() }.distinct(),
            )
        }
    }

    private fun scopeInteractionRegions(
        type: String,
        members: List<UiElementObservation>,
    ): List<InteractionRegion> {
        val merged =
            members.flatMap { it.interactionRegions }
                .distinctBy { "${it.leftFraction}|${it.topFraction}|${it.rightFraction}|${it.bottomFraction}|${it.label}" }
                .sortedByDescending { it.score }
                .take(4)
        if (merged.isNotEmpty()) return merged
        return when {
            type.contains("conversation") || type.contains("row") ->
                listOf(
                    InteractionRegion(0.12f, 0.2f, 0.62f, 0.72f, score = 0.92f, label = "row_primary"),
                    InteractionRegion(0.18f, 0.22f, 0.82f, 0.74f, score = 0.74f, label = "row_center"),
                )

            type.contains("detail") || type.contains("container") ->
                listOf(
                    InteractionRegion(0.18f, 0.18f, 0.86f, 0.7f, score = 0.88f, label = "scope_body"),
                    InteractionRegion(0.28f, 0.26f, 0.68f, 0.56f, score = 0.76f, label = "scope_focus"),
                )

            else ->
                listOf(
                    InteractionRegion(0.2f, 0.2f, 0.8f, 0.8f, score = 0.82f, label = "scope_center"),
                )
        }
    }

    private fun bitmapDensity(
        bitmap: Bitmap,
        rect: Rect,
    ): Float {
        if (rect.isEmpty) return 0f
        val stepX = maxOf(4, rect.width() / 18)
        val stepY = maxOf(4, rect.height() / 12)
        var samples = 0
        var contrastScore = 0f
        var chromaScore = 0f
        var previousLuma = -1
        for (y in rect.top until rect.bottom step stepY) {
            for (x in rect.left until rect.right step stepX) {
                val safeX = x.coerceIn(0, bitmap.width - 1)
                val safeY = y.coerceIn(0, bitmap.height - 1)
                val pixel = bitmap.getPixel(safeX, safeY)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val luma = (0.299f * r + 0.587f * g + 0.114f * b)
                val chroma = (maxOf(r, g, b) - minOf(r, g, b)) / 255f
                if (previousLuma >= 0) {
                    contrastScore += abs(luma - previousLuma) / 255f
                }
                chromaScore += chroma
                previousLuma = luma.toInt()
                samples += 1
            }
        }
        if (samples == 0) return 0f
        return ((contrastScore / samples.toFloat()) * 0.62f + (chromaScore / samples.toFloat()) * 0.38f).coerceIn(0f, 1f)
    }

    private fun buildRegionSignature(
        layer: String,
        type: String,
        descriptorTokens: List<String>,
    ): String =
        listOf(layer, type, descriptorTokens.joinToString("|")).joinToString(":").take(96)

    private fun buildSpatialSignature(
        rect: Rect?,
        width: Int,
        height: Int,
    ): String {
        if (rect == null || width <= 0 || height <= 0) return ""
        val left = (rect.left.toFloat() / width.toFloat()).coerceIn(0f, 1f)
        val top = (rect.top.toFloat() / height.toFloat()).coerceIn(0f, 1f)
        val right = (rect.right.toFloat() / width.toFloat()).coerceIn(0f, 1f)
        val bottom = (rect.bottom.toFloat() / height.toFloat()).coerceIn(0f, 1f)
        return "l=${"%.2f".format(left)},t=${"%.2f".format(top)},r=${"%.2f".format(right)},b=${"%.2f".format(bottom)}"
    }

    private fun parseBounds(bounds: String): Rect? {
        val values = Regex("""-?\d+""").findAll(bounds).map { it.value.toInt() }.toList()
        if (values.size < 4) return null
        return Rect(values[0], values[1], values[2], values[3])
    }

    private fun rectToBounds(rect: Rect): String = "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]"

    private fun unionRect(rects: List<Rect>): Rect =
        Rect(
            rects.minOf { it.left },
            rects.minOf { it.top },
            rects.maxOf { it.right },
            rects.maxOf { it.bottom },
        )

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
        val base = (minOf(a.width() * a.height(), b.width() * b.height())).coerceAtLeast(1)
        return area.toFloat() / base.toFloat()
    }
}
