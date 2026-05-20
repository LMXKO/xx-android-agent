package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class PlannerProviderHealthSnapshot(
    val providerId: String,
    val totalRoutes: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val healthScore: Int = 60,
    val packageAffinity: Int = 0,
    val stageAffinity: Int = 0,
    val learnedScore: Int = 60,
    val updatedAtMs: Long = 0L,
) {
    val successRate: Double
        get() = if (totalRoutes <= 0) 0.0 else successCount.toDouble() / totalRoutes.toDouble()
}

internal data class PlannerProviderFeedbackEntry(
    val providerId: String,
    val totalRoutes: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val healthScore: Int = 60,
    val packageScores: Map<String, Int> = emptyMap(),
    val stageScores: Map<String, Int> = emptyMap(),
    val updatedAtMs: Long = 0L,
)

internal object PlannerProviderFeedbackStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "planner_provider_feedback.json"
    private val lock = Any()
    private var hydrated = false
    private var entries: Map<String, PlannerProviderFeedbackEntry> = emptyMap()

    fun feedbackScores(
        providerIds: Collection<String>,
        targetPackageName: String,
        stage: String,
    ): Map<String, PlannerProviderHealthSnapshot> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            providerIds
                .map { providerId ->
                    val normalized = PlannerProviderKey.resolveProviderId(providerId) ?: providerId.trim().lowercase()
                    normalized to healthSnapshotUnlocked(normalized, targetPackageName, stage)
                }.toMap()
        }

    fun summaryLines(
        limit: Int = 8,
    ): List<String> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            entries.values
                .sortedWith(
                    compareByDescending<PlannerProviderFeedbackEntry> { it.healthScore }
                        .thenByDescending { it.successCount }
                        .thenBy { it.providerId },
                ).take(limit.coerceAtLeast(1))
                .map { entry ->
                    val totalRoutes = entry.totalRoutes
                    val successRate =
                        if (totalRoutes <= 0) {
                            0
                        } else {
                            ((entry.successCount.toDouble() / totalRoutes.toDouble()) * 100.0).toInt()
                        }
                    "${entry.providerId} | health=${entry.healthScore} | success=${successRate}% | fail=${entry.failureCount} | streak=${entry.consecutiveFailures}"
                }
        }

    fun recordOutcome(
        providerId: String,
        targetPackageName: String,
        stage: String,
        routeLabel: String,
        success: Boolean,
        fallbackApplied: Boolean,
    ) {
        val normalizedProviderId =
            PlannerProviderKey.resolveProviderId(providerId)
                ?: providerId.trim().lowercase()
        if (normalizedProviderId.isBlank()) return
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val now = System.currentTimeMillis()
            val current = entries[normalizedProviderId] ?: PlannerProviderFeedbackEntry(providerId = normalizedProviderId)
            val routeBias =
                when {
                    routeLabel.contains("fallback", ignoreCase = true) -> -1
                    routeLabel.contains("resume", ignoreCase = true) -> 1
                    else -> 0
                }
            val nextHealth =
                (
                    current.healthScore +
                        when {
                            success && fallbackApplied -> 4 + routeBias
                            success -> 6 + routeBias
                            fallbackApplied -> -10 + routeBias
                            else -> -14 + routeBias
                        }
                    ).coerceIn(12, 100)
            val nextPackageScores =
                current.packageScores.updateFeedbackScore(
                    key = targetPackageName,
                    success = success,
                    successDelta = 3,
                    failureDelta = -5,
                )
            val nextStageScores =
                current.stageScores.updateFeedbackScore(
                    key = stage,
                    success = success,
                    successDelta = 3,
                    failureDelta = -5,
                )
            entries =
                entries + (
                    normalizedProviderId to
                        current.copy(
                            totalRoutes = current.totalRoutes + 1,
                            successCount = current.successCount + if (success) 1 else 0,
                            failureCount = current.failureCount + if (success) 0 else 1,
                            consecutiveFailures = if (success) 0 else current.consecutiveFailures + 1,
                            healthScore = nextHealth,
                            packageScores = nextPackageScores,
                            stageScores = nextStageScores,
                            updatedAtMs = now,
                        )
                    )
            persistUnlocked()
        }
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = storeFile() ?: return
        if (!file.exists()) return
        entries =
            runCatching {
                file.readText()
                    .let(::JSONObject)
                    .optJSONArray("entries")
                    .toFeedbackEntries()
                    .associateBy { it.providerId }
            }.getOrDefault(emptyMap())
    }

    private fun persistUnlocked() {
        val file = storeFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "entries",
                    JSONArray().apply {
                        entries.values
                            .sortedBy { it.providerId }
                            .forEach { put(it.toJson()) }
                    },
                )
            }.toString(2),
        )
    }

    private fun healthSnapshotUnlocked(
        providerId: String,
        targetPackageName: String,
        stage: String,
    ): PlannerProviderHealthSnapshot {
        val entry = entries[providerId] ?: PlannerProviderFeedbackEntry(providerId = providerId)
        val packageAffinity = entry.packageScores[targetPackageName].orZero()
        val stageAffinity = entry.stageScores[stage].orZero()
        val learnedScore =
            (
                entry.healthScore +
                    packageAffinity +
                    stageAffinity +
                    if (entry.totalRoutes >= 2) ((entry.successCount * 20) / entry.totalRoutes) else 0 -
                    entry.consecutiveFailures * 8
                ).coerceIn(0, 160)
        return PlannerProviderHealthSnapshot(
            providerId = providerId,
            totalRoutes = entry.totalRoutes,
            successCount = entry.successCount,
            failureCount = entry.failureCount,
            consecutiveFailures = entry.consecutiveFailures,
            healthScore = entry.healthScore,
            packageAffinity = packageAffinity,
            stageAffinity = stageAffinity,
            learnedScore = learnedScore,
            updatedAtMs = entry.updatedAtMs,
        )
    }

    private fun storeFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, STORE_DIR), STORE_FILE)
    }
}

