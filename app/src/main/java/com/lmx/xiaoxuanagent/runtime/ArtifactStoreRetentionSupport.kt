package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONObject

internal object ArtifactStoreRetentionSupport {
    private data class ArtifactIndexEntry(
        val artifactId: String,
        val type: String,
        val summary: String,
        val turn: Int,
        val createdAt: Long,
        val fileName: String,
        val payloadHash: String,
    )

    fun readLifecycleSnapshot(
        sessionId: String,
        policy: ArtifactRetentionPolicy,
        beforeTurnInclusive: Int,
        resolveSessionDir: (String) -> File?,
    ): ArtifactLifecycleSnapshot {
        if (sessionId.isBlank()) {
            return ArtifactLifecycleSnapshot(sessionId = "")
        }
        val dir =
            resolveSessionDir(sessionId)
                ?: return ArtifactLifecycleSnapshot(
                    sessionId = sessionId,
                    lines = listOf("artifact_session_dir_missing"),
                )
        val entries =
            readIndexEntriesUnlocked(dir)
                .filter { it.turn <= beforeTurnInclusive }
                .sortedByDescending { it.createdAt }
        if (entries.isEmpty()) {
            return ArtifactLifecycleSnapshot(
                sessionId = sessionId,
                lines = listOf("artifact_index_empty"),
            )
        }
        val now = System.currentTimeMillis()
        val pinnedTypes = policy.pinnedTypes.toSet()
        val pinnedArtifacts = entries.count { it.type in pinnedTypes }
        val hotArtifacts = entries.count { now - it.createdAt <= policy.hotArtifactWindowMs }
        val newestCreatedAt = entries.maxOfOrNull { it.createdAt } ?: 0L
        val oldestCreatedAt = entries.minOfOrNull { it.createdAt } ?: 0L
        val newestArtifactAgeMinutes =
            if (newestCreatedAt > 0L) {
                ((now - newestCreatedAt).coerceAtLeast(0L) / 60_000L).toInt()
            } else {
                0
            }
        val oldestArtifactAgeDays =
            if (oldestCreatedAt > 0L) {
                ((now - oldestCreatedAt).coerceAtLeast(0L) / (24L * 60L * 60L * 1000L)).toInt()
            } else {
                0
            }
        val distinctTurns =
            entries
                .mapNotNull { it.turn.takeIf { turn -> turn >= 0 } }
                .distinct()
                .sorted()
        val turnWindowSummary =
            when {
                distinctTurns.isEmpty() -> "turns=-"
                distinctTurns.size == 1 -> "turn=${distinctTurns.first()}"
                else -> "turns=${distinctTurns.first()}..${distinctTurns.last()} count=${distinctTurns.size}"
            }
        val deleteReasons = linkedMapOf<String, String>()
        entries.drop(policy.maxArtifactsPerSession.coerceAtLeast(1)).forEach { entry ->
            deleteReasons.putIfAbsent(entry.artifactId, "session_cap")
        }
        entries.groupBy { it.type }.values.forEach { group ->
            group.sortedByDescending { it.createdAt }
                .drop(policy.maxArtifactsPerType.coerceAtLeast(1))
                .forEach { entry ->
                    deleteReasons.putIfAbsent(entry.artifactId, "type_cap")
                }
        }
        entries.groupBy { artifactFamily(it.type) }.values.forEach { group ->
            val protectedIds =
                group
                    .sortedByDescending { it.createdAt }
                    .take(policy.keepLatestPerFamily.coerceAtLeast(1))
                    .map { it.artifactId }
                    .toSet()
            group.sortedByDescending { it.createdAt }
                .drop(policy.maxArtifactsPerFamily.coerceAtLeast(1))
                .forEach { entry ->
                    if (entry.artifactId !in protectedIds) {
                        deleteReasons.putIfAbsent(entry.artifactId, "family_cap")
                    }
                }
        }
        entries.filter { it.type !in pinnedTypes }
            .filter { it.artifactId !in entries.filter { entry -> now - entry.createdAt <= policy.hotArtifactWindowMs }.map { it.artifactId }.toSet() }
            .filter { it.createdAt > 0L && now - it.createdAt >= policy.maxAgeMs }
            .forEach { entry ->
                deleteReasons.putIfAbsent(entry.artifactId, "age")
            }
        val deletionRiskSummary =
            buildString {
                append("delete_candidates=").append(deleteReasons.size)
                append(" session_cap=").append(deleteReasons.count { it.value == "session_cap" })
                append(" type_cap=").append(deleteReasons.count { it.value == "type_cap" })
                append(" family_cap=").append(deleteReasons.count { it.value == "family_cap" })
                append(" age=").append(deleteReasons.count { it.value == "age" })
            }
        val familySummaries =
            entries
                .groupBy { artifactFamily(it.type) }
                .entries
                .sortedByDescending { it.value.size }
                .map { (family, familyEntries) ->
                    "$family=${familyEntries.size}"
                }
        val typeSummaries =
            entries
                .groupBy { it.type }
                .entries
                .sortedByDescending { it.value.size }
                .take(6)
                .map { (type, typeEntries) -> "$type=${typeEntries.size}" }
        val recentArtifactLines =
            entries
                .take(4)
                .map { entry ->
                    buildString {
                        append("turn=").append(entry.turn)
                        append(" ")
                        append(entry.type)
                        append(" | ")
                        append(entry.summary.take(72))
                    }
                }
        return ArtifactLifecycleSnapshot(
            sessionId = sessionId,
            totalArtifacts = entries.size,
            pinnedArtifacts = pinnedArtifacts,
            hotArtifacts = hotArtifacts,
            oldestArtifactAgeDays = oldestArtifactAgeDays,
            newestArtifactAgeMinutes = newestArtifactAgeMinutes,
            distinctTurnCount = distinctTurns.size,
            turnWindowSummary = turnWindowSummary,
            deletionCandidateCount = deleteReasons.size,
            deletionRiskSummary = deletionRiskSummary,
            recentArtifactLines = recentArtifactLines,
            familySummaries = familySummaries,
            typeSummaries = typeSummaries,
            lines =
                buildList {
                    add("total=${entries.size}")
                    add("pinned=$pinnedArtifacts")
                    add("hot=$hotArtifacts")
                    add("window=$turnWindowSummary")
                    add("age_days_oldest=$oldestArtifactAgeDays newest_min=$newestArtifactAgeMinutes")
                    add(deletionRiskSummary)
                    add("families=${familySummaries.joinToString(",").ifBlank { "-" }}")
                    add("types=${typeSummaries.joinToString(",").ifBlank { "-" }}")
                    recentArtifactLines.forEach { add("recent=$it") }
                },
        )
    }

