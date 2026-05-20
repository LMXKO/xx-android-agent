package com.lmx.xiaoxuanagent.agent

import android.graphics.Rect
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import java.io.File
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

private data class ActionRegionHeadPrior(
    val priorId: String,
    val packageName: String,
    val pageState: String,
    val roleHint: String,
    val objectType: String,
    val descriptorTokens: List<String>,
    val source: String,
    val centerXFraction: Float,
    val centerYFraction: Float,
    val widthFraction: Float,
    val heightFraction: Float,
    val successCount: Int,
    val updatedAtMs: Long,
)

internal object ActionRegionHeadStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "action_region_head_store.json"
    private const val MAX_PRIORS = 420
    private val lock = Any()
    private val priors = ArrayDeque<ActionRegionHeadPrior>()
    private var hydrated = false

    fun scoreCandidate(
        packageName: String,
        pageState: String,
        roleHint: String,
        objectType: String,
        descriptorTokens: List<String>,
        textFree: Boolean,
        source: String,
        centerXFraction: Float,
        centerYFraction: Float,
        widthFraction: Float,
        heightFraction: Float,
    ): Float =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val currentTokens =
                (descriptorTokens + listOf(roleHint, objectType))
                    .map(::normalizeToken)
                    .filter { it.length >= 2 }
                    .distinct()
            priors
                .asSequence()
                .filter { it.packageName == packageName }
                .mapNotNull { prior ->
                    val descriptorOverlap = prior.descriptorTokens.count { currentTokens.contains(it) }
                    val roleBoost = if (prior.roleHint.isNotBlank() && prior.roleHint.equals(roleHint, ignoreCase = true)) 2 else 0
                    val objectBoost = if (prior.objectType.isNotBlank() && prior.objectType.equals(objectType, ignoreCase = true)) 2 else 0
                    val sourceBoost =
                        when {
                            prior.source.equals(source, ignoreCase = true) -> 2
                            source.startsWith("grid_") && prior.source.startsWith("grid_") -> 1
                            source.startsWith("learned_") && prior.source.startsWith("learned_") -> 1
                            else -> 0
                        }
                    val pageBoost =
                        when {
                            pageState.isBlank() || prior.pageState.isBlank() -> 0
                            prior.pageState.equals(pageState, ignoreCase = true) -> 2
                            else -> 0
                        }
                    val geometryDistance =
                        abs(prior.centerXFraction - centerXFraction) +
                            abs(prior.centerYFraction - centerYFraction) +
                            abs(prior.widthFraction - widthFraction) +
                            abs(prior.heightFraction - heightFraction)
                    val geometryScore =
                        when {
                            geometryDistance <= 0.18f -> 4
                            geometryDistance <= 0.34f -> 3
                            geometryDistance <= 0.5f -> 1
                            else -> 0
                        }
                    val score = descriptorOverlap * 2 + roleBoost + objectBoost + sourceBoost + pageBoost + geometryScore + prior.successCount.coerceAtMost(if (textFree) 6 else 4)
                    if (score < if (textFree) 6 else 7) null else score
                }.maxOrNull()
                ?.let { best ->
                    (0.08f + best * 0.035f).coerceAtMost(0.46f)
                } ?: 0f
        }

    fun suggestRegions(
        packageName: String,
        pageState: String,
        roleHint: String,
        objectType: String,
        descriptorTokens: List<String>,
        textFree: Boolean,
    ): List<InteractionRegion> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val currentTokens =
                (descriptorTokens + listOf(roleHint, objectType))
                    .map(::normalizeToken)
                    .filter { it.length >= 2 }
                    .distinct()
            priors
                .asSequence()
                .filter { it.packageName == packageName }
                .mapNotNull { prior ->
                    val tokenOverlap = prior.descriptorTokens.count { currentTokens.contains(it) }
                    val roleBoost = if (prior.roleHint.equals(roleHint, ignoreCase = true) && roleHint.isNotBlank()) 2 else 0
                    val objectBoost = if (prior.objectType.equals(objectType, ignoreCase = true) && objectType.isNotBlank()) 2 else 0
                    val pageBoost =
                        when {
                            pageState.isBlank() || prior.pageState.isBlank() -> 0
                            prior.pageState.equals(pageState, ignoreCase = true) -> 2
                            else -> 0
                        }
                    val rawScore = tokenOverlap * 2 + roleBoost + objectBoost + pageBoost + prior.successCount.coerceAtMost(if (textFree) 6 else 4)
                    if (rawScore < if (textFree) 5 else 6) {
                        null
                    } else {
                        InteractionRegion(
                            leftFraction = (prior.centerXFraction - prior.widthFraction / 2f).coerceIn(0f, 0.94f),
                            topFraction = (prior.centerYFraction - prior.heightFraction / 2f).coerceIn(0f, 0.94f),
                            rightFraction = (prior.centerXFraction + prior.widthFraction / 2f).coerceIn(0.06f, 1f),
                            bottomFraction = (prior.centerYFraction + prior.heightFraction / 2f).coerceIn(0.06f, 1f),
                            score = (0.42f + rawScore * 0.04f).coerceAtMost(0.96f),
                            label = "action_head_prior:${prior.source}",
                        )
                    }
                }.sortedByDescending { it.score }
                .take(6)
                .toList()
        }

    fun recordVerified(
        observation: ScreenObservation,
        executionResult: AgentExecutionResult,
    ) {
        val bounds = parseBounds(executionResult.resolvedTargetBounds) ?: return
        val pointX = executionResult.resolvedRegionHitX
        val pointY = executionResult.resolvedRegionHitY
        if (pointX <= 0f || pointY <= 0f) return
        val width = bounds.width().toFloat().coerceAtLeast(1f)
        val height = bounds.height().toFloat().coerceAtLeast(1f)
        val descriptorTokens =
            (
                executionResult.targetDescriptorTokens +
                    executionResult.targetVisualDescriptorTokens +
                    executionResult.targetContextHints +
                    listOf(executionResult.expectedEntityHint, executionResult.targetObjectType)
            ).map(::normalizeToken)
                .filter { it.length >= 2 }
                .distinct()
                .take(12)
        if (descriptorTokens.isEmpty()) return
        val prior =
            ActionRegionHeadPrior(
                priorId =
                    buildPriorId(
                        packageName = observation.packageName,
                        objectType = executionResult.targetObjectType,
                        source = executionResult.resolvedRegionHitSource,
                        descriptorTokens = descriptorTokens,
                    ),
                packageName = observation.packageName,
                pageState = observation.pageState,
                roleHint = executionResult.targetObjectType,
                objectType = executionResult.targetObjectType,
                descriptorTokens = descriptorTokens,
                source = executionResult.resolvedRegionHitSource,
                centerXFraction = ((pointX - bounds.left) / width).coerceIn(0.05f, 0.95f),
                centerYFraction = ((pointY - bounds.top) / height).coerceIn(0.05f, 0.95f),
                widthFraction = (bounds.width().toFloat() / deriveScreenExtent(observation, bounds).width().toFloat().coerceAtLeast(1f)).coerceIn(0.04f, 1f),
                heightFraction = (bounds.height().toFloat() / deriveScreenExtent(observation, bounds).height().toFloat().coerceAtLeast(1f)).coerceIn(0.04f, 1f),
                successCount = 1,
                updatedAtMs = System.currentTimeMillis(),
            )
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val existingIndex = priors.indexOfFirst { it.priorId == prior.priorId && it.packageName == prior.packageName }
            if (existingIndex >= 0) {
                val existing = priors.elementAt(existingIndex)
                priors.remove(existing)
                priors.addFirst(
                    existing.copy(
                        pageState = prior.pageState.ifBlank { existing.pageState },
                        roleHint = prior.roleHint.ifBlank { existing.roleHint },
                        objectType = prior.objectType.ifBlank { existing.objectType },
                        descriptorTokens = (existing.descriptorTokens + prior.descriptorTokens).distinct().take(12),
                        source = prior.source.ifBlank { existing.source },
                        centerXFraction = average(existing.centerXFraction, prior.centerXFraction),
                        centerYFraction = average(existing.centerYFraction, prior.centerYFraction),
                        widthFraction = average(existing.widthFraction, prior.widthFraction),
                        heightFraction = average(existing.heightFraction, prior.heightFraction),
                        successCount = existing.successCount + 1,
                        updatedAtMs = prior.updatedAtMs,
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
                    JSONArray().apply { priors.forEach { put(it.toJson()) } },
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

    private fun ActionRegionHeadPrior.toJson(): JSONObject =
        JSONObject().apply {
            put("prior_id", priorId)
            put("package_name", packageName)
            put("page_state", pageState)
            put("role_hint", roleHint)
            put("object_type", objectType)
            put("descriptor_tokens", JSONArray(descriptorTokens))
            put("source", source)
            put("center_x_fraction", centerXFraction.toDouble())
            put("center_y_fraction", centerYFraction.toDouble())
            put("width_fraction", widthFraction.toDouble())
            put("height_fraction", heightFraction.toDouble())
            put("success_count", successCount)
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toPrior(): ActionRegionHeadPrior =
        ActionRegionHeadPrior(
            priorId = optString("prior_id"),
            packageName = optString("package_name"),
            pageState = optString("page_state"),
            roleHint = optString("role_hint"),
            objectType = optString("object_type"),
            descriptorTokens =
                optJSONArray("descriptor_tokens")
                    ?.let { array -> buildList { for (index in 0 until array.length()) add(array.optString(index)) } }
                    .orEmpty(),
            source = optString("source"),
            centerXFraction = optDouble("center_x_fraction", 0.5).toFloat(),
            centerYFraction = optDouble("center_y_fraction", 0.5).toFloat(),
            widthFraction = optDouble("width_fraction", 0.25).toFloat(),
            heightFraction = optDouble("height_fraction", 0.25).toFloat(),
            successCount = optInt("success_count", 1),
            updatedAtMs = optLong("updated_at_ms"),
        )

    private fun buildPriorId(
        packageName: String,
        objectType: String,
        source: String,
        descriptorTokens: List<String>,
    ): String = "${packageName}_${objectType}_${source}_${descriptorTokens.take(4).joinToString("_")}".lowercase()

    private fun parseBounds(bounds: String): Rect? {
        val values = Regex("""-?\d+""").findAll(bounds).map { it.value.toInt() }.toList()
        if (values.size < 4) return null
        return Rect(values[0], values[1], values[2], values[3])
    }

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

    private fun normalizeToken(value: String): String =
        value.trim().lowercase().replace(Regex("""\s+"""), " ")

    private fun average(
        a: Float,
        b: Float,
    ): Float = ((a + b) / 2f).coerceIn(0f, 1f)
}
