package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentTurnRecord
import com.lmx.xiaoxuanagent.agent.TaskResultField
import com.lmx.xiaoxuanagent.agent.TaskResultPayload
import com.lmx.xiaoxuanagent.safety.PendingSafetyConfirmation
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionWorkingMemorySnapshot(
    val sessionId: String,
    val task: String = "",
    val targetPackageName: String = "",
    val status: String = "running",
    val turnCount: Int = 0,
    val progressSummary: String = "",
    val recentFacts: List<String> = emptyList(),
    val openLoops: List<String> = emptyList(),
    val cautionNotes: List<String> = emptyList(),
    val preferredTools: List<String> = emptyList(),
    val nextFocusHint: String = "",
    val lastPlanStage: String = "",
    val updatedAtMs: Long = 0L,
)

object SessionWorkingMemoryStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_working_memory.json"
    private const val MAX_SESSIONS = 96
    private const val MAX_FACTS = 8
    private const val MAX_OPEN_LOOPS = 5
    private const val MAX_CAUTIONS = 6
    private const val MAX_TOOL_HINTS = 4
    private val lock = Any()
    private val snapshots = LinkedHashMap<String, SessionWorkingMemorySnapshot>()
    private var hydrated = false

    fun readSnapshot(
        sessionId: String,
    ): SessionWorkingMemorySnapshot? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshots[sessionId]
        }

    fun planningMemoryLines(
        sessionId: String,
        limit: Int = 6,
    ): List<String> =
        readSnapshot(sessionId)
            ?.let { snapshot ->
                buildList {
                    snapshot.progressSummary.takeIf { it.isNotBlank() }?.let { add("session_progress: $it") }
                    snapshot.nextFocusHint.takeIf { it.isNotBlank() }?.let { add("session_focus: $it") }
                    snapshot.openLoops.take(limit.coerceAtLeast(1)).forEach { add("open_loop: $it") }
                    snapshot.cautionNotes.take(2).forEach { add("session_caution: $it") }
                    snapshot.preferredTools.take(2).forEach { add("tool_preference: $it") }
                }.take(limit.coerceAtLeast(1))
            }.orEmpty()

    fun recordTurn(
        sessionId: String,
        task: String,
        targetPackageName: String,
        turn: Int,
        planStage: String,
        nextObjective: String,
        turnRecord: AgentTurnRecord,
        taskResult: TaskResultPayload?,
        keepRunning: Boolean,
    ): SessionWorkingMemorySnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val previous = snapshots[sessionId]
            val now = System.currentTimeMillis()
            val recentFacts =
                mergeWindow(
                    previous?.recentFacts.orEmpty(),
                    deriveRecentFacts(task, planStage, turnRecord, taskResult),
                    MAX_FACTS,
                )
            val openLoops =
                if (keepRunning) {
                    mergeWindow(
                        previous?.openLoops.orEmpty(),
                        listOfNotNull(
                            buildOpenLoop(planStage, nextObjective, turnRecord),
                        ),
                        MAX_OPEN_LOOPS,
                    )
                } else {
                    emptyList()
                }
            val cautionNotes =
                mergeWindow(
                    previous?.cautionNotes.orEmpty(),
                    deriveCautionNotes(planStage, turnRecord),
                    MAX_CAUTIONS,
                )
            val preferredTools =
                mergeWindow(
                    previous?.preferredTools.orEmpty(),
                    listOfNotNull(classifyPreferredTool(turnRecord)),
                    MAX_TOOL_HINTS,
                )
            val nextFocusHint =
                nextObjective.takeIf { it.isNotBlank() }
                    ?: openLoops.lastOrNull().orEmpty()
            val progressSummary =
                buildProgressSummary(
                    turn = turn,
                    stage = planStage,
                    recentFacts = recentFacts,
                    nextFocusHint = nextFocusHint,
                )
            val snapshot =
                SessionWorkingMemorySnapshot(
                    sessionId = sessionId,
                    task = task,
                    targetPackageName = targetPackageName,
                    status = if (keepRunning) "running" else "completed",
                    turnCount = maxOf(previous?.turnCount ?: 0, turn),
                    progressSummary = progressSummary,
                    recentFacts = recentFacts,
                    openLoops = openLoops,
                    cautionNotes = cautionNotes,
                    preferredTools = preferredTools,
                    nextFocusHint = nextFocusHint,
                    lastPlanStage = planStage,
                    updatedAtMs = now,
                )
            snapshots[sessionId] = snapshot
            trimUnlocked()
            persistUnlocked()
            snapshot
        }

    fun markApproval(
        sessionId: String,
        confirmation: PendingSafetyConfirmation,
        userCorrection: String = "",
    ): SessionWorkingMemorySnapshot? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val current = snapshots[sessionId] ?: return null
            val note =
                buildString {
                    append("审批已放行 ").append(confirmation.actionLabel.ifBlank { "高风险动作" })
                    confirmation.nextObjective.takeIf { it.isNotBlank() }?.let { append(" | 下一步 ").append(it) }
                    userCorrection.takeIf { it.isNotBlank() }?.let { append(" | 纠错 ").append(it.take(48)) }
                }
            val next =
                current.copy(
                    cautionNotes = mergeWindow(current.cautionNotes, listOf(note), MAX_CAUTIONS),
                    nextFocusHint =
                        confirmation.nextObjective.ifBlank {
                            current.nextFocusHint.ifBlank { confirmation.planStage }
                        },
                    updatedAtMs = System.currentTimeMillis(),
                )
            snapshots[sessionId] = next
            persistUnlocked()
            next
        }

    fun markFinished(
        sessionId: String,
        finalStatus: String,
        finalResult: String,
    ): SessionWorkingMemorySnapshot? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val current = snapshots[sessionId] ?: return null
            val next =
                current.copy(
                    status = finalStatus,
                    progressSummary =
                        listOf(
                            current.progressSummary,
                            finalResult.take(96).takeIf { it.isNotBlank() },
                        ).filterNotNull().joinToString(" | ").take(220),
                    recentFacts = mergeWindow(current.recentFacts, listOf(finalResult.take(96)), MAX_FACTS),
                    openLoops = emptyList(),
                    nextFocusHint = "",
                    updatedAtMs = System.currentTimeMillis(),
                )
            snapshots[sessionId] = next
            persistUnlocked()
            next
        }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = storeFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("snapshots") ?: JSONArray()
            for (index in 0 until array.length()) {
                val snapshot = array.optJSONObject(index)?.toSnapshot() ?: continue
                snapshots[snapshot.sessionId] = snapshot
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
                        snapshots.values
                            .sortedByDescending { it.updatedAtMs }
                            .forEach { put(it.toJson()) }
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

    private fun mergeWindow(
        previous: List<String>,
        additions: List<String>,
        maxSize: Int,
    ): List<String> {
        val merged = LinkedHashSet<String>()
        (previous + additions)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { merged.add(it) }
        return merged.toList().takeLast(maxSize.coerceAtLeast(1))
    }

    private fun deriveRecentFacts(
        task: String,
        planStage: String,
        turnRecord: AgentTurnRecord,
        taskResult: TaskResultPayload?,
    ): List<String> =
        buildList {
            taskResult?.title?.takeIf { it.isNotBlank() }?.let { add("阶段结果: $it") }
            taskResult?.summary?.takeIf { it.isNotBlank() }?.let { add("阶段摘要: ${it.take(96)}") }
            taskResult?.fields
                .orEmpty()
                .mapNotNull(::formatTaskResultField)
                .take(3)
                .forEach(::add)
            if (isProgressLike(turnRecord.result)) {
                add("${planStage.ifBlank { "observe" }} -> ${turnRecord.result.take(88)}")
            }
            if (taskResult == null && planStage.isNotBlank()) {
                add("任务推进: ${task.take(24)} | stage=$planStage")
            }
        }

    private fun deriveCautionNotes(
        planStage: String,
        turnRecord: AgentTurnRecord,
    ): List<String> =
        buildList {
            turnRecord.recoveryCategory.takeIf { it.isNotBlank() }?.let { category ->
                add("恢复提醒: $category | ${turnRecord.recoverySummary.take(72)}".trim())
            }
            turnRecord.suggestedRecoveryAction.takeIf { it.isNotBlank() }?.let {
                add("建议纠偏: ${it.take(72)}")
            }
            if (turnRecord.result.contains("失败") || turnRecord.result.contains("阻止")) {
                add("${planStage.ifBlank { "execute" }} 失败: ${turnRecord.result.take(80)}")
            }
            if (turnRecord.result.contains("等待")) {
                add("当前进入等待态: ${turnRecord.result.take(72)}")
            }
        }

    private fun buildOpenLoop(
        planStage: String,
        nextObjective: String,
        turnRecord: AgentTurnRecord,
    ): String? {
        val focus = nextObjective.ifBlank { turnRecord.suggestedRecoveryAction.ifBlank { turnRecord.result } }
        if (focus.isBlank()) return null
        return "${planStage.ifBlank { "observe" }} | ${focus.take(88)}"
    }

    private fun buildProgressSummary(
        turn: Int,
        stage: String,
        recentFacts: List<String>,
        nextFocusHint: String,
    ): String =
        buildString {
            append("turn=").append(turn)
            stage.takeIf { it.isNotBlank() }?.let { append(" | stage=").append(it) }
            recentFacts.lastOrNull()?.takeIf { it.isNotBlank() }?.let { append(" | ").append(it.take(84)) }
            nextFocusHint.takeIf { it.isNotBlank() }?.let { append(" | next=").append(it.take(72)) }
        }

    private fun formatTaskResultField(
        field: TaskResultField,
    ): String? {
        val value = field.value.trim()
        if (value.isBlank()) return null
        val label = field.label.trim().ifBlank { field.key.trim() }.ifBlank { "field" }
        return "$label: ${value.take(64)}"
    }

    private fun classifyPreferredTool(
        turnRecord: AgentTurnRecord,
    ): String? {
        if (!isProgressLike(turnRecord.result)) return null
        val action = turnRecord.action.lowercase()
        return when {
            action.startsWith("submit_primary_action") -> "semantic.submit_primary_action"
            action.startsWith("populate_primary_input") -> "semantic.populate_primary_input"
            action.startsWith("open_best_candidate") -> "semantic.open_best_candidate"
            action.startsWith("dismiss_interrupt") -> "semantic.dismiss_interrupt"
            action.startsWith("launch_app") -> "system.launch_app"
            action.startsWith("press_enter") -> "gui.press_enter"
            action.startsWith("click") -> "gui.click"
            else -> null
        }
    }

    private fun isProgressLike(
        result: String,
    ): Boolean {
        if (result.isBlank()) return false
        return !result.contains("失败") && !result.contains("阻止")
    }

    private fun SessionWorkingMemorySnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("session_id", sessionId)
            put("task", task)
            put("target_package_name", targetPackageName)
            put("status", status)
            put("turn_count", turnCount)
            put("progress_summary", progressSummary)
            put("recent_facts", JSONArray(recentFacts))
            put("open_loops", JSONArray(openLoops))
            put("caution_notes", JSONArray(cautionNotes))
            put("preferred_tools", JSONArray(preferredTools))
            put("next_focus_hint", nextFocusHint)
            put("last_plan_stage", lastPlanStage)
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toSnapshot(): SessionWorkingMemorySnapshot =
        SessionWorkingMemorySnapshot(
            sessionId = optString("session_id"),
            task = optString("task"),
            targetPackageName = optString("target_package_name"),
            status = optString("status", "running"),
            turnCount = optInt("turn_count"),
            progressSummary = optString("progress_summary"),
            recentFacts = optJSONArray("recent_facts").toStringList(),
            openLoops = optJSONArray("open_loops").toStringList(),
            cautionNotes = optJSONArray("caution_notes").toStringList(),
            preferredTools = optJSONArray("preferred_tools").toStringList(),
            nextFocusHint = optString("next_focus_hint"),
            lastPlanStage = optString("last_plan_stage"),
            updatedAtMs = optLong("updated_at_ms"),
        )

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
}
