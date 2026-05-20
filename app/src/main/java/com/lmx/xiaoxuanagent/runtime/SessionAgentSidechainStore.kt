package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class SessionAgentSidechainStage {
    ENQUEUED,
    SCHEDULED,
    LAUNCHED,
    RUNNING,
    WAITING_EXTERNAL,
    WAITING_APPROVAL,
    WAITING_CHILDREN,
    PAUSED,
    DEFERRED,
    COMPLETED,
    FAILED,
    STOPPED,
    RESULT_REPORTED,
}

data class SessionAgentSidechainEvent(
    val eventId: String,
    val workerId: String,
    val agentId: String,
    val parentAgentId: String = "",
    val sessionId: String = "",
    val rootSessionId: String = "",
    val coordinatorSessionId: String = "",
    val stage: SessionAgentSidechainStage,
    val summary: String = "",
    val resultSummary: String = "",
    val metadata: Map<String, String> = emptyMap(),
    val timestamp: Long,
)

data class SessionAgentSidechainSnapshot(
    val agentId: String,
    val workerId: String,
    val parentAgentId: String = "",
    val sessionId: String = "",
    val rootSessionId: String = "",
    val coordinatorSessionId: String = "",
    val latestStage: SessionAgentSidechainStage,
    val latestSummary: String = "",
    val latestResultSummary: String = "",
    val updatedAtMs: Long,
    val recentEvents: List<SessionAgentSidechainEvent> = emptyList(),
)

object SessionAgentSidechainStore {
    private const val SIDECCHAIN_DIR = "runtime"
    private const val SIDECCHAIN_FILE = "agent_sidechain_events.json"
    private const val MAX_EVENTS = 400
    private val lock = Any()
    private val events = ArrayDeque<SessionAgentSidechainEvent>()
    private var hydrated = false

    fun recordWorkerEnqueued(
        worker: SessionWorkerRecord,
        metadata: Map<String, String> = emptyMap(),
    ) {
        append(
            worker = worker,
            stage = SessionAgentSidechainStage.ENQUEUED,
            summary = worker.summary.ifBlank { worker.task },
            metadata = metadata + mapOf("entry_source" to worker.entrySource),
        )
    }

    fun recordWorkerScheduled(
        worker: SessionWorkerRecord,
        proactiveTaskId: String,
    ) {
        append(
            worker = worker,
            stage = SessionAgentSidechainStage.SCHEDULED,
            summary = worker.summary.ifBlank { worker.task },
            metadata = mapOf("proactive_task_id" to proactiveTaskId),
        )
    }

    fun recordWorkerState(
        worker: SessionWorkerRecord,
        summary: String = worker.summary,
        resultSummary: String = worker.resultSummary,
        metadata: Map<String, String> = emptyMap(),
    ) {
        append(
            worker = worker,
            stage = worker.status.toSidechainStage(),
            summary = summary.ifBlank { worker.summary.ifBlank { worker.task } },
            resultSummary = resultSummary,
            metadata = metadata,
        )
    }

    fun recordWorkerResult(
        worker: SessionWorkerRecord,
        summary: String = worker.summary,
        resultSummary: String = worker.resultSummary,
        metadata: Map<String, String> = emptyMap(),
    ) {
        append(
            worker = worker,
            stage = SessionAgentSidechainStage.RESULT_REPORTED,
            summary = summary.ifBlank { worker.summary.ifBlank { worker.task } },
            resultSummary = resultSummary.ifBlank { worker.resultSummary },
            metadata = metadata,
        )
    }

