package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionGraphNode(
    val sessionId: String,
    val parentSessionId: String = "",
    val workerId: String = "",
    val parentWorkerId: String = "",
    val agentId: String = "",
    val parentAgentId: String = "",
    val rootSessionId: String = "",
    val coordinatorSessionId: String = "",
    val task: String = "",
    val entrySource: String = "",
    val statusSnapshot: AgentUiStatusSnapshot = AgentUiStatus.resolve(AgentUiStatus.IDLE).toSnapshot(),
    val depth: Int = 0,
    val blockedReason: String = "",
    val childSessionIds: List<String> = emptyList(),
    val pendingChildSessionIds: List<String> = emptyList(),
    val pendingApprovalCount: Int = 0,
    val mailboxPendingCount: Int = 0,
    val descendantSessionCount: Int = 0,
    val latestResultSummary: String = "",
    val latestErrorSummary: String = "",
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val metadata: Map<String, String> = emptyMap(),
) {
    val status: String
        get() = statusSnapshot.code

    val statusModel: AgentUiStatusModel
        get() = statusSnapshot.resolveModel()
}

object SessionSessionGraphStore {
    private const val GRAPH_DIR = "runtime"
    private const val GRAPH_FILE = "session_graph_nodes.json"
    private const val MAX_NODES = 160
    private val lock = Any()
    private val nodes = ArrayDeque<SessionGraphNode>()
    private var hydrated = false

    fun ensureRootSession(
        sessionId: String,
        task: String,
        entrySource: String,
        metadata: Map<String, String> = emptyMap(),
    ): SessionGraphNode {
        val now = System.currentTimeMillis()
        return upsert(sessionId) { current ->
            current.copy(
                sessionId = sessionId,
                task = task.ifBlank { current.task },
                entrySource = entrySource.ifBlank { current.entrySource },
                rootSessionId = current.rootSessionId.ifBlank { sessionId },
                coordinatorSessionId = current.coordinatorSessionId.ifBlank { sessionId },
                updatedAtMs = now,
                createdAtMs = current.createdAtMs.takeIf { it > 0L } ?: now,
                metadata = current.metadata + metadata,
            )
        }
    }

    fun attachWorkerSession(
        sessionId: String,
        worker: SessionWorkerRecord,
        entrySource: String,
        metadata: Map<String, String> = emptyMap(),
    ): SessionGraphNode {
        val now = System.currentTimeMillis()
        val node =
            upsert(sessionId) { current ->
                current.copy(
                    sessionId = sessionId,
                    parentSessionId = worker.parentSessionId,
                    workerId = worker.workerId,
                    parentWorkerId = worker.parentWorkerId,
                    agentId = worker.agentId,
                    parentAgentId = worker.parentAgentId,
                    rootSessionId = worker.rootSessionId.ifBlank { worker.parentSessionId.ifBlank { sessionId } },
                    coordinatorSessionId = worker.coordinatorSessionId.ifBlank { worker.parentSessionId.ifBlank { sessionId } },
                    task = worker.task.ifBlank { current.task },
                    entrySource = entrySource.ifBlank { current.entrySource },
                    depth = worker.depth,
                    blockedReason = worker.blockedReason.ifBlank { current.blockedReason },
                    updatedAtMs = now,
                    createdAtMs = current.createdAtMs.takeIf { it > 0L } ?: now,
                    metadata = current.metadata + metadata + worker.metadata,
                )
            }
        if (worker.parentSessionId.isNotBlank()) {
            upsert(worker.parentSessionId) { current ->
                val nextChildSessionIds = (current.childSessionIds + sessionId).distinct()
                current.copy(
                    sessionId = worker.parentSessionId,
                    rootSessionId = current.rootSessionId.ifBlank { worker.rootSessionId.ifBlank { worker.parentSessionId } },
                    coordinatorSessionId = current.coordinatorSessionId.ifBlank { worker.coordinatorSessionId.ifBlank { worker.parentSessionId } },
                    childSessionIds = nextChildSessionIds,
                    pendingChildSessionIds = nextChildSessionIds,
                    descendantSessionCount = countDescendants(worker.parentSessionId, linkedSetOf()),
                    updatedAtMs = now,
                    createdAtMs = current.createdAtMs.takeIf { it > 0L } ?: now,
                )
            }
        }
        return node
    }

