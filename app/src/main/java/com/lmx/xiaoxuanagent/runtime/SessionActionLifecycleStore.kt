package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.AgentToolCatalog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionActionLifecycleEntry(
    val entryId: String,
    val sessionId: String,
    val turn: Int = 0,
    val toolName: String = "",
    val actionLabel: String = "",
    val phase: String = "",
    val status: String = "",
    val permissionFamily: String = "",
    val progressLabel: String = "",
    val runtimeState: String = "",
    val uiMessage: String = "",
    val summary: String = "",
    val recommendedCommands: List<String> = emptyList(),
    val detailLines: List<String> = emptyList(),
    val createdAtMs: Long = 0L,
)

data class SessionActionLifecycleSnapshot(
    val sessionId: String = "",
    val totalCount: Int = 0,
    val latestSummary: String = "",
    val attentionSummary: String = "",
    val lines: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
)

object SessionActionLifecycleStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_action_lifecycle.json"
    private const val MAX_ENTRIES = 640
    private val lock = Any()
    private val entries = ArrayDeque<SessionActionLifecycleEntry>()
    private var hydrated = false

    fun record(
        sessionId: String,
        turn: Int,
        action: AgentAction,
        phase: String,
        status: String,
        summary: String,
        runtimeState: String = "",
        uiMessage: String = "",
        recommendedCommands: List<String> = emptyList(),
        detailLines: List<String> = emptyList(),
    ): SessionActionLifecycleEntry? {
        if (sessionId.isBlank() || phase.isBlank() || status.isBlank() || summary.isBlank()) return null
        val descriptor = AgentToolCatalog.find(action.toolName)
        val now = System.currentTimeMillis()
        val entry =
            SessionActionLifecycleEntry(
                entryId = "lifecycle_${now}_${action.toolName.hashCode().toUInt().toString(16)}",
                sessionId = sessionId,
                turn = turn,
                toolName = action.toolName,
                actionLabel = action.label,
                phase = phase,
                status = status,
                permissionFamily = descriptor?.permissionFamily.orEmpty(),
                progressLabel = descriptor?.progressLabel.orEmpty(),
                runtimeState = runtimeState.take(32),
                uiMessage = uiMessage.take(180),
                summary = summary.take(180),
                recommendedCommands = recommendedCommands.distinct().take(6),
                detailLines = detailLines.map(String::trim).filter(String::isNotBlank).take(6),
                createdAtMs = now,
        )
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            entries.addFirst(entry)
            trimUnlocked()
            persistUnlocked()
        }
        return entry
    }

    fun recordSynthetic(
        sessionId: String,
        turn: Int,
        toolName: String,
        actionLabel: String,
        phase: String,
        status: String,
        summary: String,
        permissionFamily: String = "",
        progressLabel: String = "",
        runtimeState: String = "",
        uiMessage: String = "",
        recommendedCommands: List<String> = emptyList(),
        detailLines: List<String> = emptyList(),
    ): SessionActionLifecycleEntry? {
        if (sessionId.isBlank() || toolName.isBlank() || phase.isBlank() || status.isBlank() || summary.isBlank()) return null
        val now = System.currentTimeMillis()
        val entry =
            SessionActionLifecycleEntry(
                entryId = "lifecycle_${now}_${toolName.hashCode().toUInt().toString(16)}",
                sessionId = sessionId,
                turn = turn,
                toolName = toolName,
                actionLabel = actionLabel,
                phase = phase,
                status = status,
                permissionFamily = permissionFamily,
                progressLabel = progressLabel,
                runtimeState = runtimeState.take(32),
                uiMessage = uiMessage.take(180),
                summary = summary.take(180),
                recommendedCommands = recommendedCommands.distinct().take(6),
                detailLines = detailLines.map(String::trim).filter(String::isNotBlank).take(6),
                createdAtMs = now,
            )
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            entries.addFirst(entry)
            trimUnlocked()
            persistUnlocked()
        }
        return entry
    }

    fun readRecent(
        sessionId: String = "",
        limit: Int = 10,
    ): List<SessionActionLifecycleEntry> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            entries
                .asSequence()
                .filter { sessionId.isBlank() || it.sessionId == sessionId }
                .take(limit.coerceAtLeast(1))
                .toList()
        }

    fun readSnapshot(
        sessionId: String,
        limit: Int = 10,
    ): SessionActionLifecycleSnapshot {
        val recent = readRecent(sessionId = sessionId, limit = limit)
        val awaiting = recent.count { it.status == "awaiting_approval" }
        val failed = recent.count { it.status == "failed" || it.status == "blocked" || it.status == "recovery" }
        return SessionActionLifecycleSnapshot(
            sessionId = sessionId,
            totalCount =
                synchronized(lock) {
                    hydrateIfNeededUnlocked()
                    entries.count { it.sessionId == sessionId }
                },
            latestSummary = recent.firstOrNull()?.summary.orEmpty(),
            attentionSummary =
                buildString {
                    append("awaiting=").append(awaiting)
                    append(" attention=").append(failed)
                    recent.firstOrNull()?.let { append(" latest=").append(it.phase).append("/").append(it.status) }
                },
            lines =
                recent.map { entry ->
                    buildString {
                        append('#').append(entry.turn)
                        append(" | ").append(entry.phase)
                        append(" | ").append(entry.status)
                        append(" | ").append(entry.toolName)
                        entry.runtimeState.takeIf { it.isNotBlank() }?.let { append(" | state=").append(it) }
                        entry.progressLabel.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
                        append(" | ").append(entry.uiMessage.ifBlank { entry.summary }.take(96))
                    }
                },
            recommendedCommands =
                recent.flatMap { it.recommendedCommands }.distinct().take(8),
        )
    }

    fun planningLines(
        sessionId: String,
        limit: Int = 3,
    ): List<String> {
        val snapshot = readSnapshot(sessionId = sessionId, limit = maxOf(limit, 6))
        if (snapshot.totalCount <= 0) return emptyList()
        return buildList {
            add("action_lifecycle: ${snapshot.attentionSummary}")
            addAll(snapshot.lines.take(limit.coerceAtLeast(1)).map { "action_lifecycle_recent: $it" })
        }.take(limit.coerceAtLeast(1))
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = storeFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("entries") ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toEntry()?.let(entries::addLast)
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
                    "entries",
                    JSONArray().apply {
                        entries.forEach { put(it.toJson()) }
                    },
                )
            }.toString(2),
        )
    }

    private fun trimUnlocked() {
        while (entries.size > MAX_ENTRIES) {
            entries.removeLast()
        }
    }

    private fun storeFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, STORE_DIR), STORE_FILE)
    }

    private fun SessionActionLifecycleEntry.toJson(): JSONObject =
        JSONObject().apply {
            put("entry_id", entryId)
            put("session_id", sessionId)
            put("turn", turn)
            put("tool_name", toolName)
            put("action_label", actionLabel)
            put("phase", phase)
            put("status", status)
            put("permission_family", permissionFamily)
            put("progress_label", progressLabel)
            put("runtime_state", runtimeState)
            put("ui_message", uiMessage)
            put("summary", summary)
            put("recommended_commands", JSONArray(recommendedCommands))
            put("detail_lines", JSONArray(detailLines))
            put("created_at_ms", createdAtMs)
        }

    private fun JSONObject.toEntry(): SessionActionLifecycleEntry =
        SessionActionLifecycleEntry(
            entryId = optString("entry_id"),
            sessionId = optString("session_id"),
            turn = optInt("turn"),
            toolName = optString("tool_name"),
            actionLabel = optString("action_label"),
            phase = optString("phase"),
            status = optString("status"),
            permissionFamily = optString("permission_family"),
            progressLabel = optString("progress_label"),
            runtimeState = optString("runtime_state"),
            uiMessage = optString("ui_message"),
            summary = optString("summary"),
            recommendedCommands = optJSONArray("recommended_commands").toStringList(),
            detailLines = optJSONArray("detail_lines").toStringList(),
            createdAtMs = optLong("created_at_ms"),
        )
}
