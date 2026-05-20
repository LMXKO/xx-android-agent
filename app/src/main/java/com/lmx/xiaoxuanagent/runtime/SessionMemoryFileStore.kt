package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionMemoryFileSnapshot(
    val sessionId: String,
    val task: String = "",
    val profileId: String = "",
    val headline: String = "",
    val previewLines: List<String> = emptyList(),
    val markdownPath: String = "",
    val updatedAtMs: Long = 0L,
)

object SessionMemoryFileStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_memory_file.json"
    private const val FILE_DIR = "session_memory_files"
    private const val MAX_SESSIONS = 128
    private val lock = Any()
    private val snapshots = LinkedHashMap<String, SessionMemoryFileSnapshot>()
    private var hydrated = false

    fun refresh(
        sessionId: String,
        task: String,
        profileId: String,
        trigger: String,
    ): SessionMemoryFileSnapshot? {
        if (sessionId.isBlank()) return null
        val notebook = SessionMemoryNotebookStore.readSnapshot(sessionId)
        val document = SessionMemoryDocumentStore.readSnapshot(sessionId)
        val working = SessionWorkingMemoryStore.readSnapshot(sessionId)
        val webTrace = PlatformWebResearchTraceStore.readRecent(sessionId, limit = 3)
        val worker = SessionWorkerStore.readBySessionId(sessionId)
        val markdown =
            buildString {
                append("# Session Memory File\n\n")
                append("- session: ").append(sessionId).append('\n')
                append("- task: ").append(task.ifBlank { "-" }).append('\n')
                append("- profile: ").append(profileId.ifBlank { "-" }).append('\n')
                append("- trigger: ").append(trigger.ifBlank { "-" }).append("\n\n")

                append("## Current State\n\n")
                append("- progress: ").append(working?.progressSummary?.ifBlank { "-" } ?: "-").append('\n')
                append("- next_focus: ").append(working?.nextFocusHint?.ifBlank { "-" } ?: "-").append('\n')
                append("- status: ").append(working?.status?.ifBlank { "-" } ?: "-").append("\n\n")

                append("## Open Threads\n\n")
                if (working?.openLoops.isNullOrEmpty()) {
                    append("- none\n\n")
                } else {
                    working?.openLoops.orEmpty().take(6).forEach { append("- ").append(it).append('\n') }
                    append('\n')
                }

                append("## Stable Facts\n\n")
                if (document == null && notebook == null) {
                    append("- none\n\n")
                } else {
                    document?.previewLines?.take(3)?.forEach { append("- ").append(it).append('\n') }
                    notebook?.previewLines?.take(3)?.forEach { append("- ").append(it).append('\n') }
                    append('\n')
                }

                append("## Web Research\n\n")
                if (webTrace.isEmpty()) {
                    append("- none\n\n")
                } else {
                    webTrace.forEach { trace ->
                        append("- ").append(trace.summary.ifBlank { trace.query.ifBlank { trace.url } }).append('\n')
                    }
                    append('\n')
                }

                append("## Worker State\n\n")
                append("- worker: ").append(worker?.workerId?.ifBlank { "-" } ?: "-").append('\n')
                append("- worker_status: ").append(worker?.status?.name?.lowercase() ?: "-").append('\n')
                append("- worker_summary: ").append(worker?.summary?.ifBlank { "-" } ?: "-").append('\n')
            }
        return persist(
            SessionMemoryFileSnapshot(
                sessionId = sessionId,
                task = task,
                profileId = profileId,
                headline =
                    listOfNotNull(
                        working?.nextFocusHint?.takeIf { it.isNotBlank() }?.take(72),
                        notebook?.headline?.takeIf { it.isNotBlank() }?.take(72),
                    ).joinToString(" | ").ifBlank { "session_memory_file" },
                previewLines =
                    buildList {
                        working?.progressSummary?.takeIf { it.isNotBlank() }?.let { add("progress: ${it.take(96)}") }
                        working?.openLoops?.firstOrNull()?.let { add("open_loop: ${it.take(96)}") }
                        webTrace.firstOrNull()?.summary?.takeIf { it.isNotBlank() }?.let { add("web: ${it.take(96)}") }
                        worker?.status?.let { add("worker_status=${it.name.lowercase()}") }
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
                snapshot.headline.takeIf { it.isNotBlank() }?.let { add("session_memory_file: $it") }
                addAll(snapshot.previewLines.take((limit - 1).coerceAtLeast(0)))
            }.take(limit.coerceAtLeast(1))
        }.orEmpty()

    fun readSnapshot(
        sessionId: String,
    ): SessionMemoryFileSnapshot? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshots[sessionId]
        }

    private fun persist(
        snapshot: SessionMemoryFileSnapshot,
        markdown: String,
    ): SessionMemoryFileSnapshot? {
        val file = fileFor(snapshot.sessionId) ?: return null
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

    private fun fileFor(
        sessionId: String,
    ): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, FILE_DIR), "$sessionId.md")
    }

    private fun SessionMemoryFileSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("session_id", sessionId)
            put("task", task)
            put("profile_id", profileId)
            put("headline", headline)
            put("preview_lines", JSONArray(previewLines))
            put("markdown_path", markdownPath)
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toSnapshot(): SessionMemoryFileSnapshot =
        SessionMemoryFileSnapshot(
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
