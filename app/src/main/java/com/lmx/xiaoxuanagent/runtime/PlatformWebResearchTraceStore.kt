package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class PlatformWebResearchTrace(
    val traceId: String,
    val sessionId: String,
    val type: String,
    val query: String = "",
    val url: String = "",
    val summary: String = "",
    val detailLines: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

object PlatformWebResearchTraceStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "web_research_traces.json"
    private const val MAX_TRACES = 160
    private val lock = Any()
    private val traces = ArrayDeque<PlatformWebResearchTrace>()
    private var hydrated = false

    fun record(
        sessionId: String,
        type: String,
        query: String = "",
        url: String = "",
        summary: String,
        detailLines: List<String>,
    ) {
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            traces.addFirst(
                PlatformWebResearchTrace(
                    traceId = "web_${System.currentTimeMillis()}_${type}",
                    sessionId = sessionId,
                    type = type,
                    query = query,
                    url = url,
                    summary = summary,
                    detailLines = detailLines.take(8),
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
            while (traces.size > MAX_TRACES) traces.removeLast()
            persistUnlocked()
        }
    }

    fun readRecent(
        sessionId: String,
        limit: Int = 6,
    ): List<PlatformWebResearchTrace> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            traces.filter { sessionId.isBlank() || it.sessionId == sessionId }.take(limit)
        }

    fun planningLines(
        sessionId: String,
        limit: Int = 3,
    ): List<String> =
        readRecent(sessionId, limit).flatMap { trace ->
            buildList {
                add("web_trace: ${trace.type} | ${trace.summary.take(120)}")
                trace.detailLines.firstOrNull()?.let { add("web_trace_detail: ${it.take(120)}") }
            }
        }.take(limit.coerceAtLeast(1))

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = storeFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("traces") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                traces.addLast(item.toTrace())
            }
        }
    }

    private fun persistUnlocked() {
        val file = storeFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put("traces", JSONArray().apply { traces.forEach { put(it.toJson()) } })
            }.toString(2),
        )
    }

    private fun storeFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, STORE_DIR), STORE_FILE)
    }

    private fun PlatformWebResearchTrace.toJson(): JSONObject =
        JSONObject().apply {
            put("trace_id", traceId)
            put("session_id", sessionId)
            put("type", type)
            put("query", query)
            put("url", url)
            put("summary", summary)
            put("detail_lines", JSONArray(detailLines))
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toTrace(): PlatformWebResearchTrace =
        PlatformWebResearchTrace(
            traceId = optString("trace_id"),
            sessionId = optString("session_id"),
            type = optString("type"),
            query = optString("query"),
            url = optString("url"),
            summary = optString("summary"),
            detailLines =
                optJSONArray("detail_lines")?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) add(array.optString(index))
                    }
                }.orEmpty(),
            updatedAtMs = optLong("updated_at_ms"),
        )
}
