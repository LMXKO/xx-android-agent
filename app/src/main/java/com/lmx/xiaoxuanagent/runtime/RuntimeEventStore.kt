package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class RuntimeEventEntry(
    val timestamp: Long,
    val sessionId: String,
    val commandType: String,
    val commandPayload: String = "",
    val transition: String,
    val statusCode: String,
    val turn: Int,
    val summary: String,
    val metadata: Map<String, String> = emptyMap(),
)

object RuntimeEventStore {
    private const val MAX_EVENT_ENTRIES = 240
    private const val LEDGER_DIR = "runtime"
    private const val LEDGER_FILE = "runtime_event_ledger.json"
    private val lock = Any()
    private val entries = ArrayDeque<RuntimeEventEntry>()
    private var hydrated = false

    fun append(
        command: SessionCommand,
        beforeState: SessionRuntimeState,
        afterState: SessionRuntimeState,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        val sessionId = afterState.session.sessionId.ifBlank { beforeState.session.sessionId }
        val beforeStatus = beforeState.resolveSessionSemantics().statusModel.code
        val afterStatus = afterState.resolveSessionSemantics().statusModel.code
        val transition = afterState.lastTransition.ifBlank { command.reasonText() }
        val metadata = buildMetadata(command, beforeState, afterState, beforeStatus, afterStatus, transition)
        val summary = buildSummary(command, afterState, beforeStatus, afterStatus, transition)
        val entry =
            RuntimeEventEntry(
                timestamp = timestamp,
                sessionId = sessionId,
                commandType = command.typeName(),
                commandPayload = command.toJson().toString(),
                transition = transition,
                statusCode = afterStatus,
                turn = afterState.session.turns,
                summary = summary,
                metadata = metadata,
            )
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            entries.addFirst(entry)
            while (entries.size > MAX_EVENT_ENTRIES) {
                entries.removeLast()
            }
            persistUnlocked()
            RuntimeMetricsStore.recordCommand(command)
        }
    }

    fun readRecent(
        sessionId: String,
        limit: Int = 8,
    ): List<RuntimeEventEntry> {
        if (sessionId.isBlank() || limit <= 0) {
            return emptyList()
        }
        return synchronized(lock) {
            hydrateIfNeededUnlocked()
            entries
                .asSequence()
                .filter { it.sessionId == sessionId }
                .take(limit)
                .toList()
        }
    }

    fun readGlobalRecent(limit: Int = 8): List<RuntimeEventEntry> {
        if (limit <= 0) {
            return emptyList()
        }
        return synchronized(lock) {
            hydrateIfNeededUnlocked()
            entries.take(limit).toList()
        }
    }

    fun readSessionCommandLedger(
        sessionId: String,
    ): List<SessionCommand> {
        if (sessionId.isBlank()) return emptyList()
        return synchronized(lock) {
            hydrateIfNeededUnlocked()
            entries
                .asSequence()
                .filter { it.sessionId == sessionId && it.commandPayload.isNotBlank() }
                .toList()
                .asReversed()
                .mapNotNull { entry ->
                    runCatching { jsonToSessionCommand(JSONObject(entry.commandPayload)) }.getOrNull()
                }
        }
    }

    fun readSessionTimeline(
        sessionId: String,
        limit: Int = 32,
    ): List<RuntimeEventEntry> {
        if (sessionId.isBlank()) return emptyList()
        return synchronized(lock) {
            hydrateIfNeededUnlocked()
            entries
                .asSequence()
                .filter { it.sessionId == sessionId }
                .toList()
                .asReversed()
                .takeLast(limit)
        }
    }

    fun importEntries(
        importedEntries: List<RuntimeEventEntry>,
    ) {
        if (importedEntries.isEmpty()) return
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val merged =
                (entries + importedEntries)
                    .distinctBy { "${it.timestamp}|${it.sessionId}|${it.commandType}|${it.summary}" }
                    .sortedByDescending { it.timestamp }
                    .take(MAX_EVENT_ENTRIES)
            entries.clear()
            merged.forEach(entries::addLast)
            persistUnlocked()
            RuntimeMetricsStore.recordPlatformEvent("import_runtime_events")
        }
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = ledgerFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("entries") ?: JSONArray()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                entries.addLast(
                    RuntimeEventEntry(
                        timestamp = json.optLong("timestamp"),
                        sessionId = json.optString("session_id"),
                        commandType = json.optString("command_type"),
                        commandPayload = json.optString("command_payload"),
                        transition = json.optString("transition"),
                        statusCode = json.optString("status_code"),
                        turn = json.optInt("turn"),
                        summary = json.optString("summary"),
                        metadata = json.optJSONObject("metadata").toStringMap(),
                    ),
                )
            }
        }
    }

    private fun persistUnlocked() {
        val file = ledgerFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "entries",
                    JSONArray().apply {
                        entries.forEach { entry ->
                            put(
                                JSONObject().apply {
                                    put("timestamp", entry.timestamp)
                                    put("session_id", entry.sessionId)
                                    put("command_type", entry.commandType)
                                    put("command_payload", entry.commandPayload)
                                    put("transition", entry.transition)
                                    put("status_code", entry.statusCode)
                                    put("turn", entry.turn)
                                    put("summary", entry.summary)
                                    put(
                                        "metadata",
                                        JSONObject().apply {
                                            entry.metadata.forEach { (key, value) ->
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

    private fun ledgerFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, LEDGER_DIR), LEDGER_FILE)
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap {
            keys().forEach { key ->
                put(key, optString(key))
            }
        }
    }

    private fun buildSummary(
        command: SessionCommand,
        afterState: SessionRuntimeState,
        beforeStatus: String,
        afterStatus: String,
        transition: String,
    ): String {
        val session = afterState.session
        val detail =
            listOfNotNull(
                session.task.takeIf { it.isNotBlank() }?.let { "task=${it.take(24)}" },
                afterState.routeSnapshot?.reason?.takeIf { it.isNotBlank() }?.let { "route=$it" },
                afterState.resultSnapshot?.summary?.takeIf { it.isNotBlank() }?.let { "result=${it.take(32)}" },
                afterState.resultSnapshot?.lastError?.takeIf { it.isNotBlank() }?.let { "error=${it.take(32)}" },
                afterState.takeoverSnapshot?.latestTakeoverReason?.takeIf { it.isNotBlank() }?.let { "takeover=${it.take(28)}" },
            ).firstOrNull().orEmpty()
        return buildString {
            append(command.typeName())
            append(" ")
            append(beforeStatus)
            append(" -> ")
            append(afterStatus)
            append(" @")
            append(transition)
            if (session.turns > 0) {
                append(" turn=")
                append(session.turns)
            }
            if (detail.isNotBlank()) {
                append(" | ")
                append(detail)
            }
        }
    }

    private fun buildMetadata(
        command: SessionCommand,
        beforeState: SessionRuntimeState,
        afterState: SessionRuntimeState,
        beforeStatus: String,
        afterStatus: String,
        transition: String,
    ): Map<String, String> =
        buildMap {
            put("transition", transition)
            put("before_status", beforeStatus)
            put("after_status", afterStatus)
            put("before_turn", beforeState.session.turns.toString())
            put("after_turn", afterState.session.turns.toString())
            put("reason", command.reasonText())
            afterState.routeSnapshot?.reason?.takeIf { it.isNotBlank() }?.let { put("route_reason", it) }
            afterState.resultSnapshot?.lastAction?.takeIf { it.isNotBlank() }?.let { put("last_action", it) }
            afterState.resultSnapshot?.summary?.takeIf { it.isNotBlank() }?.let { put("result_summary", it.take(120)) }
            afterState.resultSnapshot?.lastError?.takeIf { it.isNotBlank() }?.let { put("last_error", it.take(120)) }
            afterState.takeoverSnapshot?.latestTakeoverType?.takeIf { it.isNotBlank() }?.let { put("takeover_type", it) }
            afterState.takeoverSnapshot?.latestTakeoverReason?.takeIf { it.isNotBlank() }?.let { put("takeover_reason", it.take(120)) }
            afterState.safety.pendingConfirmation?.actionLabel?.takeIf { it.isNotBlank() }?.let { put("pending_confirmation_action", it) }
        }
}

private fun SessionCommand.typeName(): String =
    when (this) {
        is SessionCommand.BlockPreflight -> "BlockPreflight"
        is SessionCommand.EnterRouting -> "EnterRouting"
        is SessionCommand.RoutingFailed -> "RoutingFailed"
        is SessionCommand.MarkAppUnavailable -> "MarkAppUnavailable"
        is SessionCommand.RouteResolved -> "RouteResolved"
        is SessionCommand.StartExecution -> "StartExecution"
        is SessionCommand.RecordPlannerDecisionState -> "RecordPlannerDecisionState"
        is SessionCommand.RecordResumeDecisionState -> "RecordResumeDecisionState"
        is SessionCommand.ResetSafety -> "ResetSafety"
        is SessionCommand.RejectSafetyAction -> "RejectSafetyAction"
        is SessionCommand.ConsumeGrantedRiskApproval -> "ConsumeGrantedRiskApproval"
        is SessionCommand.FinishTurn -> "FinishTurn"
        is SessionCommand.AcquirePlanning -> "AcquirePlanning"
        is SessionCommand.PlanningAcquired -> "PlanningAcquired"
        is SessionCommand.RecordRecoverableError -> "RecordRecoverableError"
        is SessionCommand.TerminateSession -> "TerminateSession"
        is SessionCommand.EnterTakeover -> "EnterTakeover"
        is SessionCommand.ResumeExecution -> "ResumeExecution"
        is SessionCommand.AdoptResumeContext -> "AdoptResumeContext"
        is SessionCommand.AdvanceMissionLeg -> "AdvanceMissionLeg"
    }

private fun SessionCommand.reasonText(): String =
    when (this) {
        is SessionCommand.BlockPreflight -> reason
        is SessionCommand.EnterRouting -> reason
        is SessionCommand.RoutingFailed -> reason
        is SessionCommand.MarkAppUnavailable -> reason
        is SessionCommand.RouteResolved -> reason
        is SessionCommand.StartExecution -> reason
        is SessionCommand.RecordPlannerDecisionState -> reason
        is SessionCommand.RecordResumeDecisionState -> reason
        is SessionCommand.ResetSafety -> reason
        is SessionCommand.RejectSafetyAction -> reason
        is SessionCommand.ConsumeGrantedRiskApproval -> reason
        is SessionCommand.FinishTurn -> reason
        is SessionCommand.AcquirePlanning -> reason
        is SessionCommand.PlanningAcquired -> reason
        is SessionCommand.RecordRecoverableError -> reason
        is SessionCommand.TerminateSession -> reason
        is SessionCommand.EnterTakeover -> reason
        is SessionCommand.ResumeExecution -> reason
        is SessionCommand.AdoptResumeContext -> reason
        is SessionCommand.AdvanceMissionLeg -> reason
    }
