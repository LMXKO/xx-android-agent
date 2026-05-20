package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.TaskResultField
import com.lmx.xiaoxuanagent.agent.TaskResultPayload
import com.lmx.xiaoxuanagent.memory.PersonalMemoryStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class BackgroundMemoryTaskType {
    SESSION_OUTCOME,
    TASK_PROGRESS,
    CORRECTION_TEMPLATE,
    SESSION_NOTEBOOK,
}

enum class BackgroundMemoryTaskStatus {
    PENDING,
    RUNNING,
    DEFERRED,
    COMPLETED,
    FAILED,
}

data class BackgroundMemoryQueueTask(
    val taskId: String,
    val type: BackgroundMemoryTaskType,
    val status: BackgroundMemoryTaskStatus = BackgroundMemoryTaskStatus.PENDING,
    val sessionId: String = "",
    val turn: Int = 0,
    val task: String = "",
    val profileId: String = "",
    val resultStatus: String = "",
    val finalMessage: String = "",
    val correction: String = "",
    val taskResult: TaskResultPayload? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val nextEligibleAtMs: Long = 0L,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val failureReason: String = "",
)

private enum class BackgroundMemoryTaskDisposition {
    COMPLETED_SYNC,
    RUNNING_ASYNC,
}

object BackgroundMemoryExtractor {
    private const val QUEUE_DIR = "runtime"
    private const val QUEUE_FILE = "background_memory_queue.json"
    private const val MAX_TASKS = 240
    private const val BASE_RETRY_MS = 2 * 60 * 1000L
    private val extractorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val draining = AtomicBoolean(false)
    private val lock = Any()
    private val queue = ArrayDeque<BackgroundMemoryQueueTask>()
    private var hydrated = false

    init {
        drainAsync(reason = "extractor_init")
    }

    fun enqueueSessionOutcome(
        sessionId: String,
        task: String,
        profileId: String,
        status: String,
        finalMessage: String,
        taskResult: TaskResultPayload? = null,
    ) {
        if (task.isBlank() || profileId.isBlank()) return
        enqueue(
            BackgroundMemoryTaskType.SESSION_OUTCOME,
            sessionId = sessionId,
            task = task,
            profileId = profileId,
            resultStatus = status,
            finalMessage = finalMessage,
            taskResult = taskResult,
        )
    }

    fun enqueueTaskProgress(
        sessionId: String,
        turn: Int,
        task: String,
        profileId: String,
        taskResult: TaskResultPayload,
    ) {
        if (task.isBlank() || profileId.isBlank()) return
        enqueue(
            BackgroundMemoryTaskType.TASK_PROGRESS,
            sessionId = sessionId,
            turn = turn,
            task = task,
            profileId = profileId,
            taskResult = taskResult,
        )
    }

    fun enqueueCorrectionTemplate(
        task: String,
        profileId: String,
        correction: String,
    ) {
        if (task.isBlank() || profileId.isBlank() || correction.isBlank()) return
        enqueue(
            BackgroundMemoryTaskType.CORRECTION_TEMPLATE,
            task = task,
            profileId = profileId,
            correction = correction,
        )
    }

    fun enqueueSessionNotebookUpdate(
        sessionId: String,
        task: String,
        profileId: String,
    ) {
        if (sessionId.isBlank()) return
        enqueue(
            BackgroundMemoryTaskType.SESSION_NOTEBOOK,
            sessionId = sessionId,
            task = task,
            profileId = profileId,
        )
    }

