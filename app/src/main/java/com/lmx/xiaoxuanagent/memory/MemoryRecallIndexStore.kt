package com.lmx.xiaoxuanagent.memory

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class MemoryRecallEntry(
    val id: String,
    val type: String,
    val title: String,
    val content: String,
    val profileId: String = "",
    val updatedAt: Long = 0L,
    val tokens: List<String> = emptyList(),
)

data class MemoryRecallHit(
    val id: String,
    val type: String,
    val title: String,
    val preview: String,
    val profileId: String = "",
    val score: Int = 0,
    val updatedAt: Long = 0L,
)

object MemoryRecallIndexStore {
    private const val MEMORY_DIR = "memory"
    private const val INDEX_FILE = "memory_recall_index.json"
    private const val MAX_INDEX_ENTRIES = 240
    private val lock = Any()

    fun rebuild(
        memoryJson: JSONObject,
    ) {
        val entries = buildEntries(memoryJson).take(MAX_INDEX_ENTRIES)
        synchronized(lock) {
            val file = indexFile() ?: return
            file.parentFile?.mkdirs()
            file.writeText(
                JSONObject().apply {
                    put(
                        "entries",
                        JSONArray().apply {
                            entries.forEach { entry ->
                                put(
                                    JSONObject().apply {
                                        put("id", entry.id)
                                        put("type", entry.type)
                                        put("title", entry.title)
                                        put("content", entry.content)
                                        put("profile_id", entry.profileId)
                                        put("updated_at", entry.updatedAt)
                                        put("tokens", JSONArray(entry.tokens))
                                    },
                                )
                            }
                        },
                    )
                }.toString(2),
            )
        }
    }

    fun search(
        query: String,
        profileId: String = "",
        limit: Int = 6,
    ): List<MemoryRecallHit> {
        val normalized = query.trim()
        if (normalized.isBlank()) return emptyList()
        val queryTokens = tokenize(normalized).toSet()
        return synchronized(lock) {
            readEntriesUnlocked()
                .map { entry ->
                    MemoryRecallHit(
                        id = entry.id,
                        type = entry.type,
                        title = entry.title,
                        preview = buildPreview(entry),
                        profileId = entry.profileId,
                        score = scoreEntry(entry, normalized, queryTokens, profileId),
                        updatedAt = entry.updatedAt,
                    )
                }
                .filter { it.score > 0 }
                .sortedWith(compareByDescending<MemoryRecallHit> { it.score }.thenByDescending { it.updatedAt })
                .take(limit)
        }
    }

