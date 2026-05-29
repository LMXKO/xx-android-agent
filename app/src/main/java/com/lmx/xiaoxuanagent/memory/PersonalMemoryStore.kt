package com.lmx.xiaoxuanagent.memory

import com.lmx.xiaoxuanagent.agent.PlanningMemoryContext
import com.lmx.xiaoxuanagent.agent.TaskResultPayload
import com.lmx.xiaoxuanagent.assistantos.AssistantExternalSignal
import com.lmx.xiaoxuanagent.assistantos.AssistantExternalSignalType
import com.lmx.xiaoxuanagent.runtime.AgentUiStatus
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

object PersonalMemoryStore {
    private const val MEMORY_DIR = "memory"
    private const val MEMORY_FILE = "personal_memory.json"
    private const val IMPORTED_INSIGHT_FILE = "imported_insight_snapshot.json"
    private const val MEMORY_WORKSPACE_DIR = "memory_workspace"
    private const val CORE_MEMORY_FILE = "memory.md"
    private const val DAILY_MEMORY_DIR = "daily"
    private const val MAX_EPISODES = 30
    private const val MAX_FACTS = 20
    private const val MAX_STRUCTURED_ITEMS = 20
    private const val MAX_AUDIT_LOGS = 48
    private val lock = Any()
    private val dailyFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    @Volatile
    private var governanceHydrated = false
    private val governanceSnapshotFlow = MutableStateFlow(PersonalMemoryGovernanceSnapshot())
    @Volatile
    private var importedInsightHydrated = false
    private var importedInsightSnapshot = PersonalMemoryInsightSnapshot()

    fun recall(
        task: String,
        profileId: String,
    ): PlanningMemoryContext {
        val taskKeywords = extractKeywords(task)
        val json = readMemoryJson() ?: return PlanningMemoryContext()
        var rankedHits = MemoryRecallIndexStore.search(task, profileId, limit = 6)
        if (rankedHits.isEmpty()) {
            MemoryRecallIndexStore.rebuild(json)
            rankedHits = MemoryRecallIndexStore.search(task, profileId, limit = 6)
        }
        val episodes = json.optJSONArray("episodes") ?: JSONArray()
        val profileStats = json.optJSONObject("profile_stats") ?: JSONObject()
        val profileSummaries = json.optJSONObject("profile_summaries") ?: JSONObject()
        val facts = json.optJSONArray("facts") ?: JSONArray()
        val structuredSnapshot = MemoryRetriever.recallStructured(task, profileId, json)

        val longTermMemories = mutableListOf<String>()
        for (index in 0 until episodes.length()) {
            val episode = episodes.optJSONObject(index) ?: continue
            val episodeTask = episode.optString("task")
            val overlap = extractKeywords(episodeTask).intersect(taskKeywords).size
            if (overlap <= 0) continue
            val summary =
                buildString {
                    append("历史任务: ").append(episodeTask)
                    append(" -> ").append(episode.optString("final_message"))
                }
            longTermMemories += summary
            if (longTermMemories.size >= 4) break
        }
        MemoryRetriever.formatContactMemories(structuredSnapshot.contacts).forEach { summary ->
            longTermMemories += "联系人记忆: $summary"
        }
        MemoryRetriever.formatLocationMemories(structuredSnapshot.locations).forEach { summary ->
            longTermMemories += "地点记忆: $summary"
        }
        MemoryRetriever.formatResultArtifactMemories(structuredSnapshot.resultArtifacts).forEach { summary ->
            longTermMemories += "结果记忆: $summary"
        }
        MemoryRetriever.formatCorrectionTemplateMemories(structuredSnapshot.correctionTemplates).forEach { summary ->
            longTermMemories += "纠错经验: $summary"
        }
        MemoryWorkspaceGovernance.buildRecallLines(
            snapshot = MemoryWorkspaceGovernance.scanWorkspace(),
            limit = 3,
        ).forEach { line ->
            longTermMemories += line
        }

        val userPreferences = mutableListOf<String>()
        val profile = profileStats.optJSONObject(profileId)
        if (profile != null) {
            val successCount = profile.optInt("success_count", 0)
            val runCount = profile.optInt("run_count", 0)
            if (runCount > 0) {
                userPreferences += "历史上该应用任务运行 $runCount 次，成功 $successCount 次。"
            }
        }
        profileSummaries.optJSONObject(profileId)?.optString("summary")?.takeIf { it.isNotBlank() }?.let {
            userPreferences += "偏好摘要: $it"
        }
        MemoryRetriever.formatAppPreferenceMemories(structuredSnapshot.appPreferences).forEach { summary ->
            userPreferences += "应用偏好: $summary"
        }
        MemoryRetriever.formatSafetyRuleMemories(structuredSnapshot.safetyRules).forEach { summary ->
            userPreferences += "安全规则: $summary"
        }
        for (index in 0 until facts.length()) {
            val fact = facts.optJSONObject(index) ?: continue
            val content = fact.optString("content")
            if (content.isBlank()) continue
            if (taskKeywords.isEmpty() || extractKeywords(content).intersect(taskKeywords).isNotEmpty()) {
                userPreferences += "记忆事实: $content"
            }
            if (userPreferences.size >= 6) break
        }

        val shortTermNotes =
            buildList {
                if (taskKeywords.isNotEmpty()) {
                    add("当前任务关键词: ${taskKeywords.joinToString("/")}")
                }
                rankedHits.take(3).forEach { hit ->
                    add("检索记忆: ${hit.preview}")
                }
                if (structuredSnapshot.resultArtifacts.isNotEmpty()) {
                    add("命中结果记忆: ${structuredSnapshot.resultArtifacts.joinToString(" / ") { it.title }}")
                }
                if (structuredSnapshot.contacts.isNotEmpty()) {
                    add("命中联系人记忆: ${structuredSnapshot.contacts.joinToString(" / ") { it.name }}")
                }
                if (structuredSnapshot.locations.isNotEmpty()) {
                    add("命中地点记忆: ${structuredSnapshot.locations.joinToString(" / ") { it.name }}")
                }
                if (structuredSnapshot.correctionTemplates.isNotEmpty()) {
                    add(
                        "命中纠错经验: ${
                            structuredSnapshot.correctionTemplates.joinToString(" / ") {
                                it.templateType + it.argument.takeIf { argument -> argument.isNotBlank() }?.let { argument -> ":$argument" }.orEmpty()
                            }
                        }",
                    )
                }
            }

