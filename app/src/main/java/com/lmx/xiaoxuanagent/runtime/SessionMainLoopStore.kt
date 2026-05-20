package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionMainLoopSnapshot(
    val sessionId: String,
    val turn: Int = 0,
    val phase: String = "",
    val summary: String = "",
    val queueSummary: String = "",
    val memorySummary: String = "",
    val toolSummary: String = "",
    val permissionSummary: String = "",
    val groundingSummary: String = "",
    val recommendedCommands: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

object SessionMainLoopStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_main_loop.json"
    private const val MAX_SESSIONS = 160
    private val lock = Any()
    private val snapshots = LinkedHashMap<String, SessionMainLoopSnapshot>()
    private var hydrated = false

    fun refresh(
        sessionId: String,
        turn: Int,
        phase: String,
        summary: String = "",
        recommendedCommands: List<String> = emptyList(),
    ): SessionMainLoopSnapshot? {
        if (sessionId.isBlank()) return null
        val maintenance = SessionMemoryMaintenanceStore.read()
        val memoryPolicy = SessionMemoryPolicyStore.readSnapshot(sessionId)
        val memoryCurator = SessionMemoryCuratorStore.readSnapshot(sessionId)
        val memoryFork = SessionMemoryForkRuntimeStore.refresh(sessionId)
        val loopInbox = SessionLoopInboxStore.refresh(sessionId = sessionId)
        val loopRuntime = SessionLoopRuntimeStore.refresh(sessionId = sessionId, turn = turn, phase = phase)
        val turnLoop = SessionTurnLoopStore.readSnapshot(sessionId)
        val toolRuntime = SessionToolRuntimeStore.readSnapshot(sessionId)
        val toolContracts = SessionToolContractStore.readSnapshot(limit = 6)
        val lifecycle = SessionActionLifecycleStore.readSnapshot(sessionId)
        val permissionCenter = SessionPermissionCenterStore.readSnapshot(sessionId)
        val permissionProduct = SessionPermissionProductStore.refresh(sessionId = sessionId, limit = 8)
        val groundingHealth = SessionGroundingHealthStore.readSnapshot(sessionId)
        val commandCenter = SessionCommandCenterStore.readRecentForSession(sessionId, limit = 3)
        val taskOs =
            com.lmx.xiaoxuanagent.assistantos.AssistantTaskOsStore.derive(
                tasks = SessionPlatformFacade.readProactiveTasks(limit = 24),
                activeSessionId = SessionRuntime.State.runtimeState().session.sessionId,
            )
        val queueSummary =
            buildString {
                append("commands=").append(commandCenter.size)
                loopInbox?.summary?.takeIf { it.isNotBlank() }?.let { append(" inbox=").append(it.take(64)) }
                loopRuntime?.summary?.takeIf { it.isNotBlank() }?.let { append(" runtime=").append(it.take(72)) }
                loopRuntime?.midTurnSummary?.takeIf { it.isNotBlank() }?.let { append(" mid_turn=").append(it.take(72)) }
                turnLoop?.blockerLines?.firstOrNull()?.let { append(" blocker=").append(it.take(48)) }
                if (taskOs.overdueCount > 0 || taskOs.approvalCount > 0) {
                    append(" task_os=").append(taskOs.summary)
                }
            }
        val snapshot =
            SessionMainLoopSnapshot(
                sessionId = sessionId,
                turn = turn,
                phase = phase,
                summary = summary.ifBlank { turnLoop?.summary.orEmpty() }.take(180),
                queueSummary = queueSummary,
                memorySummary =
                    buildString {
                        append("pending=").append(maintenance.pendingCount)
                        append(" deferred=").append(maintenance.deferredCount)
                        append(" failed=").append(maintenance.failedCount)
                        memoryPolicy?.summary?.takeIf { it.isNotBlank() }?.let { append(" cadence=").append(it) }
                        memoryCurator?.summary?.takeIf { it.isNotBlank() }?.let { append(" curator=").append(it.take(48)) }
                        memoryFork?.summary?.takeIf { it.isNotBlank() }?.let { append(" fork=").append(it.take(48)) }
                    },
                toolSummary =
                    buildString {
                        append(toolRuntime.summary)
                        append(" contracts=").append(toolContracts.totalCount)
                        lifecycle.latestSummary.takeIf { it.isNotBlank() }?.let { append(" latest=").append(it.take(72)) }
                    },
                permissionSummary = "${permissionCenter.summary} | ${permissionProduct.summary}".trim(),
                groundingSummary = groundingHealth.summary,
                recommendedCommands =
                    (
                        recommendedCommands +
                            permissionCenter.recommendedCommands +
                            permissionProduct.recommendedCommands +
                            turnLoop?.recommendedCommands.orEmpty() +
                            lifecycle.recommendedCommands +
                            memoryPolicy?.recommendedCommands.orEmpty() +
                            memoryCurator?.recommendedCommands.orEmpty() +
                            memoryFork?.recommendedCommands.orEmpty() +
                            loopRuntime?.recommendedCommands.orEmpty() +
                            loopInbox?.recommendedCommands.orEmpty()
                    ).distinct().take(8),
                updatedAtMs = System.currentTimeMillis(),
            )
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshots[sessionId] = snapshot
            trimUnlocked()
            persistUnlocked()
        }
        return snapshot
    }

    fun readSnapshot(
        sessionId: String,
    ): SessionMainLoopSnapshot? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshots[sessionId]
        }

    fun planningLines(
        sessionId: String,
        limit: Int = 4,
    ): List<String> =
        readSnapshot(sessionId)?.let { snapshot ->
            buildList {
                add("main_loop: ${snapshot.phase.ifBlank { "-" }} | ${snapshot.summary.ifBlank { "-" }}")
                add("main_loop_queue: ${snapshot.queueSummary.ifBlank { "-" }}")
                add("main_loop_memory: ${snapshot.memorySummary.ifBlank { "-" }}")
                add("main_loop_tool: ${snapshot.toolSummary.ifBlank { "-" }}")
                add("main_loop_grounding: ${snapshot.groundingSummary.ifBlank { "-" }}")
            }.take(limit.coerceAtLeast(1))
        }.orEmpty()

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

    private fun SessionMainLoopSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("session_id", sessionId)
            put("turn", turn)
            put("phase", phase)
            put("summary", summary)
            put("queue_summary", queueSummary)
            put("memory_summary", memorySummary)
            put("tool_summary", toolSummary)
            put("permission_summary", permissionSummary)
            put("grounding_summary", groundingSummary)
            put("recommended_commands", JSONArray(recommendedCommands))
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toSnapshot(): SessionMainLoopSnapshot =
        SessionMainLoopSnapshot(
            sessionId = optString("session_id"),
            turn = optInt("turn"),
            phase = optString("phase"),
            summary = optString("summary"),
            queueSummary = optString("queue_summary"),
            memorySummary = optString("memory_summary"),
            toolSummary = optString("tool_summary"),
            permissionSummary = optString("permission_summary"),
            groundingSummary = optString("grounding_summary"),
            recommendedCommands = optJSONArray("recommended_commands").toStringList(),
            updatedAtMs = optLong("updated_at_ms"),
        )
}
