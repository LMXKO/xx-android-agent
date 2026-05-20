package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionGroundingHealthEntry(
    val entryId: String,
    val sessionId: String,
    val turn: Int,
    val packageName: String = "",
    val pageState: String = "",
    val toolName: String = "",
    val actionLabel: String = "",
    val outcome: String = "",
    val groundingSource: String = "",
    val groundingBucket: String = "",
    val targetText: String = "",
    val targetContainerId: String = "",
    val entityType: String = "",
    val recoveryCategory: String = "",
    val summary: String = "",
    val createdAtMs: Long = 0L,
)

data class SessionGroundingHealthSnapshot(
    val sessionId: String = "",
    val totalCount: Int = 0,
    val failureCount: Int = 0,
    val retryCount: Int = 0,
    val recoveryCount: Int = 0,
    val topPackageName: String = "",
    val bucketSummary: List<String> = emptyList(),
    val summary: String = "",
    val lines: List<String> = emptyList(),
)

object SessionGroundingHealthStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_grounding_health.json"
    private const val MAX_ENTRIES = 480
    private val lock = Any()
    private val entries = ArrayDeque<SessionGroundingHealthEntry>()
    private var hydrated = false

    fun record(
        sessionId: String,
        turn: Int,
        packageName: String,
        pageState: String,
        toolName: String,
        actionLabel: String,
        outcome: String,
        groundingSource: String = "",
        targetText: String = "",
        targetContainerId: String = "",
        entityType: String = "",
        recoveryCategory: String = "",
        summary: String = "",
    ): SessionGroundingHealthEntry? {
        if (sessionId.isBlank() || toolName.isBlank() || outcome.isBlank()) return null
        val now = System.currentTimeMillis()
        val entry =
            SessionGroundingHealthEntry(
                entryId = "ground_${now}_${toolName.hashCode().toUInt().toString(16)}",
                sessionId = sessionId,
                turn = turn,
                packageName = packageName,
                pageState = pageState,
                toolName = toolName,
                actionLabel = actionLabel,
                outcome = outcome,
                groundingSource = groundingSource,
                groundingBucket = normalizeGroundingBucket(groundingSource),
                targetText = targetText.take(60),
                targetContainerId = targetContainerId,
                entityType = entityType,
                recoveryCategory = recoveryCategory,
                summary = summary.take(180),
                createdAtMs = now,
            )
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            entries.addFirst(entry)
            trimUnlocked()
            persistUnlocked()
        }
        return entry
    }

    fun readRecent(
        sessionId: String = "",
        limit: Int = 12,
    ): List<SessionGroundingHealthEntry> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            entries
                .asSequence()
                .filter { sessionId.isBlank() || it.sessionId == sessionId }
                .take(limit.coerceAtLeast(1))
                .toList()
        }

    fun readSnapshot(
        sessionId: String,
        limit: Int = 12,
    ): SessionGroundingHealthSnapshot {
        val recent = readRecent(sessionId = sessionId, limit = limit)
        val failures = recent.count { it.outcome == "failed" }
        val retries = recent.count { it.outcome == "retry" }
        val recoveries = recent.count { it.recoveryCategory.isNotBlank() }
        val topPackage = recent.groupingBy { it.packageName.ifBlank { "-" } }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
        val bucketSummary =
            recent.groupBy { it.groundingBucket.ifBlank { "other" } }
                .entries
                .sortedByDescending { it.value.size }
                .map { (bucket, entries) ->
                    val successCount = entries.count { it.outcome == "success" }
                    "$bucket:${entries.size}/ok=$successCount"
                }
                .take(5)
        return SessionGroundingHealthSnapshot(
            sessionId = sessionId,
            totalCount = recent.size,
            failureCount = failures,
            retryCount = retries,
            recoveryCount = recoveries,
            topPackageName = topPackage,
            bucketSummary = bucketSummary,
            summary =
                buildString {
                    append("grounding=").append(recent.size)
                    append(" fail=").append(failures)
                    append(" retry=").append(retries)
                    append(" recovery=").append(recoveries)
                    append(" top_pkg=").append(topPackage.ifBlank { "-" })
                    if (bucketSummary.isNotEmpty()) {
                        append(" buckets=").append(bucketSummary.joinToString(","))
                    }
                },
            lines =
                recent.map { entry ->
                    buildString {
                        append(entry.turn).append(" | ").append(entry.outcome)
                        append(" | ").append(entry.toolName)
                        entry.groundingBucket.takeIf { it.isNotBlank() }?.let { append(" | bucket=").append(it) }
                        entry.groundingSource.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
                        entry.targetText.takeIf { it.isNotBlank() }?.let { append(" | target=").append(it.take(24)) }
                        entry.entityType.takeIf { it.isNotBlank() }?.let { append(" | entity=").append(it) }
                        entry.packageName.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
                        entry.recoveryCategory.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
                        entry.summary.takeIf { it.isNotBlank() }?.let { append(" | ").append(it.take(88)) }
                    }
                },
        )
    }

    fun planningLines(
        sessionId: String,
        limit: Int = 3,
    ): List<String> {
        val snapshot = readSnapshot(sessionId, limit = maxOf(limit, 6))
        return buildList {
            add("grounding_health: ${snapshot.summary}")
            addAll(snapshot.lines.take(limit.coerceAtLeast(1)).map { "grounding_recent: $it" })
        }.take(limit.coerceAtLeast(1))
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = storeFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("entries") ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toEntry()?.let(entries::addLast)
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
                    "entries",
                    JSONArray().apply {
                        entries.forEach { put(it.toJson()) }
                    },
                )
            }.toString(2),
        )
    }

    private fun trimUnlocked() {
        while (entries.size > MAX_ENTRIES) {
            entries.removeLast()
        }
    }

    private fun storeFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, STORE_DIR), STORE_FILE)
    }

    private fun SessionGroundingHealthEntry.toJson(): JSONObject =
        JSONObject().apply {
            put("entry_id", entryId)
            put("session_id", sessionId)
            put("turn", turn)
            put("package_name", packageName)
            put("page_state", pageState)
            put("tool_name", toolName)
            put("action_label", actionLabel)
            put("outcome", outcome)
            put("grounding_source", groundingSource)
            put("grounding_bucket", groundingBucket)
            put("target_text", targetText)
            put("target_container_id", targetContainerId)
            put("entity_type", entityType)
            put("recovery_category", recoveryCategory)
            put("summary", summary)
            put("created_at_ms", createdAtMs)
        }

    private fun JSONObject.toEntry(): SessionGroundingHealthEntry =
        SessionGroundingHealthEntry(
            entryId = optString("entry_id"),
            sessionId = optString("session_id"),
            turn = optInt("turn"),
            packageName = optString("package_name"),
            pageState = optString("page_state"),
            toolName = optString("tool_name"),
            actionLabel = optString("action_label"),
            outcome = optString("outcome"),
            groundingSource = optString("grounding_source"),
            groundingBucket = optString("grounding_bucket"),
            targetText = optString("target_text"),
            targetContainerId = optString("target_container_id"),
            entityType = optString("entity_type"),
            recoveryCategory = optString("recovery_category"),
            summary = optString("summary"),
            createdAtMs = optLong("created_at_ms"),
        )

    private fun normalizeGroundingBucket(
        source: String,
    ): String {
        val semantic = source.lowercase()
        return when {
            semantic.contains("screen_parser") || semantic.contains("parser_scope") -> "parser_scope"
            semantic.contains("visual_detector") -> "detector_visual"
            semantic.contains("overflow_hidden") -> "hidden_search"
            semantic.contains("region_") -> "region_hit"
            semantic.contains("overflow_recall") -> "recall"
            semantic.contains("overflow_tree") -> "secondary_tree"
            semantic.contains("gesture") || semantic.contains("coordinate") || semantic.contains("hotspot") -> "hotspot"
            semantic.contains("ocr") || semantic.contains("grounded") -> "visual_text"
            semantic.contains("container_region") || semantic.contains("visual") || semantic.contains("tree_shape") -> "mixed_visual"
            semantic.contains("tree") -> "tree"
            else -> "other"
        }
    }
}