    private fun readEntriesUnlocked(): List<MemoryRecallEntry> {
        val file = indexFile() ?: return emptyList()
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONObject(file.readText()).optJSONArray("entries") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    add(
                        MemoryRecallEntry(
                            id = json.optString("id"),
                            type = json.optString("type"),
                            title = json.optString("title"),
                            content = json.optString("content"),
                            profileId = json.optString("profile_id"),
                            updatedAt = json.optLong("updated_at"),
                            tokens =
                                buildList {
                                    val tokens = json.optJSONArray("tokens") ?: JSONArray()
                                    for (tokenIndex in 0 until tokens.length()) {
                                        tokens.optString(tokenIndex).takeIf { it.isNotBlank() }?.let(::add)
                                    }
                                },
                        ),
                    )
                }
            }
        }.getOrElse {
            emptyList()
        }
    }

    private fun buildEntries(
        memoryJson: JSONObject,
    ): List<MemoryRecallEntry> {
        val entries = mutableListOf<MemoryRecallEntry>()
        val episodes = memoryJson.optJSONArray("episodes") ?: JSONArray()
        for (index in 0 until episodes.length()) {
            val episode = episodes.optJSONObject(index) ?: continue
            val task = episode.optString("task")
            val message = episode.optString("final_message")
            val content = listOf(task, message).filter { it.isNotBlank() }.joinToString(" | ")
            if (content.isBlank()) continue
            entries +=
                memoryEntry(
                    id = "episode:$index",
                    type = "episode",
                    title = task.ifBlank { "历史任务" },
                    content = content,
                    profileId = episode.optString("profile_id"),
                    updatedAt = episode.optLong("timestamp"),
                )
        }
        val facts = memoryJson.optJSONArray("facts") ?: JSONArray()
        for (index in 0 until facts.length()) {
            val fact = facts.optJSONObject(index) ?: continue
            val content = fact.optString("content")
            if (content.isBlank()) continue
            entries +=
                memoryEntry(
                    id = "fact:$index",
                    type = "fact",
                    title = content.take(24),
                    content = content,
                    profileId = fact.optString("profile_id"),
                    updatedAt = fact.optLong("updated_at"),
                )
        }
        val structured = memoryJson.optJSONObject("structured") ?: JSONObject()
        entries += structured.optJSONArray("result_artifacts").toEntries("result_artifact") { item, index ->
            memoryEntry(
                id = "result_artifact:$index",
                type = "result_artifact",
                title = item.optString("title").ifBlank { "结果记忆" },
                content = "${item.optString("title")} | ${item.optString("summary")}",
                profileId = item.optString("source_profile_id"),
                updatedAt = item.optLong("updated_at"),
            )
        }
        entries += structured.optJSONArray("contacts").toEntries("contact") { item, index ->
            memoryEntry(
                id = "contact:$index",
                type = "contact",
                title = item.optString("name").ifBlank { "联系人" },
                content =
                    listOf(
                        item.optString("name"),
                        item.optJSONArray("aliases").toStringList().joinToString(" "),
                        item.optString("note"),
                    ).filter { it.isNotBlank() }.joinToString(" | "),
                profileId = item.optString("source_profile_id"),
                updatedAt = item.optLong("updated_at"),
            )
        }
        entries += structured.optJSONArray("locations").toEntries("location") { item, index ->
            memoryEntry(
                id = "location:$index",
                type = "location",
                title = item.optString("name").ifBlank { "地点" },
                content =
                    listOf(
                        item.optString("name"),
                        item.optString("category"),
                        item.optString("note"),
                    ).filter { it.isNotBlank() }.joinToString(" | "),
                profileId = item.optString("source_profile_id"),
                updatedAt = item.optLong("updated_at"),
            )
        }
        entries += structured.optJSONArray("app_preferences").toEntries("app_preference") { item, index ->
            memoryEntry(
                id = "app_preference:$index",
                type = "app_preference",
                title = item.optString("profile_id").ifBlank { "应用偏好" },
                content = "${item.optString("preference")} | ${item.optString("note")}",
                profileId = item.optString("profile_id"),
                updatedAt = item.optLong("updated_at"),
            )
        }
        entries += structured.optJSONArray("safety_rules").toEntries("safety_rule") { item, index ->
            memoryEntry(
                id = "safety_rule:$index",
                type = "safety_rule",
                title = item.optString("rule").ifBlank { "安全规则" },
                content = "${item.optString("rule")} | ${item.optString("note")}",
                updatedAt = item.optLong("updated_at"),
            )
        }
        entries += structured.optJSONArray("correction_templates").toEntries("correction_template") { item, index ->
            memoryEntry(
                id = "correction_template:$index",
                type = "correction_template",
                title = item.optString("template_type").ifBlank { "纠错经验" },
                content =
                    listOf(
                        item.optString("template_type"),
                        item.optString("argument"),
                        item.optString("instruction"),
                        item.optString("note"),
                    ).filter { it.isNotBlank() }.joinToString(" | "),
                profileId = item.optString("source_profile_id"),
                updatedAt = item.optLong("updated_at"),
            )
        }
        return entries
            .filter { it.content.isNotBlank() }
            .sortedByDescending { it.updatedAt }
    }

    private fun memoryEntry(
        id: String,
        type: String,
        title: String,
        content: String,
        profileId: String = "",
        updatedAt: Long = 0L,
    ): MemoryRecallEntry =
        MemoryRecallEntry(
            id = id,
            type = type,
            title = title.take(80),
            content = content.take(280),
            profileId = profileId,
            updatedAt = updatedAt,
            tokens = tokenize("$title $content"),
        )

    private fun JSONArray?.toEntries(
        @Suppress("UNUSED_PARAMETER") prefix: String,
        builder: (JSONObject, Int) -> MemoryRecallEntry,
    ): List<MemoryRecallEntry> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(builder(item, index))
            }
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun scoreEntry(
        entry: MemoryRecallEntry,
        query: String,
        queryTokens: Set<String>,
        profileId: String,
    ): Int {
        val loweredQuery = query.lowercase()
        val title = entry.title.lowercase()
        val content = entry.content.lowercase()
        var score = 0
        if (title.contains(loweredQuery)) score += 8
        if (content.contains(loweredQuery)) score += 10
        score += entry.tokens.count { it in queryTokens } * 3
        // 语义相似度加成：用 token/bigram 重叠系数，让近义/共享子串（如"蓝牙耳机"↔"无线耳机"）也能召回。
        score += (MemoryTextMatching.similarity(loweredQuery, "$title $content") * 6).toInt()
        if (profileId.isNotBlank() && entry.profileId == profileId) score += 3
        if (entry.updatedAt > 0L) {
            val ageMs = System.currentTimeMillis() - entry.updatedAt
            if (ageMs <= 3L * 24 * 60 * 60 * 1000) score += 2
            if (ageMs <= 12L * 60 * 60 * 1000) score += 1
        }
        return score
    }

    private fun buildPreview(
        entry: MemoryRecallEntry,
    ): String =
        buildString {
            append(entry.type)
            append(": ")
            append(entry.title.ifBlank { entry.content.take(24) })
            entry.content.takeIf { it.isNotBlank() && !it.startsWith(entry.title) }?.let {
                append(" | ").append(it.take(96))
            }
        }

    private fun tokenize(
        value: String,
    ): List<String> =
        Regex("[\\p{L}\\p{N}]+")
            .findAll(value.lowercase())
            .map { it.value.trim() }
            .filter { it.length >= 2 }
            .toList()

    private fun indexFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, MEMORY_DIR), INDEX_FILE)
    }
}