    fun readQueue(
        includeCompleted: Boolean = false,
        limit: Int = 24,
    ): List<BackgroundMemoryQueueTask> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            queue
                .asSequence()
                .filter { includeCompleted || it.status != BackgroundMemoryTaskStatus.COMPLETED }
                .take(limit)
                .toList()
        }

    fun retry(
        taskId: String,
    ): BackgroundMemoryQueueTask? {
        val updated =
            mutate(taskId) { current ->
                current.copy(
                    status = BackgroundMemoryTaskStatus.PENDING,
                    nextEligibleAtMs = System.currentTimeMillis(),
                    updatedAtMs = System.currentTimeMillis(),
                    failureReason = "",
                )
            }
        if (updated != null) {
            drainAsync(reason = "manual_retry")
        }
        return updated
    }

    fun drainAsync(
        reason: String,
        limit: Int = 8,
    ) {
        extractorScope.launch {
            drain(reason = reason, limit = limit)
        }
    }

    fun exportJson(
        limit: Int = 80,
    ): JSONArray =
        JSONArray().apply {
            readQueue(includeCompleted = true, limit = limit).forEach { put(it.toJson()) }
        }

    fun importJson(
        array: JSONArray?,
    ) {
        if (array == null || array.length() <= 0) return
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                queue.addLast(json.toTask())
            }
            dedupeTrimUnlocked()
            persistUnlocked()
        }
        drainAsync(reason = "import")
    }

    private fun enqueue(
        type: BackgroundMemoryTaskType,
        sessionId: String = "",
        turn: Int = 0,
        task: String = "",
        profileId: String = "",
        resultStatus: String = "",
        finalMessage: String = "",
        correction: String = "",
        taskResult: TaskResultPayload? = null,
    ) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            queue.addFirst(
                BackgroundMemoryQueueTask(
                    taskId = "mem_${now}_${type.name.lowercase()}",
                    type = type,
                    sessionId = sessionId,
                    turn = turn,
                    task = task,
                    profileId = profileId,
                    resultStatus = resultStatus,
                    finalMessage = finalMessage,
                    correction = correction,
                    taskResult = taskResult,
                    nextEligibleAtMs = now,
                    createdAtMs = now,
                    updatedAtMs = now,
                ),
            )
            dedupeTrimUnlocked()
            persistUnlocked()
        }
        drainAsync(reason = "enqueue_$type")
    }

    private fun drain(
        reason: String,
        limit: Int,
    ) {
        if (!draining.compareAndSet(false, true)) return
        try {
            var remaining = limit.coerceAtLeast(1)
            while (remaining > 0) {
                val task = nextDueTask() ?: break
                remaining -= 1
                val outcome =
                    runCatching {
                        processTask(task)
                    }
                val failure = outcome.exceptionOrNull()
                when {
                    failure != null -> deferOrFail(task.taskId, failure.message.orEmpty().ifBlank { reason })
                    outcome.getOrNull() == BackgroundMemoryTaskDisposition.COMPLETED_SYNC -> markCompleted(task.taskId)
                }
            }
        } finally {
            draining.set(false)
        }
    }

    private fun processTask(
        task: BackgroundMemoryQueueTask,
    ): BackgroundMemoryTaskDisposition {
        SessionMemoryMaintenanceStore.recordTaskState(
            task = task,
            status = BackgroundMemoryTaskStatus.RUNNING,
            summary = "后台记忆任务执行中：${task.type.name.lowercase()}",
        )
        return when (task.type) {
            BackgroundMemoryTaskType.SESSION_OUTCOME ->
                PersonalMemoryStore.recordSessionOutcome(
                    task = task.task,
                    profileId = task.profileId,
                    status = task.resultStatus,
                    finalMessage = task.finalMessage,
                    taskResult = task.taskResult,
                    artifactFacts = replayArtifactFacts(task.sessionId),
                ).let { BackgroundMemoryTaskDisposition.COMPLETED_SYNC }

            BackgroundMemoryTaskType.TASK_PROGRESS ->
                PersonalMemoryStore.recordTaskProgress(
                    task = task.task,
                    profileId = task.profileId,
                    taskResult = task.taskResult ?: error("task progress 缺少 taskResult"),
                    artifactFacts = replayArtifactFacts(task.sessionId, turn = task.turn),
                ).let { BackgroundMemoryTaskDisposition.COMPLETED_SYNC }

            BackgroundMemoryTaskType.CORRECTION_TEMPLATE ->
                PersonalMemoryStore.recordCorrectionTemplate(
                    task = task.task,
                    profileId = task.profileId,
                    correction = task.correction,
                ).let { BackgroundMemoryTaskDisposition.COMPLETED_SYNC }

            BackgroundMemoryTaskType.SESSION_NOTEBOOK -> {
                val dispatched =
                    SessionMemoryForkKernel.dispatchNotebookFork(
                        task = task,
                        onCompleted = {
                            markCompleted(task.taskId)
                            drainAsync(reason = "memory_fork_completed")
                        },
                        onFailed = { failureReason ->
                            deferOrFail(task.taskId, failureReason)
                            drainAsync(reason = "memory_fork_failed")
                        },
                    )
                if (!dispatched) {
                    error("无法启动 detached session memory fork worker。")
                }
                SessionMemoryMaintenanceStore.recordTaskState(
                    task = task,
                    status = BackgroundMemoryTaskStatus.RUNNING,
                    summary = "后台记忆任务已转交 detached fork worker：${task.type.name.lowercase()}",
                )
                BackgroundMemoryTaskDisposition.RUNNING_ASYNC
            }
        }
    }

    private fun nextDueTask(): BackgroundMemoryQueueTask? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val now = System.currentTimeMillis()
            val index =
                queue.indexOfFirst { task ->
                    (task.status == BackgroundMemoryTaskStatus.PENDING || task.status == BackgroundMemoryTaskStatus.DEFERRED) &&
                        task.nextEligibleAtMs <= now
                }
            if (index < 0) {
                return@synchronized null
            }
            val current = queue.elementAt(index)
            val running =
                current.copy(
                    status = BackgroundMemoryTaskStatus.RUNNING,
                    updatedAtMs = now,
                )
            queue.removeAt(index)
            queue.add(index, running)
            persistUnlocked()
            running
        }

    private fun markCompleted(
        taskId: String,
    ) {
        mutate(taskId) { current ->
            current.copy(
                status = BackgroundMemoryTaskStatus.COMPLETED,
                updatedAtMs = System.currentTimeMillis(),
                failureReason = "",
            )
        }?.also { task ->
            SessionMemoryMaintenanceStore.recordTaskState(
                task = task,
                status = BackgroundMemoryTaskStatus.COMPLETED,
                summary = "后台记忆任务已完成：${task.type.name.lowercase()}",
            )
            if (task.type == BackgroundMemoryTaskType.SESSION_NOTEBOOK) {
                SessionMemoryPolicyStore.markNotebookUpdated(
                    sessionId = task.sessionId,
                    reason = task.type.name.lowercase(),
                )
                SessionMemoryForkKernel.completeNotebookFork(task)
            }
            task.sessionId.takeIf { it.isNotBlank() }?.let { sessionId ->
                SessionExplanationStore.record(
                    sessionId = sessionId,
                    phase = "memory_maintenance",
                    title = "Background memory completed",
                    summary = "后台记忆维护已完成 ${task.type.name.lowercase()}。",
                    detailLines = listOfNotNull(task.task.takeIf { it.isNotBlank() }, task.profileId.takeIf { it.isNotBlank() }),
                )
            }
        }
    }

    private fun deferOrFail(
        taskId: String,
        reason: String,
    ) {
        mutate(taskId) { current ->
            val now = System.currentTimeMillis()
            val nextRetry = current.retryCount + 1
            if (nextRetry > current.maxRetries) {
                current.copy(
                    status = BackgroundMemoryTaskStatus.FAILED,
                    retryCount = nextRetry,
                    updatedAtMs = now,
                    failureReason = reason,
                )
            } else {
                current.copy(
                    status = BackgroundMemoryTaskStatus.DEFERRED,
                    retryCount = nextRetry,
                    nextEligibleAtMs = now + BASE_RETRY_MS * nextRetry,
                    updatedAtMs = now,
                    failureReason = reason,
                )
            }
        }?.also { task ->
            SessionMemoryMaintenanceStore.recordTaskState(
                task = task,
                status = task.status,
                summary = "后台记忆任务${if (task.status == BackgroundMemoryTaskStatus.FAILED) "失败" else "延后"}：${task.type.name.lowercase()} | ${reason.take(88)}",
            )
            if (task.type == BackgroundMemoryTaskType.SESSION_NOTEBOOK && task.sessionId.isNotBlank()) {
                SessionMemoryPolicyStore.markNotebookFailed(
                    sessionId = task.sessionId,
                    reason = reason,
                )
                SessionMemoryForkKernel.failNotebookFork(
                    task = task,
                    reason = reason,
                    deferred = task.status == BackgroundMemoryTaskStatus.DEFERRED,
                )
            }
            task.sessionId.takeIf { it.isNotBlank() }?.let { sessionId ->
                SessionExplanationStore.record(
                    sessionId = sessionId,
                    phase = "memory_maintenance",
                    title = "Background memory delayed",
                    summary = "后台记忆维护${if (task.status == BackgroundMemoryTaskStatus.FAILED) "失败" else "延后"}：${task.type.name.lowercase()}",
                    detailLines = listOf(reason.take(96)),
                )
            }
        }
    }

    private fun mutate(
        taskId: String,
        reducer: (BackgroundMemoryQueueTask) -> BackgroundMemoryQueueTask,
    ): BackgroundMemoryQueueTask? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val index = queue.indexOfFirst { it.taskId == taskId }
            if (index < 0) return null
            val next = reducer(queue.elementAt(index))
            queue.removeAt(index)
            queue.add(index, next)
            persistUnlocked()
            next
        }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = queueFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("tasks") ?: JSONArray()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val task = json.toTask()
                queue.addLast(
                    if (task.status == BackgroundMemoryTaskStatus.RUNNING) {
                        task.copy(
                            status = BackgroundMemoryTaskStatus.DEFERRED,
                            nextEligibleAtMs = System.currentTimeMillis(),
                            updatedAtMs = System.currentTimeMillis(),
                        )
                    } else {
                        task
                    },
                )
            }
            dedupeTrimUnlocked()
        }
    }

    private fun persistUnlocked() {
        val file = queueFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "tasks",
                    JSONArray().apply {
                        queue.forEach { put(it.toJson()) }
                    },
                )
            }.toString(2),
        )
    }

    private fun dedupeTrimUnlocked() {
        val merged =
            queue
                .distinctBy { it.taskId }
                .sortedByDescending { it.updatedAtMs }
                .take(MAX_TASKS)
        queue.clear()
        merged.forEach(queue::addLast)
    }

    private fun queueFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, QUEUE_DIR), QUEUE_FILE)
    }

    private fun replayArtifactFacts(
        sessionId: String,
        turn: Int? = null,
    ): List<String> {
        if (sessionId.isBlank()) return emptyList()
        val summaries =
            if (turn == null) {
                ReplayStore.readRecentTurnArtifactSummaries(sessionId = sessionId, limit = 4)
            } else {
                ReplayStore.readTurnArtifactGroup(sessionId = sessionId, turn = turn, artifactLimit = 4)?.artifacts.orEmpty()
            }
        return summaries.map { artifact -> "产物摘要: ${artifact.type} ${artifact.summary.take(80)}" }.distinct().take(3)
    }

    private fun BackgroundMemoryQueueTask.toJson(): JSONObject =
        JSONObject().apply {
            put("task_id", taskId)
            put("type", type.name)
            put("status", status.name)
            put("session_id", sessionId)
            put("turn", turn)
            put("task", task)
            put("profile_id", profileId)
            put("result_status", resultStatus)
            put("final_message", finalMessage)
            put("correction", correction)
            put("retry_count", retryCount)
            put("max_retries", maxRetries)
            put("next_eligible_at_ms", nextEligibleAtMs)
            put("created_at_ms", createdAtMs)
            put("updated_at_ms", updatedAtMs)
            put("failure_reason", failureReason)
            put("task_result", taskResult?.toJson() ?: JSONObject())
        }

    private fun JSONObject.toTask(): BackgroundMemoryQueueTask =
        BackgroundMemoryQueueTask(
            taskId = optString("task_id"),
            type =
                runCatching { BackgroundMemoryTaskType.valueOf(optString("type")) }
                    .getOrDefault(BackgroundMemoryTaskType.SESSION_OUTCOME),
            status =
                runCatching { BackgroundMemoryTaskStatus.valueOf(optString("status")) }
                    .getOrDefault(BackgroundMemoryTaskStatus.PENDING),
            sessionId = optString("session_id"),
            turn = optInt("turn"),
            task = optString("task"),
            profileId = optString("profile_id"),
            resultStatus = optString("result_status"),
            finalMessage = optString("final_message"),
            correction = optString("correction"),
            taskResult = optJSONObject("task_result").toTaskResultPayload(),
            retryCount = optInt("retry_count"),
            maxRetries = optInt("max_retries", 3),
            nextEligibleAtMs = optLong("next_eligible_at_ms"),
            createdAtMs = optLong("created_at_ms"),
            updatedAtMs = optLong("updated_at_ms"),
            failureReason = optString("failure_reason"),
        )

    private fun TaskResultPayload.toJson(): JSONObject =
        JSONObject().apply {
            put("intent_type", intentType)
            put("title", title)
            put("summary", summary)
            put("highlights", JSONArray(highlights))
            put(
                "fields",
                JSONArray().apply {
                    fields.forEach { field ->
                        put(
                            JSONObject().apply {
                                put("key", field.key)
                                put("label", field.label)
                                put("value", field.value)
                            },
                        )
                    }
                },
            )
        }

    private fun JSONObject?.toTaskResultPayload(): TaskResultPayload? {
        if (this == null || length() <= 0) return null
        return TaskResultPayload(
            intentType = optString("intent_type"),
            title = optString("title"),
            summary = optString("summary"),
            highlights = optJSONArray("highlights").toStringList(),
            fields =
                buildList {
                    val array = optJSONArray("fields") ?: JSONArray()
                    for (index in 0 until array.length()) {
                        val field = array.optJSONObject(index) ?: continue
                        add(
                            TaskResultField(
                                key = field.optString("key"),
                                label = field.optString("label"),
                                value = field.optString("value"),
                            ),
                        )
                    }
                },
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
}
