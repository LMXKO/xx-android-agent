package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionMemoryMaintenanceSnapshot(
    val pendingCount: Int = 0,
    val runningCount: Int = 0,
    val deferredCount: Int = 0,
    val failedCount: Int = 0,
    val completedCount: Int = 0,
    val notebookUpdates: Int = 0,
    val lastTaskId: String = "",
    val lastTaskType: String = "",
    val lastStatus: String = "",
    val lastSummary: String = "",
    val lastUpdatedAtMs: Long = 0L,
)

object SessionMemoryMaintenanceStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_memory_maintenance.json"
    private val lock = Any()
    private var hydrated = false
    private var snapshot = SessionMemoryMaintenanceSnapshot()

    fun read(): SessionMemoryMaintenanceSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshot
        }

    fun recordTaskState(
        task: BackgroundMemoryQueueTask,
        status: BackgroundMemoryTaskStatus,
        summary: String,
    ): SessionMemoryMaintenanceSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val queue = BackgroundMemoryExtractor.readQueue(includeCompleted = true, limit = 240)
            val next =
                SessionMemoryMaintenanceSnapshot(
                    pendingCount = queue.count { it.status == BackgroundMemoryTaskStatus.PENDING },
                    runningCount = queue.count { it.status == BackgroundMemoryTaskStatus.RUNNING },
                    deferredCount = queue.count { it.status == BackgroundMemoryTaskStatus.DEFERRED },
                    failedCount = queue.count { it.status == BackgroundMemoryTaskStatus.FAILED },
                    completedCount = queue.count { it.status == BackgroundMemoryTaskStatus.COMPLETED },
                    notebookUpdates = queue.count { it.type == BackgroundMemoryTaskType.SESSION_NOTEBOOK && it.status == BackgroundMemoryTaskStatus.COMPLETED },
                    lastTaskId = task.taskId,
                    lastTaskType = task.type.name.lowercase(),
                    lastStatus = status.name.lowercase(),
                    lastSummary = summary.take(160),
                    lastUpdatedAtMs = System.currentTimeMillis(),
                )
            snapshot = next
            persistUnlocked()
            next
        }

    fun planningLines(
        limit: Int = 3,
    ): List<String> =
        read().let { current ->
            buildList {
                add("memory_maintenance: pending=${current.pendingCount} deferred=${current.deferredCount} failed=${current.failedCount}")
                current.lastSummary.takeIf { it.isNotBlank() }?.let {
                    add("memory_last: ${current.lastStatus.ifBlank { "-" }} | ${current.lastTaskType.ifBlank { "-" }} | ${it.take(88)}")
                }
            }.take(limit.coerceAtLeast(1))
        }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = storeFile() ?: return
        if (!file.exists()) return
        runCatching {
            snapshot = JSONObject(file.readText()).toSnapshot()
        }
    }

    private fun persistUnlocked() {
        val file = storeFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(snapshot.toJson().toString(2))
    }

    private fun storeFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, STORE_DIR), STORE_FILE)
    }

    private fun SessionMemoryMaintenanceSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("pending_count", pendingCount)
            put("running_count", runningCount)
            put("deferred_count", deferredCount)
            put("failed_count", failedCount)
            put("completed_count", completedCount)
            put("notebook_updates", notebookUpdates)
            put("last_task_id", lastTaskId)
            put("last_task_type", lastTaskType)
            put("last_status", lastStatus)
            put("last_summary", lastSummary)
            put("last_updated_at_ms", lastUpdatedAtMs)
        }

    private fun JSONObject.toSnapshot(): SessionMemoryMaintenanceSnapshot =
        SessionMemoryMaintenanceSnapshot(
            pendingCount = optInt("pending_count"),
            runningCount = optInt("running_count"),
            deferredCount = optInt("deferred_count"),
            failedCount = optInt("failed_count"),
            completedCount = optInt("completed_count"),
            notebookUpdates = optInt("notebook_updates"),
            lastTaskId = optString("last_task_id"),
            lastTaskType = optString("last_task_type"),
            lastStatus = optString("last_status"),
            lastSummary = optString("last_summary"),
            lastUpdatedAtMs = optLong("last_updated_at_ms"),
        )
}