    fun sweepRetention(
        policy: ArtifactRetentionPolicy,
        keepSessionIds: Set<String>,
        nowMs: Long,
        applyDeletes: Boolean,
        resolveRootDir: () -> File?,
    ): ArtifactRetentionReport {
        if (!policy.enabled) {
            return ArtifactRetentionReport(
                policy = policy,
                lines = listOf("artifact retention disabled"),
            )
        }
        val root =
            resolveRootDir()
                ?: return ArtifactRetentionReport(policy = policy, lines = listOf("artifact root missing"))
        if (!root.exists()) {
            return ArtifactRetentionReport(policy = policy, lines = listOf("artifact root missing"))
        }
        val sessionDirs = root.listFiles().orEmpty().filter { it.isDirectory }
        var deletedSessions = 0
        var deletedArtifacts = 0
        var keptArtifacts = 0
        var pinnedArtifacts = 0
        var hotArtifacts = 0
        var deletedBySessionCap = 0
        var deletedByTypeCap = 0
        var deletedByFamilyCap = 0
        var deletedByAge = 0
        var deletedByOrphanSession = 0
        val lines = mutableListOf<String>()
        sessionDirs.forEach { dir ->
            val sessionId = dir.name
            val protectedSession = policy.protectActiveSessions && sessionId in keepSessionIds
            val entries = readIndexEntriesUnlocked(dir)
            if (entries.isEmpty()) {
                if (!protectedSession && applyDeletes && dir.deleteRecursively()) {
                    deletedSessions += 1
                    lines += "$sessionId | delete_empty_session_dir"
                }
                return@forEach
            }
            val latestCreatedAt = entries.maxOfOrNull { it.createdAt } ?: 0L
            if (
                !protectedSession &&
                sessionId !in keepSessionIds &&
                latestCreatedAt > 0L &&
                nowMs - latestCreatedAt >= policy.orphanSessionMaxAgeMs
            ) {
                if (applyDeletes && dir.deleteRecursively()) {
                    deletedSessions += 1
                }
                deletedArtifacts += entries.size
                deletedByOrphanSession += entries.size
                lines += "$sessionId | delete_orphan_session | artifacts=${entries.size}"
                return@forEach
            }
            val pinnedTypes = policy.pinnedTypes.toSet()
            val sorted = entries.sortedByDescending { it.createdAt }
            val hotArtifactIds =
                sorted
                    .filter { nowMs - it.createdAt <= policy.hotArtifactWindowMs }
                    .map { it.artifactId }
                    .toSet()
            pinnedArtifacts += sorted.count { it.type in pinnedTypes }
            hotArtifacts += hotArtifactIds.size
            val deleteReasons = linkedMapOf<String, String>()
            if (!protectedSession) {
                sorted.drop(policy.maxArtifactsPerSession.coerceAtLeast(1)).forEach { entry ->
                    deleteReasons.putIfAbsent(entry.artifactId, "session_cap")
                }
                sorted.groupBy { it.type }.values.forEach { group ->
                    group.sortedByDescending { it.createdAt }
                        .drop(policy.maxArtifactsPerType.coerceAtLeast(1))
                        .forEach { entry ->
                            deleteReasons.putIfAbsent(entry.artifactId, "type_cap")
                        }
                }
                sorted.groupBy { artifactFamily(it.type) }.values.forEach { group ->
                    val protectedIds =
                        group
                            .sortedByDescending { it.createdAt }
                            .take(policy.keepLatestPerFamily.coerceAtLeast(1))
                            .map { it.artifactId }
                            .toSet()
                    group.sortedByDescending { it.createdAt }
                        .drop(policy.maxArtifactsPerFamily.coerceAtLeast(1))
                        .forEach { entry ->
                            if (entry.artifactId !in protectedIds) {
                                deleteReasons.putIfAbsent(entry.artifactId, "family_cap")
                            }
                        }
                }
                sorted.filter { it.type !in pinnedTypes }
                    .filter { it.artifactId !in hotArtifactIds }
                    .filter { it.createdAt > 0L && nowMs - it.createdAt >= policy.maxAgeMs }
                    .forEach { entry ->
                        deleteReasons.putIfAbsent(entry.artifactId, "age")
                    }
            }
            val deleteIds = deleteReasons.keys
            val kept = sorted.filterNot { it.artifactId in deleteIds }
            keptArtifacts += kept.size
            deletedArtifacts += deleteIds.size
            deletedBySessionCap += deleteReasons.count { it.value == "session_cap" }
            deletedByTypeCap += deleteReasons.count { it.value == "type_cap" }
            deletedByFamilyCap += deleteReasons.count { it.value == "family_cap" }
            deletedByAge += deleteReasons.count { it.value == "age" }
            if (deleteIds.isNotEmpty()) {
                lines +=
                    "$sessionId | delete_artifacts=${deleteIds.size} keep=${kept.size} " +
                        "session_cap=${deleteReasons.count { it.value == "session_cap" }} " +
                        "type_cap=${deleteReasons.count { it.value == "type_cap" }} " +
                        "family_cap=${deleteReasons.count { it.value == "family_cap" }} " +
                        "age=${deleteReasons.count { it.value == "age" }} hot=${hotArtifactIds.size}"
                if (applyDeletes) {
                    deleteIds.forEach { artifactId ->
                        File(dir, "$artifactId.json").takeIf { it.exists() }?.delete()
                    }
                    rewriteIndexUnlocked(dir, kept)
                }
            }
        }
        return ArtifactRetentionReport(
            policy = policy,
            scannedSessions = sessionDirs.size,
            deletedSessions = deletedSessions,
            deletedArtifacts = deletedArtifacts,
            keptArtifacts = keptArtifacts,
            pinnedArtifacts = pinnedArtifacts,
            hotArtifacts = hotArtifacts,
            deletedBySessionCap = deletedBySessionCap,
            deletedByTypeCap = deletedByTypeCap,
            deletedByFamilyCap = deletedByFamilyCap,
            deletedByAge = deletedByAge,
            deletedByOrphanSession = deletedByOrphanSession,
            lines = lines.take(policy.previewLineLimit.coerceAtLeast(4)),
        )
    }

