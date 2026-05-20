package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class PlatformTraceEntry(
    val traceId: String,
    val timestamp: Long,
    val category: String,
    val sessionId: String = "",
    val summary: String,
    val metadata: Map<String, String> = emptyMap(),
)

data class PlatformTraceSnapshot(
    val sessionId: String = "",
    val totalCount: Int = 0,
    val categoryCounts: List<String> = emptyList(),
    val sessionCoverageCount: Int = 0,
    val attentionCount: Int = 0,
    val latestAgeMinutes: Int = 0,
    val hottestCategory: String = "",
    val hottestSessionId: String = "",
    val categorySummary: String = "",
    val coverageSummary: String = "",
    val attentionSummary: String = "",
    val recentLines: List<String> = emptyList(),
    val lines: List<String> = emptyList(),
)

object PlatformTraceStore {
    private const val TRACE_DIR = "runtime"
    private const val TRACE_FILE = "platform_traces.json"
    private const val MAX_TRACE_ENTRIES = 320
    private val lock = Any()
    private val entries = ArrayDeque<PlatformTraceEntry>()
    private var hydrated = false

    fun record(
        category: String,
        summary: String,
        sessionId: String = "",
        metadata: Map<String, String> = emptyMap(),
    ) {
        val entry =
            PlatformTraceEntry(
                traceId = "trace_${System.currentTimeMillis()}_${category.hashCode().toUInt().toString(16)}",
                timestamp = System.currentTimeMillis(),
                category = category,
                sessionId = sessionId,
                summary = summary,
                metadata = metadata,
            )
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            entries.addFirst(entry)
            while (entries.size > MAX_TRACE_ENTRIES) {
                entries.removeLast()
            }
            persistUnlocked()
        }
    }

    fun readRecent(
        sessionId: String = "",
        limit: Int = 16,
    ): List<PlatformTraceEntry> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            entries
                .asSequence()
                .filter { sessionId.isBlank() || it.sessionId == sessionId }
                .take(limit)
                .toList()
        }

    fun readSnapshot(
        sessionId: String = "",
        limit: Int = 16,
    ): PlatformTraceSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            buildSnapshot(
                entries =
                    entries
                        .asSequence()
                        .filter { sessionId.isBlank() || it.sessionId == sessionId }
                        .take(limit)
                        .toList(),
                sessionId = sessionId,
            )
        }

    fun importEntries(
        importedEntries: List<PlatformTraceEntry>,
    ) {
        if (importedEntries.isEmpty()) return
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val merged =
                (entries + importedEntries)
                    .distinctBy { "${it.timestamp}|${it.category}|${it.sessionId}|${it.summary}" }
                    .sortedByDescending { it.timestamp }
                    .take(MAX_TRACE_ENTRIES)
            entries.clear()
            merged.forEach(entries::addLast)
            persistUnlocked()
        }
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = traceFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("entries") ?: JSONArray()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                entries.addLast(
                    PlatformTraceEntry(
                        traceId = json.optString("trace_id"),
                        timestamp = json.optLong("timestamp"),
                        category = json.optString("category"),
                        sessionId = json.optString("session_id"),
                        summary = json.optString("summary"),
                        metadata = json.optJSONObject("metadata").toStringMap(),
                    ),
                )
            }
        }
    }

    internal fun buildSnapshot(
        entries: List<PlatformTraceEntry>,
        sessionId: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ): PlatformTraceSnapshot {
        if (entries.isEmpty()) {
            return PlatformTraceSnapshot(
                sessionId = sessionId,
                lines = listOf("trace_count=0"),
            )
        }
        val categoryCounts =
            entries
                .groupBy { it.category.ifBlank { "unknown" } }
                .entries
                .sortedByDescending { it.value.size }
                .map { (category, items) -> "$category=${items.size}" }
        val distinctSessions =
            entries
                .mapNotNull { it.sessionId.takeIf(String::isNotBlank) }
                .distinct()
        val attentionEntries = entries.filter { it.isAttentionLike() }
        val hottestCategory = categoryCounts.firstOrNull()?.substringBefore('=').orEmpty()
        val hottestSessionId =
            entries
                .groupBy { it.sessionId.ifBlank { "-" } }
                .entries
                .maxByOrNull { it.value.size }
                ?.key
                .orEmpty()
                .takeIf { it != "-" }
                .orEmpty()
        val latestAgeMinutes =
            ((nowMs - entries.maxOf { it.timestamp }).coerceAtLeast(0L) / 60_000L).toInt()
        val categorySummary =
            buildString {
                append("categories=").append(categoryCounts.size)
                hottestCategory.takeIf { it.isNotBlank() }?.let {
                    append(" hottest=").append(it)
                }
                append(" mix=").append(categoryCounts.take(4).joinToString(",").ifBlank { "-" })
            }
        val coverageSummary =
            buildString {
                append("sessions=").append(distinctSessions.size)
                hottestSessionId.takeIf { it.isNotBlank() }?.let {
                    append(" hottest_session=").append(it)
                }
                append(" latest_age_min=").append(latestAgeMinutes)
            }
        val attentionSummary =
            buildString {
                append("attention=").append(attentionEntries.size)
                attentionEntries.firstOrNull()?.let { first ->
                    append(" top=").append(first.category.ifBlank { "unknown" })
                    append(":").append(first.summary.take(48))
                }
            }
        val recentLines =
            entries.take(6).map { entry ->
                "${entry.category.ifBlank { "unknown" }} | ${entry.sessionId.ifBlank { "-" }} | ${entry.summary.take(88)}"
            }
        return PlatformTraceSnapshot(
            sessionId = sessionId,
            totalCount = entries.size,
            categoryCounts = categoryCounts,
            sessionCoverageCount = distinctSessions.size,
            attentionCount = attentionEntries.size,
            latestAgeMinutes = latestAgeMinutes,
            hottestCategory = hottestCategory,
            hottestSessionId = hottestSessionId,
            categorySummary = categorySummary,
            coverageSummary = coverageSummary,
            attentionSummary = attentionSummary,
            recentLines = recentLines,
            lines =
                buildList {
                    add("trace_count=${entries.size}")
                    add(categorySummary)
                    add(coverageSummary)
                    add(attentionSummary)
                    recentLines.forEach { add("recent=$it") }
                },
        )
    }

    private fun persistUnlocked() {
        val file = traceFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "entries",
                    JSONArray().apply {
                        entries.forEach { entry ->
                            put(entry.toJson())
                        }
                    },
                )
            }.toString(2),
        )
    }

    private fun PlatformTraceEntry.toJson(): JSONObject =
        JSONObject().apply {
            put("trace_id", traceId)
            put("timestamp", timestamp)
            put("category", category)
            put("session_id", sessionId)
            put("summary", summary)
            put(
                "metadata",
                JSONObject().apply {
                    metadata.forEach { (key, value) ->
                        put(key, value)
                    }
                },
            )
        }

    private fun traceFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, TRACE_DIR), TRACE_FILE)
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap {
            keys().forEach { key ->
                put(key, optString(key))
            }
        }
    }

    private fun PlatformTraceEntry.isAttentionLike(): Boolean {
        val normalizedSummary = summary.lowercase()
        val normalizedCategory = category.lowercase()
        val status = metadata["status"].orEmpty().lowercase()
        return status == "failed" ||
            status == "error" ||
            normalizedCategory.contains("failed") ||
            normalizedCategory.contains("error") ||
            normalizedSummary.contains("failed") ||
            normalizedSummary.contains("error") ||
            normalizedSummary.contains("timeout") ||
            normalizedSummary.contains("rejected") ||
            normalizedSummary.contains("stale") ||
            normalizedSummary.contains("cooldown")
    }
}