    fun syncPlatformSnapshot(
        snapshot: SessionPlatformSnapshot,
    ) {
        val sessionId = snapshot.sessionId.ifBlank { snapshot.bridgeSnapshot.sessionId }
        if (sessionId.isBlank()) return
        val now = System.currentTimeMillis()
        val mailbox = SessionWorkerMailboxStore.readMailbox(target = sessionId, includeConsumed = false, limit = 64)
        upsert(sessionId) { current ->
            val pendingChildSessionIds =
                current.childSessionIds.filter { childId ->
                    read(childId)?.status?.isSessionTerminal() != true
                }
            current.copy(
                sessionId = sessionId,
                task = snapshot.bridgeSnapshot.task.ifBlank { current.task },
                entrySource = snapshot.bridgeSnapshot.entrySource.ifBlank { current.entrySource },
                statusSnapshot = snapshot.bridgeSnapshot.toStatusSnapshot(),
                blockedReason =
                    when {
                        snapshot.pendingSafetySummary.isNotBlank() -> snapshot.pendingSafetySummary
                        snapshot.bridgeSnapshot.statusTakeoverReason == AgentUiTakeoverReason.WAITING_EXTERNAL ->
                            snapshot.bridgeSnapshot.statusCode
                        snapshot.bridgeSnapshot.statusCategory == AgentUiStatusCategory.TAKEOVER ->
                            snapshot.bridgeSnapshot.takeoverSummary.ifBlank { "paused" }
                        else -> current.blockedReason
                    },
                pendingChildSessionIds = pendingChildSessionIds,
                pendingApprovalCount =
                    mailbox.count { it.type == SessionWorkerMailboxMessageType.PERMISSION_REQUEST },
                mailboxPendingCount = mailbox.size,
                descendantSessionCount = countDescendants(sessionId, linkedSetOf()),
                latestResultSummary = snapshot.bridgeSnapshot.resultSummary.ifBlank { snapshot.state.resultSnapshot?.summary.orEmpty() },
                latestErrorSummary = snapshot.bridgeSnapshot.errorSummary.ifBlank { snapshot.state.resultSnapshot?.lastError.orEmpty() },
                rootSessionId = current.rootSessionId.ifBlank { sessionId },
                coordinatorSessionId = current.coordinatorSessionId.ifBlank { current.rootSessionId.ifBlank { sessionId } },
                updatedAtMs = now,
                createdAtMs = current.createdAtMs.takeIf { it > 0L } ?: now,
            )
        }
    }

