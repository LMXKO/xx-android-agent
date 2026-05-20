package com.lmx.xiaoxuanagent.memory

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

enum class MemoryWorkspaceEntryType {
    CORE,
    DAILY,
    GENERATED,
}

data class MemoryWorkspaceEntry(
    val name: String,
    val path: String,
    val type: MemoryWorkspaceEntryType,
    val summary: String = "",
    val ageBucket: String = "fresh",
    val updatedAtMs: Long = 0L,
)

data class MemoryWorkspaceSnapshot(
    val rootPath: String = "",
    val coreMemoryPath: String = "",
    val digestPath: String = "",
    val manifestPath: String = "",
    val totalFiles: Int = 0,
    val freshFiles: Int = 0,
    val agingFiles: Int = 0,
    val staleFiles: Int = 0,
    val entries: List<MemoryWorkspaceEntry> = emptyList(),
    val summary: String = "",
    val updatedAtMs: Long = 0L,
)

object MemoryWorkspaceGovernance {
    private const val WORKSPACE_DIR = "memory_workspace"
    private const val CORE_MEMORY_FILE = "memory.md"
    private const val DIGEST_FILE = "memory_digest.md"
    private const val MANIFEST_FILE = "memory_manifest.json"
    private const val MANIFEST_MARKDOWN_FILE = "memory_manifest.md"
    private const val DAILY_DIR = "daily"
    private const val FRESH_WINDOW_MS = 3L * 24L * 60L * 60L * 1000L
    private const val AGING_WINDOW_MS = 14L * 24L * 60L * 60L * 1000L
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun refresh(
        json: JSONObject,
        reason: String,
    ): MemoryWorkspaceSnapshot? {
        val workspace = workspaceDir() ?: return null
        if (!workspace.exists()) {
            workspace.mkdirs()
        }
        val snapshot = scanWorkspace()
        writeDigest(workspace, json, snapshot, reason)
        writeManifest(workspace, snapshot)
        return scanWorkspace()
    }

    fun exportJson(
        snapshot: MemoryWorkspaceSnapshot = scanWorkspace(),
    ): JSONObject = snapshot.toJson(workspaceDir())

    fun importJson(
        json: JSONObject?,
    ): MemoryWorkspaceSnapshot {
        val workspace = workspaceDir() ?: return MemoryWorkspaceSnapshot()
        if (!workspace.exists()) {
            workspace.mkdirs()
        }
        if (json == null || json.length() == 0) {
            return scanWorkspace()
        }
        val importedSnapshot = json.toMemoryWorkspaceSnapshot()
        writeImportedWorkspace(
            workspace = workspace,
            snapshot = importedSnapshot,
            coreMemoryText = json.optString("core_memory_text"),
            digestMarkdown = json.optString("digest_markdown"),
            manifestMarkdown = json.optString("manifest_markdown"),
        )
        return scanWorkspace()
    }

