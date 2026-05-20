package com.lmx.xiaoxuanagent.memory

import org.json.JSONArray
import org.json.JSONObject

object MemoryRetriever {
    data class RouteProfileStats(
        val runCount: Int = 0,
        val successCount: Int = 0,
    )

    fun buildRouteHints(
        task: String,
        candidateProfileIds: Set<String>,
        profileStats: JSONObject,
        profileSummaries: JSONObject,
        appPreferences: JSONArray,
        resultArtifacts: JSONArray,
    ): List<RouteMemoryHint> {
        val preferenceNotesByProfile = mutableMapOf<String, MutableList<String>>()
        for (index in 0 until appPreferences.length()) {
            val item = appPreferences.optJSONObject(index) ?: continue
            val profileId = item.optString("profile_id").takeIf { it in candidateProfileIds } ?: continue
            val note =
                buildString {
                    append(item.optString("preference").takeIf { it.isNotBlank() } ?: "")
                    item.optString("note").takeIf { it.isNotBlank() }?.let {
                        if (isNotBlank()) append(" | ")
                        append(it)
                    }
                }.trim()
            if (note.isBlank()) continue
            preferenceNotesByProfile.getOrPut(profileId) { mutableListOf() }.add(note)
        }
        val statsByProfile =
            candidateProfileIds.associateWith { profileId ->
                profileStats.optJSONObject(profileId)?.let { stats ->
                    RouteProfileStats(
                        runCount = stats.optInt("run_count", 0),
                        successCount = stats.optInt("success_count", 0),
                    )
                } ?: RouteProfileStats()
            }
        val summariesByProfile =
            candidateProfileIds.associateWith { profileId ->
                profileSummaries.optJSONObject(profileId)?.optString("summary").orEmpty()
            }
        val resultArtifactsByProfile =
            parseResultArtifacts(resultArtifacts)
                .filter { it.sourceProfileId in candidateProfileIds }
                .groupBy { it.sourceProfileId }
        return buildRouteHintsFromData(
            task = task,
            candidateProfileIds = candidateProfileIds,
            profileStats = statsByProfile,
            profileSummaries = summariesByProfile,
            preferenceNotes = preferenceNotesByProfile,
            resultArtifacts = resultArtifactsByProfile,
        )
    }

