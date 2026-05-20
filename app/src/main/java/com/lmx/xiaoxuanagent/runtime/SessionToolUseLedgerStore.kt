package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.AgentToolCatalog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class SessionToolUseStatus {
    ATTEMPTED,
    SUCCEEDED,
    FAILED,
    BLOCKED,
    AWAITING_APPROVAL,
}

data class SessionToolUseEntry(
    val entryId: String,
    val sessionId: String,
    val turn: Int,
    val actionLabel: String,
    val toolName: String,
    val toolType: String,
    val status: SessionToolUseStatus = SessionToolUseStatus.ATTEMPTED,
    val summary: String = "",
    val permissionOutcome: String = "",
    val durationMs: Long = 0L,
    val readOnly: Boolean = false,
    val progressLabel: String = "",
    val interruptBehavior: String = "",
    val protocolSummary: String = "",
    val runtimeState: String = "",
    val queuedMessage: String = "",
    val progressMessage: String = "",
    val rejectedMessage: String = "",
    val errorMessage: String = "",
    val successMessage: String = "",
    val detailLines: List<String> = emptyList(),
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

object SessionToolUseLedgerStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_tool_use_ledger.json"
    private const val MAX_ENTRIES = 360
    private val lock = Any()
    private val entries = ArrayDeque<SessionToolUseEntry>()
    private var hydrated = false

    fun record(
        sessionId: String,
        turn: Int,
        action: AgentAction,
        status: SessionToolUseStatus,
        summary: String,
        permissionOutcome: String = "",
        durationMs: Long = 0L,
        readOnly: Boolean = false,
        progressLabel: String = "",
        interruptBehavior: String = "",
        protocolSummary: String = "",
        runtimeState: String = "",
        queuedMessage: String = "",
        progressMessage: String = "",
        rejectedMessage: String = "",
        errorMessage: String = "",
        successMessage: String = "",
        detailLines: List<String> = emptyList(),
    ): SessionToolUseEntry {
        val now = System.currentTimeMillis()
        val descriptor = AgentToolCatalog.find(action.toolName)
        val entry =
            SessionToolUseEntry(
                entryId = "tool_${now}_${action.toolName.hashCode().toUInt().toString(16)}",
                sessionId = sessionId,
                turn = turn,
                actionLabel = action.label,
                toolName = action.toolName,
                toolType = action.toolType.name.lowercase(),
                status = status,
                summary = summary.take(160),
                permissionOutcome = permissionOutcome.take(48),
                durationMs = durationMs.coerceAtLeast(0L),
                readOnly = readOnly,
                progressLabel = progressLabel.ifBlank { descriptor?.progressLabel.orEmpty() }.take(48),
                interruptBehavior = interruptBehavior.ifBlank { descriptor?.interruptBehavior.orEmpty() }.take(32),
                protocolSummary = protocolSummary.take(160),
                runtimeState = runtimeState.take(32),
                queuedMessage = queuedMessage.take(160),
                progressMessage = progressMessage.take(160),
                rejectedMessage = rejectedMessage.take(160),
                errorMessage = errorMessage.take(160),
                successMessage = successMessage.take(160),
                detailLines = detailLines.map { it.take(120) }.filter { it.isNotBlank() }.take(6),
                createdAtMs = now,
                updatedAtMs = now,
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
        limit: Int = 8,
    ): List<SessionToolUseEntry> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            entries
                .asSequence()
                .filter { sessionId.isBlank() || it.sessionId == sessionId }
                .take(limit.coerceAtLeast(1))
                .toList()
        }

    fun planningLines(
        sessionId: String,
        limit: Int = 4,
    ): List<String> =
        readRecent(sessionId = sessionId, limit = limit)
            .map { entry ->
                buildString {
                    append("tool_result: ")
                    append(entry.status.name.lowercase())
                    append(" | ").append(entry.toolName)
                    if (entry.readOnly) append(" | read_only")
                    entry.permissionOutcome.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
                    entry.runtimeState.takeIf { it.isNotBlank() }?.let { append(" | state=").append(it) }
                    entry.protocolSummary.takeIf { it.isNotBlank() }?.let { append(" | ").append(it.take(48)) }
                    listOf(entry.successMessage, entry.errorMessage, entry.rejectedMessage, entry.progressMessage, entry.summary)
                        .firstOrNull { it.isNotBlank() }
                        ?.let { append(" | ").append(it.take(72)) }
                }
            }

    fun summary(
        sessionId: String,
        limit: Int = 12,
    ): String {
        val recent = readRecent(sessionId = sessionId, limit = limit)
        if (recent.isEmpty()) return "tool_ledger=empty"
        val successCount = recent.count { it.status == SessionToolUseStatus.SUCCEEDED }
        val blockedCount = recent.count { it.status == SessionToolUseStatus.BLOCKED || it.status == SessionToolUseStatus.AWAITING_APPROVAL }
        val failedCount = recent.count { it.status == SessionToolUseStatus.FAILED }
        val topTool = recent.groupingBy { it.toolName }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
        return "tools=${recent.size} ok=$successCount blocked=$blockedCount failed=$failedCount top=${topTool.ifBlank { "-" }}"
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

    private fun SessionToolUseEntry.toJson(): JSONObject =
        JSONObject().apply {
            put("entry_id", entryId)
            put("session_id", sessionId)
            put("turn", turn)
            put("action_label", actionLabel)
            put("tool_name", toolName)
            put("tool_type", toolType)
            put("status", status.name)
            put("summary", summary)
            put("permission_outcome", permissionOutcome)
            put("duration_ms", durationMs)
            put("read_only", readOnly)
            put("progress_label", progressLabel)
            put("interrupt_behavior", interruptBehavior)
            put("protocol_summary", protocolSummary)
            put("runtime_state", runtimeState)
            put("queued_message", queuedMessage)
            put("progress_message", progressMessage)
            put("rejected_message", rejectedMessage)
            put("error_message", errorMessage)
            put("success_message", successMessage)
            put("detail_lines", JSONArray(detailLines))
            put("created_at_ms", createdAtMs)
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toEntry(): SessionToolUseEntry =
        SessionToolUseEntry(
            entryId = optString("entry_id"),
            sessionId = optString("session_id"),
            turn = optInt("turn"),
            actionLabel = optString("action_label"),
            toolName = optString("tool_name"),
            toolType = optString("tool_type"),
            status =
                optString("status")
                    .takeIf { it.isNotBlank() }
                    ?.let { runCatching { SessionToolUseStatus.valueOf(it) }.getOrNull() }
                    ?: SessionToolUseStatus.ATTEMPTED,
            summary = optString("summary"),
            permissionOutcome = optString("permission_outcome"),
            durationMs = optLong("duration_ms"),
            readOnly = optBoolean("read_only"),
            progressLabel = optString("progress_label"),
            interruptBehavior = optString("interrupt_behavior"),
            protocolSummary = optString("protocol_summary"),
            runtimeState = optString("runtime_state"),
            queuedMessage = optString("queued_message"),
            progressMessage = optString("progress_message"),
            rejectedMessage = optString("rejected_message"),
            errorMessage = optString("error_message"),
            successMessage = optString("success_message"),
            detailLines = optJSONArray("detail_lines").toStringList(),
            createdAtMs = optLong("created_at_ms"),
            updatedAtMs = optLong("updated_at_ms"),
        )
}