    fun readRecent(
        limit: Int = 24,
        agentId: String = "",
        workerId: String = "",
        rootSessionId: String = "",
    ): List<SessionAgentSidechainEvent> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            events
                .asSequence()
                .filter { agentId.isBlank() || it.agentId == agentId }
                .filter { workerId.isBlank() || it.workerId == workerId }
                .filter { rootSessionId.isBlank() || it.rootSessionId == rootSessionId }
                .take(limit)
                .toList()
        }

    fun readSnapshots(
        limit: Int = 16,
        rootSessionId: String = "",
    ): List<SessionAgentSidechainSnapshot> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            events
                .groupBy { it.agentId.ifBlank { it.workerId } }
                .values
                .mapNotNull { group -> buildSnapshot(group) }
                .filter { rootSessionId.isBlank() || it.rootSessionId == rootSessionId }
                .sortedByDescending { it.updatedAtMs }
                .take(limit)
        }

    fun readSnapshot(
        agentId: String = "",
        workerId: String = "",
    ): SessionAgentSidechainSnapshot? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val matches =
                events.filter { event ->
                    (agentId.isBlank() || event.agentId == agentId) &&
                        (workerId.isBlank() || event.workerId == workerId)
                }
            buildSnapshot(matches)
        }

    fun exportJson(
        limit: Int = 48,
    ): JSONArray =
        JSONArray().apply {
            readRecent(limit = limit).forEach { put(it.toJson()) }
        }

    fun importJson(
        array: JSONArray?,
    ) {
        if (array == null || array.length() <= 0) return
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                events.addLast(json.toEvent())
            }
            dedupeAndTrimUnlocked()
            persistUnlocked()
        }
    }

    private fun append(
        worker: SessionWorkerRecord,
        stage: SessionAgentSidechainStage,
        summary: String,
        resultSummary: String = "",
        metadata: Map<String, String> = emptyMap(),
    ) {
        if (worker.workerId.isBlank() && worker.agentId.isBlank()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val latest =
                events.firstOrNull { current ->
                    current.workerId == worker.workerId &&
                        current.stage == stage &&
                        current.sessionId == worker.sessionId &&
                        current.summary == summary &&
                        current.resultSummary == resultSummary
                }
            if (latest != null && now - latest.timestamp <= 1500L) {
                return
            }
            events.addFirst(
                SessionAgentSidechainEvent(
                    eventId = "sidechain_${now}_${worker.workerId.ifBlank { worker.agentId }}",
                    workerId = worker.workerId,
                    agentId = worker.agentId,
                    parentAgentId = worker.parentAgentId,
                    sessionId = worker.sessionId,
                    rootSessionId = worker.rootSessionId,
                    coordinatorSessionId = worker.coordinatorSessionId,
                    stage = stage,
                    summary = summary,
                    resultSummary = resultSummary,
                    metadata = metadata,
                    timestamp = now,
                ),
            )
            dedupeAndTrimUnlocked()
            persistUnlocked()
        }
    }

    private fun buildSnapshot(
        matches: List<SessionAgentSidechainEvent>,
    ): SessionAgentSidechainSnapshot? {
        if (matches.isEmpty()) return null
        val ordered = matches.sortedByDescending { it.timestamp }
        val latest = ordered.first()
        return SessionAgentSidechainSnapshot(
            agentId = latest.agentId,
            workerId = latest.workerId,
            parentAgentId = latest.parentAgentId,
            sessionId = latest.sessionId,
            rootSessionId = latest.rootSessionId,
            coordinatorSessionId = latest.coordinatorSessionId,
            latestStage = latest.stage,
            latestSummary = latest.summary,
            latestResultSummary = latest.resultSummary,
            updatedAtMs = latest.timestamp,
            recentEvents = ordered.take(12),
        )
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = sidechainFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("events") ?: JSONArray()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                events.addLast(json.toEvent())
            }
            dedupeAndTrimUnlocked()
        }
    }

    private fun dedupeAndTrimUnlocked() {
        val merged =
            events
                .distinctBy { "${it.eventId}|${it.workerId}|${it.stage}|${it.timestamp}" }
                .sortedByDescending { it.timestamp }
                .take(MAX_EVENTS)
        events.clear()
        merged.forEach(events::addLast)
    }

    private fun persistUnlocked() {
        val file = sidechainFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "events",
                    JSONArray().apply {
                        events.forEach { put(it.toJson()) }
                    },
                )
            }.toString(2),
        )
    }

    private fun sidechainFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, SIDECCHAIN_DIR), SIDECCHAIN_FILE)
    }

    private fun SessionAgentSidechainEvent.toJson(): JSONObject =
        JSONObject().apply {
            put("event_id", eventId)
            put("worker_id", workerId)
            put("agent_id", agentId)
            put("parent_agent_id", parentAgentId)
            put("session_id", sessionId)
            put("root_session_id", rootSessionId)
            put("coordinator_session_id", coordinatorSessionId)
            put("stage", stage.name)
            put("summary", summary)
            put("result_summary", resultSummary)
            put("timestamp", timestamp)
            put(
                "metadata",
                JSONObject().apply {
                    metadata.forEach { (key, value) -> put(key, value) }
                },
            )
        }

    private fun JSONObject.toEvent(): SessionAgentSidechainEvent =
        SessionAgentSidechainEvent(
            eventId = optString("event_id"),
            workerId = optString("worker_id"),
            agentId = optString("agent_id"),
            parentAgentId = optString("parent_agent_id"),
            sessionId = optString("session_id"),
            rootSessionId = optString("root_session_id"),
            coordinatorSessionId = optString("coordinator_session_id"),
            stage =
                runCatching { SessionAgentSidechainStage.valueOf(optString("stage")) }
                    .getOrDefault(SessionAgentSidechainStage.ENQUEUED),
            summary = optString("summary"),
            resultSummary = optString("result_summary"),
            metadata = optJSONObject("metadata").toStringMap(),
            timestamp = optLong("timestamp"),
        )

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap {
            keys().forEach { key -> put(key, optString(key)) }
        }
    }
}

private fun SessionWorkerStatus.toSidechainStage(): SessionAgentSidechainStage =
    when (this) {
        SessionWorkerStatus.QUEUED -> SessionAgentSidechainStage.ENQUEUED
        SessionWorkerStatus.RUNNING -> SessionAgentSidechainStage.RUNNING
        SessionWorkerStatus.WAITING_EXTERNAL -> SessionAgentSidechainStage.WAITING_EXTERNAL
        SessionWorkerStatus.WAITING_APPROVAL -> SessionAgentSidechainStage.WAITING_APPROVAL
        SessionWorkerStatus.WAITING_CHILDREN -> SessionAgentSidechainStage.WAITING_CHILDREN
        SessionWorkerStatus.PAUSED -> SessionAgentSidechainStage.PAUSED
        SessionWorkerStatus.DEFERRED -> SessionAgentSidechainStage.DEFERRED
        SessionWorkerStatus.COMPLETED -> SessionAgentSidechainStage.COMPLETED
        SessionWorkerStatus.FAILED -> SessionAgentSidechainStage.FAILED
        SessionWorkerStatus.STOPPED -> SessionAgentSidechainStage.STOPPED
    }
