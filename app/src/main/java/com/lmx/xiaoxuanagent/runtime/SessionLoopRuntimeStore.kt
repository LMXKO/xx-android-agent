package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentToolCatalog
import com.lmx.xiaoxuanagent.assistantos.AssistantTaskOsStore
import com.lmx.xiaoxuanagent.skills.SkillRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionLoopRuntimeSnapshot(
    val sessionId: String = "",
    val turn: Int = 0,
    val phase: String = "",
    val queueDrainCount: Int = 0,
    val attachmentCount: Int = 0,
    val prefetchCount: Int = 0,
    val taskSignalCount: Int = 0,
    val toolCatalogCount: Int = 0,
    val drainLines: List<String> = emptyList(),
    val prefetchLines: List<String> = emptyList(),
    val skillPrefetchLines: List<String> = emptyList(),
    val toolRefreshLines: List<String> = emptyList(),
    val taskSummaryLines: List<String> = emptyList(),
    val shouldContinueNow: Boolean = false,
    val midTurnSummary: String = "",
    val summary: String = "",
    val lines: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

object SessionLoopRuntimeStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_loop_runtime.json"
    private const val MAX_SESSIONS = 160
    private val lock = Any()
    private val snapshots = LinkedHashMap<String, SessionLoopRuntimeSnapshot>()
    private var hydrated = false

    fun refresh(
        sessionId: String,
        turn: Int = 0,
        task: String = "",
        profileId: String = "",
        phase: String = "",
        turnAttachments: SessionTurnAttachmentSnapshot? = null,
        limit: Int = 8,
    ): SessionLoopRuntimeSnapshot? {
        if (sessionId.isBlank()) return null
        val loopInbox = SessionLoopInboxStore.refresh(sessionId = sessionId, task = task, limit = limit)
        val attachmentSnapshot =
            turnAttachments
                ?: SessionTurnAttachmentStore.readSnapshot(sessionId)
                ?: SessionTurnAttachmentStore.refresh(
                    sessionId = sessionId,
                    turn = turn,
                    task = task,
                    profileId = profileId,
                    limit = limit,
                )
        val mailbox = SessionWorkerMailboxStore.readSnapshot(target = sessionId, limit = limit)
        val commands =
            SessionCommandCenterStore.readRecentForSession(sessionId, limit = limit)
                .filter {
                    it.status == SessionCommandReceiptStatus.SUBMITTED ||
                        it.status == SessionCommandReceiptStatus.RUNNING ||
                        it.status == SessionCommandReceiptStatus.FAILED
                }
        val memoryTasks =
            BackgroundMemoryExtractor.readQueue(includeCompleted = false, limit = 12)
                .filter { it.sessionId == sessionId || (it.sessionId.isBlank() && task.isNotBlank() && it.task == task) }
        val proactiveTasks = SessionPlatformFacade.readProactiveTasks(limit = 24)
        val taskOs = SessionPlatformFacade.readTaskOs(limit = 24)
        val taskSignals = AssistantTaskOsStore.deriveSessionSignals(proactiveTasks, activeSessionId = sessionId)
        val toolCatalog = AgentToolCatalog.descriptors()
        val toolContracts = SessionToolContractStore.readSnapshot(limit = 6)
        val skillExecutors =
            if (task.isNotBlank()) {
                SkillRegistry.resolveExecutors(task = task, profileId = profileId).take(4)
            } else {
                emptyList()
            }
        val alwaysLoadCount = toolCatalog.count { it.alwaysLoad }
        val deferredCount = toolCatalog.count { it.shouldDefer }
        val interactiveCount = toolCatalog.count { it.requiresUserInteraction }
        val drainLines =
            buildList {
                commands.take(3).forEach { receipt ->
                    add("command | ${receipt.status.name.lowercase()} | ${receipt.capability.ifBlank { receipt.resolvedCommand }.take(42)} | ${receipt.summary.take(72)}")
                }
                mailbox.recentLines.take(2).forEach { add("mailbox | ${it.take(96)}") }
                attachmentSnapshot?.lines?.take(2)?.forEach { add("attachment | ${it.take(120)}") }
            }
        val prefetchLines =
            buildList {
                memoryTasks.take(4).forEach { taskItem ->
                    add("memory | ${taskItem.type.name.lowercase()} | ${taskItem.status.name.lowercase()} | retry=${taskItem.retryCount}/${taskItem.maxRetries}")
                }
            }
        val skillPrefetchLines =
            buildList {
                skillExecutors.forEach { skill ->
                    add(
                        buildString {
                            append("skill | ").append(skill.spec.id)
                            append(" | ").append(skill.spec.title.take(32))
                            append(" | ").append(skill.spec.layer.name.lowercase())
                            skill.spec.requiredTools.takeIf { it.isNotEmpty() }?.let { append(" | tools=").append(it.joinToString(",").take(42)) }
                        },
                    )
                }
            }
        val toolRefreshLines =
            buildList {
                toolContracts.lines.take(3).forEach { add("tool_refresh | ${it.take(96)}") }
                add("tool_catalog | total=${toolCatalog.size} | always=$alwaysLoadCount | defer=$deferredCount | interact=$interactiveCount")
            }
        val taskSummaryLines =
            buildList {
                add("task_os | ${taskOs.summary.ifBlank { "-" }}")
                taskSignals.values.take(2).forEach { signal ->
                    add("task_signal | ${signal.sessionId.takeIf { it.isNotBlank() } ?: "-"} | ${signal.summary.take(88)}")
                }
            }
        val shouldContinueNow =
            commands.isNotEmpty() ||
                mailbox.pendingCount > 0 ||
                (attachmentSnapshot?.attachmentCount ?: 0) > 0 ||
                memoryTasks.isNotEmpty() ||
                taskSignals.isNotEmpty()
        val snapshot =
            SessionLoopRuntimeSnapshot(
                sessionId = sessionId,
                turn = turn,
                phase = phase,
                queueDrainCount = commands.size + mailbox.pendingCount,
                attachmentCount = (attachmentSnapshot?.attachmentCount ?: loopInbox?.totalCount ?: 0) + mailbox.pendingCount,
                prefetchCount = memoryTasks.size,
                taskSignalCount = taskSignals.size + taskOs.overdueCount + taskOs.approvalCount,
                toolCatalogCount = toolCatalog.size,
                drainLines = drainLines.take(limit.coerceAtLeast(1)),
                prefetchLines = prefetchLines.take(limit.coerceAtLeast(1)),
                skillPrefetchLines = skillPrefetchLines.take(limit.coerceAtLeast(1)),
                toolRefreshLines = toolRefreshLines.take(limit.coerceAtLeast(1)),
                taskSummaryLines = taskSummaryLines.take(limit.coerceAtLeast(1)),
                shouldContinueNow = shouldContinueNow,
                midTurnSummary =
                    buildString {
                        append("drain=").append(commands.size + mailbox.pendingCount)
                        append(" attachments=").append(attachmentSnapshot?.attachmentCount ?: loopInbox?.totalCount ?: 0)
                        append(" prefetch=").append(memoryTasks.size)
                        append(" skills=").append(skillExecutors.size)
                        append(" task=").append(taskSignals.size)
                    },
                summary =
                    buildString {
                        append("queue=").append(commands.size)
                        append(" mailbox=").append(mailbox.pendingCount)
                        append(" inbox=").append(loopInbox?.totalCount ?: 0)
                        append(" attachments=").append(attachmentSnapshot?.attachmentCount ?: 0)
                        append(" prefetch=").append(memoryTasks.size)
                        append(" skill_prefetch=").append(skillExecutors.size)
                        append(" task_signals=").append(taskSignals.size)
                        append(" tools=").append(toolCatalog.size)
                    },
                lines =
                    buildList {
                        add("queue_drain=${commands.size} | mailbox_pending=${mailbox.pendingCount}")
                        add("attachments=${attachmentSnapshot?.attachmentCount ?: loopInbox?.totalCount ?: 0} | prefetch=${memoryTasks.size}")
                        add("mid_turn=${if (shouldContinueNow) "continue" else "hold"} | ${task.ifBlank { sessionId }.take(48)}")
                        add("task_os=${taskOs.summary.ifBlank { "-" }}")
                        add("tool_catalog=${toolCatalog.size} | contracts=${toolContracts.totalCount} | always=${alwaysLoadCount} | defer=${deferredCount} | interact=${interactiveCount}")
                        addAll(drainLines.take(2))
                        addAll(prefetchLines.take(2))
                        addAll(skillPrefetchLines.take(1))
                        addAll(toolRefreshLines.take(1))
                        addAll(taskSummaryLines.take(2))
                    }.take(limit.coerceAtLeast(1)),
                recommendedCommands =
                    listOfNotNull(
                        "/loop-runtime --session-id $sessionId",
                        "/main-loop --session-id $sessionId",
                        loopInbox?.takeIf { it.totalCount > 0 }?.let { "/loop-inbox --session-id $sessionId" },
                        if (mailbox.pendingCount > 0) "/worker-mailbox --target $sessionId" else null,
                        if (memoryTasks.isNotEmpty()) "/memory-maintenance" else null,
                        if (taskOs.overdueCount > 0 || taskOs.approvalCount > 0) "/task-os" else null,
                        if (skillExecutors.isNotEmpty()) "/help --category session" else null,
                        "/tool-catalog",
                    ).distinct(),
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
    ): SessionLoopRuntimeSnapshot? =
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
                add("loop_runtime: ${snapshot.summary.ifBlank { "-" }}")
                addAll(snapshot.lines.take(limit.coerceAtLeast(1)).map { "loop_runtime_item: $it" })
                addAll(snapshot.drainLines.take(1).map { "loop_runtime_drain: $it" })
                addAll(snapshot.prefetchLines.take(1).map { "loop_runtime_prefetch: $it" })
                addAll(snapshot.skillPrefetchLines.take(1).map { "loop_runtime_skill: $it" })
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

    private fun SessionLoopRuntimeSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("session_id", sessionId)
            put("turn", turn)
            put("phase", phase)
            put("queue_drain_count", queueDrainCount)
            put("attachment_count", attachmentCount)
            put("prefetch_count", prefetchCount)
            put("task_signal_count", taskSignalCount)
            put("tool_catalog_count", toolCatalogCount)
            put("drain_lines", JSONArray(drainLines))
            put("prefetch_lines", JSONArray(prefetchLines))
            put("skill_prefetch_lines", JSONArray(skillPrefetchLines))
            put("tool_refresh_lines", JSONArray(toolRefreshLines))
            put("task_summary_lines", JSONArray(taskSummaryLines))
            put("should_continue_now", shouldContinueNow)
            put("mid_turn_summary", midTurnSummary)
            put("summary", summary)
            put("lines", JSONArray(lines))
            put("recommended_commands", JSONArray(recommendedCommands))
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toSnapshot(): SessionLoopRuntimeSnapshot =
        SessionLoopRuntimeSnapshot(
            sessionId = optString("session_id"),
            turn = optInt("turn"),
            phase = optString("phase"),
            queueDrainCount = optInt("queue_drain_count"),
            attachmentCount = optInt("attachment_count"),
            prefetchCount = optInt("prefetch_count"),
            taskSignalCount = optInt("task_signal_count"),
            toolCatalogCount = optInt("tool_catalog_count"),
            drainLines = optJSONArray("drain_lines").toStringList(),
            prefetchLines = optJSONArray("prefetch_lines").toStringList(),
            skillPrefetchLines = optJSONArray("skill_prefetch_lines").toStringList(),
            toolRefreshLines = optJSONArray("tool_refresh_lines").toStringList(),
            taskSummaryLines = optJSONArray("task_summary_lines").toStringList(),
            shouldContinueNow = optBoolean("should_continue_now"),
            midTurnSummary = optString("mid_turn_summary"),
            summary = optString("summary"),
            lines = optJSONArray("lines").toStringList(),
            recommendedCommands = optJSONArray("recommended_commands").toStringList(),
            updatedAtMs = optLong("updated_at_ms"),
        )
}