private fun Map<String, Int>.updateFeedbackScore(
    key: String,
    success: Boolean,
    successDelta: Int,
    failureDelta: Int,
): Map<String, Int> {
    if (key.isBlank()) return this
    val current = this[key].orZero()
    val next = (current + if (success) successDelta else failureDelta).coerceIn(-30, 24)
    return this + (key to next)
}

private fun Int?.orZero(): Int = this ?: 0

private fun PlannerProviderFeedbackEntry.toJson(): JSONObject =
    JSONObject().apply {
        put("provider_id", providerId)
        put("total_routes", totalRoutes)
        put("success_count", successCount)
        put("failure_count", failureCount)
        put("consecutive_failures", consecutiveFailures)
        put("health_score", healthScore)
        put("package_scores", packageScores.toFeedbackJson("package_name"))
        put("stage_scores", stageScores.toFeedbackJson("stage"))
        put("updated_at_ms", updatedAtMs)
    }

private fun Map<String, Int>.toFeedbackJson(
    keyField: String,
): JSONArray =
    JSONArray().apply {
        entries.sortedBy { it.key }.forEach { (key, score) ->
            put(
                JSONObject().apply {
                    put(keyField, key)
                    put("score", score)
                },
            )
        }
    }

private fun JSONArray?.toFeedbackEntries(): List<PlannerProviderFeedbackEntry> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val providerId =
                PlannerProviderKey.resolveProviderId(item.optString("provider_id"))
                    ?: item.optString("provider_id").trim().lowercase()
            if (providerId.isBlank()) continue
            add(
                PlannerProviderFeedbackEntry(
                    providerId = providerId,
                    totalRoutes = item.optInt("total_routes", 0),
                    successCount = item.optInt("success_count", 0),
                    failureCount = item.optInt("failure_count", 0),
                    consecutiveFailures = item.optInt("consecutive_failures", 0),
                    healthScore = item.optInt("health_score", 60),
                    packageScores = item.optJSONArray("package_scores").toFeedbackScoreMap("package_name"),
                    stageScores = item.optJSONArray("stage_scores").toFeedbackScoreMap("stage"),
                    updatedAtMs = item.optLong("updated_at_ms", 0L),
                ),
            )
        }
    }
}

private fun JSONArray?.toFeedbackScoreMap(
    keyField: String,
): Map<String, Int> {
    if (this == null) return emptyMap()
    return buildMap {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val key = item.optString(keyField)
            if (key.isBlank()) continue
            put(key, item.optInt("score", 0))
        }
    }
}
