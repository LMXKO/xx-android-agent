package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class RuntimeCounter(
    val key: String,
    val count: Int,
)

data class RuntimeMetricsSnapshot(
    val commandCounters: List<RuntimeCounter> = emptyList(),
    val bridgeEventCounters: List<RuntimeCounter> = emptyList(),
    val hookCounters: List<RuntimeCounter> = emptyList(),
    val lastUpdatedAtMs: Long = 0L,
)

object RuntimeMetricsStore {
    private const val METRICS_DIR = "runtime"
    private const val METRICS_FILE = "runtime_metrics.json"
    private val lock = Any()
    private var snapshot = RuntimeMetricsSnapshot()
    private var hydrated = false

    fun read(): RuntimeMetricsSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshot
        }

    fun recordCommand(command: SessionCommand) {
        increment(counterGroup = "command", key = command.javaClass.simpleName)
    }

    fun recordBridgeEvent(eventType: String) {
        increment(counterGroup = "bridge", key = eventType)
    }

    fun recordHookOutcome(stage: AgentHookStage, outcome: String) {
        increment(counterGroup = "hook", key = "${stage.name.lowercase()}:$outcome")
    }

    fun recordPlatformEvent(key: String) {
        increment(counterGroup = "hook", key = "platform:$key")
    }

    private fun increment(
        counterGroup: String,
        key: String,
    ) {
        if (key.isBlank()) return
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshot =
                when (counterGroup) {
                    "command" ->
                        snapshot.copy(
                            commandCounters = increment(snapshot.commandCounters, key),
                            lastUpdatedAtMs = System.currentTimeMillis(),
                        )
                    "bridge" ->
                        snapshot.copy(
                            bridgeEventCounters = increment(snapshot.bridgeEventCounters, key),
                            lastUpdatedAtMs = System.currentTimeMillis(),
                        )
                    else ->
                        snapshot.copy(
                            hookCounters = increment(snapshot.hookCounters, key),
                            lastUpdatedAtMs = System.currentTimeMillis(),
                        )
                }
            persistUnlocked()
        }
    }

    private fun increment(
        counters: List<RuntimeCounter>,
        key: String,
    ): List<RuntimeCounter> {
        val updated = counters.toMutableList()
        val index = updated.indexOfFirst { it.key == key }
        if (index >= 0) {
            val current = updated[index]
            updated[index] = current.copy(count = current.count + 1)
        } else {
            updated += RuntimeCounter(key = key, count = 1)
        }
        return updated.sortedByDescending { it.count }
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = metricsFile() ?: return
        if (!file.exists()) return
        snapshot =
            runCatching {
                val json = JSONObject(file.readText())
                RuntimeMetricsSnapshot(
                    commandCounters = json.optJSONArray("command_counters").toCounters(),
                    bridgeEventCounters = json.optJSONArray("bridge_event_counters").toCounters(),
                    hookCounters = json.optJSONArray("hook_counters").toCounters(),
                    lastUpdatedAtMs = json.optLong("last_updated_at_ms"),
                )
            }.getOrDefault(RuntimeMetricsSnapshot())
    }

    private fun persistUnlocked() {
        val file = metricsFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put("command_counters", snapshot.commandCounters.toJson())
                put("bridge_event_counters", snapshot.bridgeEventCounters.toJson())
                put("hook_counters", snapshot.hookCounters.toJson())
                put("last_updated_at_ms", snapshot.lastUpdatedAtMs)
            }.toString(2),
        )
    }

    private fun metricsFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, METRICS_DIR), METRICS_FILE)
    }

    private fun JSONArray?.toCounters(): List<RuntimeCounter> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(RuntimeCounter(item.optString("key"), item.optInt("count")))
            }
        }
    }

    private fun List<RuntimeCounter>.toJson(): JSONArray =
        JSONArray().apply {
            this@toJson.forEach { counter ->
                put(
                    JSONObject().apply {
                        put("key", counter.key)
                        put("count", counter.count)
                    },
                )
            }
        }
}
