package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionMemoryDocumentSnapshot(
    val sessionId: String,
    val task: String = "",
    val profileId: String = "",
    val headline: String = "",
    val previewLines: List<String> = emptyList(),
    val markdownPath: String = "",
    val updatedAtMs: Long = 0L,
)

object SessionMemoryDocumentStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_memory_documents.json"
    private const val DOC_DIR = "session_memory_documents"
    private const val MAX_SESSIONS = 128
    private val lock = Any()
    private val snapshots = LinkedHashMap<String, SessionMemoryDocumentSnapshot>()
    private var hydrated = false

    fun refresh(
        sessionId: String,
        task: String,
        profileId: String,
        trigger: String,
    ): SessionMemoryDocumentSnapshot? {
        if (sessionId.isBlank()) return null
        val notebook = SessionMemoryNotebookStore.readSnapshot(sessionId)
        val todo = SessionTodoBoardStore.readSnapshot(sessionId)
        val compact = SessionTranscriptCompactionStore.readSnapshot(sessionId)
        val mailbox = SessionWorkerMailboxStore.readSnapshot(target = sessionId, includeConsumed = false, limit = 4)
        val working = SessionWorkingMemoryStore.readSnapshot(sessionId)
        val markdown =
            buildString {
                append("# Session Memory Document\n\n")
                append("- session: ").append(sessionId).append('\n')
                append("- task: ").append(task.ifBlank { "-" }).append('\n')
                append("- profile: ").append(profileId.ifBlank { "-" }).append('\n')
                append("- trigger: ").append(trigger.ifBlank { "-" }).append("\n\n")

                append("## Focus\n\n")
                append("- headline: ").append(notebook?.headline?.ifBlank { "-" } ?: "-").append('\n')
                append("- progress: ").append(working?.progressSummary?.ifBlank { "-" } ?: "-").append('\n')
                append("- next_focus: ").append(working?.nextFocusHint?.ifBlank { "-" } ?: "-").append("\n\n")

                append("## Notebook\n\n")
                if (notebook == null) {
                    append("- none\n\n")
                } else {
                    notebook.previewLines.take(6).forEach { append("- ").append(it).append('\n') }
                    append('\n')
                }

                append("## Todo Board\n\n")
                if (todo == null || todo.items.isEmpty()) {
                    append("- none\n\n")
                } else {
                    todo.items.take(6).forEach { item ->
                        append("- ").append(item.status).append(" | ").append(item.text).append('\n')
                    }
                    append('\n')
                }

                append("## Transcript Compact\n\n")
                append("- mode: ").append(compact?.mode?.ifBlank { "-" } ?: "-").append('\n')
                append("- digest: ").append(compact?.boundaryDigest?.ifBlank { "-" } ?: "-").append('\n')
                compact?.lines?.take(4)?.forEach { append("- ").append(it).append('\n') }
                append('\n')

                append("## Worker Mailbox\n\n")
                if (mailbox.pendingCount <= 0) {
                    append("- none\n")
                } else {
                    mailbox.recentLines.take(4).forEach { append("- ").append(it).append('\n') }
                }
            }
        return persist(
            SessionMemoryDocumentSnapshot(
                sessionId = sessionId,
                task = task,
                profileId = profileId,
                headline =
                    listOfNotNull(
                        notebook?.headline?.takeIf { it.isNotBlank() },
                        working?.nextFocusHint?.takeIf { it.isNotBlank() }?.take(80),
                    ).joinToString(" | ").ifBlank { "session_memory_document" },
                previewLines =
                    buildList {
                        notebook?.previewLines?.take(2)?.forEach(::add)
                        todo?.summary?.takeIf { it.isNotBlank() }?.let { add("todo: $it") }
                        compact?.boundaryDigest?.takeIf { it.isNotBlank() }?.let { add("compact: ${it.take(96)}") }
                        if (mailbox.pendingCount > 0) {
                            add("mailbox_pending=${mailbox.pendingCount}")
                        }
                    }.take(6),
                markdownPath = "",
                updatedAtMs = System.currentTimeMillis(),
            ),
            markdown = markdown,
        )
    }

    fun planningLines(
        sessionId: String,
        limit: Int = 4,
    ): List<String> =
        readSnapshot(sessionId)?.let { snapshot ->
            buildList {
                snapshot.headline.takeIf { it.isNotBlank() }?.let { add("memory_document: $it") }
                addAll(snapshot.previewLines.take((limit - 1).coerceAtLeast(0)))
            }.take(limit.coerceAtLeast(1))
        }.orEmpty()

    fun readSnapshot(
        sessionId: String,
    ): SessionMemoryDocumentSnapshot? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshots[sessionId]
        }

    private fun persist(
        snapshot: SessionMemoryDocumentSnapshot,
        markdown: String,
    ): SessionMemoryDocumentSnapshot? {
        val file = documentFile(snapshot.sessionId) ?: return null
        file.parentFile?.mkdirs()
        file.writeText(markdown)
        val persisted = snapshot.copy(markdownPath = file.absolutePath, updatedAtMs = System.currentTimeMillis())
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshots[persisted.sessionId] = persisted
            trimUnlocked()
            persistUnlocked()
        }
        return persisted
    }

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

    private fun documentFile(
        sessionId: String,
    ): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, DOC_DIR), "$sessionId.md")
    }

    private fun SessionMemoryDocumentSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("session_id", sessionId)
            put("task", task)
            put("profile_id", profileId)
            put("headline", headline)
            put("preview_lines", JSONArray(previewLines))
            put("markdown_path", markdownPath)
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toSnapshot(): SessionMemoryDocumentSnapshot =
        SessionMemoryDocumentSnapshot(
            sessionId = optString("session_id"),
            task = optString("task"),
            profileId = optString("profile_id"),
            headline = optString("headline"),
            previewLines =
                optJSONArray("preview_lines")?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            add(array.optString(index))
                        }
                    }
                }.orEmpty(),
            markdownPath = optString("markdown_path"),
            updatedAtMs = optLong("updated_at_ms"),
        )
}