    fun readAll(
        limit: Int = 24,
        rootSessionId: String = "",
    ): List<SessionGraphNode> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            nodes
                .asSequence()
                .filter { rootSessionId.isBlank() || it.rootSessionId == rootSessionId || it.sessionId == rootSessionId }
                .take(limit)
                .toList()
        }

    fun read(
        sessionId: String,
    ): SessionGraphNode? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            nodes.firstOrNull { it.sessionId == sessionId }
        }

    fun resolveRootSessionId(
        sessionId: String,
    ): String =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            nodes.firstOrNull { it.sessionId == sessionId }?.rootSessionId?.ifBlank { sessionId }.orEmpty()
        }

    fun exportJson(
        limit: Int = 48,
    ): JSONArray =
        JSONArray().apply {
            readAll(limit = limit).forEach { put(it.toJson()) }
        }

    fun importJson(
        array: JSONArray?,
    ) {
        if (array == null || array.length() <= 0) return
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                nodes.addLast(json.toNode())
            }
            dedupeTrimUnlocked()
            persistUnlocked()
        }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            hydrated = false
            nodes.clear()
        }
    }

    private fun upsert(
        sessionId: String,
        reducer: (SessionGraphNode) -> SessionGraphNode,
    ): SessionGraphNode =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val index = nodes.indexOfFirst { it.sessionId == sessionId }
            val current =
                if (index >= 0) {
                    nodes.elementAt(index)
                } else {
                    SessionGraphNode(sessionId = sessionId)
                }
            val next = reducer(current)
            if (index >= 0) {
                nodes.removeAt(index)
                nodes.add(index, next)
            } else {
                nodes.addFirst(next)
            }
            dedupeTrimUnlocked()
            persistUnlocked()
            next
        }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = graphFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("nodes") ?: JSONArray()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                nodes.addLast(json.toNode())
            }
            dedupeTrimUnlocked()
        }
    }

    private fun dedupeTrimUnlocked() {
        val merged =
            nodes
                .distinctBy { it.sessionId }
                .sortedByDescending { it.updatedAtMs }
                .take(MAX_NODES)
        nodes.clear()
        merged.forEach(nodes::addLast)
    }

    private fun persistUnlocked() {
        val file = graphFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "nodes",
                    JSONArray().apply {
                        nodes.forEach { put(it.toJson()) }
                    },
                )
            }.toString(2),
        )
    }

    private fun graphFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, GRAPH_DIR), GRAPH_FILE)
    }

    private fun SessionGraphNode.toJson(): JSONObject =
        JSONObject().apply {
            put("session_id", sessionId)
            put("parent_session_id", parentSessionId)
            put("worker_id", workerId)
            put("parent_worker_id", parentWorkerId)
            put("agent_id", agentId)
            put("parent_agent_id", parentAgentId)
            put("root_session_id", rootSessionId)
            put("coordinator_session_id", coordinatorSessionId)
            put("task", task)
            put("entry_source", entrySource)
            put("status", status)
            put("status_snapshot", statusSnapshot.toJson())
            put("depth", depth)
            put("blocked_reason", blockedReason)
            put("child_session_ids", JSONArray(childSessionIds))
            put("pending_child_session_ids", JSONArray(pendingChildSessionIds))
            put("pending_approval_count", pendingApprovalCount)
            put("mailbox_pending_count", mailboxPendingCount)
            put("descendant_session_count", descendantSessionCount)
            put("latest_result_summary", latestResultSummary)
            put("latest_error_summary", latestErrorSummary)
            put("created_at_ms", createdAtMs)
            put("updated_at_ms", updatedAtMs)
            put(
                "metadata",
                JSONObject().apply {
                    metadata.forEach { (key, value) -> put(key, value) }
                },
            )
        }

    private fun JSONObject.toNode(): SessionGraphNode =
        SessionGraphNode(
            sessionId = optString("session_id"),
            parentSessionId = optString("parent_session_id"),
            workerId = optString("worker_id"),
            parentWorkerId = optString("parent_worker_id"),
            agentId = optString("agent_id"),
            parentAgentId = optString("parent_agent_id"),
            rootSessionId = optString("root_session_id"),
            coordinatorSessionId = optString("coordinator_session_id"),
            task = optString("task"),
            entrySource = optString("entry_source"),
            statusSnapshot = optJSONObject("status_snapshot").toAgentUiStatusSnapshot(optString("status", AgentUiStatus.IDLE)),
            depth = optInt("depth"),
            blockedReason = optString("blocked_reason"),
            childSessionIds = optJSONArray("child_session_ids").toStringList(),
            pendingChildSessionIds = optJSONArray("pending_child_session_ids").toStringList(),
            pendingApprovalCount = optInt("pending_approval_count"),
            mailboxPendingCount = optInt("mailbox_pending_count"),
            descendantSessionCount = optInt("descendant_session_count"),
            latestResultSummary = optString("latest_result_summary"),
            latestErrorSummary = optString("latest_error_summary"),
            createdAtMs = optLong("created_at_ms"),
            updatedAtMs = optLong("updated_at_ms"),
            metadata = optJSONObject("metadata").toStringMap(),
        )

    private fun countDescendants(
        sessionId: String,
        visited: MutableSet<String>,
    ): Int {
        if (!visited.add(sessionId)) return 0
        val node = nodes.firstOrNull { it.sessionId == sessionId } ?: return 0
        return node.childSessionIds.sumOf { childId -> 1 + countDescendants(childId, visited) }
    }

    private fun String.isSessionTerminal(): Boolean =
        AgentUiStatus.isTerminal(this)

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap {
            keys().forEach { key -> put(key, optString(key)) }
        }
    }
}
