package com.lmx.xiaoxuanagent.runtime

data class SessionHistoryEntry(
    val sessionId: String,
    val title: String,
    val task: String,
    val statusCode: String,
    val summary: String,
    val entrySource: String = "",
    val targetPackageName: String = "",
    val turnCount: Int = 0,
    val updatedAtMs: Long = 0L,
    val resumable: Boolean = false,
    val pendingSafety: Boolean = false,
    val lastActivitySummary: String = "",
)

data class SessionHistorySnapshot(
    val entries: List<SessionHistoryEntry> = emptyList(),
    val totalSessions: Int = 0,
    val resumableSessions: Int = 0,
    val failedSessions: Int = 0,
    val summary: String = "",
)

data class SessionHistorySearchResult(
    val query: String,
    val matches: List<SessionHistoryEntry> = emptyList(),
    val totalSessions: Int = 0,
    val summary: String = "",
)

internal object SessionHistoryService {
    fun readHistory(
        limit: Int = 24,
    ): SessionHistorySnapshot {
        val entries =
            ReplayStore.readRecentSessionSnapshots(limit = limit.coerceAtLeast(1) * 2)
                .map(::toHistoryEntry)
                .distinctBy { it.sessionId }
                .sortedByDescending { it.updatedAtMs }
                .take(limit)
        return SessionHistorySnapshot(
            entries = entries,
            totalSessions = entries.size,
            resumableSessions = entries.count { it.resumable },
            failedSessions = entries.count { AgentUiStatus.isFailed(it.statusCode) || AgentUiStatus.isStopped(it.statusCode) },
            summary =
                buildString {
                    append("sessions=").append(entries.size)
                    append(" resumable=").append(entries.count { it.resumable })
                    append(" failed=").append(entries.count { AgentUiStatus.isFailed(it.statusCode) || AgentUiStatus.isStopped(it.statusCode) })
                },
        )
    }

    fun searchHistory(
        query: String,
        limit: Int = 12,
    ): SessionHistorySearchResult {
        val needle = query.trim().lowercase()
        val history = readHistory(limit = 80)
        if (needle.isBlank()) {
            return SessionHistorySearchResult(
                query = query,
                matches = history.entries.take(limit),
                totalSessions = history.totalSessions,
                summary = history.summary,
            )
        }
        val matches =
            history.entries.filter { entry ->
                listOf(
                    entry.title,
                    entry.task,
                    entry.summary,
                    entry.entrySource,
                    entry.targetPackageName,
                    entry.lastActivitySummary,
                ).any { it.lowercase().contains(needle) }
            }.take(limit)
        return SessionHistorySearchResult(
            query = query,
            matches = matches,
            totalSessions = history.totalSessions,
            summary = if (matches.isEmpty()) "未命中 session history。" else "命中 ${matches.size} 条 session history。",
        )
    }

    fun buildHistorySummary(
        limit: Int = 4,
    ): String =
        readHistory(limit = limit).entries.joinToString(" | ") { entry ->
            "${entry.statusCode}:${entry.title.take(18)}"
        }.ifBlank { "-" }

    internal fun buildSessionTitle(
        snapshot: ReplaySessionSnapshot,
    ): String =
        when {
            snapshot.finalTaskResult?.title?.isNotBlank() == true ->
                snapshot.finalTaskResult.title.take(36)

            snapshot.latestTurn?.planStage?.isNotBlank() == true ->
                "${snapshot.task.take(20)} @ ${snapshot.latestTurn.planStage.take(24)}"

            snapshot.task.isNotBlank() ->
                snapshot.task.take(36)

            else -> snapshot.sessionId.take(16)
        }

    private fun toHistoryEntry(
        snapshot: ReplaySessionSnapshot,
    ): SessionHistoryEntry {
        val runtimeSummary =
            RuntimeEventStore.readRecent(snapshot.sessionId, 1).firstOrNull()?.summary.orEmpty()
        return SessionHistoryEntry(
            sessionId = snapshot.sessionId,
            title = buildSessionTitle(snapshot),
            task = snapshot.task,
            statusCode = snapshot.status,
            summary =
                snapshot.finalTaskResult?.summary?.ifBlank {
                    snapshot.finalMessage.ifBlank {
                        snapshot.latestTurn?.result.orEmpty()
                    }
                }.orEmpty(),
            entrySource = snapshot.entrySource,
            targetPackageName = snapshot.targetPackageName,
            turnCount = snapshot.turnCount,
            updatedAtMs = snapshot.updatedAt,
            resumable = snapshot.resume.active || SessionResumeStore.readSessionSnapshot(snapshot.sessionId)?.resumable == true,
            pendingSafety = snapshot.pendingSafetyConfirmation.isNotBlank(),
            lastActivitySummary = runtimeSummary,
        )
    }
}
