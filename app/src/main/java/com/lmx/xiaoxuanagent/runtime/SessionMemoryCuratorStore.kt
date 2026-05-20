package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionMemoryCuratorSnapshot(
    val sessionId: String = "",
    val workerId: String = "",
    val status: String = "",
    val summary: String = "",
    val policyReason: String = "",
    val lastTaskId: String = "",
    val notebookPath: String = "",
    val failureReason: String = "",
    val lines: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

object SessionMemoryCuratorStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_memory_curator.json"
    private const val MAX_SESSIONS = 160
    private val lock = Any()
    private val snapshots = LinkedHashMap<String, SessionMemoryCuratorSnapshot>()
    private var hydrated = false

    fun markQueued(
        sessionId: String,
        taskId: String = "",
        workerId: String = "",
        policyReason: String = "",
        summary: String = "",
    ): SessionMemoryCuratorSnapshot? =
        update(sessionId) { previous ->
            previous.copy(
                workerId = workerId.ifBlank { previous.workerId },
                status = "queued",
                summary = summary.ifBlank { "session notebook curator 已排队。" }.take(180),
                policyReason = policyReason.ifBlank { previous.policyReason }.take(160),
                lastTaskId = taskId.ifBlank { previous.lastTaskId },
                failureReason = "",
                lines =
                    buildList {
                        add("status=queued")
                        policyReason.takeIf { it.isNotBlank() }?.let { add("policy_reason=$it") }
                        taskId.takeIf { it.isNotBlank() }?.let { add("task_id=$it") }
                    },
                recommendedCommands = listOf("/memory-curator --session-id $sessionId", "/memory-policy --session-id $sessionId"),
            )
        }

    fun markRunning(
        sessionId: String,
        taskId: String = "",
        workerId: String = "",
    ): SessionMemoryCuratorSnapshot? =
        update(sessionId) { previous ->
            previous.copy(
                workerId = workerId.ifBlank { previous.workerId },
                status = "running",
                summary = "session notebook curator 正在整理记忆。",
                lastTaskId = taskId.ifBlank { previous.lastTaskId },
                lines =
                    buildList {
                        add("status=running")
                        taskId.takeIf { it.isNotBlank() }?.let { add("task_id=$it") }
                        previous.policyReason.takeIf { it.isNotBlank() }?.let { add("policy_reason=$it") }
                    },
                recommendedCommands = listOf("/memory-curator --session-id $sessionId", "/notebook --session-id $sessionId"),
            )
        }

    fun markCompleted(
        sessionId: String,
        summary: String = "",
        notebookPath: String = "",
        workerId: String = "",
    ): SessionMemoryCuratorSnapshot? =
        update(sessionId) { previous ->
            previous.copy(
                workerId = workerId.ifBlank { previous.workerId },
                status = "completed",
                summary = summary.ifBlank { "session notebook curator 已完成整理。" }.take(180),
                notebookPath = notebookPath.ifBlank { previous.notebookPath },
                failureReason = "",
                lines =
                    buildList {
                        add("status=completed")
                        previous.policyReason.takeIf { it.isNotBlank() }?.let { add("policy_reason=$it") }
                        notebookPath.ifBlank { previous.notebookPath }.takeIf { it.isNotBlank() }?.let { add("notebook=$it") }
                    },
                recommendedCommands = listOf("/memory-curator --session-id $sessionId", "/notebook --session-id $sessionId"),
            )
        }

    fun markFailed(
        sessionId: String,
        reason: String,
        deferred: Boolean,
        workerId: String = "",
    ): SessionMemoryCuratorSnapshot? =
        update(sessionId) { previous ->
            previous.copy(
                workerId = workerId.ifBlank { previous.workerId },
                status = if (deferred) "deferred" else "failed",
                summary =
                    if (deferred) {
                        "session notebook curator 已延后：${reason.take(96)}"
                    } else {
                        "session notebook curator 失败：${reason.take(96)}"
                    },
                failureReason = reason.take(160),
                lines =
                    buildList {
                        add("status=${if (deferred) "deferred" else "failed"}")
                        add("reason=${reason.take(120)}")
                        previous.policyReason.takeIf { it.isNotBlank() }?.let { add("policy_reason=$it") }
                    },
                recommendedCommands = listOf("/memory-curator --session-id $sessionId", "/memory-policy --session-id $sessionId"),
            )
        }

    fun readSnapshot(
        sessionId: String,
    ): SessionMemoryCuratorSnapshot? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshots[sessionId]
        }

    fun planningLines(
        sessionId: String,
        limit: Int = 3,
    ): List<String> =
        readSnapshot(sessionId)?.let { snapshot ->
            buildList {
                add("memory_curator: ${snapshot.status.ifBlank { "-" }} | ${snapshot.summary.ifBlank { "-" }}")
                snapshot.lines.take(2).forEach { add("memory_curator_detail: $it") }
            }.take(limit.coerceAtLeast(1))
        }.orEmpty()

    private fun update(
        sessionId: String,
        reducer: (SessionMemoryCuratorSnapshot) -> SessionMemoryCuratorSnapshot,
    ): SessionMemoryCuratorSnapshot? {
        if (sessionId.isBlank()) return null
        return synchronized(lock) {
            hydrateIfNeededUnlocked()
            val current = snapshots[sessionId] ?: SessionMemoryCuratorSnapshot(sessionId = sessionId)
            val next = reducer(current).copy(updatedAtMs = System.currentTimeMillis())
            snapshots[sessionId] = next
            trimUnlocked()
            persistUnlocked()
            next
        }
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = storeFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("snapshots") ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toSnapshot()?.let { snapshots[it.sessionId] = it }
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
                    "snapshots",
                    JSONArray().apply {
                        snapshots.values.sortedByDescending { it.updatedAtMs }.forEach { put(it.toJson()) }
                    },
                )
            }.toString(2),
        )
    }

    private fun trimUnlocked() {
        while (snapshots.size > MAX_SESSIONS) {
            val oldest = snapshots.minByOrNull { it.value.updatedAtMs }?.key ?: break
            snapshots.remove(oldest)
        }
    }

    private fun storeFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, STORE_DIR), STORE_FILE)
    }

    private fun SessionMemoryCuratorSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("session_id", sessionId)
            put("worker_id", workerId)
            put("status", status)
            put("summary", summary)
            put("policy_reason", policyReason)
            put("last_task_id", lastTaskId)
            put("notebook_path", notebookPath)
            put("failure_reason", failureReason)
            put("lines", JSONArray(lines))
            put("recommended_commands", JSONArray(recommendedCommands))
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toSnapshot(): SessionMemoryCuratorSnapshot =
        SessionMemoryCuratorSnapshot(
            sessionId = optString("session_id"),
            workerId = optString("worker_id"),
            status = optString("status"),
            summary = optString("summary"),
            policyReason = optString("policy_reason"),
            lastTaskId = optString("last_task_id"),
            notebookPath = optString("notebook_path"),
            failureReason = optString("failure_reason"),
            lines = optJSONArray("lines").toStringList(),
            recommendedCommands = optJSONArray("recommended_commands").toStringList(),
            updatedAtMs = optLong("updated_at_ms"),
        )
}
