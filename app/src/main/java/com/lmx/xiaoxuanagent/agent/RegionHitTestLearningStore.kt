package com.lmx.xiaoxuanagent.agent

import android.graphics.Rect
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

private data class RegionHitPrior(
    val priorId: String,
    val packageName: String,
    val pageState: String,
    val roleHint: String,
    val objectType: String,
    val descriptorTokens: List<String>,
    val source: String,
    val centerXFraction: Float,
    val centerYFraction: Float,
    val radiusFraction: Float,
    val successCount: Int,
    val updatedAtMs: Long = 0L,
)

internal object RegionHitTestLearningStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "region_hit_learning.json"
    private const val MAX_PRIORS = 320
    private val lock = Any()
    private val priors = ArrayDeque<RegionHitPrior>()
    private var hydrated = false

    fun suggest(
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
                    val descriptorOverlap = prior.descriptorTokens.count { currentTokens.contains(it) }
                    val roleBoost = if (prior.roleHint.isNotBlank() && prior.roleHint.equals(roleHint, ignoreCase = true)) 2 else 0
                    val objectBoost = if (prior.objectType.isNotBlank() && prior.objectType.equals(objectType, ignoreCase = true)) 2 else 0
                    val pageBoost =
                        when {
                            pageState.isBlank() || prior.pageState.isBlank() -> 0
                            prior.pageState.equals(pageState, ignoreCase = true) -> 2
                            else -> 0
                        }
                    val score = descriptorOverlap + roleBoost + objectBoost + pageBoost + prior.successCount.coerceAtMost(3)
                    if (score < if (textFree) 2 else 3) null else prior to score
                }.sortedByDescending { it.second }
                .take(4)
                .map { (prior, score) ->
                    val radius = prior.radiusFraction.coerceIn(0.12f, 0.36f)
                    InteractionRegion(
                        leftFraction = (prior.centerXFraction - radius).coerceIn(0f, 0.88f),
                        topFraction = (prior.centerYFraction - radius).coerceIn(0f, 0.88f),
                        rightFraction = (prior.centerXFraction + radius).coerceIn(0.12f, 1f),
                        bottomFraction = (prior.centerYFraction + radius).coerceIn(0.12f, 1f),
                        score = (0.58f + score * 0.04f).coerceAtMost(0.98f),
                        label = "learned_${prior.source.ifBlank { "region" }}",
                    )
                }.toList()
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
        val centerXFraction = ((pointX - bounds.left) / width).coerceIn(0.05f, 0.95f)
        val centerYFraction = ((pointY - bounds.top) / height).coerceIn(0.05f, 0.95f)
        val descriptorTokens =
            (
                executionResult.targetDescriptorTokens +
                    executionResult.targetVisualDescriptorTokens +
                    executionResult.targetContextHints +
                    listOf(executionResult.expectedEntityHint, executionResult.targetObjectType)
            ).map(::normalizeToken)
                .filter { it.length >= 2 }
                .distinct()
                .take(10)
        if (descriptorTokens.isEmpty()) return
        val baseRadius =
            when {
                executionResult.targetObjectType.contains("icon", ignoreCase = true) -> 0.16f
                executionResult.targetObjectType.contains("button", ignoreCase = true) -> 0.18f
                else -> 0.24f
            }
        val now = System.currentTimeMillis()
        val prior =
            RegionHitPrior(
                priorId = buildPriorId(observation.packageName, executionResult.targetObjectType, executionResult.resolvedRegionHitSource, descriptorTokens),
                packageName = observation.packageName,
                pageState = observation.pageState,
                roleHint = executionResult.targetObjectType,
                objectType = executionResult.targetObjectType,
                descriptorTokens = descriptorTokens,
                source = executionResult.resolvedRegionHitSource,
                centerXFraction = centerXFraction,
                centerYFraction = centerYFraction,
                radiusFraction = baseRadius,
                successCount = 1,
                updatedAtMs = now,
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
                        descriptorTokens = (existing.descriptorTokens + prior.descriptorTokens).distinct().take(10),
                        source = prior.source.ifBlank { existing.source },
                        centerXFraction = average(existing.centerXFraction, prior.centerXFraction),
                        centerYFraction = average(existing.centerYFraction, prior.centerYFraction),
                        radiusFraction = average(existing.radiusFraction, prior.radiusFraction),
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

    private fun RegionHitPrior.toJson(): JSONObject =
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
            put("radius_fraction", radiusFraction.toDouble())
            put("success_count", successCount)
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toPrior(): RegionHitPrior =
        RegionHitPrior(
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
            radiusFraction = optDouble("radius_fraction", 0.22).toFloat(),
            successCount = optInt("success_count", 1),
            updatedAtMs = optLong("updated_at_ms"),
        )

    private fun parseBounds(bounds: String): Rect? {
        val values = Regex("""-?\d+""").findAll(bounds).map { it.value.toInt() }.toList()
        if (values.size < 4) return null
        return Rect(values[0], values[1], values[2], values[3])
    }

    private fun buildPriorId(
        packageName: String,
        objectType: String,
        source: String,
        descriptorTokens: List<String>,
    ): String = "${packageName}_${objectType}_${source}_${descriptorTokens.take(4).joinToString("_")}".lowercase()

    private fun average(
        a: Float,
        b: Float,
    ): Float = ((a + b) / 2f).coerceIn(0f, 1f)

    private fun normalizeToken(raw: String): String = raw.replace("\\s+".toRegex(), " ").trim()
}
