package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class AssistantExternalSignalType {
    MESSAGE,
    CALENDAR,
    LOCATION,
    NOTIFICATION,
    CONTACT,
    CALL_LOG,
    SYSTEM_EVENT,
    CLIPBOARD,
    APP_FOREGROUND,
}

data class AssistantExternalSignal(
    val id: String,
    val type: AssistantExternalSignalType,
    val capability: SessionCapabilityKey,
    val title: String,
    val summary: String,
    val task: String = "",
    val sessionId: String = "",
    val query: String = "",
    val fireAtMs: Long,
    val enabled: Boolean = true,
    val source: String = "",
    val payload: Map<String, String> = emptyMap(),
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

object AssistantExternalSignalStore {
    private const val SIGNAL_DIR = "assistant_os"
    private const val SIGNAL_FILE = "external_signals.json"
    private const val MAX_SIGNALS = 120
    private val lock = Any()
    private val signals = ArrayDeque<AssistantExternalSignal>()
    private var hydrated = false

    fun record(
        type: AssistantExternalSignalType,
        capability: SessionCapabilityKey,
        title: String,
        summary: String,
        task: String = "",
        sessionId: String = "",
        query: String = "",
        fireAtMs: Long = System.currentTimeMillis(),
        source: String = "",
        payload: Map<String, String> = emptyMap(),
    ): AssistantExternalSignal {
        val now = System.currentTimeMillis()
        val signal =
            AssistantExternalSignal(
                id = "signal_${now}_${type.name.lowercase()}",
                type = type,
                capability = capability,
                title = title,
                summary = summary,
                task = task.trim(),
                sessionId = sessionId,
                query = query.trim(),
                fireAtMs = fireAtMs,
                source = source,
                payload = payload,
                createdAtMs = now,
                updatedAtMs = now,
            )
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            signals.addFirst(signal)
            trimUnlocked()
            persistUnlocked()
        }
        notifySignalRecorded(signal)
        return signal
    }

    fun recordProviderSignal(
        type: AssistantExternalSignalType,
        capability: SessionCapabilityKey,
        title: String,
        summary: String,
        task: String = "",
        sessionId: String = "",
        query: String = "",
        fireAtMs: Long = System.currentTimeMillis(),
        source: String,
        signalKey: String,
        payload: Map<String, String> = emptyMap(),
        dedupeWindowMs: Long = 5 * 60 * 1000L,
    ): AssistantExternalSignal {
        val now = System.currentTimeMillis()
        val effectivePayload =
            payload +
                mapOf(
                    "provider_source" to source,
                    "signal_key" to signalKey,
                )
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val index =
                signals.indexOfFirst { current ->
                    current.enabled &&
                        current.source == source &&
                        current.payload["signal_key"] == signalKey &&
                        now - current.updatedAtMs <= dedupeWindowMs
                }
            if (index >= 0) {
                val current = signals.elementAt(index)
                val next =
                    current.copy(
                        type = type,
                        capability = capability,
                        title = title,
                        summary = summary,
                        task = task.trim(),
                        sessionId = sessionId,
                        query = query.trim(),
                        fireAtMs = fireAtMs,
                        payload = effectivePayload,
                        updatedAtMs = now,
                    )
                signals.removeAt(index)
                signals.add(index, next)
                persistUnlocked()
                notifySignalRecorded(next)
                return next
            }
        }
        return record(
            type = type,
            capability = capability,
            title = title,
            summary = summary,
            task = task,
            sessionId = sessionId,
            query = query,
            fireAtMs = fireAtMs,
            source = source,
            payload = effectivePayload,
        )
    }

    fun readAll(
        limit: Int = 24,
    ): List<AssistantExternalSignal> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            signals.sortedByDescending { it.updatedAtMs }.take(limit)
        }

    fun read(
        id: String,
    ): AssistantExternalSignal? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            signals.firstOrNull { it.id == id }
        }

    fun readDue(
        nowMs: Long = System.currentTimeMillis(),
        limit: Int = 8,
    ): List<AssistantExternalSignal> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            signals
                .filter { it.enabled && it.fireAtMs in 1..nowMs }
                .sortedBy { it.fireAtMs }
                .take(limit)
        }

    fun markConsumed(
        id: String,
    ) {
        mutate(id) { current ->
            current.copy(
                enabled = false,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun defer(
        id: String,
        deferByMs: Long,
    ) {
        mutate(id) { current ->
            current.copy(
                fireAtMs = System.currentTimeMillis() + deferByMs,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun importSignals(
        importedSignals: List<AssistantExternalSignal>,
    ) {
        if (importedSignals.isEmpty()) return
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val merged =
                (signals + importedSignals)
                    .distinctBy { it.id }
                    .sortedByDescending { it.updatedAtMs }
                    .take(MAX_SIGNALS)
            signals.clear()
            merged.forEach(signals::addLast)
            persistUnlocked()
        }
    }

    private fun mutate(
        id: String,
        reducer: (AssistantExternalSignal) -> AssistantExternalSignal,
    ) {
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val index = signals.indexOfFirst { it.id == id }
            if (index < 0) return
            val next = reducer(signals.elementAt(index))
            signals.removeAt(index)
            signals.add(index, next)
            persistUnlocked()
        }
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = signalFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("signals") ?: JSONArray()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                signals.addLast(json.toSignal())
            }
        }
    }

    private fun persistUnlocked() {
        val file = signalFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "signals",
                    JSONArray().apply {
                        signals.forEach { signal ->
                            put(signal.toJson())
                        }
                    },
                )
            }.toString(2),
        )
    }

    private fun trimUnlocked() {
        while (signals.size > MAX_SIGNALS) {
            signals.removeLast()
        }
    }

    private fun signalFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, SIGNAL_DIR), SIGNAL_FILE)
    }

    private fun notifySignalRecorded(
        signal: AssistantExternalSignal,
    ) {
        if (!signal.enabled) return
        if (signal.fireAtMs > System.currentTimeMillis()) return
        PersistentAssistantEngine.onExternalSignalRecorded(
            signalId = signal.id,
            reason = "signal_recorded:${signal.source.ifBlank { signal.type.name.lowercase() }}",
        )
    }

    private fun AssistantExternalSignal.toJson(): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("type", type.name)
            put("capability", capability.name)
            put("title", title)
            put("summary", summary)
            put("task", task)
            put("session_id", sessionId)
            put("query", query)
            put("fire_at_ms", fireAtMs)
            put("enabled", enabled)
            put("source", source)
            put("created_at_ms", createdAtMs)
            put("updated_at_ms", updatedAtMs)
            put(
                "payload",
                JSONObject().apply {
                    payload.forEach { (key, value) ->
                        put(key, value)
                    }
                },
            )
        }

    private fun JSONObject.toSignal(): AssistantExternalSignal =
        AssistantExternalSignal(
            id = optString("id"),
            type =
                runCatching { AssistantExternalSignalType.valueOf(optString("type")) }
                    .getOrDefault(AssistantExternalSignalType.SYSTEM_EVENT),
            capability =
                runCatching { SessionCapabilityKey.valueOf(optString("capability")) }
                    .getOrDefault(SessionCapabilityKey.START_SESSION),
            title = optString("title"),
            summary = optString("summary"),
            task = optString("task"),
            sessionId = optString("session_id"),
            query = optString("query"),
            fireAtMs = optLong("fire_at_ms"),
            enabled = optBoolean("enabled", true),
            source = optString("source"),
            payload = optJSONObject("payload").toStringMap(),
            createdAtMs = optLong("created_at_ms"),
            updatedAtMs = optLong("updated_at_ms"),
        )

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap {
            keys().forEach { key ->
                put(key, optString(key))
            }
        }
    }
}