    private fun readIndexEntriesUnlocked(
        dir: File,
    ): List<ArtifactIndexEntry> {
        val indexFile = File(dir, "index.jsonl")
        if (!indexFile.exists()) return emptyList()
        return indexFile.readLines()
            .mapNotNull { line ->
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
                ArtifactIndexEntry(
                    artifactId = json.optString("artifact_id"),
                    type = json.optString("type"),
                    summary = json.optString("summary"),
                    turn = json.optInt("turn"),
                    createdAt = json.optLong("created_at"),
                    fileName = json.optString("file_name", "${json.optString("artifact_id")}.json"),
                    payloadHash = json.optString("payload_hash"),
                ).takeIf { it.artifactId.isNotBlank() }
            }
            .distinctBy { it.artifactId }
    }

    private fun rewriteIndexUnlocked(
        dir: File,
        entries: List<ArtifactIndexEntry>,
    ) {
        val indexFile = File(dir, "index.jsonl")
        indexFile.writeText(
            buildString {
                entries.sortedBy { it.createdAt }.forEach { entry ->
                    append(
                        JSONObject().apply {
                            put("artifact_id", entry.artifactId)
                            put("type", entry.type)
                            put("summary", entry.summary)
                            put("turn", entry.turn)
                            put("created_at", entry.createdAt)
                            put("file_name", entry.fileName)
                            put("payload_hash", entry.payloadHash)
                        }.toString(),
                    )
                    append('\n')
                }
            },
        )
    }

    private fun artifactFamily(
        type: String,
    ): String =
        when (type) {
            "planning_observation",
            "ui_xml_snapshot",
            "planning_screenshot",
            "planner_decision",
            -> "planning"

            "execution_trace",
            "execution_verification",
            -> "execution"

            "task_result_summary" -> "result"
            "turn_failure" -> "failure"
            else -> "other"
        }
}
