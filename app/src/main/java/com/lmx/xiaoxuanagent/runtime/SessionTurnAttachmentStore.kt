package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.PlanningTurnAttachment
import com.lmx.xiaoxuanagent.assistantos.AssistantTaskOsStore
import com.lmx.xiaoxuanagent.skills.SkillRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionTurnAttachmentSnapshot(
    val sessionId: String = "",
    val turn: Int = 0,
    val attachmentCount: Int = 0,
    val summary: String = "",
    val lines: List<String> = emptyList(),
    val attachments: List<PlanningTurnAttachment> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

object SessionTurnAttachmentStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_turn_attachments.json"
    private const val MAX_SESSIONS = 160
    private val lock = Any()
    private val snapshots = LinkedHashMap<String, SessionTurnAttachmentSnapshot>()
    private var hydrated = false

    fun refresh(
        sessionId: String,
        turn: Int = 0,
        task: String = "",
        profileId: String = "",
        limit: Int = 8,
    ): SessionTurnAttachmentSnapshot? {
        if (sessionId.isBlank()) return null
        val loopDrain =
            SessionLoopInboxStore.drainForPlanning(
                sessionId = sessionId,
                task = task,
                turn = turn,
                limit = limit,
            )
        val loopAttachments =
            loopDrain?.items.orEmpty().map { item ->
                PlanningTurnAttachment(
                    attachmentId = "${item.source}:${item.itemId}",
                    source = item.source,
                    type = "loop_inbox",
                    title = item.title.ifBlank { item.itemId },
                    summary = item.summary,
                    priority = item.priority,
                    detailLines = item.detailLines.take(4),
                    recommendedCommands =
                        listOfNotNull(
                            item.recommendedCommand.takeIf { it.isNotBlank() },
                            "/loop-inbox --session-id $sessionId",
                        ).distinct(),
                    consumedAtMs = System.currentTimeMillis(),
                )
            }
        val skillAttachments =
            if (task.isNotBlank()) {
                SkillRegistry.resolveExecutors(task = task, profileId = profileId)
                    .take(3)
                    .mapIndexed { index, skill ->
                        PlanningTurnAttachment(
                            attachmentId = "skill:${skill.spec.id}:$index",
                            source = "skill_prefetch",
                            type = "skill_prefetch",
                            title = skill.spec.title.ifBlank { skill.spec.id },
                            summary = skill.spec.description,
                            priority = 52 - index,
                            detailLines =
                                buildList {
                                    add("layer=${skill.spec.layer.name.lowercase()}")
                                    skill.spec.requiredTools.takeIf { it.isNotEmpty() }?.let { add("tools=${it.joinToString(",")}") }
                                },
                            recommendedCommands = listOf("/help --category session", "/tool-catalog"),
                            consumedAtMs = System.currentTimeMillis(),
                        )
                    }
            } else {
                emptyList()
            }
        val taskSignals =
            AssistantTaskOsStore.deriveSessionSignals(
                tasks = SessionPlatformFacade.readProactiveTasks(limit = 24),
                activeSessionId = sessionId,
            )
        val taskAttachments =
            taskSignals.values.take(3).mapIndexed { index, signal ->
                PlanningTurnAttachment(
                    attachmentId = "task_signal:${signal.sessionId}:${index}",
                    source = "task_os",
                    type = "task_signal",
                    title = signal.sessionId.ifBlank { sessionId },
                    summary = signal.summary,
                    priority = 48 - index,
                    detailLines =
                        buildList {
                            add("due=${signal.dueCount}")
                            add("overdue=${signal.overdueCount}")
                            add("approvals=${signal.approvalCount}")
                            add("blocked=${if (signal.blockedByActiveSession) 1 else 0}")
                        },
                    recommendedCommands = listOf("/task-os", "/follow-ups"),
                    consumedAtMs = System.currentTimeMillis(),
                )
            }
        val attachments =
            (loopAttachments + skillAttachments + taskAttachments)
                .sortedWith(compareByDescending<PlanningTurnAttachment> { it.priority }.thenBy { it.attachmentId })
                .take(limit.coerceAtLeast(1))
        val snapshot =
            SessionTurnAttachmentSnapshot(
                sessionId = sessionId,
                turn = turn,
                attachmentCount = attachments.size,
                summary =
                    buildString {
                        append("attachments=").append(attachments.size)
                        append(" loop=").append(loopAttachments.size)
                        append(" skill=").append(skillAttachments.size)
                        append(" task=").append(taskAttachments.size)
                        attachments.firstOrNull()?.let { append(" top=").append(it.title.ifBlank { it.source }.take(36)) }
                    },
                lines =
                    attachments.map { attachment ->
                        buildString {
                            append(attachment.source)
                            append(" | ").append(attachment.type)
                            append(" | p=").append(attachment.priority)
                            append(" | ").append(attachment.title.take(48))
                            attachment.summary.takeIf { it.isNotBlank() }?.let { append(" | ").append(it.take(88)) }
                        }
                    },
                attachments = attachments,
                recommendedCommands =
                    attachments.flatMap { it.recommendedCommands }
                        .plus("/turn-attachments --session-id $sessionId")
                        .distinct()
                        .take(8),
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
    ): SessionTurnAttachmentSnapshot? =
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
                add("turn_attachments: ${snapshot.summary.ifBlank { "-" }}")
                addAll(snapshot.lines.take(limit.coerceAtLeast(1)).map { "turn_attachment: $it" })
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

    private fun SessionTurnAttachmentSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("session_id", sessionId)
            put("turn", turn)
            put("attachment_count", attachmentCount)
            put("summary", summary)
            put("lines", JSONArray(lines))
            put(
                "attachments",
                JSONArray().apply {
                    attachments.forEach { attachment ->
                        put(
                            JSONObject().apply {
                                put("attachment_id", attachment.attachmentId)
                                put("source", attachment.source)
                                put("type", attachment.type)
                                put("title", attachment.title)
                                put("summary", attachment.summary)
                                put("priority", attachment.priority)
                                put("detail_lines", JSONArray(attachment.detailLines))
                                put("recommended_commands", JSONArray(attachment.recommendedCommands))
                                put("consumed_at_ms", attachment.consumedAtMs)
                            },
                        )
                    }
                },
            )
            put("recommended_commands", JSONArray(recommendedCommands))
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toSnapshot(): SessionTurnAttachmentSnapshot =
        SessionTurnAttachmentSnapshot(
            sessionId = optString("session_id"),
            turn = optInt("turn"),
            attachmentCount = optInt("attachment_count"),
            summary = optString("summary"),
            lines = optJSONArray("lines").toStringList(),
            attachments =
                optJSONArray("attachments")
                    ?.let { array ->
                        buildList {
                            for (index in 0 until array.length()) {
                                array.optJSONObject(index)?.let { attachment ->
                                    add(
                                        PlanningTurnAttachment(
                                            attachmentId = attachment.optString("attachment_id"),
                                            source = attachment.optString("source"),
                                            type = attachment.optString("type"),
                                            title = attachment.optString("title"),
                                            summary = attachment.optString("summary"),
                                            priority = attachment.optInt("priority"),
                                            detailLines = attachment.optJSONArray("detail_lines").toStringList(),
                                            recommendedCommands = attachment.optJSONArray("recommended_commands").toStringList(),
                                            consumedAtMs = attachment.optLong("consumed_at_ms"),
                                        ),
                                    )
                                }
                            }
                        }
                    }.orEmpty(),
            recommendedCommands = optJSONArray("recommended_commands").toStringList(),
            updatedAtMs = optLong("updated_at_ms"),
        )
}
