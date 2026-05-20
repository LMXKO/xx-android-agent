package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class AssistantAnalyticsSinkKey {
    LOCAL_LEDGER,
    PRODUCT_SHELL,
    ASSISTANT_OS,
}

data class AssistantAnalyticsEvent(
    val eventId: String,
    val name: String,
    val source: String,
    val metadata: Map<String, String> = emptyMap(),
    val createdAtMs: Long = 0L,
)

data class AssistantAnalyticsSummary(
    val totalEvents: Int = 0,
    val topCounters: List<String> = emptyList(),
    val enabledSinks: List<String> = emptyList(),
    val recentEvents: List<String> = emptyList(),
)

data class AssistantAnalyticsSnapshot(
    val events: List<AssistantAnalyticsEvent> = emptyList(),
    val counters: Map<String, Int> = emptyMap(),
    val sinkStates: Map<AssistantAnalyticsSinkKey, Boolean> =
        AssistantAnalyticsSinkKey.entries.associateWith { true },
    val updatedAtMs: Long = 0L,
) {
    fun isSinkEnabled(
        sink: AssistantAnalyticsSinkKey,
    ): Boolean = sinkStates[sink] ?: true

    fun toSummary(
        counterLimit: Int = 4,
        eventLimit: Int = 6,
    ): AssistantAnalyticsSummary =
        AssistantAnalyticsSummary(
            totalEvents = events.size,
            topCounters =
                counters.entries
                    .sortedByDescending { it.value }
                    .take(counterLimit)
                    .map { "${it.key}:${it.value}" },
            enabledSinks =
                sinkStates.entries
                    .filter { it.value }
                    .map { it.key.name.lowercase() },
            recentEvents =
                events.take(eventLimit).map { event ->
                    buildString {
                        append(event.name)
                        append(" | ").append(event.source.ifBlank { "-" })
                        if (event.metadata.isNotEmpty()) {
                            append(" | ").append(event.metadata.entries.joinToString(",") { "${it.key}=${it.value}" }.take(96))
                        }
                    }
                },
        )
}

object AssistantAnalyticsStore {
    private const val ANALYTICS_DIR = "assistant_os"
    private const val ANALYTICS_FILE = "analytics_ledger.json"
    private const val MAX_EVENTS = 200
    private val lock = Any()
    @Volatile
    private var hydrated = false
    private var snapshot = AssistantAnalyticsSnapshot()
    private val snapshotFlow = MutableStateFlow(snapshot)

    fun read(): AssistantAnalyticsSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshot
        }

    fun logEvent(
        name: String,
        source: String,
        metadata: Map<String, String> = emptyMap(),
        sinks: Set<AssistantAnalyticsSinkKey> = AssistantAnalyticsSinkKey.entries.toSet(),
    ): AssistantAnalyticsSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val current = snapshot
            val enabledSinks = sinks.filter { current.isSinkEnabled(it) }.toSet()
            if (enabledSinks.isEmpty()) {
                return@synchronized current
            }
            val now = System.currentTimeMillis()
            val event =
                AssistantAnalyticsEvent(
                    eventId = "analytics_${now}_${name.take(24)}",
                    name = name.trim().ifBlank { "unknown_event" },
                    source = source.trim().ifBlank { "unknown_source" },
                    metadata = metadata.filterValues { it.isNotBlank() },
                    createdAtMs = now,
                )
            val counterKey = "${event.source}:${event.name}"
            val next =
                current.copy(
                    events = (listOf(event) + current.events).take(MAX_EVENTS),
                    counters = current.counters + (counterKey to ((current.counters[counterKey] ?: 0) + 1)),
                    updatedAtMs = now,
                )
            writeUnlocked(next)
            next
        }

    fun setSinkEnabled(
        sink: AssistantAnalyticsSinkKey,
        enabled: Boolean,
    ): AssistantAnalyticsSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val current = snapshot
            val next =
                current.copy(
                    sinkStates = current.sinkStates + (sink to enabled),
                    updatedAtMs = System.currentTimeMillis(),
                )
            writeUnlocked(next)
            next
        }

    fun exportJson(): JSONObject =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshot.toJson()
        }

    fun importJson(
        json: JSONObject?,
    ): AssistantAnalyticsSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val next = json?.toAnalyticsSnapshot() ?: AssistantAnalyticsSnapshot()
            writeUnlocked(next)
            next
        }

    private fun readUnlocked(): AssistantAnalyticsSnapshot {
        hydrateIfNeededUnlocked()
        return snapshot
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = analyticsFile()
        snapshot =
            if (file == null || !file.exists()) {
                AssistantAnalyticsSnapshot()
            } else {
                runCatching { JSONObject(file.readText()).toAnalyticsSnapshot() }.getOrDefault(AssistantAnalyticsSnapshot())
            }
        snapshotFlow.value = snapshot
    }

    private fun writeUnlocked(
        snapshot: AssistantAnalyticsSnapshot,
    ) {
        this.snapshot = snapshot
        snapshotFlow.value = snapshot
        val file = analyticsFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(snapshot.toJson().toString(2))
    }

    private fun analyticsFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, ANALYTICS_DIR), ANALYTICS_FILE)
    }
}

private fun AssistantAnalyticsSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("updated_at_ms", updatedAtMs)
        put(
            "events",
            JSONArray().apply {
                events.forEach { event ->
                    put(
                        JSONObject().apply {
                            put("event_id", event.eventId)
                            put("name", event.name)
                            put("source", event.source)
                            put("created_at_ms", event.createdAtMs)
                            put(
                                "metadata",
                                JSONObject().apply {
                                    event.metadata.forEach { (key, value) ->
                                        put(key, value)
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
        put(
            "counters",
            JSONObject().apply {
                counters.forEach { (key, value) ->
                    put(key, value)
                }
            },
        )
        put(
            "sink_states",
            JSONObject().apply {
                sinkStates.forEach { (key, value) ->
                    put(key.name, value)
                }
            },
        )
    }

private fun JSONObject.toAnalyticsSnapshot(): AssistantAnalyticsSnapshot =
    AssistantAnalyticsSnapshot(
        events =
            buildList {
                val array = optJSONArray("events") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val event = array.optJSONObject(index) ?: continue
                    add(
                        AssistantAnalyticsEvent(
                            eventId = event.optString("event_id"),
                            name = event.optString("name"),
                            source = event.optString("source"),
                            metadata =
                                buildMap {
                                    val metadata = event.optJSONObject("metadata") ?: JSONObject()
                                    val keys = metadata.keys()
                                    while (keys.hasNext()) {
                                        val key = keys.next()
                                        metadata.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) }
                                    }
                                },
                            createdAtMs = event.optLong("created_at_ms"),
                        ),
                    )
                }
            },
        counters =
            buildMap {
                val counters = optJSONObject("counters") ?: JSONObject()
                val keys = counters.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, counters.optInt(key))
                }
            },
        sinkStates =
            AssistantAnalyticsSinkKey.entries.associateWith { sink ->
                optJSONObject("sink_states")?.optBoolean(sink.name, true) ?: true
            },
        updatedAtMs = optLong("updated_at_ms"),
    )
