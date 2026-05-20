package com.lmx.xiaoxuanagent.agent

import android.graphics.Bitmap
import android.graphics.Rect
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

private data class ScreenParserModelPrior(
    val priorId: String,
    val packageName: String,
    val pageState: String,
    val objectType: String,
    val roleHint: String,
    val label: String,
    val descriptorTokens: List<String>,
    val visualSignatures: List<String>,
    val spatialSignature: String,
    val parserLayer: String,
    val searchTier: String,
    val leftFraction: Float,
    val topFraction: Float,
    val rightFraction: Float,
    val bottomFraction: Float,
    val confidence: Float,
    val successCount: Int,
    val updatedAtMs: Long,
)

internal object ScreenParserModelStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "screen_parser_model_store.json"
    private const val MAX_PRIORS = 320
    private val lock = Any()
    private val priors = ArrayDeque<ScreenParserModelPrior>()
    private var hydrated = false

    fun suggest(
        bitmap: Bitmap,
        observation: ScreenObservation,
        visualObjects: List<VisualObjectObservation>,
        parserRegions: List<ScreenParserRegion>,
    ): List<ScreenParserRegion> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val liveTokens =
                (
                    observation.topTexts +
                        observation.structureHints +
                        observation.elements.flatMap {
                            it.descriptorTokens +
                                it.visualDescriptorTokens +
                                it.neighborTexts +
                                listOf(
                                    it.text,
                                    it.accessibilityLabel,
                                    it.roleHint,
                                    it.containerTitle,
                                    it.collectionPosition,
                                )
                        } +
                        visualObjects.flatMap { it.descriptorTokens + it.contextHints + listOf(it.label, it.type, it.roleHint) } +
                        parserRegions.flatMap { it.descriptorTokens + it.contextHints + listOf(it.label, it.type, it.roleHint) }
                ).map(::normalizeToken)
                    .filter { it.length >= 2 }
                    .distinct()
            val liveVisualSignatures =
                (
                    visualObjects.map { it.visualSignature } +
                        parserRegions.map { it.visualSignature }
                ).filter { it.isNotBlank() }.toSet()
            val liveSpatialSignatures =
                (
                    visualObjects.map { it.spatialSignature } +
                        parserRegions.map { it.spatialSignature }
                ).filter { it.isNotBlank() }.toSet()
            priors
                .asSequence()
                .filter { it.packageName == observation.packageName }
                .mapNotNull { prior ->
                    val tokenOverlap = prior.descriptorTokens.count { liveTokens.contains(it) }
                    val visualBoost = prior.visualSignatures.count { liveVisualSignatures.contains(it) } * 3
                    val spatialBoost = if (prior.spatialSignature.isNotBlank() && liveSpatialSignatures.contains(prior.spatialSignature)) 2 else 0
                    val roleBoost =
                        if (prior.roleHint.isNotBlank() && liveTokens.any { it.contains(prior.roleHint, ignoreCase = true) }) {
                            2
                        } else {
                            0
                        }
                    val pageBoost =
                        when {
                            prior.pageState.isBlank() -> 0
                            prior.pageState.equals(observation.pageState, ignoreCase = true) -> 3
                            observation.pageState.contains(prior.pageState, ignoreCase = true) -> 2
                            else -> 0
                        }
                    val score = tokenOverlap * 2 + visualBoost + spatialBoost + roleBoost + pageBoost + prior.successCount.coerceAtMost(4)
                    if (score < 7) {
                        null
                    } else {
                        prior to score
                    }
                }.sortedByDescending { it.second }
                .take(8)
                .mapIndexed { index, (prior, score) ->
                    val rect =
                        Rect(
                            (bitmap.width * prior.leftFraction).toInt().coerceAtLeast(0),
                            (bitmap.height * prior.topFraction).toInt().coerceAtLeast(0),
                            (bitmap.width * prior.rightFraction).toInt().coerceIn(1, bitmap.width),
                            (bitmap.height * prior.bottomFraction).toInt().coerceIn(1, bitmap.height),
                        )
                    val descriptorTokens = prior.descriptorTokens.take(10)
                    ScreenParserRegion(
                        id = "parser_model_${index + 1}_${prior.priorId.takeLast(6)}",
                        type = prior.objectType.ifBlank { "model_scope" },
                        label = prior.label.ifBlank { descriptorTokens.firstOrNull().orEmpty() },
                        bounds = rectToBounds(rect),
                        confidence = (prior.confidence + score * 0.02f).coerceIn(0.56f, 0.98f),
                        source = "screen_parser_model",
                        parserLayer = if (prior.parserLayer.isNotBlank()) prior.parserLayer else "model",
                        searchTier = if (prior.searchTier.isNotBlank()) prior.searchTier else "local_model",
                        roleHint = prior.roleHint,
                    contextHints = descriptorTokens.take(4),
                    descriptorTokens = descriptorTokens,
                    textFree = prior.label.isBlank() && descriptorTokens.isEmpty(),
                    tapPattern = defaultTapPattern(prior.objectType, prior.roleHint),
                    tapHotspotX = defaultHotspotX(prior.objectType, prior.roleHint),
                    tapHotspotY = defaultHotspotY(prior.objectType, prior.roleHint),
                    interactionRegions = defaultInteractionRegions(prior.objectType, prior.roleHint),
                        visualSignature = buildModelSignature(prior.objectType, descriptorTokens + prior.visualSignatures.take(2)),
                        spatialSignature = buildSpatialSignature(rect, bitmap.width, bitmap.height),
                    )
                }.toList()
        }

    fun recordVerified(
        observation: ScreenObservation,
        executionResult: AgentExecutionResult,
        entityDescriptor: GroundingEntityDescriptor,
        visualContext: VisualPerceptionContext?,
    ) {
        val bounds = parseBounds(executionResult.resolvedTargetBounds) ?: return
        val extent = deriveScreenExtent(observation, bounds)
        if (extent.width() <= 0 || extent.height() <= 0) return
        val descriptorTokens =
            (
                executionResult.targetDescriptorTokens +
                    executionResult.targetVisualDescriptorTokens +
                    executionResult.targetContextHints +
                    entityDescriptor.anchors +
                    entityDescriptor.contextTokens +
                    entityDescriptor.visualTokens +
                    listOf(
                        executionResult.expectedEntityHint,
                        executionResult.resolvedTargetText,
                        executionResult.targetObjectType,
                        entityDescriptor.type,
                    )
            ).map(::normalizeToken)
                .filter { it.length >= 2 }
                .distinct()
                .take(12)
        if (descriptorTokens.isEmpty()) return
        val contextVisualSignatures =
            buildList {
                executionResult.resolvedTargetVisualSignature.takeIf { it.isNotBlank() }?.let(::add)
                visualContext
                    ?.visualObjects
                    ?.filter { objectObservation ->
                        parseBounds(objectObservation.bounds)?.let { overlapScore(bounds, it) >= 0.24f } == true ||
                            objectObservation.matchedElementId == executionResult.resolvedElementId ||
                            objectObservation.matchedContainerId == executionResult.resolvedContainerId
                    }?.mapNotNull { it.visualSignature.takeIf(String::isNotBlank) }
                    ?.let(::addAll)
                visualContext
                    ?.parserRegions
                    ?.filter { region ->
                        region.id == executionResult.resolvedParserRegionId ||
                            region.matchedContainerIds.contains(executionResult.resolvedContainerId) ||
                            parseBounds(region.bounds)?.let { overlapScore(bounds, it) >= 0.24f } == true
                    }?.mapNotNull { it.visualSignature.takeIf(String::isNotBlank) }
                    ?.let(::addAll)
            }.distinct().take(6)
        val width = extent.width().toFloat().coerceAtLeast(1f)
        val height = extent.height().toFloat().coerceAtLeast(1f)
        val now = System.currentTimeMillis()
        val prior =
            ScreenParserModelPrior(
                priorId = buildPriorId(observation.packageName, executionResult.targetObjectType, descriptorTokens),
                packageName = observation.packageName,
                pageState = observation.pageState,
                objectType = executionResult.targetObjectType.ifBlank { entityDescriptor.type },
                roleHint = executionResult.targetObjectType,
                label = executionResult.resolvedTargetText,
                descriptorTokens = descriptorTokens,
                visualSignatures = contextVisualSignatures,
                spatialSignature = executionResult.resolvedTargetSpatialSignature,
                parserLayer = "model",
                searchTier = "local_model",
                leftFraction = ((bounds.left - extent.left).toFloat() / width).coerceIn(0f, 0.95f),
                topFraction = ((bounds.top - extent.top).toFloat() / height).coerceIn(0f, 0.95f),
                rightFraction = ((bounds.right - extent.left).toFloat() / width).coerceIn(0.05f, 1f),
                bottomFraction = ((bounds.bottom - extent.top).toFloat() / height).coerceIn(0.05f, 1f),
                confidence = 0.78f,
                successCount = 1,
                updatedAtMs = now,
            )
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val existingIndex =
                priors.indexOfFirst {
                    it.priorId == prior.priorId &&
                        it.packageName == prior.packageName
                }
            if (existingIndex >= 0) {
                val existing = priors.elementAt(existingIndex)
                priors.remove(existing)
                priors.addFirst(
                    existing.copy(
                        pageState = prior.pageState.ifBlank { existing.pageState },
                        objectType = prior.objectType.ifBlank { existing.objectType },
                        roleHint = prior.roleHint.ifBlank { existing.roleHint },
                        label = prior.label.ifBlank { existing.label },
                        descriptorTokens = (existing.descriptorTokens + prior.descriptorTokens).distinct().take(12),
                        visualSignatures = (existing.visualSignatures + prior.visualSignatures).distinct().take(6),
                        spatialSignature = prior.spatialSignature.ifBlank { existing.spatialSignature },
                        leftFraction = average(existing.leftFraction, prior.leftFraction),
                        topFraction = average(existing.topFraction, prior.topFraction),
                        rightFraction = average(existing.rightFraction, prior.rightFraction),
                        bottomFraction = average(existing.bottomFraction, prior.bottomFraction),
                        confidence = (existing.confidence + 0.05f).coerceAtMost(0.98f),
                        successCount = existing.successCount + 1,
                        updatedAtMs = now,
                    ),
                )
            } else {
                priors.addFirst(prior)
            }
            trimUnlocked()
            persistUnlocked()
        }
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = storeFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("priors") ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toPrior()?.let(priors::addLast)
            }
            trimUnlocked()
        }
    }

    private fun persistUnlocked() {
        val file = storeFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "priors",
                    JSONArray().apply {
                        priors.forEach { put(it.toJson()) }
                    },
                )
            }.toString(2),
        )
    }

    private fun trimUnlocked() {
        while (priors.size > MAX_PRIORS) {
            priors.removeLast()
        }
    }

    private fun storeFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, STORE_DIR), STORE_FILE)
    }

    private fun ScreenParserModelPrior.toJson(): JSONObject =
        JSONObject().apply {
            put("prior_id", priorId)
            put("package_name", packageName)
            put("page_state", pageState)
            put("object_type", objectType)
            put("role_hint", roleHint)
            put("label", label)
            put("descriptor_tokens", JSONArray(descriptorTokens))
            put("visual_signatures", JSONArray(visualSignatures))
            put("spatial_signature", spatialSignature)
            put("parser_layer", parserLayer)
            put("search_tier", searchTier)
            put("left_fraction", leftFraction.toDouble())
            put("top_fraction", topFraction.toDouble())
            put("right_fraction", rightFraction.toDouble())
            put("bottom_fraction", bottomFraction.toDouble())
            put("confidence", confidence.toDouble())
            put("success_count", successCount)
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toPrior(): ScreenParserModelPrior =
        ScreenParserModelPrior(
            priorId = optString("prior_id"),
            packageName = optString("package_name"),
            pageState = optString("page_state"),
            objectType = optString("object_type"),
            roleHint = optString("role_hint"),
            label = optString("label"),
            descriptorTokens =
                optJSONArray("descriptor_tokens")
                    ?.let { array -> buildList { for (index in 0 until array.length()) add(array.optString(index)) } }
                    .orEmpty(),
            visualSignatures =
                optJSONArray("visual_signatures")
                    ?.let { array -> buildList { for (index in 0 until array.length()) add(array.optString(index)) } }
                    .orEmpty(),
            spatialSignature = optString("spatial_signature"),
            parserLayer = optString("parser_layer"),
            searchTier = optString("search_tier"),
            leftFraction = optDouble("left_fraction", 0.1).toFloat(),
            topFraction = optDouble("top_fraction", 0.1).toFloat(),
            rightFraction = optDouble("right_fraction", 0.9).toFloat(),
            bottomFraction = optDouble("bottom_fraction", 0.9).toFloat(),
            confidence = optDouble("confidence", 0.72).toFloat(),
            successCount = optInt("success_count", 1),
            updatedAtMs = optLong("updated_at_ms"),
        )

    private fun normalizeToken(value: String): String =
        value.trim().lowercase().replace(Regex("""\s+"""), " ")

    private fun buildModelSignature(
        objectType: String,
        descriptorTokens: List<String>,
    ): String =
        (listOf(objectType) + descriptorTokens.map(::normalizeToken))
            .filter { it.isNotBlank() }
            .take(6)
            .joinToString("|")

    private fun buildPriorId(
        packageName: String,
        objectType: String,
        descriptorTokens: List<String>,
    ): String = "${packageName}_${objectType}_${descriptorTokens.take(4).joinToString("_")}".lowercase()

    private fun parseBounds(bounds: String): Rect? {
        val values = Regex("""-?\d+""").findAll(bounds).map { it.value.toInt() }.toList()
        if (values.size < 4) return null
        return Rect(values[0], values[1], values[2], values[3])
    }

    private fun rectToBounds(rect: Rect): String = "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]"

    private fun deriveScreenExtent(
        observation: ScreenObservation,
        fallbackBounds: Rect,
    ): Rect {
        val rects = observation.elements.mapNotNull { parseBounds(it.bounds) }
        if (rects.isEmpty()) return fallbackBounds
        val left = rects.minOf { it.left }.coerceAtMost(fallbackBounds.left)
        val top = rects.minOf { it.top }.coerceAtMost(fallbackBounds.top)
        val right = rects.maxOf { it.right }.coerceAtLeast(fallbackBounds.right)
        val bottom = rects.maxOf { it.bottom }.coerceAtLeast(fallbackBounds.bottom)
        return Rect(left, top, right, bottom)
    }

    private fun overlapScore(a: Rect, b: Rect): Float {
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

    private fun buildSpatialSignature(
        rect: Rect,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): String {
        val horizontal =
            when {
                rect.exactCenterX() <= bitmapWidth * 0.33f -> "left"
                rect.exactCenterX() >= bitmapWidth * 0.66f -> "right"
                else -> "center"
            }
        val vertical =
            when {
                rect.exactCenterY() <= bitmapHeight * 0.22f -> "top"
                rect.exactCenterY() >= bitmapHeight * 0.78f -> "bottom"
                else -> "middle"
            }
        val widthBand =
            when {
                rect.width() >= bitmapWidth * 0.52f -> "wide"
                rect.width() <= bitmapWidth * 0.18f -> "narrow"
                else -> "medium"
            }
        val heightBand =
            when {
                rect.height() >= bitmapHeight * 0.24f -> "tall"
                rect.height() <= bitmapHeight * 0.1f -> "short"
                else -> "normal"
            }
        return "$vertical:$horizontal:$widthBand:$heightBand"
    }

    private fun average(
        a: Float,
        b: Float,
    ): Float = ((a + b) / 2f).coerceIn(0f, 1f)

    private fun defaultTapPattern(
        objectType: String,
        roleHint: String,
    ): String =
        when {
            roleHint == "dismiss" -> "corner_action"
            objectType.contains("conversation", ignoreCase = true) -> "list_row"
            objectType.contains("route", ignoreCase = true) -> "list_row"
            objectType.contains("card", ignoreCase = true) -> "card_body"
            objectType.contains("icon", ignoreCase = true) -> "icon_cluster"
            else -> "container_body"
        }

    private fun defaultHotspotX(
        objectType: String,
        roleHint: String,
    ): Float =
        when {
            roleHint == "dismiss" -> 0.78f
            objectType.contains("icon", ignoreCase = true) -> 0.42f
            else -> 0.5f
        }

    private fun defaultHotspotY(
        objectType: String,
        roleHint: String,
    ): Float =
        when {
            roleHint == "dismiss" -> 0.22f
            objectType.contains("card", ignoreCase = true) -> 0.38f
            else -> 0.5f
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
}
