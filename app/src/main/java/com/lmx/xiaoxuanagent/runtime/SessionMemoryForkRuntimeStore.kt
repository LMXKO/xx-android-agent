package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionMemoryForkRuntimeSnapshot(
    val sessionId: String = "",
    val workerId: String = "",
    val workerStatus: String = "",
    val forkMode: String = "",
    val modelName: String = "",
    val blockedReason: String = "",
    val policyReason: String = "",
    val notebookPath: String = "",
    val promptPreview: String = "",
    val summary: String = "",
    val lines: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

object SessionMemoryForkRuntimeStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_memory_fork_runtime.json"
    private const val MAX_SESSIONS = 160
    private val lock = Any()
    private val snapshots = LinkedHashMap<String, SessionMemoryForkRuntimeSnapshot>()
    private var hydrated = false

    fun refresh(
        sessionId: String,
    ): SessionMemoryForkRuntimeSnapshot? {
        if (sessionId.isBlank()) return null
        val curator = SessionMemoryCuratorStore.readSnapshot(sessionId)
        val worker =
            curator?.workerId
                ?.takeIf { it.isNotBlank() }
                ?.let(SessionWorkerStore::readByWorkerId)
                ?: SessionWorkerStore.readChildren(parentSessionId = sessionId, limit = 24)
                    .filter { it.metadata["worker_role"] == "memory_curator" }
                    .maxByOrNull { it.updatedAtMs }
        val notebook = SessionMemoryNotebookStore.readSnapshot(sessionId)
        val policy = SessionMemoryPolicyStore.readSnapshot(sessionId)
        val promptPreview = notebook?.previewLines?.firstOrNull { it.startsWith("fork_prompt=") }?.substringAfter('=').orEmpty()
        val snapshot =
            SessionMemoryForkRuntimeSnapshot(
                sessionId = sessionId,
                workerId = worker?.workerId.orEmpty().ifBlank { curator?.workerId.orEmpty() },
                workerStatus = worker?.status?.name?.lowercase().orEmpty().ifBlank { curator?.status.orEmpty() },
                forkMode = notebook?.forkMode.orEmpty().ifBlank { if (worker != null || curator != null) "detached_memory_fork_worker" else "idle" },
                modelName = notebook?.forkModel.orEmpty(),
                blockedReason = worker?.blockedReason.orEmpty().ifBlank { curator?.failureReason.orEmpty() },
                policyReason = curator?.policyReason.orEmpty().ifBlank { policy?.lastReason.orEmpty() },
                notebookPath = notebook?.markdownPath.orEmpty().ifBlank { curator?.notebookPath.orEmpty() },
                promptPreview = promptPreview,
                summary =
                    buildString {
                        append("fork=").append(worker?.status?.name?.lowercase() ?: curator?.status.orEmpty().ifBlank { "idle" })
                        append(" mode=").append(notebook?.forkMode.orEmpty().ifBlank { if (worker != null || curator != null) "detached" else "idle" })
                        append(" worker=").append(worker?.workerId.orEmpty().ifBlank { "-" })
                        notebook?.forkModel?.takeIf { it.isNotBlank() }?.let { append(" model=").append(it.take(32)) }
                        append(" notebook=").append((notebook?.markdownPath ?: curator?.notebookPath).orEmpty().ifBlank { "-" })
                        policy?.summary?.takeIf { it.isNotBlank() }?.let { append(" policy=").append(it.take(48)) }
                    },
                lines =
                    buildList {
                        add("fork_mode=${notebook?.forkMode.orEmpty().ifBlank { if (worker != null || curator != null) "detached_memory_fork_worker" else "idle" }}")
                        add("worker_status=${worker?.status?.name?.lowercase() ?: curator?.status.orEmpty().ifBlank { "-" }}")
                        add("worker_id=${worker?.workerId.orEmpty().ifBlank { curator?.workerId.orEmpty().ifBlank { "-" } }}")
                        notebook?.forkModel?.takeIf { it.isNotBlank() }?.let { add("fork_model=${it.take(96)}") }
                        promptPreview.takeIf { it.isNotBlank() }?.let { add("prompt_preview=${it.take(120)}") }
                        worker?.summary?.takeIf { it.isNotBlank() }?.let { add("worker_summary=${it.take(120)}") }
                        worker?.blockedReason?.takeIf { it.isNotBlank() }?.let { add("blocked_reason=${it.take(120)}") }
                        curator?.policyReason?.takeIf { it.isNotBlank() }?.let { add("policy_reason=${it.take(120)}") }
                        notebook?.markdownPath?.takeIf { it.isNotBlank() }?.let { add("notebook_path=$it") }
                        worker?.metadata?.entries?.filter { (key, _) -> key in setOf("worker_role", "join_policy", "priority", "max_retries") }
                            ?.forEach { (key, value) -> add("worker_meta | $key=$value") }
                    },
                recommendedCommands =
                    listOfNotNull(
                        "/memory-fork --session-id $sessionId",
                        "/memory-curator --session-id $sessionId",
                        "/memory-policy --session-id $sessionId",
                        notebook?.takeIf { it.markdownPath.isNotBlank() }?.let { "/notebook --session-id $sessionId" },
                        worker?.workerId?.takeIf { it.isNotBlank() }?.let { "/worker-mailbox --target $it" },
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
    ): SessionMemoryForkRuntimeSnapshot? =
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
                add("memory_fork: ${snapshot.summary.ifBlank { "-" }}")
                addAll(snapshot.lines.take(limit.coerceAtLeast(1)).map { "memory_fork_item: $it" })
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

    private fun SessionMemoryForkRuntimeSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("session_id", sessionId)
            put("worker_id", workerId)
            put("worker_status", workerStatus)
            put("fork_mode", forkMode)
            put("model_name", modelName)
            put("blocked_reason", blockedReason)
            put("policy_reason", policyReason)
            put("notebook_path", notebookPath)
            put("prompt_preview", promptPreview)
            put("summary", summary)
            put("lines", JSONArray(lines))
            put("recommended_commands", JSONArray(recommendedCommands))
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toSnapshot(): SessionMemoryForkRuntimeSnapshot =
        SessionMemoryForkRuntimeSnapshot(
            sessionId = optString("session_id"),
            workerId = optString("worker_id"),
            workerStatus = optString("worker_status"),
            forkMode = optString("fork_mode"),
            modelName = optString("model_name"),
            blockedReason = optString("blocked_reason"),
            policyReason = optString("policy_reason"),
            notebookPath = optString("notebook_path"),
            promptPreview = optString("prompt_preview"),
            summary = optString("summary"),
            lines = optJSONArray("lines").toStringList(),
            recommendedCommands = optJSONArray("recommended_commands").toStringList(),
            updatedAtMs = optLong("updated_at_ms"),
        )
}
