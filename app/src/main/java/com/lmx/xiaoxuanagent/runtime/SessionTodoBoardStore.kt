package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionTodoItem(
    val id: String,
    val text: String,
    val status: String = "open",
    val source: String = "",
    val updatedAtMs: Long = 0L,
)

data class SessionTodoBoardSnapshot(
    val sessionId: String,
    val items: List<SessionTodoItem> = emptyList(),
    val summary: String = "",
    val updatedAtMs: Long = 0L,
)

object SessionTodoBoardStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_todo_board.json"
    private const val MAX_SESSIONS = 80
    private const val MAX_ITEMS = 24
    private val lock = Any()
    private val snapshots = LinkedHashMap<String, SessionTodoBoardSnapshot>()
    private var hydrated = false

    fun readSnapshot(
        sessionId: String,
    ): SessionTodoBoardSnapshot? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshots[sessionId]
        }

    fun upsertItems(
        sessionId: String,
        rawItems: List<String>,
        mode: String = "append",
        source: String = "tool",
    ): SessionTodoBoardSnapshot? =
        synchronized(lock) {
            if (sessionId.isBlank()) return null
            hydrateIfNeededUnlocked()
            val normalized =
                rawItems
                    .map { it.trim().trimStart('-', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', ' ') }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(MAX_ITEMS)
            if (normalized.isEmpty()) return snapshots[sessionId]
            val previous = snapshots[sessionId]
            val now = System.currentTimeMillis()
            val existing =
                if (mode.equals("replace", ignoreCase = true)) {
                    emptyList()
                } else {
                    previous?.items.orEmpty()
                }
            val merged =
                (existing + normalized.map { text ->
                    SessionTodoItem(
                        id = todoId(text),
                        text = text,
                        status = "open",
                        source = source,
                        updatedAtMs = now,
                    )
                }).associateBy(SessionTodoItem::id)
                    .values
                    .sortedBy { it.text }
                    .takeLast(MAX_ITEMS)
            val snapshot =
                SessionTodoBoardSnapshot(
                    sessionId = sessionId,
                    items = merged,
                    summary = buildSummary(merged),
                    updatedAtMs = now,
                )
            snapshots[sessionId] = snapshot
            trimUnlocked()
            persistUnlocked()
            snapshot
        }

    fun completeMatching(
        sessionId: String,
        text: String,
    ): SessionTodoBoardSnapshot? =
        synchronized(lock) {
            if (sessionId.isBlank() || text.isBlank()) return null
            hydrateIfNeededUnlocked()
            val previous = snapshots[sessionId] ?: return null
            val now = System.currentTimeMillis()
            val nextItems =
                previous.items.map { item ->
                    if (item.text.contains(text, ignoreCase = true)) {
                        item.copy(status = "done", updatedAtMs = now)
                    } else {
                        item
                    }
                }
            val snapshot =
                previous.copy(
                    items = nextItems,
                    summary = buildSummary(nextItems),
                    updatedAtMs = now,
                )
            snapshots[sessionId] = snapshot
            persistUnlocked()
            snapshot
        }

    fun planningLines(
        sessionId: String,
        limit: Int = 4,
    ): List<String> =
        readSnapshot(sessionId)?.let { snapshot ->
            buildList {
                add("todo_board: ${snapshot.summary.ifBlank { "-" }}")
                snapshot.items.take(limit.coerceAtLeast(1)).forEach { item ->
                    add("todo_item: ${item.status} | ${item.text.take(88)}")
                }
            }
        }.orEmpty()

    private fun buildSummary(
        items: List<SessionTodoItem>,
    ): String =
        buildString {
            append("open=").append(items.count { it.status != "done" })
            append(" done=").append(items.count { it.status == "done" })
            items.firstOrNull()?.let { append(" top=").append(it.text.take(36)) }
        }

    private fun todoId(
        text: String,
    ): String = "todo_${text.lowercase().hashCode().toUInt().toString(16)}"

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = storeFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("snapshots") ?: JSONArray()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val snapshot = json.toSnapshot()
                snapshots[snapshot.sessionId] = snapshot
            }
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

    private fun SessionTodoBoardSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("session_id", sessionId)
            put("summary", summary)
            put("updated_at_ms", updatedAtMs)
            put(
                "items",
                JSONArray().apply {
                    items.forEach { item ->
                        put(
                            JSONObject().apply {
                                put("id", item.id)
                                put("text", item.text)
                                put("status", item.status)
                                put("source", item.source)
                                put("updated_at_ms", item.updatedAtMs)
                            },
                        )
                    }
                },
            )
        }

    private fun JSONObject.toSnapshot(): SessionTodoBoardSnapshot =
        SessionTodoBoardSnapshot(
            sessionId = optString("session_id"),
            items =
                optJSONArray("items")?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            val item = array.optJSONObject(index) ?: continue
                            add(
                                SessionTodoItem(
                                    id = item.optString("id"),
                                    text = item.optString("text"),
                                    status = item.optString("status").ifBlank { "open" },
                                    source = item.optString("source"),
                                    updatedAtMs = item.optLong("updated_at_ms"),
                                ),
                            )
                        }
                    }
                }.orEmpty(),
            summary = optString("summary"),
            updatedAtMs = optLong("updated_at_ms"),
        )
}