    fun buildRouteHintsFromData(
        task: String,
        candidateProfileIds: Set<String>,
        profileStats: Map<String, RouteProfileStats>,
        profileSummaries: Map<String, String>,
        preferenceNotes: Map<String, List<String>>,
        resultArtifacts: Map<String, List<ResultArtifactMemory>> = emptyMap(),
    ): List<RouteMemoryHint> {
        if (candidateProfileIds.isEmpty()) return emptyList()
        val taskKeywords = extractKeywords(task)
        val taskIntentHints = detectRouteIntentHints(task)
        return candidateProfileIds.mapNotNull { profileId ->
            val stats = profileStats[profileId] ?: RouteProfileStats()
            val summary = profileSummaries[profileId].orEmpty()
            val preferenceNotesForProfile = preferenceNotes[profileId].orEmpty()
            val matchedResultArtifact = bestMatchingResultArtifact(resultArtifacts[profileId].orEmpty(), taskKeywords, taskIntentHints)
            val runCount = stats.runCount
            val successCount = stats.successCount
            val successRate =
                if (runCount > 0) {
                    successCount.toDouble() / runCount.toDouble()
                } else {
                    0.0
                }
            val overlapScore =
                sequenceOf(summary)
                    .plus(preferenceNotesForProfile.asSequence())
                    .filter { it.isNotBlank() }
                    .sumOf { text ->
                        extractKeywords(text).intersect(taskKeywords).size
                    }
            val resultArtifactScore = matchedResultArtifact?.second ?: 0
            val score =
                (if (successCount > 0) 20 else 0) +
                    (successRate * 40.0).toInt() +
                    overlapScore * 18 +
                    resultArtifactScore +
                    minOf(runCount, 6) * 3
            if (score <= 0) {
                null
            } else {
                RouteMemoryHint(
                    profileId = profileId,
                    summary =
                        buildString {
                            if (successCount > 0 && runCount > 0) {
                                append("历史成功 ").append(successCount).append("/").append(runCount)
                            }
                            if (summary.isNotBlank()) {
                                if (isNotBlank()) append(" | ")
                                append(summary.take(80))
                            }
                            preferenceNotesForProfile.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                                if (isNotBlank()) append(" | ")
                                append(it.take(80))
                            }
                            matchedResultArtifact?.first?.let { artifact ->
                                if (isNotBlank()) append(" | ")
                                append("历史结果: ").append(artifact.title.take(48))
                                artifact.summary.takeIf { it.isNotBlank() }?.let { summary ->
                                    append(" -> ").append(summary.take(48))
                                }
                            }
                        }.ifBlank { "命中历史应用偏好" },
                    score = score,
                )
            }
        }
            .sortedByDescending { it.score }
            .take(4)
    }

    fun recallStructured(
        task: String,
        profileId: String,
        json: JSONObject,
    ): StructuredMemorySnapshot {
        val structured = json.optJSONObject("structured") ?: JSONObject()
        return selectRelevant(
            task = task,
            profileId = profileId,
            snapshot =
                StructuredMemorySnapshot(
                    resultArtifacts = parseResultArtifacts(structured.optJSONArray("result_artifacts")),
                    contacts = parseContacts(structured.optJSONArray("contacts")),
                    locations = parseLocations(structured.optJSONArray("locations")),
                    appPreferences = parseAppPreferences(structured.optJSONArray("app_preferences")),
                    safetyRules = parseSafetyRules(structured.optJSONArray("safety_rules")),
                    correctionTemplates = parseCorrectionTemplates(structured.optJSONArray("correction_templates")),
                ),
        )
    }

    fun selectRelevant(
        task: String,
        profileId: String,
        snapshot: StructuredMemorySnapshot,
    ): StructuredMemorySnapshot {
        val keywords = extractKeywords(task)
        val contacts =
            snapshot.contacts
                .filter { memory ->
                    matchesKeywords(memory.name, memory.aliases, keywords) || looksLikeMessagingTask(task)
                }
                .sortedByDescending { it.updatedAt }
                .take(3)
        val locations =
            snapshot.locations
                .filter { memory ->
                    matchesKeywords(memory.name, emptyList(), keywords) || looksLikeNavigationTask(task)
                }
                .sortedByDescending { it.updatedAt }
                .take(3)
        val appPreferences =
            snapshot.appPreferences
                .filter { memory ->
                    memory.profileId == profileId || keywords.any { keyword -> memory.note.contains(keyword, ignoreCase = true) }
                }
                .sortedByDescending { it.updatedAt }
                .take(3)
        val safetyRules =
            snapshot.safetyRules
                .filter { memory ->
                    keywords.isEmpty() || matchesKeywords(memory.rule, emptyList(), keywords) || memory.level == "block"
                }
                .sortedByDescending { it.updatedAt }
                .take(4)
        val resultArtifacts =
            snapshot.resultArtifacts
                .filter { memory ->
                    keywords.isEmpty() ||
                        matchesKeywords(memory.title, emptyList(), keywords) ||
                        matchesKeywords(memory.summary, emptyList(), keywords) ||
                        task.contains(memory.intentType, ignoreCase = true)
                }
                .sortedByDescending { it.updatedAt }
                .take(3)
        val correctionTemplates =
            snapshot.correctionTemplates
                .map { memory ->
                    memory to scoreCorrectionTemplate(task, profileId, keywords, memory)
                }
                .filter { (_, score) -> score > 0 }
                .sortedWith(
                    compareByDescending<Pair<CorrectionTemplateMemory, Int>> { it.second }
                        .thenByDescending { it.first.updatedAt },
                )
                .map { it.first }
                .take(4)

        return StructuredMemorySnapshot(
            resultArtifacts = resultArtifacts,
            contacts = contacts,
            locations = locations,
            appPreferences = appPreferences,
            safetyRules = safetyRules,
            correctionTemplates = correctionTemplates,
        )
    }

    fun dashboardLines(json: JSONObject): List<String> {
        val structured = json.optJSONObject("structured") ?: JSONObject()
        val lines = mutableListOf<String>()
        parseResultArtifacts(structured.optJSONArray("result_artifacts")).take(2).forEach { memory ->
            lines += "结果记忆: ${memory.title}${memory.summary.takeIf { it.isNotBlank() }?.let { " | $it" }.orEmpty()}"
        }
        parseContacts(structured.optJSONArray("contacts")).take(2).forEach { memory ->
            lines += "联系人: ${memory.name}${memory.note.takeIf { it.isNotBlank() }?.let { " | $it" }.orEmpty()}"
        }
        parseLocations(structured.optJSONArray("locations")).take(2).forEach { memory ->
            lines += "地点: ${memory.name}${memory.category.takeIf { it.isNotBlank() }?.let { " | $it" }.orEmpty()}"
        }
        parseSafetyRules(structured.optJSONArray("safety_rules")).take(2).forEach { memory ->
            lines += "安全规则: ${memory.rule}"
        }
        parseCorrectionTemplates(structured.optJSONArray("correction_templates")).take(2).forEach { memory ->
            lines += "纠错经验: ${memory.templateType}${memory.argument.takeIf { it.isNotBlank() }?.let { ":$it" }.orEmpty()}"
        }
        return lines.distinct()
    }

    fun formatContactMemories(memories: List<ContactMemory>): List<String> =
        memories.map { memory ->
            buildString {
                append(memory.name)
                if (memory.aliases.isNotEmpty()) {
                    append(" (别名=").append(memory.aliases.joinToString("/")).append(")")
                }
                if (memory.note.isNotBlank()) {
                    append(" | ").append(memory.note)
                }
            }
        }

    fun formatResultArtifactMemories(memories: List<ResultArtifactMemory>): List<String> =
        memories.map { memory ->
            buildString {
                append(memory.intentType).append(" | ").append(memory.title)
                if (memory.summary.isNotBlank()) {
                    append(" | ").append(memory.summary)
                }
            }
        }

    fun formatLocationMemories(memories: List<LocationMemory>): List<String> =
        memories.map { memory ->
            buildString {
                append(memory.name)
                if (memory.category.isNotBlank()) {
                    append(" | ").append(memory.category)
                }
                if (memory.note.isNotBlank()) {
                    append(" | ").append(memory.note)
                }
            }
        }

    fun formatAppPreferenceMemories(memories: List<AppPreferenceMemory>): List<String> =
        memories.map { memory ->
            buildString {
                append(memory.profileId).append(" | ").append(memory.preference)
                if (memory.note.isNotBlank()) {
                    append(" | ").append(memory.note)
                }
            }
        }

    fun formatSafetyRuleMemories(memories: List<SafetyRuleMemory>): List<String> =
        memories.map { memory ->
            buildString {
                append(memory.rule).append(" | ").append(memory.level)
                if (memory.note.isNotBlank()) {
                    append(" | ").append(memory.note)
                }
            }
        }

    fun formatCorrectionTemplateMemories(memories: List<CorrectionTemplateMemory>): List<String> =
        memories.map { memory ->
            buildString {
                append(memory.templateType)
                if (memory.argument.isNotBlank()) {
                    append(":").append(memory.argument)
                }
                if (memory.instruction.isNotBlank()) {
                    append(" | ").append(memory.instruction)
                }
                if (memory.note.isNotBlank()) {
                    append(" | ").append(memory.note)
                }
            }
        }

    private fun parseContacts(array: JSONArray?): List<ContactMemory> =
        parseArray(array) { json ->
            ContactMemory(
                name = json.optString("name"),
                aliases = parseStringArray(json.optJSONArray("aliases")),
                note = json.optString("note"),
                sourceProfileId = json.optString("source_profile_id"),
                updatedAt = json.optLong("updated_at"),
            )
        }.filter { it.name.isNotBlank() }

    private fun parseResultArtifacts(array: JSONArray?): List<ResultArtifactMemory> =
        parseArray(array) { json ->
            ResultArtifactMemory(
                intentType = json.optString("intent_type"),
                title = json.optString("title"),
                summary = json.optString("summary"),
                sourceProfileId = json.optString("source_profile_id"),
                updatedAt = json.optLong("updated_at"),
            )
        }.filter { it.intentType.isNotBlank() && it.title.isNotBlank() }

    private fun parseLocations(array: JSONArray?): List<LocationMemory> =
        parseArray(array) { json ->
            LocationMemory(
                name = json.optString("name"),
                category = json.optString("category"),
                note = json.optString("note"),
                sourceProfileId = json.optString("source_profile_id"),
                updatedAt = json.optLong("updated_at"),
            )
        }.filter { it.name.isNotBlank() }

    private fun parseAppPreferences(array: JSONArray?): List<AppPreferenceMemory> =
        parseArray(array) { json ->
            AppPreferenceMemory(
                profileId = json.optString("profile_id"),
                preference = json.optString("preference"),
                note = json.optString("note"),
                updatedAt = json.optLong("updated_at"),
            )
        }.filter { it.profileId.isNotBlank() && it.preference.isNotBlank() }

    private fun parseSafetyRules(array: JSONArray?): List<SafetyRuleMemory> =
        parseArray(array) { json ->
            SafetyRuleMemory(
                rule = json.optString("rule"),
                level = json.optString("level").ifBlank { "confirm" },
                note = json.optString("note"),
                updatedAt = json.optLong("updated_at"),
            )
        }.filter { it.rule.isNotBlank() }

    private fun parseCorrectionTemplates(array: JSONArray?): List<CorrectionTemplateMemory> =
        parseArray(array) { json ->
            CorrectionTemplateMemory(
                templateType = json.optString("template_type"),
                argument = json.optString("argument"),
                instruction = json.optString("instruction"),
                sourceProfileId = json.optString("source_profile_id"),
                note = json.optString("note"),
                updatedAt = json.optLong("updated_at"),
            )
        }.filter { it.templateType.isNotBlank() }

    private fun <T> parseArray(
        array: JSONArray?,
        parser: (JSONObject) -> T,
    ): List<T> {
        if (array == null) return emptyList()
        val result = mutableListOf<T>()
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            result += parser(json)
        }
        return result
    }

    private fun parseStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val result = mutableListOf<String>()
        for (index in 0 until array.length()) {
            array.optString(index).takeIf { it.isNotBlank() }?.let(result::add)
        }
        return result
    }

    private fun matchesKeywords(
        primary: String,
        aliases: List<String>,
        keywords: Set<String>,
    ): Boolean {
        if (keywords.isEmpty()) return false
        val candidates = listOf(primary) + aliases
        return candidates.any { candidate ->
            keywords.any { keyword ->
                candidate.contains(keyword, ignoreCase = true) || keyword.contains(candidate, ignoreCase = true)
            }
        }
    }

    private fun looksLikeMessagingTask(task: String): Boolean =
        listOf("发消息", "微信", "回复", "联系人", "聊天").any { task.contains(it) }

    private fun looksLikeNavigationTask(task: String): Boolean =
        listOf("导航", "怎么走", "路线", "多久", "多长时间", "去").any { task.contains(it) }

    private fun looksLikeShoppingTask(task: String): Boolean =
        listOf("商品", "购买", "下单", "价格", "比价", "评价", "参数", "搜", "搜索").any { task.contains(it) }

    private fun looksLikeContentTask(task: String): Boolean =
        listOf("评论", "高赞", "热评", "最新一期", "视频", "文章", "笔记", "弹幕", "点赞最高").any { task.contains(it) }

    private fun scoreCorrectionTemplate(
        task: String,
        profileId: String,
        keywords: Set<String>,
        memory: CorrectionTemplateMemory,
    ): Int {
        val noteOverlap = extractKeywords(memory.note).intersect(keywords).size
        val argumentMatch =
            memory.argument.takeIf { it.isNotBlank() }?.let { argument ->
                if (task.contains(argument, ignoreCase = true) || keywords.any { it.contains(argument, ignoreCase = true) }) {
                    1
                } else {
                    0
                }
            } ?: 0
        val genericTemplateScore =
            when (memory.templateType) {
                "backtrack", "overscroll_up", "refocus_entry" -> 1
                else -> 0
            }
        return genericTemplateScore +
            noteOverlap * 4 +
            argumentMatch * 5 +
            if (memory.sourceProfileId.isNotBlank() && memory.sourceProfileId == profileId) 3 else 0
    }

    private fun extractKeywords(task: String): Set<String> {
        val compact = task.trim()
        if (compact.isBlank()) return emptySet()
        val result = linkedSetOf<String>()
        Regex("""[A-Za-z0-9]{2,}""").findAll(compact).forEach { result += it.value.lowercase() }
        val cjk = compact.replace(Regex("""[^\p{IsHan}]"""), "")
        if (cjk.length in 2..6) {
            result += cjk
        } else if (cjk.length > 6) {
            for (size in 4 downTo 2) {
                for (index in 0..cjk.length - size) {
                    result += cjk.substring(index, index + size)
                    if (result.size >= 8) break
                }
                if (result.size >= 8) break
            }
        }
        return result
    }

    private fun detectRouteIntentHints(task: String): Set<String> =
        buildSet {
            if (looksLikeMessagingTask(task)) add("messaging")
            if (looksLikeNavigationTask(task)) add("navigation")
            if (looksLikeShoppingTask(task)) add("shopping")
            if (looksLikeContentTask(task)) add("content")
        }

    private fun bestMatchingResultArtifact(
        artifacts: List<ResultArtifactMemory>,
        taskKeywords: Set<String>,
        taskIntentHints: Set<String>,
    ): Pair<ResultArtifactMemory, Int>? {
        var best: Pair<ResultArtifactMemory, Int>? = null
        artifacts.forEach { artifact ->
            val artifactText = "${artifact.title} ${artifact.summary}"
            val keywordOverlap =
                extractKeywords(artifactText)
                    .intersect(taskKeywords)
                    .size
            val containsScore =
                taskKeywords.fold(0) { acc, keyword ->
                    acc +
                        when {
                            artifact.title.contains(keyword, ignoreCase = true) -> 16
                            artifact.summary.contains(keyword, ignoreCase = true) -> 8
                            else -> 0
                        }
                }
            val intentScore = if (artifact.intentType in taskIntentHints) 18 else 0
            val recencyScore = if (artifact.updatedAt > 0L) 6 else 0
            val score = keywordOverlap * 18 + containsScore + intentScore + recencyScore
            if (score > 0 && (best == null || score > best!!.second)) {
                best = artifact to score
            }
        }
        return best
    }
}