    fun scanWorkspace(): MemoryWorkspaceSnapshot {
        val workspace = workspaceDir() ?: return MemoryWorkspaceSnapshot()
        if (!workspace.exists()) return MemoryWorkspaceSnapshot(rootPath = workspace.absolutePath)
        val files =
            workspace.walkTopDown()
                .filter { it.isFile && (it.extension == "md" || it.name == MANIFEST_FILE) }
                .toList()
        val entries =
            files
                .mapNotNull { file ->
                    when {
                        file.name == MANIFEST_FILE -> null
                        file.extension != "md" -> null
                        else -> {
                            val ageBucket = resolveAgeBucket(System.currentTimeMillis() - file.lastModified())
                            MemoryWorkspaceEntry(
                                name = file.name,
                                path = file.absolutePath,
                                type = resolveType(file),
                                summary = summarizeFile(file),
                                ageBucket = ageBucket,
                                updatedAtMs = file.lastModified(),
                            )
                        }
                    }
                }
                .sortedByDescending { it.updatedAtMs }
        val freshFiles = entries.count { it.ageBucket == "fresh" }
        val agingFiles = entries.count { it.ageBucket == "aging" }
        val staleFiles = entries.count { it.ageBucket == "stale" }
        return MemoryWorkspaceSnapshot(
            rootPath = workspace.absolutePath,
            coreMemoryPath = File(workspace, CORE_MEMORY_FILE).absolutePath,
            digestPath = File(workspace, DIGEST_FILE).absolutePath,
            manifestPath = File(workspace, MANIFEST_FILE).absolutePath,
            totalFiles = entries.size,
            freshFiles = freshFiles,
            agingFiles = agingFiles,
            staleFiles = staleFiles,
            entries = entries.take(40),
            summary =
                buildString {
                    append("files=").append(entries.size)
                    append(" fresh=").append(freshFiles)
                    append(" aging=").append(agingFiles)
                    append(" stale=").append(staleFiles)
                },
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    fun buildRecallLines(
        snapshot: MemoryWorkspaceSnapshot,
        limit: Int = 4,
    ): List<String> =
        buildList {
            if (snapshot.totalFiles > 0) {
                add("记忆工作区: ${snapshot.summary}")
            }
            snapshot.entries
                .filter { it.type == MemoryWorkspaceEntryType.DAILY || it.type == MemoryWorkspaceEntryType.GENERATED }
                .take(limit)
                .forEach { entry ->
                    add("工作区条目: ${entry.name} ${entry.summary.take(72)}")
                }
        }

    private fun workspaceDir(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(context.filesDir, WORKSPACE_DIR)
    }

    private fun resolveType(
        file: File,
    ): MemoryWorkspaceEntryType =
        when {
            file.name == CORE_MEMORY_FILE -> MemoryWorkspaceEntryType.CORE
            file.parentFile?.name == DAILY_DIR -> MemoryWorkspaceEntryType.DAILY
            else -> MemoryWorkspaceEntryType.GENERATED
        }

    private fun summarizeFile(
        file: File,
    ): String =
        runCatching {
            file.readLines()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                .orEmpty()
        }.getOrDefault("")

    private fun resolveAgeBucket(
        ageMs: Long,
    ): String =
        when {
            ageMs <= FRESH_WINDOW_MS -> "fresh"
            ageMs <= AGING_WINDOW_MS -> "aging"
            else -> "stale"
        }

    private fun writeDigest(
        workspace: File,
        json: JSONObject,
        snapshot: MemoryWorkspaceSnapshot,
        reason: String,
    ) {
        val file = File(workspace, DIGEST_FILE)
        val profileSummaries = json.optJSONObject("profile_summaries") ?: JSONObject()
        val facts = json.optJSONArray("facts") ?: JSONArray()
        val lines =
            buildList {
                add("# Xiaoxuan Memory Digest")
                add("")
                add("生成时间: ${dateFormatter.format(Date())}")
                add("原因: $reason")
                add("工作区: ${snapshot.summary.ifBlank { "files=0" }}")
                add("")
                add("## Profile Summaries")
                val keys = profileSummaries.keys()
                var count = 0
                while (keys.hasNext() && count < 6) {
                    val key = keys.next()
                    val summary = profileSummaries.optJSONObject(key)?.optString("summary").orEmpty()
                    if (summary.isNotBlank()) {
                        add("- $key: $summary")
                        count += 1
                    }
                }
                add("")
                add("## Recent Facts")
                for (index in maxOf(0, facts.length() - 8) until facts.length()) {
                    val fact = facts.optJSONObject(index)?.optString("content").orEmpty()
                    if (fact.isNotBlank()) {
                        add("- $fact")
                    }
                }
                add("")
                add("## Recent Files")
                snapshot.entries.take(8).forEach { entry ->
                    add("- [${entry.type.name.lowercase()}] ${entry.name} (${entry.ageBucket}) ${entry.summary.take(100)}")
                }
            }
        file.writeText(lines.joinToString("\n"))
    }

    private fun writeManifest(
        workspace: File,
        snapshot: MemoryWorkspaceSnapshot,
    ) {
        File(workspace, MANIFEST_FILE).writeText(
            JSONObject().apply {
                put("root_path", snapshot.rootPath)
                put("core_memory_path", snapshot.coreMemoryPath)
                put("digest_path", snapshot.digestPath)
                put("summary", snapshot.summary)
                put("updated_at_ms", snapshot.updatedAtMs)
                put(
                    "entries",
                    JSONArray().apply {
                        snapshot.entries.forEach { entry ->
                            put(
                                JSONObject().apply {
                                    put("name", entry.name)
                                    put("path", entry.path)
                                    put("type", entry.type.name)
                                    put("summary", entry.summary)
                                    put("age_bucket", entry.ageBucket)
                                    put("updated_at_ms", entry.updatedAtMs)
                                },
                            )
                        }
                    },
                )
            }.toString(2),
        )
        File(workspace, MANIFEST_MARKDOWN_FILE).writeText(
            buildString {
                append("# Memory Workspace Manifest\n\n")
                append("- summary: ").append(snapshot.summary).append('\n')
                append("- updated_at: ").append(snapshot.updatedAtMs).append('\n')
                append("- digest: ").append(snapshot.digestPath).append('\n')
                append('\n')
                snapshot.entries.take(20).forEach { entry ->
                    append("- [")
                    append(entry.type.name.lowercase())
                    append("] ")
                    append(entry.name)
                    append(" (")
                    append(entry.ageBucket)
                    append("): ")
                    append(entry.summary.take(100))
                    append('\n')
                }
            },
        )
    }

    private fun writeImportedWorkspace(
        workspace: File,
        snapshot: MemoryWorkspaceSnapshot,
        coreMemoryText: String,
        digestMarkdown: String,
        manifestMarkdown: String,
    ) {
        File(workspace, CORE_MEMORY_FILE)
            .takeIf { coreMemoryText.isNotBlank() || !it.exists() }
            ?.writeText(coreMemoryText.ifBlank { "# Xiaoxuan Memory\n" })
        File(workspace, DIGEST_FILE)
            .takeIf { digestMarkdown.isNotBlank() || !it.exists() }
            ?.writeText(
                digestMarkdown.ifBlank {
                    buildString {
                        append("# Xiaoxuan Memory Digest\n\n")
                        append("导入摘要: ").append(snapshot.summary.ifBlank { "files=0" }).append('\n')
                    }
                },
            )
        File(workspace, MANIFEST_FILE).writeText(snapshot.toManifestJson().toString(2))
        File(workspace, MANIFEST_MARKDOWN_FILE).writeText(
            manifestMarkdown.ifBlank { snapshot.toManifestMarkdown() },
        )
    }
}

private fun MemoryWorkspaceSnapshot.toJson(
    workspace: File?,
): JSONObject =
    JSONObject().apply {
        put("root_path", rootPath)
        put("core_memory_path", coreMemoryPath)
        put("digest_path", digestPath)
        put("manifest_path", manifestPath)
        put("total_files", totalFiles)
        put("fresh_files", freshFiles)
        put("aging_files", agingFiles)
        put("stale_files", staleFiles)
        put("summary", summary)
        put("updated_at_ms", updatedAtMs)
        put(
            "entries",
            JSONArray().apply {
                entries.forEach { entry ->
                    put(
                        JSONObject().apply {
                            put("name", entry.name)
                            put("path", entry.path)
                            put("type", entry.type.name)
                            put("summary", entry.summary)
                            put("age_bucket", entry.ageBucket)
                            put("updated_at_ms", entry.updatedAtMs)
                        },
                    )
                }
            },
        )
        put(
            "core_memory_text",
            workspace?.let { File(it, "memory.md").readTextIfExists() }.orEmpty(),
        )
        put(
            "digest_markdown",
            workspace?.let { File(it, "memory_digest.md").readTextIfExists() }.orEmpty(),
        )
        put(
            "manifest_markdown",
            workspace?.let { File(it, "memory_manifest.md").readTextIfExists() }.orEmpty(),
        )
    }

private fun MemoryWorkspaceSnapshot.toManifestJson(): JSONObject =
    JSONObject().apply {
        put("root_path", rootPath)
        put("core_memory_path", coreMemoryPath)
        put("digest_path", digestPath)
        put("summary", summary)
        put("updated_at_ms", updatedAtMs)
        put(
            "entries",
            JSONArray().apply {
                entries.forEach { entry ->
                    put(
                        JSONObject().apply {
                            put("name", entry.name)
                            put("path", entry.path)
                            put("type", entry.type.name)
                            put("summary", entry.summary)
                            put("age_bucket", entry.ageBucket)
                            put("updated_at_ms", entry.updatedAtMs)
                        },
                    )
                }
            },
        )
    }

private fun MemoryWorkspaceSnapshot.toManifestMarkdown(): String =
    buildString {
        append("# Memory Workspace Manifest\n\n")
        append("- summary: ").append(summary).append('\n')
        append("- updated_at: ").append(updatedAtMs).append('\n')
        append("- digest: ").append(digestPath).append('\n')
        append('\n')
        entries.take(20).forEach { entry ->
            append("- [")
            append(entry.type.name.lowercase())
            append("] ")
            append(entry.name)
            append(" (")
            append(entry.ageBucket)
            append("): ")
            append(entry.summary.take(100))
            append('\n')
        }
    }

private fun JSONObject.toMemoryWorkspaceSnapshot(): MemoryWorkspaceSnapshot =
    MemoryWorkspaceSnapshot(
        rootPath = optString("root_path"),
        coreMemoryPath = optString("core_memory_path"),
        digestPath = optString("digest_path"),
        manifestPath = optString("manifest_path"),
        totalFiles = optInt("total_files"),
        freshFiles = optInt("fresh_files"),
        agingFiles = optInt("aging_files"),
        staleFiles = optInt("stale_files"),
        entries =
            buildList {
                val array = optJSONArray("entries") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val entry = array.optJSONObject(index) ?: continue
                    add(
                        MemoryWorkspaceEntry(
                            name = entry.optString("name"),
                            path = entry.optString("path"),
                            type =
                                runCatching {
                                    MemoryWorkspaceEntryType.valueOf(entry.optString("type"))
                                }.getOrDefault(MemoryWorkspaceEntryType.GENERATED),
                            summary = entry.optString("summary"),
                            ageBucket = entry.optString("age_bucket", "fresh"),
                            updatedAtMs = entry.optLong("updated_at_ms"),
                        ),
                    )
                }
            },
        summary = optString("summary"),
        updatedAtMs = optLong("updated_at_ms"),
    )

private fun File.readTextIfExists(): String = takeIf(File::exists)?.readText().orEmpty()
