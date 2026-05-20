package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class AssistantProactiveTaskType {
    SCHEDULED_TASK,
    REMOTE_REQUEST,
    WORKER_TASK,
    APPROVAL_FOLLOW_UP,
    MEMORY_NUDGE,
}

data class AssistantProactiveTask(
    val id: String,
    val type: AssistantProactiveTaskType,
    val dedupeKey: String = "",
    val capability: SessionCapabilityKey,
    val title: String,
    val summary: String,
    val task: String = "",
    val sessionId: String = "",
    val parentSessionId: String = "",
    val fireAtMs: Long,
    val enabled: Boolean = true,
    val source: String = "",
    val priority: Int = 0,
    val deadlineAtMs: Long = 0L,
    val deferCount: Int = 0,
    val recommendedCommand: String = "",
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val metadata: Map<String, String> = emptyMap(),
)

object AssistantProactiveTaskStore {
    private const val TASK_DIR = "assistant_os"
    private const val TASK_FILE = "proactive_tasks.json"
    private const val MAX_TASKS = 120
    private val lock = Any()
    private val tasks = ArrayDeque<AssistantProactiveTask>()
    private var hydrated = false

    fun schedule(
        type: AssistantProactiveTaskType,
        capability: SessionCapabilityKey,
        dedupeKey: String = "",
        title: String,
        summary: String,
        task: String = "",
        sessionId: String = "",
        parentSessionId: String = "",
        fireAtMs: Long = System.currentTimeMillis(),
        source: String = "",
        priority: Int = 0,
        deadlineAtMs: Long = 0L,
        recommendedCommand: String = "",
        metadata: Map<String, String> = emptyMap(),
    ): AssistantProactiveTask {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            if (dedupeKey.isNotBlank()) {
                val existingIndex =
                    tasks.indexOfFirst { candidate ->
                        candidate.enabled && candidate.dedupeKey == dedupeKey
                    }
                if (existingIndex >= 0) {
                    val current = tasks.elementAt(existingIndex)
                    val updated =
                        current.copy(
                            type = type,
                            capability = capability,
                            title = title,
                            summary = summary,
                            task = task.trim(),
                            sessionId = sessionId,
                            parentSessionId = parentSessionId,
                            fireAtMs = fireAtMs,
                            enabled = true,
                            source = source,
                            priority = priority,
                            deadlineAtMs = deadlineAtMs,
                            deferCount = current.deferCount,
                            recommendedCommand = recommendedCommand,
                            updatedAtMs = now,
                            metadata = metadata,
                        )
                    tasks.removeAt(existingIndex)
                    tasks.add(existingIndex, updated)
                    persistUnlocked()
                    return updated
                }
            }
            val proactiveTask =
                AssistantProactiveTask(
                    id = "proactive_${now}_${type.name.lowercase()}",
                    type = type,
                    dedupeKey = dedupeKey,
                    capability = capability,
                    title = title,
                    summary = summary,
                    task = task.trim(),
                    sessionId = sessionId,
                    parentSessionId = parentSessionId,
                    fireAtMs = fireAtMs,
                    source = source,
                    priority = priority,
                    deadlineAtMs = deadlineAtMs,
                    recommendedCommand = recommendedCommand,
                    createdAtMs = now,
                    updatedAtMs = now,
                    metadata = metadata,
                )
            tasks.addFirst(proactiveTask)
            trimUnlocked()
            persistUnlocked()
            return proactiveTask
        }
    }

    fun readAll(
        limit: Int = 24,
    ): List<AssistantProactiveTask> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            tasks.sortedByDescending { it.createdAtMs }.take(limit)
        }

    fun importTasks(
        importedTasks: List<AssistantProactiveTask>,
    ) {
        if (importedTasks.isEmpty()) return
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val merged =
                (tasks + importedTasks)
                    .distinctBy { it.id }
                    .sortedByDescending { it.updatedAtMs }
                    .take(MAX_TASKS)
            tasks.clear()
            merged.forEach(tasks::addLast)
            persistUnlocked()
        }
    }

    fun readDue(
        nowMs: Long = System.currentTimeMillis(),
        limit: Int = 8,
    ): List<AssistantProactiveTask> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            tasks
                .filter { it.enabled && it.fireAtMs in 1..nowMs }
                .sortedBy { it.fireAtMs }
                .take(limit)
        }

    internal fun readDispatchSnapshot(
        activeSessionId: String = "",
        dispatchableWorkerIds: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis(),
        limit: Int = 8,
    ): AssistantProactiveTaskQueueSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            AssistantProactiveTaskDispatchPolicy.buildSnapshot(
                tasks = tasks.toList(),
                activeSessionId = activeSessionId,
                dispatchableWorkerIds = dispatchableWorkerIds,
                nowMs = nowMs,
                limit = limit,
            )
        }

    fun markCompleted(
        id: String,
    ) {
        mutate(id) { current ->
            current.copy(
                enabled = false,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun markCompletedMatching(
        type: AssistantProactiveTaskType? = null,
        sessionId: String = "",
        dedupeKey: String = "",
    ): Int =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val now = System.currentTimeMillis()
            var changed = 0
            val rewritten =
                tasks.map { current ->
                    val matches =
                        current.enabled &&
                            (type == null || current.type == type) &&
                            (sessionId.isBlank() || current.sessionId == sessionId) &&
                            (dedupeKey.isBlank() || current.dedupeKey == dedupeKey)
                    if (matches) {
                        changed += 1
                        current.copy(enabled = false, updatedAtMs = now)
                    } else {
                        current
                    }
                }
            if (changed > 0) {
                tasks.clear()
                rewritten.forEach(tasks::addLast)
                persistUnlocked()
            }
            changed
        }

    fun defer(
        id: String,
        deferByMs: Long,
    ) {
        mutate(id) { current ->
            current.copy(
                fireAtMs = System.currentTimeMillis() + deferByMs,
                deferCount = current.deferCount + 1,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    private fun mutate(
        id: String,
        reducer: (AssistantProactiveTask) -> AssistantProactiveTask,
    ) {
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val index = tasks.indexOfFirst { it.id == id }
            if (index < 0) return
            val next = reducer(tasks.elementAt(index))
            tasks.removeAt(index)
            tasks.add(index, next)
            persistUnlocked()
        }
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = taskFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("tasks") ?: JSONArray()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val type =
                    runCatching {
                        AssistantProactiveTaskType.valueOf(json.optString("type"))
                    }.getOrDefault(AssistantProactiveTaskType.SCHEDULED_TASK)
                val capability =
                    runCatching {
                        SessionCapabilityKey.valueOf(json.optString("capability"))
                    }.getOrDefault(SessionCapabilityKey.START_SESSION)
                tasks.addLast(
                    AssistantProactiveTask(
                        id = json.optString("id"),
                        type = type,
                        dedupeKey = json.optString("dedupe_key"),
                        capability = capability,
                        title = json.optString("title"),
                        summary = json.optString("summary"),
                        task = json.optString("task"),
                        sessionId = json.optString("session_id"),
                        parentSessionId = json.optString("parent_session_id"),
                        fireAtMs = json.optLong("fire_at_ms"),
                        enabled = json.optBoolean("enabled", true),
                        source = json.optString("source"),
                        priority = json.optInt("priority"),
                        deadlineAtMs = json.optLong("deadline_at_ms"),
                        deferCount = json.optInt("defer_count"),
                        recommendedCommand = json.optString("recommended_command"),
                        createdAtMs = json.optLong("created_at_ms"),
                        updatedAtMs = json.optLong("updated_at_ms"),
                        metadata = json.optJSONObject("metadata").toStringMap(),
                    ),
                )
            }
        }
    }

    private fun persistUnlocked() {
        val file = taskFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "tasks",
                    JSONArray().apply {
                        tasks.forEach { task ->
                            put(
                                JSONObject().apply {
                                    put("id", task.id)
                                    put("type", task.type.name)
                                    put("dedupe_key", task.dedupeKey)
                                    put("capability", task.capability.name)
                                    put("title", task.title)
                                    put("summary", task.summary)
                                    put("task", task.task)
                                    put("session_id", task.sessionId)
                                    put("parent_session_id", task.parentSessionId)
                put("fire_at_ms", task.fireAtMs)
                put("enabled", task.enabled)
                put("source", task.source)
                put("priority", task.priority)
                put("deadline_at_ms", task.deadlineAtMs)
                put("defer_count", task.deferCount)
                put("recommended_command", task.recommendedCommand)
                put("created_at_ms", task.createdAtMs)
                put("updated_at_ms", task.updatedAtMs)
                                    put(
                                        "metadata",
                                        JSONObject().apply {
                                            task.metadata.forEach { (key, value) ->
                                                put(key, value)
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }.toString(2),
        )
    }

    private fun trimUnlocked() {
        while (tasks.size > MAX_TASKS) {
            tasks.removeLast()
        }
    }

    private fun taskFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, TASK_DIR), TASK_FILE)
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap {
            keys().forEach { key ->
                put(key, optString(key))
            }
        }
    }
}
