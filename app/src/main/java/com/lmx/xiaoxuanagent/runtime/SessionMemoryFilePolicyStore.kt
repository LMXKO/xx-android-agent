package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionMemoryFilePolicySnapshot(
    val sessionId: String = "",
    val initialized: Boolean = false,
    val latestTurn: Int = 0,
    val lastUpdatedTurn: Int = 0,
    val lastEstimatedTokens: Int = 0,
    val lastToolCallCount: Int = 0,
    val minimumTokensToInit: Int = 900,
    val minimumTokensBetweenUpdate: Int = 320,
    val toolCallsBetweenUpdate: Int = 2,
    val lastReason: String = "",
    val updatedAtMs: Long = 0L,
)

data class SessionMemoryFilePolicyDecision(
    val snapshot: SessionMemoryFilePolicySnapshot,
    val shouldRefresh: Boolean = false,
    val reason: String = "",
)

object SessionMemoryFilePolicyStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_memory_file_policy.json"
    private const val MAX_SESSIONS = 160
    private val lock = Any()
    private val snapshots = LinkedHashMap<String, SessionMemoryFilePolicySnapshot>()
    private var hydrated = false

    fun evaluate(
        sessionId: String,
        turn: Int,
        estimatedTokens: Int,
        totalToolCalls: Int,
        trigger: String,
        force: Boolean = false,
    ): SessionMemoryFilePolicyDecision {
        if (sessionId.isBlank()) {
            return SessionMemoryFilePolicyDecision(SessionMemoryFilePolicySnapshot(), false, "")
        }
        return synchronized(lock) {
            hydrateIfNeededUnlocked()
            val previous = snapshots[sessionId] ?: SessionMemoryFilePolicySnapshot(sessionId = sessionId)
            val initialized = previous.initialized || estimatedTokens >= previous.minimumTokensToInit
            val tokenDelta = (estimatedTokens - previous.lastEstimatedTokens).coerceAtLeast(0)
            val toolDelta = (totalToolCalls - previous.lastToolCallCount).coerceAtLeast(0)
            val turnDelta = (turn - previous.lastUpdatedTurn).coerceAtLeast(0)
            val reason =
                when {
                    force -> "forced:$trigger"
                    !initialized -> "below_init_threshold"
                    previous.lastUpdatedTurn == 0 -> "initial_refresh"
                    tokenDelta >= previous.minimumTokensBetweenUpdate && toolDelta >= previous.toolCallsBetweenUpdate -> "token_and_tool_threshold"
                    tokenDelta >= previous.minimumTokensBetweenUpdate && trigger.contains("compact", ignoreCase = true) -> "compact_growth"
                    tokenDelta >= previous.minimumTokensBetweenUpdate && turnDelta >= 2 -> "token_growth"
                    toolDelta >= previous.toolCallsBetweenUpdate && trigger.contains("worker", ignoreCase = true) -> "worker_tool_growth"
                    else -> ""
                }
            val shouldRefresh = reason.isNotBlank() && reason != "below_init_threshold"
            val next =
                previous.copy(
                    sessionId = sessionId,
                    initialized = initialized,
                    latestTurn = maxOf(previous.latestTurn, turn),
                    lastUpdatedTurn = if (shouldRefresh) turn else previous.lastUpdatedTurn,
                    lastEstimatedTokens = if (shouldRefresh) estimatedTokens else previous.lastEstimatedTokens,
                    lastToolCallCount = if (shouldRefresh) totalToolCalls else previous.lastToolCallCount,
                    lastReason = reason.ifBlank { previous.lastReason },
                    updatedAtMs = System.currentTimeMillis(),
                )
            snapshots[sessionId] = next
            trimUnlocked()
            persistUnlocked()
            SessionMemoryFilePolicyDecision(snapshot = next, shouldRefresh = shouldRefresh, reason = reason)
        }
    }

    fun planningLines(
        sessionId: String,
        limit: Int = 3,
    ): List<String> =
        readSnapshot(sessionId)?.let { snapshot ->
            buildList {
                add("memory_file_policy: init=${snapshot.initialized} turn=${snapshot.lastUpdatedTurn} tokens=${snapshot.lastEstimatedTokens}")
                snapshot.lastReason.takeIf { it.isNotBlank() }?.let { add("memory_file_reason: ${it.take(96)}") }
            }.take(limit.coerceAtLeast(1))
        }.orEmpty()

    fun readSnapshot(sessionId: String): SessionMemoryFilePolicySnapshot? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshots[sessionId]
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

    private fun SessionMemoryFilePolicySnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("session_id", sessionId)
            put("initialized", initialized)
            put("latest_turn", latestTurn)
            put("last_updated_turn", lastUpdatedTurn)
            put("last_estimated_tokens", lastEstimatedTokens)
            put("last_tool_call_count", lastToolCallCount)
            put("minimum_tokens_to_init", minimumTokensToInit)
            put("minimum_tokens_between_update", minimumTokensBetweenUpdate)
            put("tool_calls_between_update", toolCallsBetweenUpdate)
            put("last_reason", lastReason)
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toSnapshot(): SessionMemoryFilePolicySnapshot =
        SessionMemoryFilePolicySnapshot(
            sessionId = optString("session_id"),
            initialized = optBoolean("initialized"),
            latestTurn = optInt("latest_turn"),
            lastUpdatedTurn = optInt("last_updated_turn"),
            lastEstimatedTokens = optInt("last_estimated_tokens"),
            lastToolCallCount = optInt("last_tool_call_count"),
            minimumTokensToInit = optInt("minimum_tokens_to_init", 900),
            minimumTokensBetweenUpdate = optInt("minimum_tokens_between_update", 320),
            toolCallsBetweenUpdate = optInt("tool_calls_between_update", 2),
            lastReason = optString("last_reason"),
            updatedAtMs = optLong("updated_at_ms"),
        )
}
