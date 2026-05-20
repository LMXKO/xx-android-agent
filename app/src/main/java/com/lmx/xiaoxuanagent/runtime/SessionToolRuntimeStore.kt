package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentToolCatalog
import com.lmx.xiaoxuanagent.agent.AgentToolRuntimeObjectRegistry

data class SessionToolRuntimeSnapshot(
    val sessionId: String = "",
    val totalCount: Int = 0,
    val successCount: Int = 0,
    val blockedCount: Int = 0,
    val failedCount: Int = 0,
    val avgDurationMs: Long = 0L,
    val topToolName: String = "",
    val permissionOutcomes: List<String> = emptyList(),
    val summary: String = "",
    val lines: List<String> = emptyList(),
)

object SessionToolRuntimeStore {
    fun readSnapshot(
        sessionId: String,
        limit: Int = 12,
    ): SessionToolRuntimeSnapshot {
        val entries = SessionToolUseLedgerStore.readRecent(sessionId = sessionId, limit = limit)
        val lifecycle = SessionActionLifecycleStore.readRecent(sessionId = sessionId, limit = limit)
        val total = entries.size
        val success = entries.count { it.status == SessionToolUseStatus.SUCCEEDED }
        val blocked = entries.count { it.status == SessionToolUseStatus.BLOCKED || it.status == SessionToolUseStatus.AWAITING_APPROVAL }
        val failed = entries.count { it.status == SessionToolUseStatus.FAILED }
        val avgDuration =
            entries.map { it.durationMs }.filter { it > 0L }.average().takeIf { !it.isNaN() }?.toLong() ?: 0L
        val topTool = entries.groupingBy { it.toolName }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
        val permissionOutcomes =
            entries.mapNotNull { it.permissionOutcome.takeIf(String::isNotBlank) }.distinct().take(6)
        val contractCatalog = AgentToolRuntimeObjectRegistry.catalog().associateBy { it.name }
        return SessionToolRuntimeSnapshot(
            sessionId = sessionId,
            totalCount = total,
            successCount = success,
            blockedCount = blocked,
            failedCount = failed,
                avgDurationMs = avgDuration,
                topToolName = topTool,
                permissionOutcomes = permissionOutcomes,
                summary =
                    buildString {
                        append("tools=").append(total)
                        append(" ok=").append(success)
                        append(" blocked=").append(blocked)
                        append(" failed=").append(failed)
                        append(" avg=").append(avgDuration).append("ms")
                        append(" top=").append(topTool.ifBlank { "-" })
                        if (entries.any { it.readOnly }) append(" read_only=").append(entries.count { it.readOnly })
                        append(" contracts=").append(contractCatalog.size)
                        append(" interactive=").append(contractCatalog.values.count { it.previewContract.requiresUserInteraction })
                        lifecycle.firstOrNull()?.let { append(" latest=").append(it.phase).append("/").append(it.status) }
                    },
                lines =
                    entries.map { entry ->
                        val descriptor = AgentToolCatalog.find(entry.toolName)
                        val runtimeObject = contractCatalog[entry.toolName]
                        val contract = runtimeObject?.previewContract
                        buildString {
                            append(entry.turn).append(" | ").append(entry.status.name.lowercase())
                            append(" | ").append(entry.toolName)
                            if (entry.readOnly) append(" | read_only")
                            descriptor?.permissionFamily?.takeIf { it.isNotBlank() }?.let { append(" | perm=").append(it) }
                            runtimeObject?.descriptor?.title?.takeIf { it.isNotBlank() }?.let { append(" | title=").append(it.take(24)) }
                            runtimeObject?.groupKey?.takeIf { it.isNotBlank() }?.let { append(" | group=").append(it.take(36)) }
                            entry.progressLabel.takeIf { it.isNotBlank() }?.let { append(" | progress=").append(it) }
                            entry.interruptBehavior.takeIf { it.isNotBlank() }?.let { append(" | interrupt=").append(it) }
                            contract?.requiresUserInteraction?.takeIf { it }?.let { append(" | interact") }
                            contract?.shouldDefer?.takeIf { it }?.let { append(" | defer") }
                            contract?.alwaysLoad?.takeIf { it }?.let { append(" | always") }
                            if (entry.durationMs > 0L) append(" | ").append(entry.durationMs).append("ms")
                            entry.permissionOutcome.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
                            entry.runtimeState.takeIf { it.isNotBlank() }?.let { append(" | state=").append(it) }
                            entry.protocolSummary.takeIf { it.isNotBlank() }?.let { append(" | protocol=").append(it.take(48)) }
                            listOf(
                                entry.successMessage,
                                entry.errorMessage,
                                entry.rejectedMessage,
                                entry.progressMessage,
                                entry.queuedMessage,
                                entry.summary,
                            ).firstOrNull { it.isNotBlank() }?.let { append(" | ").append(it.take(88)) }
                        }
                    },
        )
    }
}