        return PlanningMemoryContext(
            shortTermNotes = shortTermNotes,
            longTermMemories = longTermMemories,
            userPreferences = userPreferences,
            retrievalMemories = rankedHits.map { it.preview },
            resultArtifacts = MemoryRetriever.formatResultArtifactMemories(structuredSnapshot.resultArtifacts),
            correctionTemplates = MemoryRetriever.formatCorrectionTemplateMemories(structuredSnapshot.correctionTemplates),
            contacts = MemoryRetriever.formatContactMemories(structuredSnapshot.contacts),
            locations = MemoryRetriever.formatLocationMemories(structuredSnapshot.locations),
            appPreferences = MemoryRetriever.formatAppPreferenceMemories(structuredSnapshot.appPreferences),
            safetyRules = MemoryRetriever.formatSafetyRuleMemories(structuredSnapshot.safetyRules),
        )
    }

    fun recordSessionOutcome(
        task: String,
        profileId: String,
        status: String,
        finalMessage: String,
        taskResult: TaskResultPayload? = null,
        artifactFacts: List<String> = emptyList(),
    ) {
        synchronized(lock) {
            val json = ensureSchema(readMemoryJsonUnlocked() ?: JSONObject())
            val episodes = json.optJSONArray("episodes") ?: JSONArray().also { json.put("episodes", it) }
            val profileStats =
                json.optJSONObject("profile_stats") ?: JSONObject().also { json.put("profile_stats", it) }
            val profileSummaries =
                json.optJSONObject("profile_summaries") ?: JSONObject().also { json.put("profile_summaries", it) }
            val facts = json.optJSONArray("facts") ?: JSONArray().also { json.put("facts", it) }
            val structured = json.optJSONObject("structured") ?: JSONObject().also { json.put("structured", it) }

            val profile = profileStats.optJSONObject(profileId) ?: JSONObject().also { profileStats.put(profileId, it) }
            profile.put("run_count", profile.optInt("run_count", 0) + 1)
            if (AgentUiStatus.isCompleted(status)) {
                profile.put("success_count", profile.optInt("success_count", 0) + 1)
            }
            profile.put("last_status", status)
            profile.put("last_message", finalMessage.take(200))
            profile.put("updated_at", System.currentTimeMillis())
            val summary = buildProfileSummary(task, finalMessage)
            if (summary.isNotBlank()) {
                val profileSummary =
                    profileSummaries.optJSONObject(profileId) ?: JSONObject().also { profileSummaries.put(profileId, it) }
                profileSummary.put("summary", summary)
                profileSummary.put("updated_at", System.currentTimeMillis())
            }

            episodes.put(
                JSONObject().apply {
                    put("task", task)
                    put("profile_id", profileId)
                    put("status", status)
                    put("final_message", finalMessage.take(200))
                    put("timestamp", System.currentTimeMillis())
                },
            )
            while (episodes.length() > MAX_EPISODES) {
                episodes.remove(0)
            }

            val extracted = MemoryExtractor.extract(task, profileId, status, finalMessage, taskResult)
            appendFacts(facts, profileId, extracted.facts + artifactFacts)
            mergeStructuredMemories(structured, extracted)

            writeMemoryJsonUnlocked(json)
            publishGovernanceSnapshotUnlocked(json)
            MemoryRecallIndexStore.rebuild(json)
            writeWorkspaceMemoryUnlocked(
                json = json,
                dailyTitle = "Session Outcome",
                lines =
                    buildList {
                        add("task=$task")
                        add("profile=$profileId")
                        add("status=$status")
                        add("message=${finalMessage.take(160)}")
                        taskResult?.title?.takeIf { it.isNotBlank() }?.let { add("result=$it") }
                        artifactFacts.take(3).forEach { add("artifact_fact=$it") }
                    },
            )
        }
    }

    fun dashboardSummary(): String {
        val json = readMemoryJson() ?: return "暂无个人记忆。"
        val profileSummaries = json.optJSONObject("profile_summaries") ?: JSONObject()
        val facts = json.optJSONArray("facts") ?: JSONArray()
        val lines = mutableListOf<String>()
        val keys = profileSummaries.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val summary = profileSummaries.optJSONObject(key)?.optString("summary").orEmpty()
            if (summary.isNotBlank()) {
                lines += "$key: $summary"
            }
            if (lines.size >= 3) break
        }
        for (index in maxOf(0, facts.length() - 3) until facts.length()) {
            val fact = facts.optJSONObject(index)?.optString("content").orEmpty()
            if (fact.isNotBlank()) {
                lines += fact
            }
        }
        lines += MemoryRetriever.dashboardLines(json)
        return if (lines.isEmpty()) "暂无个人记忆。" else lines.distinct().joinToString("\n")
    }

    fun readGovernanceSnapshot(
        limit: Int = 24,
        auditLimit: Int = 12,
    ): PersonalMemoryGovernanceSnapshot =
        synchronized(lock) {
            val json = readMemoryJsonUnlocked() ?: return@synchronized PersonalMemoryGovernanceSnapshot()
            val snapshot = buildGovernanceSnapshot(json, limit = limit, auditLimit = auditLimit)
            publishGovernanceSnapshotUnlocked(json)
            snapshot
        }

    fun observeGovernanceSnapshot(): StateFlow<PersonalMemoryGovernanceSnapshot> =
        synchronized(lock) {
            if (!governanceHydrated) {
                val json = readMemoryJsonUnlocked() ?: JSONObject()
                publishGovernanceSnapshotUnlocked(json)
            }
            governanceSnapshotFlow.asStateFlow()
        }

    fun readUserModelSnapshot(
        limit: Int = 4,
    ): PersonalAssistantUserModelSnapshot =
        synchronized(lock) {
            val json = readMemoryJsonUnlocked() ?: return@synchronized PersonalAssistantUserModelSnapshot()
            buildUserModelSnapshot(json = json, limit = limit.coerceAtLeast(1))
        }

    fun readInsightSnapshot(
        limit: Int = 4,
    ): PersonalMemoryInsightSnapshot =
        synchronized(lock) {
            val hasStoredMemory = memoryFile()?.exists() == true
            val liveInsight =
                readMemoryJsonUnlocked()
                    ?.let { buildMemoryInsightSnapshot(json = it, limit = limit.coerceAtLeast(1)) }
                    ?: PersonalMemoryInsightSnapshot()
            mergeImportedInsightUnlocked(liveInsight, preferImported = !hasStoredMemory)
        }

    fun importInsightSnapshot(
        snapshot: PersonalMemoryInsightSnapshot,
    ): PersonalMemoryInsightSnapshot =
        synchronized(lock) {
            val hasStoredMemory = memoryFile()?.exists() == true
            importedInsightHydrated = true
            importedInsightSnapshot = snapshot
            persistImportedInsightUnlocked()
            mergeImportedInsightUnlocked(
                readMemoryJsonUnlocked()
                    ?.let { buildMemoryInsightSnapshot(json = it, limit = 4) }
                    ?: PersonalMemoryInsightSnapshot(),
                preferImported = !hasStoredMemory,
            )
        }

    private fun buildGovernanceSnapshot(
        json: JSONObject,
        limit: Int,
        auditLimit: Int,
    ): PersonalMemoryGovernanceSnapshot {
        val entries = buildGovernanceEntries(json).sortedByDescending { it.updatedAtMs }
        val factsCount = entries.count { it.type == PersonalMemoryEntryType.FACT }
        val auditTrail = buildAuditTrail(json).sortedByDescending { it.timestampMs }.take(auditLimit)
        val workspaceSnapshot = MemoryWorkspaceGovernance.scanWorkspace()
        return PersonalMemoryGovernanceSnapshot(
            summary =
                buildString {
                    append("entries=").append(entries.size)
                    append(" facts=").append(factsCount)
                    append(" structured=").append(entries.size - factsCount)
                },
            workspaceSummary = workspaceSnapshot.summary,
            totalEntries = entries.size,
            factCount = factsCount,
            structuredCount = entries.size - factsCount,
            entries = entries.take(limit),
            auditTrail = auditTrail,
        )
    }

    private fun buildUserModelSnapshot(
        json: JSONObject,
        limit: Int,
    ): PersonalAssistantUserModelSnapshot {
        val governanceEntries = buildGovernanceEntries(json).sortedByDescending { it.updatedAtMs }
        val insight = buildMemoryInsightSnapshot(json, limit)
        val workspaceSnapshot = MemoryWorkspaceGovernance.scanWorkspace()
        val structuredEntries = governanceEntries.filter { it.type != PersonalMemoryEntryType.FACT }
        val contactEntries = governanceEntries.filter { it.type == PersonalMemoryEntryType.CONTACT }.take(limit)
        val locationEntries = governanceEntries.filter { it.type == PersonalMemoryEntryType.LOCATION }.take(limit)
        val appEntries = governanceEntries.filter { it.type == PersonalMemoryEntryType.APP_PREFERENCE }.take(limit)
        val safetyEntries = governanceEntries.filter { it.type == PersonalMemoryEntryType.SAFETY_RULE }.take(limit)
        val correctionEntries = governanceEntries.filter { it.type == PersonalMemoryEntryType.CORRECTION_TEMPLATE }.take(limit)
        val profileStats = json.optJSONObject("profile_stats") ?: JSONObject()
        val profileSummaries = json.optJSONObject("profile_summaries") ?: JSONObject()
        val topProfileIds =
            buildList {
                val keys = profileStats.keys()
                while (keys.hasNext()) {
                    val profileId = keys.next()
                    val runCount = profileStats.optJSONObject(profileId)?.optInt("run_count", 0) ?: 0
                    if (runCount > 0) add(profileId to runCount)
                }
            }.sortedByDescending { it.second }.take(limit).map { it.first }
        val preferredThemes =
            buildList {
                governanceEntries.take(limit * 3).forEach { entry ->
                    when {
                        entry.type == PersonalMemoryEntryType.CONTACT -> add("关系跟进")
                        entry.type == PersonalMemoryEntryType.LOCATION -> add("场景触发")
                        entry.type == PersonalMemoryEntryType.APP_PREFERENCE -> add("应用惯例")
                        entry.type == PersonalMemoryEntryType.SAFETY_RULE -> add("安全确认")
                        entry.type == PersonalMemoryEntryType.CORRECTION_TEMPLATE -> add("纠错复用")
                    }
                }
            }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key }
        val identitySummary =
            buildString {
                append("profiles=").append(topProfileIds.joinToString(",").ifBlank { "-" })
                topProfileIds.firstOrNull()?.let { profileId ->
                    profileSummaries.optJSONObject(profileId)?.optString("summary")?.takeIf { it.isNotBlank() }?.let { summary ->
                        append(" | ").append(summary.take(96))
                    }
                }
            }
        val preferenceSummary =
            buildString {
                append("structured=").append(structuredEntries.size)
                append(" facts=").append(governanceEntries.count { it.type == PersonalMemoryEntryType.FACT })
                appEntries.firstOrNull()?.let { first ->
                    append(" | app=").append(first.title.take(48))
                }
                correctionEntries.firstOrNull()?.let { first ->
                    append(" | correction=").append(first.title.take(48))
                }
            }
        val relationshipSummary =
            contactEntries.joinToString(" | ") { it.title.take(32) }.ifBlank { "contacts=-" }
        val locationSummary =
            locationEntries.joinToString(" | ") { it.title.take(32) }.ifBlank { "locations=-" }
        val appSummary =
            buildString {
                append("apps=").append(appEntries.size)
                if (topProfileIds.isNotEmpty()) {
                    append(" top=").append(topProfileIds.joinToString(","))
                }
                appEntries.firstOrNull()?.summary?.takeIf { it.isNotBlank() }?.let {
                    append(" | ").append(it.take(72))
                }
            }
        val routineSummary =
            buildString {
                append("workspace=").append(workspaceSnapshot.summary.ifBlank { "-" })
                append(" | audits=").append(buildAuditTrail(json).take(limit).size)
                append(" | themes=").append(preferredThemes.joinToString(",").ifBlank { "-" })
                insight.consolidationSummary.takeIf { it.isNotBlank() }?.let {
                    append(" | ").append(it.take(72))
                }
            }
        val safetySummary =
            buildString {
                append("rules=").append(safetyEntries.size)
                correctionEntries.firstOrNull()?.let {
                    append(" | recovery=").append(it.title.take(40))
                }
                safetyEntries.firstOrNull()?.summary?.takeIf { it.isNotBlank() }?.let {
                    append(" | ").append(it.take(72))
                }
            }
        val summary =
            buildString {
                append("profiles=").append(topProfileIds.size)
                append(" contacts=").append(contactEntries.size)
                append(" locations=").append(locationEntries.size)
                append(" apps=").append(appEntries.size)
                append(" themes=").append(preferredThemes.joinToString(",").ifBlank { "-" })
                insight.awaySummary.takeIf { it.isNotBlank() }?.let {
                    append(" | away=").append(it.take(48))
                }
            }
        return PersonalAssistantUserModelSnapshot(
            summary = summary,
            identitySummary = identitySummary,
            preferenceSummary = preferenceSummary,
            relationshipSummary = relationshipSummary,
            locationSummary = locationSummary,
            appSummary = appSummary,
            routineSummary = routineSummary,
            safetySummary = safetySummary,
            workspaceSummary = workspaceSnapshot.summary,
            topProfileIds = topProfileIds,
            topContactNames = contactEntries.map { it.title },
            topLocationNames = locationEntries.map { it.title },
            preferredThemes = preferredThemes,
            recommendedCommands =
                listOfNotNull(
                    "/memory-governance",
                    "/memory-workspace",
                    topProfileIds.firstOrNull()?.let { "/search-sessions $it" },
                    if (safetyEntries.isNotEmpty()) "/approval-center" else null,
                ),
            lines =
                buildList {
                    add("identity | $identitySummary")
                    add("preferences | $preferenceSummary")
                    add("relationships | $relationshipSummary")
                    add("locations | $locationSummary")
                    add("apps | $appSummary")
                    add("routine | $routineSummary")
                    add("safety | $safetySummary")
                    insight.thinkbackLines.take(2).forEach { add("thinkback | $it") }
                    insight.dreamLines.take(2).forEach { add("dream | $it") }
                },
        )
    }

    private fun buildMemoryInsightSnapshot(
        json: JSONObject,
        limit: Int,
    ): PersonalMemoryInsightSnapshot {
        val entries = buildGovernanceEntries(json).sortedByDescending { it.updatedAtMs }
        val auditTrail = buildAuditTrail(json).sortedByDescending { it.timestampMs }
        val workspaceSnapshot = MemoryWorkspaceGovernance.scanWorkspace()
        val profileStats = json.optJSONObject("profile_stats") ?: JSONObject()
        val topProfiles =
            buildList {
                val keys = profileStats.keys()
                while (keys.hasNext()) {
                    val profileId = keys.next()
                    val runCount = profileStats.optJSONObject(profileId)?.optInt("run_count", 0) ?: 0
                    if (runCount > 0) add(profileId to runCount)
                }
            }.sortedByDescending { it.second }.take(limit)
        val recentFacts = entries.filter { it.type == PersonalMemoryEntryType.FACT }.take(limit)
        val recentContacts = entries.filter { it.type == PersonalMemoryEntryType.CONTACT }.take(limit)
        val recentLocations = entries.filter { it.type == PersonalMemoryEntryType.LOCATION }.take(limit)
        val appEntries = entries.filter { it.type == PersonalMemoryEntryType.APP_PREFERENCE }.take(limit)
        val safetyEntries = entries.filter { it.type == PersonalMemoryEntryType.SAFETY_RULE }.take(limit)
        val awayLines =
            buildList {
                add("workspace | ${workspaceSnapshot.summary.ifBlank { "-" }}")
                topProfiles.firstOrNull()?.let { add("top_profile | ${it.first}:${it.second}") }
                recentFacts.firstOrNull()?.let { add("recent_fact | ${it.title.take(64)}") }
                auditTrail.firstOrNull()?.let { add("recent_audit | ${it.action}:${it.summary.take(56)}") }
            }
        val thinkbackLines =
            buildList {
                auditTrail.take(limit).forEach { audit ->
                    add("${audit.action} | ${audit.type.wireName} | ${audit.summary.take(72)}")
                }
                recentFacts.take(2).forEach { fact ->
                    add("fact | ${fact.title.take(72)}")
                }
            }.distinct().take(limit + 2)
        val consolidationLines =
            buildList {
                add("entries=${entries.size} structured=${entries.count { it.type != PersonalMemoryEntryType.FACT }}")
                recentContacts.firstOrNull()?.let { add("contact_anchor=${it.title}") }
                recentLocations.firstOrNull()?.let { add("location_anchor=${it.title}") }
                appEntries.firstOrNull()?.let { add("app_anchor=${it.title}") }
                safetyEntries.firstOrNull()?.let { add("safety_anchor=${it.title}") }
            }
        val dreamLines =
            buildList {
                recentContacts.firstOrNull()?.let { add("建议围绕 ${it.title} 做关系跟进。") }
                recentLocations.firstOrNull()?.let { add("进入 ${it.title} 场景时可触发位置联动。") }
                appEntries.firstOrNull()?.let { add("在 ${it.profileId.ifBlank { "常用应用" }} 复用偏好: ${it.title.take(40)}") }
                safetyEntries.firstOrNull()?.let { add("高风险节点继续沿用安全规则: ${it.title.take(40)}") }
            }.distinct().take(limit)
        val awaySummary = awayLines.joinToString(" | ").take(120)
        val thinkbackSummary = thinkbackLines.firstOrNull().orEmpty()
        val consolidationSummary = consolidationLines.joinToString(" | ").take(120)
        val dreamSummary = dreamLines.firstOrNull().orEmpty()
        val suggestionSummary =
            listOfNotNull(
                dreamSummary.takeIf { it.isNotBlank() },
                thinkbackSummary.takeIf { it.isNotBlank() },
            ).joinToString(" | ").take(120)
        return PersonalMemoryInsightSnapshot(
            summary =
                buildString {
                    append("profiles=").append(topProfiles.size)
                    append(" facts=").append(recentFacts.size)
                    append(" audits=").append(auditTrail.take(limit).size)
                },
            awaySummary = awaySummary,
            thinkbackSummary = thinkbackSummary,
            consolidationSummary = consolidationSummary,
            dreamSummary = dreamSummary,
            suggestionSummary = suggestionSummary,
            awayLines = awayLines,
            thinkbackLines = thinkbackLines,
            consolidationLines = consolidationLines,
            dreamLines = dreamLines,
            recommendedCommands =
                listOfNotNull(
                    "/memory-governance",
                    "/memory-workspace",
                    topProfiles.firstOrNull()?.let { "/search-sessions ${it.first}" },
                ),
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private fun buildExternalSignalMemory(
        signal: AssistantExternalSignal,
    ): MemoryExtractResult? {
        val now = System.currentTimeMillis()
        val payload = signal.payload
        val sender =
            payload["sender"]
                .orEmpty()
                .ifBlank { payload["caller_name"].orEmpty() }
                .ifBlank { payload["contact_name"].orEmpty() }
                .ifBlank { signal.title.takeIf { it.isNotBlank() } ?: signal.summary.take(40) }
        return when (signal.type) {
            AssistantExternalSignalType.MESSAGE,
            AssistantExternalSignalType.NOTIFICATION,
            -> MemoryExtractResult(
                contacts =
                    listOfNotNull(
                        sender.takeIf { it.isNotBlank() }?.let {
                            ContactMemory(
                                name = it.take(60),
                                aliases = listOfNotNull(payload["sender"], payload["caller_number"]).distinct(),
                                note = signal.summary.take(120),
                                sourceProfileId = payload["profile_id"].orEmpty(),
                                updatedAt = now,
                            )
                        },
                    ),
                facts =
                    listOf(
                        "近期沟通线索: ${signal.summary.take(120)}",
                    ),
            )

            AssistantExternalSignalType.CONTACT ->
                MemoryExtractResult(
                    contacts =
                        listOf(
                            ContactMemory(
                                name = payload["contact_name"].orEmpty().ifBlank { signal.title }.take(60),
                                aliases = listOfNotNull(payload["contact_phone"]).filter { it.isNotBlank() },
                                note = signal.summary.take(120),
                                updatedAt = now,
                            ),
                        ),
                    facts = listOf("联系人更新: ${signal.title.take(80)}"),
                )

            AssistantExternalSignalType.CALL_LOG ->
                MemoryExtractResult(
                    contacts =
                        listOfNotNull(
                            sender.takeIf { it.isNotBlank() }?.let {
                                ContactMemory(
                                    name = it.take(60),
                                    aliases = listOfNotNull(payload["caller_number"]).filter { alias -> alias.isNotBlank() },
                                    note = "${payload["call_type"].orEmpty()} | ${signal.summary}".take(120),
                                    updatedAt = now,
                                )
                            },
                        ),
                    facts = listOf("近期通话: ${signal.summary.take(120)}"),
                )

            AssistantExternalSignalType.LOCATION ->
                MemoryExtractResult(
                    locations =
                        listOf(
                            LocationMemory(
                                name = payload["zone_key"].orEmpty().ifBlank { signal.title }.take(60),
                                category = payload["transition"].orEmpty().ifBlank { "location_signal" },
                                note = signal.summary.take(120),
                                updatedAt = now,
                            ),
                        ),
                    facts = listOf("位置线索: ${signal.summary.take(120)}"),
                )

            AssistantExternalSignalType.CALENDAR ->
                MemoryExtractResult(
                    locations =
                        listOfNotNull(
                            payload["event_location"]?.takeIf { it.isNotBlank() }?.let {
                                LocationMemory(
                                    name = it.take(60),
                                    category = "calendar_context",
                                    note = signal.title.take(120),
                                    updatedAt = now,
                                )
                            },
                        ),
                    facts = listOf("近期日程: ${signal.title.take(80)}"),
                )

            AssistantExternalSignalType.APP_FOREGROUND ->
                MemoryExtractResult(
                    appPreferences =
                        listOfNotNull(
                            payload["profile_id"]?.takeIf { it.isNotBlank() }?.let { profileId ->
                                AppPreferenceMemory(
                                    profileId = profileId.take(60),
                                    preference = "近期常在前台使用 ${signal.summary.ifBlank { profileId }}".take(80),
                                    note = signal.summary.take(120),
                                    updatedAt = now,
                                )
                            },
                        ),
                    facts = listOf("前台应用偏好: ${signal.summary.take(120)}"),
                )

            else ->
                signal.summary.takeIf { it.isNotBlank() }?.let {
                    MemoryExtractResult(facts = listOf("系统信号: ${it.take(120)}"))
                }
        }
    }

    private fun mergeImportedInsightUnlocked(
        liveInsight: PersonalMemoryInsightSnapshot,
        preferImported: Boolean = false,
    ): PersonalMemoryInsightSnapshot {
        hydrateImportedInsightUnlocked()
        val imported = importedInsightSnapshot
        if (!imported.hasContent()) return liveInsight
        if (preferImported) {
            return imported.copy(
                recommendedCommands = (imported.recommendedCommands + liveInsight.recommendedCommands).distinct(),
                updatedAtMs = maxOf(liveInsight.updatedAtMs, imported.updatedAtMs),
            )
        }
        return PersonalMemoryInsightSnapshot(
            summary = liveInsight.summary.ifBlank { imported.summary },
            awaySummary = liveInsight.awaySummary.ifBlank { imported.awaySummary },
            thinkbackSummary = liveInsight.thinkbackSummary.ifBlank { imported.thinkbackSummary },
            consolidationSummary = liveInsight.consolidationSummary.ifBlank { imported.consolidationSummary },
            dreamSummary = liveInsight.dreamSummary.ifBlank { imported.dreamSummary },
            suggestionSummary = liveInsight.suggestionSummary.ifBlank { imported.suggestionSummary },
            awayLines = if (liveInsight.awayLines.isNotEmpty()) liveInsight.awayLines else imported.awayLines,
            thinkbackLines = if (liveInsight.thinkbackLines.isNotEmpty()) liveInsight.thinkbackLines else imported.thinkbackLines,
            consolidationLines = if (liveInsight.consolidationLines.isNotEmpty()) liveInsight.consolidationLines else imported.consolidationLines,
            dreamLines = if (liveInsight.dreamLines.isNotEmpty()) liveInsight.dreamLines else imported.dreamLines,
            recommendedCommands = (liveInsight.recommendedCommands + imported.recommendedCommands).distinct(),
            updatedAtMs = maxOf(liveInsight.updatedAtMs, imported.updatedAtMs),
        )
    }

    private fun hydrateImportedInsightUnlocked() {
        if (importedInsightHydrated) return
        importedInsightHydrated = true
        val file = importedInsightFile() ?: return
        if (!file.exists()) return
        importedInsightSnapshot =
            runCatching { JSONObject(file.readText()).toInsightSnapshot() }
                .getOrDefault(PersonalMemoryInsightSnapshot())
    }

    private fun persistImportedInsightUnlocked() {
        val file = importedInsightFile() ?: return
        file.parentFile?.mkdirs()
        if (!importedInsightSnapshot.hasContent()) {
            if (file.exists()) {
                file.delete()
            }
            return
        }
        file.writeText(importedInsightSnapshot.toJson().toString(2))
    }

    private fun clearImportedInsightUnlocked() {
        importedInsightHydrated = true
        importedInsightSnapshot = PersonalMemoryInsightSnapshot()
        val file = importedInsightFile() ?: return
        if (file.exists()) {
            file.delete()
        }
    }

    private fun publishGovernanceSnapshotUnlocked(
        json: JSONObject,
    ) {
        governanceHydrated = true
        governanceSnapshotFlow.value = buildGovernanceSnapshot(json, limit = 24, auditLimit = 12)
    }

    fun upsertGovernedEntry(
        typeWire: String,
        primary: String,
        secondary: String = "",
        note: String = "",
        profileId: String = "",
    ): PersonalMemoryGovernanceSnapshot {
        val type =
            PersonalMemoryEntryType.fromWireName(typeWire)
                ?: return readGovernanceSnapshot()
        val normalizedPrimary = primary.trim()
        if (normalizedPrimary.isBlank()) return readGovernanceSnapshot()
        synchronized(lock) {
            val json = ensureSchema(readMemoryJsonUnlocked() ?: JSONObject())
            val now = System.currentTimeMillis()
            when (type) {
                PersonalMemoryEntryType.FACT -> {
                    val facts = json.optJSONArray("facts") ?: JSONArray().also { json.put("facts", it) }
                    upsertFact(
                        facts = facts,
                        profileId = profileId,
                        content = normalizedPrimary,
                        timestampMs = now,
                    )
                    appendGovernanceAudit(
                        json = json,
                        action = "upsert",
                        type = type,
                        entryId = governanceEntryId(type, normalizedPrimary),
                        summary = normalizedPrimary.take(120),
                        timestampMs = now,
                    )
                }

                else -> {
                    val structured = json.optJSONObject("structured") ?: JSONObject().also { json.put("structured", it) }
                    mergeStructuredMemories(
                        structured = structured,
                        extracted = buildGovernanceExtractResult(type, normalizedPrimary, secondary.trim(), note.trim(), profileId.trim(), now),
                    )
                    appendGovernanceAudit(
                        json = json,
                        action = "upsert",
                        type = type,
                        entryId = governanceEntryId(type, normalizedPrimary, secondary.trim()),
                        summary = buildGovernanceAuditSummary(type, normalizedPrimary, secondary.trim(), note.trim()),
                        timestampMs = now,
                    )
                }
            }
            writeMemoryJsonUnlocked(json)
            publishGovernanceSnapshotUnlocked(json)
            MemoryRecallIndexStore.rebuild(json)
            writeWorkspaceMemoryUnlocked(
                json = json,
                dailyTitle = "Memory Governance Upsert",
                lines =
                    listOf(
                        "type=${type.wireName}",
                        "primary=${normalizedPrimary.take(120)}",
                        "secondary=${secondary.trim().take(120)}",
                        "note=${note.trim().take(160)}",
                        "profile=${profileId.trim().take(80)}",
                    ),
            )
        }
        return readGovernanceSnapshot()
    }

    fun deleteGovernedEntry(
        typeWire: String,
        entryRef: String,
    ): PersonalMemoryGovernanceSnapshot {
        val type =
            PersonalMemoryEntryType.fromWireName(typeWire)
                ?: return readGovernanceSnapshot()
        val normalizedRef = entryRef.trim()
        if (normalizedRef.isBlank()) return readGovernanceSnapshot()
        synchronized(lock) {
            val json = ensureSchema(readMemoryJsonUnlocked() ?: JSONObject())
            val removedSummary =
                when (type) {
                    PersonalMemoryEntryType.FACT -> {
                        val facts = json.optJSONArray("facts") ?: JSONArray().also { json.put("facts", it) }
                        removeFact(facts, normalizedRef)
                    }

                    else -> {
                        val structured = json.optJSONObject("structured") ?: JSONObject().also { json.put("structured", it) }
                        removeStructuredEntry(
                            structured = structured,
                            type = type,
                            entryRef = normalizedRef,
                        )
                    }
                }
            if (removedSummary != null) {
                val now = System.currentTimeMillis()
                appendGovernanceAudit(
                    json = json,
                    action = "delete",
                    type = type,
                    entryId =
                        normalizedRef.takeIf { it.startsWith("${type.wireName}:") }
                            ?: governanceEntryId(type, normalizedRef),
                    summary = removedSummary.take(160),
                    timestampMs = now,
                )
                writeMemoryJsonUnlocked(json)
                publishGovernanceSnapshotUnlocked(json)
                MemoryRecallIndexStore.rebuild(json)
                writeWorkspaceMemoryUnlocked(
                    json = json,
                    dailyTitle = "Memory Governance Delete",
                    lines =
                        listOf(
                            "type=${type.wireName}",
                            "entry_ref=${normalizedRef.take(120)}",
                            "removed=${removedSummary.take(160)}",
                        ),
                )
            }
        }
        return readGovernanceSnapshot()
    }

    fun recordCorrectionTemplate(
        task: String,
        profileId: String,
        correction: String,
    ) {
        val extracted = MemoryExtractor.extractCorrectionTemplates(task, profileId, correction)
        if (extracted.isEmpty()) return
        synchronized(lock) {
            val json = ensureSchema(readMemoryJsonUnlocked() ?: JSONObject())
            val structured = json.optJSONObject("structured") ?: JSONObject().also { json.put("structured", it) }
            mergeStructuredMemories(
                structured = structured,
                extracted = MemoryExtractResult(correctionTemplates = extracted),
            )
            writeMemoryJsonUnlocked(json)
            publishGovernanceSnapshotUnlocked(json)
            MemoryRecallIndexStore.rebuild(json)
            writeWorkspaceMemoryUnlocked(
                json = json,
                dailyTitle = "Correction Template",
                lines = listOf("task=$task", "profile=$profileId", "correction=${correction.take(160)}"),
            )
        }
    }

    fun recordSafetyRuleGrant(
        packageName: String,
        actionFamily: String,
        scope: String,
        note: String,
    ) {
        if (actionFamily.isBlank()) return
        synchronized(lock) {
            val json = ensureSchema(readMemoryJsonUnlocked() ?: JSONObject())
            val structured = json.optJSONObject("structured") ?: JSONObject().also { json.put("structured", it) }
            mergeStructuredMemories(
                structured = structured,
                extracted =
                    MemoryExtractResult(
                        safetyRules =
                            listOf(
                                SafetyRuleMemory(
                                    rule = "${packageName.ifBlank { "any_app" }} | ${actionFamily.trim()} | grant=${scope.ifBlank { "session" }}",
                                    level = "confirm",
                                    note = note.take(120),
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            ),
                    ),
            )
            writeMemoryJsonUnlocked(json)
            publishGovernanceSnapshotUnlocked(json)
            MemoryRecallIndexStore.rebuild(json)
            writeWorkspaceMemoryUnlocked(
                json = json,
                dailyTitle = "Safety Grant",
                lines =
                    listOf(
                        "package=${packageName.ifBlank { "any_app" }}",
                        "action_family=${actionFamily}",
                        "scope=${scope.ifBlank { "session" }}",
                        "note=${note.take(160)}",
                    ),
            )
        }
    }

    fun recordTaskProgress(
        task: String,
        profileId: String,
        taskResult: TaskResultPayload,
        artifactFacts: List<String> = emptyList(),
    ) {
        synchronized(lock) {
            val json = ensureSchema(readMemoryJsonUnlocked() ?: JSONObject())
            val facts = json.optJSONArray("facts") ?: JSONArray().also { json.put("facts", it) }
            val structured = json.optJSONObject("structured") ?: JSONObject().also { json.put("structured", it) }
            val now = System.currentTimeMillis()
            mergeStructuredMemories(
                structured = structured,
                extracted =
                    MemoryExtractResult(
                        resultArtifacts =
                            listOf(
                                ResultArtifactMemory(
                                    intentType = taskResult.intentType,
                                    title = taskResult.title.take(60),
                                    summary = taskResult.summary.take(120),
                                    sourceProfileId = profileId,
                                    updatedAt = now,
                                ),
                            ),
                        facts =
                            buildList {
                                taskResult.title
                                    .takeIf { it.isNotBlank() || taskResult.summary.isNotBlank() }
                                    ?.let {
                                        add(
                                            "阶段结果: ${task.take(20)} ${taskResult.title.take(32)} ${taskResult.summary.take(80)}".trim(),
                                        )
                                    }
                                addAll(artifactFacts)
                            }.distinct(),
                    ),
            )
            appendFacts(
                facts = facts,
                profileId = profileId,
                values =
                    buildList {
                        taskResult.title
                            .takeIf { it.isNotBlank() || taskResult.summary.isNotBlank() }
                            ?.let {
                                add(
                                    "阶段结果: ${task.take(20)} ${taskResult.title.take(32)} ${taskResult.summary.take(80)}".trim(),
                                )
                            }
                        addAll(artifactFacts)
                    },
            )
            writeMemoryJsonUnlocked(json)
            publishGovernanceSnapshotUnlocked(json)
            MemoryRecallIndexStore.rebuild(json)
            writeWorkspaceMemoryUnlocked(
                json = json,
                dailyTitle = "Task Progress",
                lines =
                    buildList {
                        add("task=$task")
                        add("profile=$profileId")
                        add("title=${taskResult.title.take(80)}")
                        add("summary=${taskResult.summary.take(160)}")
                        artifactFacts.take(3).forEach { add("artifact_fact=$it") }
                    },
            )
        }
    }

    fun recordExternalSignalMemory(
        signal: AssistantExternalSignal,
    ) {
        synchronized(lock) {
            val extracted = buildExternalSignalMemory(signal) ?: return
            val json = ensureSchema(readMemoryJsonUnlocked() ?: JSONObject())
            val structured = json.optJSONObject("structured") ?: JSONObject().also { json.put("structured", it) }
            val facts = json.optJSONArray("facts") ?: JSONArray().also { json.put("facts", it) }
            mergeStructuredMemories(structured = structured, extracted = extracted)
            extracted.facts.forEach { fact ->
                upsertFact(
                    facts = facts,
                    profileId = signal.payload["profile_id"].orEmpty(),
                    content = fact,
                    timestampMs = System.currentTimeMillis(),
                )
            }
            extracted.contacts.firstOrNull()?.let { contact ->
                appendGovernanceAudit(
                    json = json,
                    action = "signal_ingest",
                    type = PersonalMemoryEntryType.CONTACT,
                    entryId = governanceEntryId(PersonalMemoryEntryType.CONTACT, contact.name),
                    summary = "signal=${signal.type.name.lowercase()} | ${contact.name}".take(160),
                    timestampMs = System.currentTimeMillis(),
                )
            }
            extracted.locations.firstOrNull()?.let { location ->
                appendGovernanceAudit(
                    json = json,
                    action = "signal_ingest",
                    type = PersonalMemoryEntryType.LOCATION,
                    entryId = governanceEntryId(PersonalMemoryEntryType.LOCATION, location.name),
                    summary = "signal=${signal.type.name.lowercase()} | ${location.name}".take(160),
                    timestampMs = System.currentTimeMillis(),
                )
            }
            extracted.appPreferences.firstOrNull()?.let { app ->
                appendGovernanceAudit(
                    json = json,
                    action = "signal_ingest",
                    type = PersonalMemoryEntryType.APP_PREFERENCE,
                    entryId = governanceEntryId(PersonalMemoryEntryType.APP_PREFERENCE, app.preference, app.profileId),
                    summary = "signal=${signal.type.name.lowercase()} | ${app.preference}".take(160),
                    timestampMs = System.currentTimeMillis(),
                )
            }
            writeMemoryJsonUnlocked(json)
            publishGovernanceSnapshotUnlocked(json)
            MemoryRecallIndexStore.rebuild(json)
            writeWorkspaceMemoryUnlocked(
                json = json,
                dailyTitle = "External Signal Memory",
                lines =
                    buildList {
                        add("signal_type=${signal.type.name.lowercase()}")
                        add("title=${signal.title.take(120)}")
                        add("summary=${signal.summary.take(160)}")
                        extracted.facts.take(3).forEach { add("fact=$it") }
                    },
            )
        }
    }

    fun recallRouteHints(
        task: String,
        candidateProfileIds: Set<String>,
    ): List<RouteMemoryHint> {
        if (candidateProfileIds.isEmpty()) return emptyList()
        val json = readMemoryJson() ?: return emptyList()
        val profileStats = json.optJSONObject("profile_stats") ?: JSONObject()
        val profileSummaries = json.optJSONObject("profile_summaries") ?: JSONObject()
        val structured = json.optJSONObject("structured") ?: JSONObject()
        val appPreferences = structured.optJSONArray("app_preferences") ?: JSONArray()
        return MemoryRetriever.buildRouteHints(
            task = task,
            candidateProfileIds = candidateProfileIds,
            profileStats = profileStats,
            profileSummaries = profileSummaries,
            appPreferences = appPreferences,
            resultArtifacts = structured.optJSONArray("result_artifacts") ?: JSONArray(),
        )
    }

    private fun extractKeywords(task: String): Set<String> {
        val compact = task.trim()
        if (compact.isBlank()) return emptySet()
        val result = linkedSetOf<String>()
        Regex("""[A-Za-z0-9]{2,}""").findAll(compact).forEach { result += it.value.lowercase() }
        val cjk = compact.replace(Regex("""[^\\p{IsHan}]"""), "")
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

    private fun readMemoryJson(): JSONObject? =
        synchronized(lock) {
            readMemoryJsonUnlocked()
        }

    private fun readMemoryJsonUnlocked(): JSONObject? {
        val file = memoryFile() ?: return null
        if (!file.exists()) {
            return ensureSchema(JSONObject())
        }
        return ensureSchema(JSONObject(file.readText()))
    }

    private fun writeMemoryJsonUnlocked(json: JSONObject) {
        val file = memoryFile() ?: return
        clearImportedInsightUnlocked()
        file.writeText(json.toString(2))
    }

    private fun writeWorkspaceMemoryUnlocked(
        json: JSONObject,
        dailyTitle: String,
        lines: List<String>,
    ) {
        writeCoreMemoryFileUnlocked(json)
        appendDailyMemoryUnlocked(dailyTitle, lines)
        MemoryWorkspaceGovernance.refresh(json = json, reason = dailyTitle)
    }

    private fun memoryFile(): File? {
        val dir = memoryRootDir() ?: return null
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, MEMORY_FILE)
    }

    private fun importedInsightFile(): File? {
        val dir = memoryRootDir() ?: return null
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, IMPORTED_INSIGHT_FILE)
    }

    private fun memoryRootDir(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(context.filesDir, MEMORY_DIR)
    }

    private fun workspaceDir(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(context.filesDir, MEMORY_WORKSPACE_DIR).also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            governanceHydrated = false
            governanceSnapshotFlow.value = PersonalMemoryGovernanceSnapshot()
            importedInsightHydrated = false
            importedInsightSnapshot = PersonalMemoryInsightSnapshot()
        }
    }

    private fun PersonalMemoryInsightSnapshot.hasContent(): Boolean =
        summary.isNotBlank() ||
            awaySummary.isNotBlank() ||
            thinkbackSummary.isNotBlank() ||
            consolidationSummary.isNotBlank() ||
            dreamSummary.isNotBlank() ||
            suggestionSummary.isNotBlank() ||
            awayLines.isNotEmpty() ||
            thinkbackLines.isNotEmpty() ||
            consolidationLines.isNotEmpty() ||
            dreamLines.isNotEmpty()

    private fun PersonalMemoryInsightSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("summary", summary)
            put("away_summary", awaySummary)
            put("thinkback_summary", thinkbackSummary)
            put("consolidation_summary", consolidationSummary)
            put("dream_summary", dreamSummary)
            put("suggestion_summary", suggestionSummary)
            put("away_lines", JSONArray(awayLines))
            put("thinkback_lines", JSONArray(thinkbackLines))
            put("consolidation_lines", JSONArray(consolidationLines))
            put("dream_lines", JSONArray(dreamLines))
            put("recommended_commands", JSONArray(recommendedCommands))
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toInsightSnapshot(): PersonalMemoryInsightSnapshot =
        PersonalMemoryInsightSnapshot(
            summary = optString("summary"),
            awaySummary = optString("away_summary"),
            thinkbackSummary = optString("thinkback_summary"),
            consolidationSummary = optString("consolidation_summary"),
            dreamSummary = optString("dream_summary"),
            suggestionSummary = optString("suggestion_summary"),
            awayLines = optJSONArray("away_lines").toStringList(),
            thinkbackLines = optJSONArray("thinkback_lines").toStringList(),
            consolidationLines = optJSONArray("consolidation_lines").toStringList(),
            dreamLines = optJSONArray("dream_lines").toStringList(),
            recommendedCommands = optJSONArray("recommended_commands").toStringList(),
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

    private fun writeCoreMemoryFileUnlocked(
        json: JSONObject,
    ) {
        val file = workspaceDir()?.let { File(it, CORE_MEMORY_FILE) } ?: return
        val profileSummaries = json.optJSONObject("profile_summaries") ?: JSONObject()
        val facts = json.optJSONArray("facts") ?: JSONArray()
        val lines =
            buildList {
                add("# Xiaoxuan Memory")
                add("")
                add("## Core Profile Summaries")
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
            }
        file.writeText(lines.joinToString(separator = "\n"))
    }

    private fun appendDailyMemoryUnlocked(
        title: String,
        lines: List<String>,
    ) {
        val baseDir = workspaceDir() ?: return
        val dailyDir = File(baseDir, DAILY_MEMORY_DIR)
        if (!dailyDir.exists()) {
            dailyDir.mkdirs()
        }
        val file = File(dailyDir, "${dailyFormatter.format(Date())}.md")
        val section =
            buildString {
                append("## ").append(title).append(" @ ").append(System.currentTimeMillis()).append('\n')
                lines
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { line ->
                        append("- ").append(line).append('\n')
                    }
                append('\n')
            }
        if (file.exists()) {
            file.appendText(section)
        } else {
            file.writeText("# Daily Memory\n\n$section")
        }
    }

    private fun ensureSchema(json: JSONObject): JSONObject =
        json.apply {
            if (!has("episodes")) put("episodes", JSONArray())
            if (!has("profile_stats")) put("profile_stats", JSONObject())
            if (!has("profile_summaries")) put("profile_summaries", JSONObject())
            if (!has("facts")) put("facts", JSONArray())
            if (!has("audit_log")) put("audit_log", JSONArray())
            if (!has("structured")) {
                put(
                    "structured",
                    JSONObject().apply {
                        put("result_artifacts", JSONArray())
                        put("contacts", JSONArray())
                        put("locations", JSONArray())
                        put("app_preferences", JSONArray())
                        put("safety_rules", JSONArray())
                        put("correction_templates", JSONArray())
                    },
                )
            } else {
                val structured = optJSONObject("structured") ?: JSONObject().also { put("structured", it) }
                if (!structured.has("result_artifacts")) structured.put("result_artifacts", JSONArray())
                if (!structured.has("contacts")) structured.put("contacts", JSONArray())
                if (!structured.has("locations")) structured.put("locations", JSONArray())
                if (!structured.has("app_preferences")) structured.put("app_preferences", JSONArray())
                if (!structured.has("safety_rules")) structured.put("safety_rules", JSONArray())
                if (!structured.has("correction_templates")) structured.put("correction_templates", JSONArray())
            }
        }

    private fun appendFacts(
        facts: JSONArray,
        profileId: String,
        values: List<String>,
    ) {
        val now = System.currentTimeMillis()
        values
            .map { it.trim().take(120) }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { fact ->
                // 与已有事实近重复 → 合并强化（bump 时间 + hits），而非新增重复条目。
                val similarIndex = MemoryConsolidation.findSimilarIndex(facts, fact)
                if (similarIndex >= 0) {
                    facts.optJSONObject(similarIndex)?.let { existing ->
                        existing.put("timestamp", now)
                        existing.put("hits", existing.optInt("hits", 1) + 1)
                    }
                } else {
                    facts.put(
                        JSONObject().apply {
                            put("profile_id", profileId)
                            put("content", fact)
                            put("timestamp", now)
                            put("hits", 1)
                        },
                    )
                }
            }
        // 超容量时按价值淘汰（命中多/近的保留），而非一律删最旧。
        MemoryConsolidation.evictToCapacity(facts, MAX_FACTS, now)
    }

    private fun upsertFact(
        facts: JSONArray,
        profileId: String,
        content: String,
        timestampMs: Long,
    ) {
        val normalizedContent = content.trim().take(120)
        if (normalizedContent.isBlank()) return
        for (index in 0 until facts.length()) {
            val existing = facts.optJSONObject(index) ?: continue
            if (existing.optString("content").trim().equals(normalizedContent, ignoreCase = true)) {
                existing.put("profile_id", profileId)
                existing.put("content", normalizedContent)
                existing.put("timestamp", timestampMs)
                facts.put(index, existing)
                return
            }
        }
        facts.put(
            JSONObject().apply {
                put("profile_id", profileId)
                put("content", normalizedContent)
                put("timestamp", timestampMs)
            },
        )
        while (facts.length() > MAX_FACTS) {
            facts.remove(0)
        }
    }

    private fun mergeStructuredMemories(
        structured: JSONObject,
        extracted: MemoryExtractResult,
    ) {
        upsertByKey(
            array = structured.optJSONArray("result_artifacts") ?: JSONArray().also { structured.put("result_artifacts", it) },
            keyOf = { "${it.optString("intent_type").lowercase()}|${it.optString("title").lowercase()}" },
            values =
                extracted.resultArtifacts.map { memory ->
                    JSONObject().apply {
                        put("intent_type", memory.intentType)
                        put("title", memory.title)
                        put("summary", memory.summary)
                        put("source_profile_id", memory.sourceProfileId)
                        put("updated_at", memory.updatedAt)
                    }
                },
        )
        upsertByKey(
            array = structured.optJSONArray("contacts") ?: JSONArray().also { structured.put("contacts", it) },
            keyOf = { it.optString("name").lowercase() },
            values =
                extracted.contacts.map { memory ->
                    JSONObject().apply {
                        put("name", memory.name)
                        put("aliases", JSONArray(memory.aliases))
                        put("note", memory.note)
                        put("source_profile_id", memory.sourceProfileId)
                        put("updated_at", memory.updatedAt)
                    }
                },
        )
        upsertByKey(
            array = structured.optJSONArray("locations") ?: JSONArray().also { structured.put("locations", it) },
            keyOf = { it.optString("name").lowercase() },
            values =
                extracted.locations.map { memory ->
                    JSONObject().apply {
                        put("name", memory.name)
                        put("category", memory.category)
                        put("note", memory.note)
                        put("source_profile_id", memory.sourceProfileId)
                        put("updated_at", memory.updatedAt)
                    }
                },
        )
        upsertByKey(
            array = structured.optJSONArray("app_preferences") ?: JSONArray().also { structured.put("app_preferences", it) },
            keyOf = { "${it.optString("profile_id").lowercase()}|${it.optString("preference").lowercase()}" },
            values =
                extracted.appPreferences.map { memory ->
                    JSONObject().apply {
                        put("profile_id", memory.profileId)
                        put("preference", memory.preference)
                        put("note", memory.note)
                        put("updated_at", memory.updatedAt)
                    }
                },
        )
        upsertByKey(
            array = structured.optJSONArray("safety_rules") ?: JSONArray().also { structured.put("safety_rules", it) },
            keyOf = { it.optString("rule").lowercase() },
            values =
                extracted.safetyRules.map { memory ->
                    JSONObject().apply {
                        put("rule", memory.rule)
                        put("level", memory.level)
                        put("note", memory.note)
                        put("updated_at", memory.updatedAt)
                    }
                },
        )
        upsertByKey(
            array =
                structured.optJSONArray("correction_templates") ?: JSONArray().also {
                    structured.put("correction_templates", it)
                },
            keyOf = { "${it.optString("template_type").lowercase()}|${it.optString("argument").lowercase()}" },
            values =
                extracted.correctionTemplates.map { memory ->
                    JSONObject().apply {
                        put("template_type", memory.templateType)
                        put("argument", memory.argument)
                        put("instruction", memory.instruction)
                        put("source_profile_id", memory.sourceProfileId)
                        put("note", memory.note)
                        put("updated_at", memory.updatedAt)
                    }
                },
        )
    }

    private fun upsertByKey(
        array: JSONArray,
        keyOf: (JSONObject) -> String,
        values: List<JSONObject>,
    ) {
        values.forEach { value ->
            val key = keyOf(value)
            if (key.isBlank()) return@forEach
            var replaced = false
            for (index in 0 until array.length()) {
                val existing = array.optJSONObject(index) ?: continue
                if (keyOf(existing) == key) {
                    array.put(index, value)
                    replaced = true
                    break
                }
            }
            if (!replaced) {
                array.put(value)
            }
        }
        while (array.length() > MAX_STRUCTURED_ITEMS) {
            array.remove(0)
        }
    }

    private fun buildGovernanceEntries(
        json: JSONObject,
    ): List<PersonalMemoryGovernanceEntry> {
        val facts = json.optJSONArray("facts") ?: JSONArray()
        val structured = json.optJSONObject("structured") ?: JSONObject()
        return buildList {
            for (index in 0 until facts.length()) {
                val fact = facts.optJSONObject(index) ?: continue
                val content = fact.optString("content").trim()
                if (content.isBlank()) continue
                add(
                    PersonalMemoryGovernanceEntry(
                        entryId = governanceEntryId(PersonalMemoryEntryType.FACT, content),
                        type = PersonalMemoryEntryType.FACT,
                        title = content,
                        summary = "记忆事实",
                        profileId = fact.optString("profile_id"),
                        updatedAtMs = fact.optLong("timestamp"),
                    ),
                )
            }
            appendGovernanceEntries(
                type = PersonalMemoryEntryType.RESULT_ARTIFACT,
                array = structured.optJSONArray("result_artifacts"),
                keySummary = { jsonObject ->
                    PersonalMemoryGovernanceEntry(
                        entryId = governanceEntryId(PersonalMemoryEntryType.RESULT_ARTIFACT, jsonObject.optString("title"), jsonObject.optString("intent_type")),
                        type = PersonalMemoryEntryType.RESULT_ARTIFACT,
                        title = jsonObject.optString("title"),
                        summary = "${jsonObject.optString("intent_type").ifBlank { "result" }} | ${jsonObject.optString("summary")}".trim(),
                        profileId = jsonObject.optString("source_profile_id"),
                        updatedAtMs = jsonObject.optLong("updated_at"),
                    )
                },
            )
            appendGovernanceEntries(
                type = PersonalMemoryEntryType.CONTACT,
                array = structured.optJSONArray("contacts"),
                keySummary = { jsonObject ->
                    PersonalMemoryGovernanceEntry(
                        entryId = governanceEntryId(PersonalMemoryEntryType.CONTACT, jsonObject.optString("name")),
                        type = PersonalMemoryEntryType.CONTACT,
                        title = jsonObject.optString("name"),
                        summary = buildList {
                            val aliases = jsonObject.optJSONArray("aliases")
                            if (aliases != null && aliases.length() > 0) {
                                add(
                                    "aliases=${
                                        buildList {
                                            for (aliasIndex in 0 until aliases.length()) {
                                                aliases.optString(aliasIndex).takeIf { it.isNotBlank() }?.let(::add)
                                            }
                                        }.joinToString(",")
                                    }",
                                )
                            }
                            jsonObject.optString("note").takeIf { it.isNotBlank() }?.let(::add)
                        }.joinToString(" | "),
                        profileId = jsonObject.optString("source_profile_id"),
                        updatedAtMs = jsonObject.optLong("updated_at"),
                    )
                },
            )
            appendGovernanceEntries(
                type = PersonalMemoryEntryType.LOCATION,
                array = structured.optJSONArray("locations"),
                keySummary = { jsonObject ->
                    PersonalMemoryGovernanceEntry(
                        entryId = governanceEntryId(PersonalMemoryEntryType.LOCATION, jsonObject.optString("name")),
                        type = PersonalMemoryEntryType.LOCATION,
                        title = jsonObject.optString("name"),
                        summary = listOf(jsonObject.optString("category"), jsonObject.optString("note")).filter { it.isNotBlank() }.joinToString(" | "),
                        profileId = jsonObject.optString("source_profile_id"),
                        updatedAtMs = jsonObject.optLong("updated_at"),
                    )
                },
            )
            appendGovernanceEntries(
                type = PersonalMemoryEntryType.APP_PREFERENCE,
                array = structured.optJSONArray("app_preferences"),
                keySummary = { jsonObject ->
                    PersonalMemoryGovernanceEntry(
                        entryId = governanceEntryId(PersonalMemoryEntryType.APP_PREFERENCE, jsonObject.optString("preference"), jsonObject.optString("profile_id")),
                        type = PersonalMemoryEntryType.APP_PREFERENCE,
                        title = jsonObject.optString("preference"),
                        summary = listOf(jsonObject.optString("profile_id"), jsonObject.optString("note")).filter { it.isNotBlank() }.joinToString(" | "),
                        profileId = jsonObject.optString("profile_id"),
                        updatedAtMs = jsonObject.optLong("updated_at"),
                    )
                },
            )
            appendGovernanceEntries(
                type = PersonalMemoryEntryType.SAFETY_RULE,
                array = structured.optJSONArray("safety_rules"),
                keySummary = { jsonObject ->
                    PersonalMemoryGovernanceEntry(
                        entryId = governanceEntryId(PersonalMemoryEntryType.SAFETY_RULE, jsonObject.optString("rule")),
                        type = PersonalMemoryEntryType.SAFETY_RULE,
                        title = jsonObject.optString("rule"),
                        summary = listOf(jsonObject.optString("level"), jsonObject.optString("note")).filter { it.isNotBlank() }.joinToString(" | "),
                        updatedAtMs = jsonObject.optLong("updated_at"),
                    )
                },
            )
            appendGovernanceEntries(
                type = PersonalMemoryEntryType.CORRECTION_TEMPLATE,
                array = structured.optJSONArray("correction_templates"),
                keySummary = { jsonObject ->
                    PersonalMemoryGovernanceEntry(
                        entryId = governanceEntryId(PersonalMemoryEntryType.CORRECTION_TEMPLATE, jsonObject.optString("template_type"), jsonObject.optString("argument")),
                        type = PersonalMemoryEntryType.CORRECTION_TEMPLATE,
                        title = jsonObject.optString("template_type"),
                        summary = listOf(jsonObject.optString("argument"), jsonObject.optString("instruction"), jsonObject.optString("note")).filter { it.isNotBlank() }.joinToString(" | "),
                        profileId = jsonObject.optString("source_profile_id"),
                        updatedAtMs = jsonObject.optLong("updated_at"),
                    )
                },
            )
        }
    }

    private fun MutableList<PersonalMemoryGovernanceEntry>.appendGovernanceEntries(
        type: PersonalMemoryEntryType,
        array: JSONArray?,
        keySummary: (JSONObject) -> PersonalMemoryGovernanceEntry,
    ) {
        if (array == null) return
        for (index in 0 until array.length()) {
            val jsonObject = array.optJSONObject(index) ?: continue
            val entry = keySummary(jsonObject)
            if (entry.title.isBlank()) continue
            add(entry.copy(type = type))
        }
    }

    private fun buildAuditTrail(
        json: JSONObject,
    ): List<PersonalMemoryAuditEntry> {
        val array = json.optJSONArray("audit_log") ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val audit = array.optJSONObject(index) ?: continue
                val type =
                    PersonalMemoryEntryType.fromWireName(audit.optString("type"))
                        ?: continue
                add(
                    PersonalMemoryAuditEntry(
                        action = audit.optString("action"),
                        type = type,
                        entryId = audit.optString("entry_id"),
                        summary = audit.optString("summary"),
                        timestampMs = audit.optLong("timestamp_ms"),
                    ),
                )
            }
        }
    }

    private fun appendGovernanceAudit(
        json: JSONObject,
        action: String,
        type: PersonalMemoryEntryType,
        entryId: String,
        summary: String,
        timestampMs: Long,
    ) {
        val auditLog = json.optJSONArray("audit_log") ?: JSONArray().also { json.put("audit_log", it) }
        auditLog.put(
            JSONObject().apply {
                put("action", action)
                put("type", type.wireName)
                put("entry_id", entryId)
                put("summary", summary.take(160))
                put("timestamp_ms", timestampMs)
            },
        )
        while (auditLog.length() > MAX_AUDIT_LOGS) {
            auditLog.remove(0)
        }
    }

    private fun governanceEntryId(
        type: PersonalMemoryEntryType,
        primary: String,
        secondary: String = "",
    ): String =
        buildString {
            append(type.wireName)
            append(':')
            append(normalizeGovernanceKey(primary))
            secondary.takeIf { it.isNotBlank() }?.let {
                append(':')
                append(normalizeGovernanceKey(it))
            }
        }

    private fun normalizeGovernanceKey(
        raw: String,
    ): String = raw.trim().lowercase().replace(Regex("\\s+"), "_")

    private fun buildGovernanceExtractResult(
        type: PersonalMemoryEntryType,
        primary: String,
        secondary: String,
        note: String,
        profileId: String,
        now: Long,
    ): MemoryExtractResult =
        when (type) {
            PersonalMemoryEntryType.RESULT_ARTIFACT ->
                MemoryExtractResult(
                    resultArtifacts =
                        listOf(
                            ResultArtifactMemory(
                                intentType = note.ifBlank { "manual" },
                                title = primary.take(60),
                                summary = secondary.take(120),
                                sourceProfileId = profileId,
                                updatedAt = now,
                            ),
                        ),
                )

            PersonalMemoryEntryType.CONTACT ->
                MemoryExtractResult(
                    contacts =
                        listOf(
                            ContactMemory(
                                name = primary.take(60),
                                aliases = secondary.split(",").mapNotNull { it.trim().takeIf(String::isNotBlank) }.take(6),
                                note = note.take(120),
                                sourceProfileId = profileId,
                                updatedAt = now,
                            ),
                        ),
                )

            PersonalMemoryEntryType.LOCATION ->
                MemoryExtractResult(
                    locations =
                        listOf(
                            LocationMemory(
                                name = primary.take(60),
                                category = secondary.take(40),
                                note = note.take(120),
                                sourceProfileId = profileId,
                                updatedAt = now,
                            ),
                        ),
                )

            PersonalMemoryEntryType.APP_PREFERENCE ->
                MemoryExtractResult(
                    appPreferences =
                        listOf(
                            AppPreferenceMemory(
                                profileId = secondary.ifBlank { profileId }.take(60),
                                preference = primary.take(80),
                                note = note.take(120),
                                updatedAt = now,
                            ),
                        ),
                )

            PersonalMemoryEntryType.SAFETY_RULE ->
                MemoryExtractResult(
                    safetyRules =
                        listOf(
                            SafetyRuleMemory(
                                rule = primary.take(100),
                                level = secondary.ifBlank { "confirm" }.take(24),
                                note = note.take(120),
                                updatedAt = now,
                            ),
                        ),
                )

            PersonalMemoryEntryType.CORRECTION_TEMPLATE ->
                MemoryExtractResult(
                    correctionTemplates =
                        listOf(
                            CorrectionTemplateMemory(
                                templateType = primary.take(60),
                                argument = secondary.take(60),
                                instruction = note.take(160),
                                sourceProfileId = profileId,
                                note = "manual",
                                updatedAt = now,
                            ),
                        ),
                )

            PersonalMemoryEntryType.FACT -> MemoryExtractResult()
        }

    private fun buildGovernanceAuditSummary(
        type: PersonalMemoryEntryType,
        primary: String,
        secondary: String,
        note: String,
    ): String =
        listOf(
            type.wireName,
            primary.take(80),
            secondary.take(80),
            note.take(80),
        ).filter { it.isNotBlank() }.joinToString(" | ")

    private fun removeFact(
        facts: JSONArray,
        entryRef: String,
    ): String? {
        val normalizedRef = normalizeGovernanceKey(entryRef.substringAfter(':', entryRef))
        for (index in 0 until facts.length()) {
            val fact = facts.optJSONObject(index) ?: continue
            val content = fact.optString("content").trim()
            if (content.isBlank()) continue
            if (normalizeGovernanceKey(content) == normalizedRef) {
                facts.remove(index)
                return content
            }
        }
        return null
    }

    private fun removeStructuredEntry(
        structured: JSONObject,
        type: PersonalMemoryEntryType,
        entryRef: String,
    ): String? {
        val normalizedRef = entryRef.substringAfter(':', entryRef).trim().lowercase()
        val (arrayName, candidateKeys, summaryBuilder) =
            when (type) {
                PersonalMemoryEntryType.RESULT_ARTIFACT ->
                    Triple(
                        "result_artifacts",
                        listOf<(JSONObject) -> String>(
                            { governanceEntryId(type, it.optString("title"), it.optString("intent_type")).substringAfter(':').lowercase() },
                            { normalizeGovernanceKey(it.optString("title")) },
                        ),
                        { item: JSONObject -> item.optString("title") },
                    )

                PersonalMemoryEntryType.CONTACT ->
                    Triple(
                        "contacts",
                        listOf<(JSONObject) -> String>({ normalizeGovernanceKey(it.optString("name")) }),
                        { item: JSONObject -> item.optString("name") },
                    )

                PersonalMemoryEntryType.LOCATION ->
                    Triple(
                        "locations",
                        listOf<(JSONObject) -> String>({ normalizeGovernanceKey(it.optString("name")) }),
                        { item: JSONObject -> item.optString("name") },
                    )

                PersonalMemoryEntryType.APP_PREFERENCE ->
                    Triple(
                        "app_preferences",
                        listOf<(JSONObject) -> String>(
                            { governanceEntryId(type, it.optString("preference"), it.optString("profile_id")).substringAfter(':').lowercase() },
                            { normalizeGovernanceKey(it.optString("preference")) },
                        ),
                        { item: JSONObject -> item.optString("preference") },
                    )

                PersonalMemoryEntryType.SAFETY_RULE ->
                    Triple(
                        "safety_rules",
                        listOf<(JSONObject) -> String>({ normalizeGovernanceKey(it.optString("rule")) }),
                        { item: JSONObject -> item.optString("rule") },
                    )

                PersonalMemoryEntryType.CORRECTION_TEMPLATE ->
                    Triple(
                        "correction_templates",
                        listOf<(JSONObject) -> String>(
                            { governanceEntryId(type, it.optString("template_type"), it.optString("argument")).substringAfter(':').lowercase() },
                            { normalizeGovernanceKey(it.optString("template_type")) },
                        ),
                        { item: JSONObject -> item.optString("template_type") },
                    )

                PersonalMemoryEntryType.FACT -> return null
            }
        val array = structured.optJSONArray(arrayName) ?: return null
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (candidateKeys.any { keyOf -> keyOf(item) == normalizedRef }) {
                array.remove(index)
                return summaryBuilder(item)
            }
        }
        return null
    }

    private fun buildProfileSummary(
        task: String,
        finalMessage: String,
    ): String {
        val taskHead = task.take(24)
        val messageHead = finalMessage.take(48)
        if (taskHead.isBlank() && messageHead.isBlank()) return ""
        return "最近任务偏向于 $taskHead，常见结果是 $messageHead"
    }

}
