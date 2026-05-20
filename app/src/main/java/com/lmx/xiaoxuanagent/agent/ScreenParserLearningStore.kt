package com.lmx.xiaoxuanagent.agent

import android.graphics.Bitmap
import android.graphics.Rect
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

private data class ScreenParserPrior(
    val priorId: String,
    val packageName: String,
    val pageState: String,
    val roleHint: String,
    val objectType: String,
    val descriptorTokens: List<String>,
    val leftFraction: Float,
    val topFraction: Float,
    val rightFraction: Float,
    val bottomFraction: Float,
    val confidence: Float,
    val successCount: Int,
    val tapPattern: String = "",
    val updatedAtMs: Long = 0L,
)

internal object ScreenParserLearningStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "screen_parser_learning.json"
    private const val MAX_PRIORS = 240
    private val lock = Any()
    private val priors = ArrayDeque<ScreenParserPrior>()
    private var hydrated = false

    fun suggest(
        bitmap: Bitmap,
        observation: ScreenObservation,
        visualObjects: List<VisualObjectObservation>,
    ): List<ScreenParserRegion> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val liveTokens =
                (
                    observation.topTexts +
                        observation.structureHints +
                        observation.elements.flatMap { it.descriptorTokens + it.visualDescriptorTokens + it.neighborTexts } +
                        observation.elements.flatMap { listOf(it.text, it.accessibilityLabel, it.roleHint, it.containerTitle, it.collectionPosition) } +
                        visualObjects.flatMap { it.descriptorTokens + it.contextHints + listOf(it.label, it.type, it.roleHint) }
                ).map(::normalizeToken)
                    .filter { it.length >= 2 }
                    .distinct()
            val ranked =
                priors
                    .asSequence()
                    .filter { it.packageName == observation.packageName }
                    .mapNotNull { prior ->
                        val overlap = prior.descriptorTokens.count { token -> liveTokens.contains(token) }
                        val roleBoost = if (prior.roleHint.isNotBlank() && liveTokens.any { it.contains(prior.roleHint, ignoreCase = true) }) 2 else 0
                        val pageBoost =
                            when {
                                prior.pageState.isBlank() -> 0
                                prior.pageState.equals(observation.pageState, ignoreCase = true) -> 3
                                observation.pageState.contains(prior.pageState, ignoreCase = true) -> 2
                                else -> 0
                            }
                        val score = overlap + roleBoost + pageBoost + prior.successCount.coerceAtMost(3)
                        if (score < 3) {
                            null
                        } else {
                            prior to score
                        }
                    }.sortedByDescending { it.second }
                    .take(6)
                    .toList()
            ranked.mapIndexed { index, (prior, score) ->
                val rect =
                    Rect(
                        (bitmap.width * prior.leftFraction).toInt().coerceAtLeast(0),
                        (bitmap.height * prior.topFraction).toInt().coerceAtLeast(0),
                        (bitmap.width * prior.rightFraction).toInt().coerceIn(1, bitmap.width),
                        (bitmap.height * prior.bottomFraction).toInt().coerceIn(1, bitmap.height),
                    )
                val descriptorTokens = prior.descriptorTokens.take(8)
                ScreenParserRegion(
                    id = "parser_learned_${index + 1}_${prior.priorId.takeLast(6)}",
                    type = prior.objectType.ifBlank { "learned_scope" },
                    label = descriptorTokens.firstOrNull().orEmpty(),
                    bounds = rectToBounds(rect),
                    confidence = (prior.confidence + score * 0.03f).coerceIn(0.54f, 0.97f),
                    source = "screen_parser_learned",
                    parserLayer = "learned",
                    searchTier = "local",
                    roleHint = prior.roleHint,
                    contextHints = descriptorTokens.take(4),
                    descriptorTokens = descriptorTokens,
                    textFree = descriptorTokens.isEmpty(),
                    tapPattern = prior.tapPattern,
                    tapHotspotX = 0.5f,
                    tapHotspotY = 0.5f,
                    interactionRegions = defaultInteractionRegions(prior.objectType, prior.roleHint),
                    visualSignature = buildSignature(prior.objectType, descriptorTokens),
                    spatialSignature = buildSpatialSignature(rect, bitmap.width, bitmap.height),
                )
            }
        }

    fun recordVerified(
        before: ScreenObservation,
        after: ScreenObservation?,
        executionResult: AgentExecutionResult,
        entityDescriptor: GroundingEntityDescriptor,
    ) {
        val bounds = parseBounds(executionResult.resolvedTargetBounds) ?: return
        val baseObservation = after ?: before
        val extent = deriveScreenExtent(baseObservation, bounds)
        if (extent.width() <= 0 || extent.height() <= 0) return
        val descriptorTokens =
            (
                executionResult.targetDescriptorTokens +
                    executionResult.targetVisualDescriptorTokens +
                    entityDescriptor.anchors +
                    entityDescriptor.contextTokens +
                    listOf(
                        executionResult.expectedEntityHint,
                        executionResult.resolvedTargetText,
                        executionResult.targetObjectType,
                        entityDescriptor.type,
                    )
            ).map(::normalizeToken)
                .filter { it.length >= 2 }
                .distinct()
                .take(10)
        if (descriptorTokens.isEmpty()) return
        val width = extent.width().toFloat().coerceAtLeast(1f)
        val height = extent.height().toFloat().coerceAtLeast(1f)
        val now = System.currentTimeMillis()
        val prior =
            ScreenParserPrior(
                priorId = buildPriorId(baseObservation.packageName, executionResult.targetObjectType, descriptorTokens),
                packageName = baseObservation.packageName,
                pageState = baseObservation.pageState,
                roleHint = executionResult.targetObjectType,
                objectType = executionResult.targetObjectType,
                descriptorTokens = descriptorTokens,
                leftFraction = ((bounds.left - extent.left).toFloat() / width).coerceIn(0f, 0.95f),
                topFraction = ((bounds.top - extent.top).toFloat() / height).coerceIn(0f, 0.95f),
                rightFraction = ((bounds.right - extent.left).toFloat() / width).coerceIn(0.05f, 1f),
                bottomFraction = ((bounds.bottom - extent.top).toFloat() / height).coerceIn(0.05f, 1f),
                confidence = 0.72f,
                successCount = 1,
                tapPattern = executionResult.targetObjectType,
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
                        roleHint = prior.roleHint.ifBlank { existing.roleHint },
                        objectType = prior.objectType.ifBlank { existing.objectType },
                        descriptorTokens = (existing.descriptorTokens + prior.descriptorTokens).distinct().take(10),
                        leftFraction = average(existing.leftFraction, prior.leftFraction),
                        topFraction = average(existing.topFraction, prior.topFraction),
                        rightFraction = average(existing.rightFraction, prior.rightFraction),
                        bottomFraction = average(existing.bottomFraction, prior.bottomFraction),
                        confidence = (existing.confidence + 0.04f).coerceAtMost(0.96f),
                        successCount = existing.successCount + 1,
                        tapPattern = prior.tapPattern.ifBlank { existing.tapPattern },
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

    private fun ScreenParserPrior.toJson(): JSONObject =
        JSONObject().apply {
            put("prior_id", priorId)
            put("package_name", packageName)
            put("page_state", pageState)
            put("role_hint", roleHint)
            put("object_type", objectType)
            put("descriptor_tokens", JSONArray(descriptorTokens))
            put("left_fraction", leftFraction.toDouble())
            put("top_fraction", topFraction.toDouble())
            put("right_fraction", rightFraction.toDouble())
            put("bottom_fraction", bottomFraction.toDouble())
            put("confidence", confidence.toDouble())
            put("success_count", successCount)
            put("tap_pattern", tapPattern)
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toPrior(): ScreenParserPrior =
        ScreenParserPrior(
            priorId = optString("prior_id"),
            packageName = optString("package_name"),
            pageState = optString("page_state"),
            roleHint = optString("role_hint"),
            objectType = optString("object_type"),
            descriptorTokens =
                optJSONArray("descriptor_tokens")
                    ?.let { array -> buildList { for (index in 0 until array.length()) add(array.optString(index)) } }
                    .orEmpty(),
            leftFraction = optDouble("left_fraction", 0.1).toFloat(),
            topFraction = optDouble("top_fraction", 0.1).toFloat(),
            rightFraction = optDouble("right_fraction", 0.9).toFloat(),
            bottomFraction = optDouble("bottom_fraction", 0.9).toFloat(),
            confidence = optDouble("confidence", 0.64).toFloat(),
            successCount = optInt("success_count", 1),
            tapPattern = optString("tap_pattern"),
            updatedAtMs = optLong("updated_at_ms"),
        )

    private fun buildPriorId(
        packageName: String,
        objectType: String,
        descriptorTokens: List<String>,
    ): String = "${packageName}_${objectType}_${descriptorTokens.take(4).joinToString("_")}".lowercase()

    private fun deriveScreenExtent(
        observation: ScreenObservation,
        fallback: Rect,
    ): Rect {
        val rects = observation.elements.mapNotNull { parseBounds(it.bounds) } + fallback
        return Rect(
            rects.minOf { it.left }.coerceAtMost(fallback.left),
            rects.minOf { it.top }.coerceAtMost(fallback.top),
            rects.maxOf { it.right }.coerceAtLeast(fallback.right),
            rects.maxOf { it.bottom }.coerceAtLeast(fallback.bottom),
        )
    }

    private fun parseBounds(bounds: String): Rect? {
        val values = Regex("""-?\d+""").findAll(bounds).map { it.value.toInt() }.toList()
        if (values.size < 4) return null
        return Rect(values[0], values[1], values[2], values[3])
    }

    private fun rectToBounds(rect: Rect): String = "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]"

    private fun average(
        a: Float,
        b: Float,
    ): Float = ((a + b) / 2f).coerceIn(0f, 1f)

    private fun buildSignature(
        type: String,
        descriptorTokens: List<String>,
    ): String = listOf(type, descriptorTokens.joinToString("|")).joinToString(":").take(120)

    private fun defaultInteractionRegions(
        objectType: String,
        roleHint: String,
    ): List<InteractionRegion> =
        buildList {
            add(InteractionRegion(0.18f, 0.18f, 0.82f, 0.82f, 0.58f, "learned_body"))
            if (objectType.contains("icon", ignoreCase = true) || roleHint == "dismiss") {
                add(InteractionRegion(0.24f, 0.16f, 0.56f, 0.52f, 0.76f, "learned_icon"))
            }
            if (objectType.contains("card", ignoreCase = true) || roleHint == "detail") {
                add(InteractionRegion(0.18f, 0.16f, 0.86f, 0.72f, 0.8f, "learned_card"))
            }
        }

    private fun buildSpatialSignature(
        rect: Rect,
        width: Int,
        height: Int,
    ): String {
        val normalizedWidth = width.coerceAtLeast(1)
        val normalizedHeight = height.coerceAtLeast(1)
        val left = rect.left.toFloat() / normalizedWidth.toFloat()
        val top = rect.top.toFloat() / normalizedHeight.toFloat()
        val right = rect.right.toFloat() / normalizedWidth.toFloat()
        val bottom = rect.bottom.toFloat() / normalizedHeight.toFloat()
        return "${left.format()}:${top.format()}:${right.format()}:${bottom.format()}"
    }

    private fun Float.format(): String = String.format("%.3f", this)

    private fun normalizeToken(raw: String): String = raw.replace("\\s+".toRegex(), " ").trim()
}
