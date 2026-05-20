package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskStore
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionLoopInboxItem(
    val source: String,
    val itemId: String,
    val title: String = "",
    val summary: String = "",
    val priority: Int = 0,
    val recommendedCommand: String = "",
    val detailLines: List<String> = emptyList(),
    val createdAtMs: Long = 0L,
    val drainToken: String = "",
)

data class SessionLoopInboxSnapshot(
    val sessionId: String = "",
    val totalCount: Int = 0,
    val attentionCount: Int = 0,
    val summary: String = "",
    val lines: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

data class SessionLoopDrainSnapshot(
    val sessionId: String = "",
    val turn: Int = 0,
    val items: List<SessionLoopInboxItem> = emptyList(),
    val attachmentCount: Int = 0,
    val summary: String = "",
    val attachmentLines: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

private data class SessionLoopDrainLedger(
    val sessionId: String,
    val lastTurn: Int = 0,
    val totalConsumed: Int = 0,
    val consumedTokens: Map<String, Long> = emptyMap(),
    val updatedAtMs: Long = 0L,
)

object SessionLoopInboxStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_loop_inbox.json"
    private const val MAX_SESSIONS = 160
    private const val MAX_CONSUMED_TOKENS = 128
    private val lock = Any()
    private val snapshots = LinkedHashMap<String, SessionLoopInboxSnapshot>()
    private val drainLedgers = LinkedHashMap<String, SessionLoopDrainLedger>()
    private var hydrated = false

    fun refresh(
        sessionId: String,
        task: String = "",
        limit: Int = 8,
    ): SessionLoopInboxSnapshot? {
        if (sessionId.isBlank()) return null
        val items = visibleItems(sessionId = sessionId, task = task)
        val ledger = readDrainLedger(sessionId)
        val snapshot =
            SessionLoopInboxSnapshot(
                sessionId = sessionId,
                totalCount = items.size,
                attentionCount = items.count { it.priority >= 90 },
                summary =
                    buildString {
                        append("items=").append(items.size)
                        append(" attention=").append(items.count { it.priority >= 90 })
                        ledger?.takeIf { it.totalConsumed > 0 }?.let { append(" consumed=").append(it.totalConsumed) }
                        items.firstOrNull()?.let { append(" top=").append(it.title.ifBlank { it.source }.take(36)) }
                    },
                lines =
                    items.take(limit.coerceAtLeast(1)).map { item ->
                        buildString {
                            append(item.source)
                            append(" | p=").append(item.priority)
                            append(" | ").append(item.title.ifBlank { item.itemId }.take(48))
                            item.summary.takeIf { it.isNotBlank() }?.let { append(" | ").append(it.take(88)) }
                        }
                    },
                recommendedCommands =
                    items.mapNotNull { it.recommendedCommand.takeIf(String::isNotBlank) }
                        .plus("/loop-inbox --session-id $sessionId")
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
    ): SessionLoopInboxSnapshot? =
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
                add("loop_inbox: ${snapshot.summary.ifBlank { "-" }}")
                addAll(snapshot.lines.take(limit.coerceAtLeast(1)).map { "loop_inbox_item: $it" })
            }.take(limit.coerceAtLeast(1))
        }.orEmpty()

    fun drainForPlanning(
        sessionId: String,
        task: String = "",
        turn: Int = 0,
        limit: Int = 6,
    ): SessionLoopDrainSnapshot? {
        if (sessionId.isBlank()) return null
        val items = visibleItems(sessionId = sessionId, task = task).take(limit.coerceAtLeast(1))
        recordDrainConsumption(sessionId = sessionId, turn = turn, items = items)
        return SessionLoopDrainSnapshot(
            sessionId = sessionId,
            turn = turn,
            items = items,
            attachmentCount = items.size,
            summary =
                buildString {
                    append("attachments=").append(items.size)
                    append(" attention=").append(items.count { it.priority >= 90 })
                    items.firstOrNull()?.let { append(" top=").append(it.title.ifBlank { it.source }.take(36)) }
                },
            attachmentLines =
                items.take(limit.coerceAtLeast(1)).map { item ->
                    buildString {
                        append("source=").append(item.source)
                        append(" | priority=").append(item.priority)
                        append(" | title=").append(item.title.ifBlank { item.itemId }.take(48))
                        item.summary.takeIf { it.isNotBlank() }?.let { append(" | summary=").append(it.take(96)) }
                        item.recommendedCommand.takeIf { it.isNotBlank() }?.let { append(" | command=").append(it) }
                        if (item.detailLines.isNotEmpty()) {
                            append(" | details=").append(item.detailLines.joinToString(" ; ").take(140))
                        }
                    }
                },
            recommendedCommands =
                items.mapNotNull { it.recommendedCommand.takeIf(String::isNotBlank) }
                    .plus("/loop-runtime --session-id $sessionId")
                    .plus("/loop-inbox --session-id $sessionId")
                    .distinct()
                    .take(8),
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private fun deriveItems(
        sessionId: String,
        task: String,
    ): List<SessionLoopInboxItem> {
        val commandItems =
            SessionCommandCenterStore.readRecentForSession(sessionId, limit = 6)
                .filter { it.status == SessionCommandReceiptStatus.SUBMITTED || it.status == SessionCommandReceiptStatus.RUNNING || it.status == SessionCommandReceiptStatus.FAILED }
                .map { receipt ->
                    SessionLoopInboxItem(
                        source = "command",
                        itemId = receipt.receiptId,
                        title = receipt.capability.ifBlank { "command" },
                        summary = receipt.summary,
                        priority =
                            when (receipt.status) {
                                SessionCommandReceiptStatus.FAILED -> 95
                                SessionCommandReceiptStatus.RUNNING -> 72
                                SessionCommandReceiptStatus.SUBMITTED -> 65
                                SessionCommandReceiptStatus.SUCCEEDED -> 20
                            },
                        recommendedCommand = "/command-center",
                        detailLines = receipt.lines.take(3),
                        createdAtMs = receipt.updatedAtMs,
                        drainToken = buildDrainToken(source = "command", itemId = receipt.receiptId, revisionMs = receipt.updatedAtMs, summary = receipt.summary),
                    )
                }
        val mailboxItems =
            SessionWorkerMailboxStore.readMailbox(target = sessionId, includeConsumed = false, limit = 6)
                .map { message ->
                    SessionLoopInboxItem(
                        source = "mailbox",
                        itemId = message.messageId,
                        title = message.type.name.lowercase(),
                        summary = message.summary.ifBlank { message.title },
                        priority = message.priority.coerceAtLeast(60),
                        recommendedCommand =
                            when (message.type) {
                                SessionWorkerMailboxMessageType.PERMISSION_REQUEST -> "/safety-center --session-id $sessionId"
                                else -> "/worker-mailbox --target $sessionId"
                            },
                        detailLines = message.payload.entries.take(3).map { "${it.key}=${it.value}" },
                        createdAtMs = message.updatedAtMs,
                        drainToken =
                            buildDrainToken(
                                source = "mailbox",
                                itemId = message.messageId,
                                revisionMs = message.updatedAtMs,
                                summary = message.summary.ifBlank { message.title },
                            ),
                    )
                }
        val proactiveItems =
            AssistantProactiveTaskStore.readAll(limit = 24)
                .filter { it.enabled && (it.sessionId == sessionId || it.parentSessionId == sessionId) }
                .map { taskItem ->
                    SessionLoopInboxItem(
                        source = "follow_up",
                        itemId = taskItem.id,
                        title = taskItem.title,
                        summary = taskItem.summary,
                        priority = taskItem.priority + if (taskItem.fireAtMs in 1..System.currentTimeMillis()) 20 else 0,
                        recommendedCommand = taskItem.recommendedCommand.ifBlank { "/follow-ups" },
                        detailLines =
                            listOfNotNull(
                                taskItem.type.name.lowercase().takeIf { it.isNotBlank() },
                                taskItem.deadlineAtMs.takeIf { it > 0L }?.let { "deadline_at_ms=$it" },
                            ),
                        createdAtMs = taskItem.updatedAtMs,
                        drainToken =
                            buildDrainToken(
                                source = "follow_up",
                                itemId = taskItem.id,
                                revisionMs = taskItem.updatedAtMs,
                                summary = taskItem.summary.ifBlank { taskItem.title },
                            ),
                    )
                }
        val memoryItems =
            BackgroundMemoryExtractor.readQueue(includeCompleted = false, limit = 12)
                .filter { it.sessionId == sessionId || (it.sessionId.isBlank() && it.task == task) }
                .map { memoryTask ->
                    SessionLoopInboxItem(
                        source = "memory",
                        itemId = memoryTask.taskId,
                        title = memoryTask.type.name.lowercase(),
                        summary = memoryTask.status.name.lowercase(),
                        priority =
                            when (memoryTask.status) {
                                BackgroundMemoryTaskStatus.FAILED -> 90
                                BackgroundMemoryTaskStatus.DEFERRED -> 75
                                BackgroundMemoryTaskStatus.RUNNING -> 55
                                else -> 35
                            },
                        recommendedCommand = "/memory-maintenance",
                        detailLines = listOfNotNull(memoryTask.failureReason.takeIf { it.isNotBlank() }),
                        createdAtMs = memoryTask.updatedAtMs,
                        drainToken =
                            buildDrainToken(
                                source = "memory",
                                itemId = memoryTask.taskId,
                                revisionMs = memoryTask.updatedAtMs,
                                summary = memoryTask.status.name.lowercase(),
                            ),
                    )
                }
        val curatorItem =
            SessionMemoryCuratorStore.readSnapshot(sessionId)?.let { curator ->
                SessionLoopInboxItem(
                    source = "curator",
                    itemId = curator.lastTaskId.ifBlank { sessionId },
                    title = "memory_curator",
                    summary = curator.summary,
                    priority =
                        when (curator.status) {
                            "failed" -> 92
                            "deferred" -> 78
                            "running" -> 58
                            "queued" -> 52
                            else -> 24
                        },
                    recommendedCommand =
                        curator.workerId.takeIf { it.isNotBlank() }?.let { "/worker-mailbox --target $it" }
                            ?: "/memory-curator --session-id $sessionId",
                    detailLines = curator.lines.take(3),
                    createdAtMs = curator.updatedAtMs,
                    drainToken =
                        buildDrainToken(
                            source = "curator",
                            itemId = curator.lastTaskId.ifBlank { sessionId },
                            revisionMs = curator.updatedAtMs,
                            summary = curator.summary,
                        ),
                )
            }
        return (commandItems + mailboxItems + proactiveItems + memoryItems + listOfNotNull(curatorItem))
            .sortedWith(compareByDescending<SessionLoopInboxItem> { it.priority }.thenByDescending { it.createdAtMs })
            .distinctBy { "${it.source}:${it.itemId}" }
            .take(16)
    }

    private fun visibleItems(
        sessionId: String,
        task: String,
    ): List<SessionLoopInboxItem> {
        val ledger = readDrainLedger(sessionId)
        return deriveItems(sessionId = sessionId, task = task)
            .filterNot { item -> ledger?.consumedTokens?.containsKey(item.drainToken) == true }
    }

    private fun recordDrainConsumption(
        sessionId: String,
        turn: Int,
        items: List<SessionLoopInboxItem>,
    ) {
        if (sessionId.isBlank() || items.isEmpty()) return
        val mailboxIdsToAcknowledge =
            items.filter { it.source == "mailbox" }
                .mapNotNull { it.itemId.takeIf(String::isNotBlank) }
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val now = System.currentTimeMillis()
            val current = drainLedgers[sessionId]
            val nextConsumed =
                LinkedHashMap(current?.consumedTokens.orEmpty()).apply {
                    items.forEach { item ->
                        item.drainToken.takeIf { it.isNotBlank() }?.let { put(it, now) }
                    }
                    while (size > MAX_CONSUMED_TOKENS) {
                        val oldest = entries.minByOrNull { it.value }?.key ?: break
                        remove(oldest)
                    }
                }
            drainLedgers[sessionId] =
                SessionLoopDrainLedger(
                    sessionId = sessionId,
                    lastTurn = turn,
                    totalConsumed = (current?.totalConsumed ?: 0) + items.size,
                    consumedTokens = nextConsumed,
                    updatedAtMs = now,
                )
            persistUnlocked()
        }
        mailboxIdsToAcknowledge.forEach { messageId ->
            SessionWorkerMailboxStore.acknowledge(
                messageId = messageId,
                note = "loop_runtime_drain:$sessionId:$turn",
            )
        }
    }

    private fun readDrainLedger(
        sessionId: String,
    ): SessionLoopDrainLedger? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            drainLedgers[sessionId]
        }

    private fun buildDrainToken(
        source: String,
        itemId: String,
        revisionMs: Long,
        summary: String,
    ): String = "$source:$itemId:$revisionMs:${summary.hashCode()}"

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = storeFile() ?: return
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            val snapshotArray = root.optJSONArray("snapshots") ?: JSONArray()
            for (index in 0 until snapshotArray.length()) {
                snapshotArray.optJSONObject(index)?.toSnapshot()?.let { snapshots[it.sessionId] = it }
            }
            val drainArray = root.optJSONArray("drain_ledgers") ?: JSONArray()
            for (index in 0 until drainArray.length()) {
                drainArray.optJSONObject(index)?.toDrainLedger()?.let { drainLedgers[it.sessionId] = it }
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
                put(
                    "drain_ledgers",
                    JSONArray().apply {
                        drainLedgers.values.sortedByDescending { it.updatedAtMs }.forEach { put(it.toJson()) }
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
        while (drainLedgers.size > MAX_SESSIONS) {
            val oldest = drainLedgers.minByOrNull { it.value.updatedAtMs }?.key ?: break
            drainLedgers.remove(oldest)
        }
    }

    private fun storeFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, STORE_DIR), STORE_FILE)
    }

    private fun SessionLoopInboxSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("session_id", sessionId)
            put("total_count", totalCount)
            put("attention_count", attentionCount)
            put("summary", summary)
            put("lines", JSONArray(lines))
            put("recommended_commands", JSONArray(recommendedCommands))
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toSnapshot(): SessionLoopInboxSnapshot =
        SessionLoopInboxSnapshot(
            sessionId = optString("session_id"),
            totalCount = optInt("total_count"),
            attentionCount = optInt("attention_count"),
            summary = optString("summary"),
            lines = optJSONArray("lines").toStringList(),
            recommendedCommands = optJSONArray("recommended_commands").toStringList(),
            updatedAtMs = optLong("updated_at_ms"),
        )

    private fun SessionLoopDrainLedger.toJson(): JSONObject =
        JSONObject().apply {
            put("session_id", sessionId)
            put("last_turn", lastTurn)
            put("total_consumed", totalConsumed)
            put("updated_at_ms", updatedAtMs)
            put(
                "consumed_tokens",
                JSONArray().apply {
                    consumedTokens.forEach { (token, consumedAtMs) ->
                        put(
                            JSONObject().apply {
                                put("token", token)
                                put("consumed_at_ms", consumedAtMs)
                            },
                        )
                    }
                },
            )
        }

    private fun JSONObject.toDrainLedger(): SessionLoopDrainLedger =
        SessionLoopDrainLedger(
            sessionId = optString("session_id"),
            lastTurn = optInt("last_turn"),
            totalConsumed = optInt("total_consumed"),
            consumedTokens =
                buildMap {
                    val array = optJSONArray("consumed_tokens") ?: JSONArray()
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.let { item ->
                            val token = item.optString("token")
                            if (token.isNotBlank()) {
                                put(token, item.optLong("consumed_at_ms"))
                            }
                        }
                    }
                },
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
