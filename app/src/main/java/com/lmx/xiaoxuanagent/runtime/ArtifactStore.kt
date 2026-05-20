package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.RecoveryDiagnosis
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.ScreenshotPayload
import com.lmx.xiaoxuanagent.agent.SkillContext
import com.lmx.xiaoxuanagent.agent.TaskPlanState
import com.lmx.xiaoxuanagent.agent.TaskResultPayload
import com.lmx.xiaoxuanagent.agent.VisualPerceptionContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import com.lmx.xiaoxuanagent.runtime.ArtifactStorePreviewSupport.toArtifactJson

data class ArtifactRecord(
    val artifactId: String,
    val type: String,
    val summary: String,
    val path: String,
    val createdAt: Long,
)

data class TurnArtifactRefs(
    val planningObservationArtifactId: String = "",
    val uiXmlArtifactId: String = "",
    val screenshotArtifactId: String = "",
    val plannerDecisionArtifactId: String = "",
    val executionTraceArtifactId: String = "",
    val taskResultSummaryArtifactId: String = "",
    val verificationArtifactId: String = "",
    val failureArtifactId: String = "",
)

data class ArtifactRetentionReport(
    val policy: ArtifactRetentionPolicy,
    val scannedSessions: Int = 0,
    val deletedSessions: Int = 0,
    val deletedArtifacts: Int = 0,
    val keptArtifacts: Int = 0,
    val pinnedArtifacts: Int = 0,
    val hotArtifacts: Int = 0,
    val deletedBySessionCap: Int = 0,
    val deletedByTypeCap: Int = 0,
    val deletedByFamilyCap: Int = 0,
    val deletedByAge: Int = 0,
    val deletedByOrphanSession: Int = 0,
    val lines: List<String> = emptyList(),
)

data class ArtifactLifecycleSnapshot(
    val sessionId: String,
    val totalArtifacts: Int = 0,
    val pinnedArtifacts: Int = 0,
    val hotArtifacts: Int = 0,
    val oldestArtifactAgeDays: Int = 0,
    val newestArtifactAgeMinutes: Int = 0,
    val distinctTurnCount: Int = 0,
    val turnWindowSummary: String = "",
    val deletionCandidateCount: Int = 0,
    val deletionRiskSummary: String = "",
    val recentArtifactLines: List<String> = emptyList(),
    val familySummaries: List<String> = emptyList(),
    val typeSummaries: List<String> = emptyList(),
    val lines: List<String> = emptyList(),
)

object ArtifactStore {
    private const val ARTIFACT_DIR = "artifacts"
    private const val INDEX_FILE = "index.jsonl"
    private val lock = Any()

    data class ArtifactSearchHit(
        val sessionId: String,
        val turn: Int,
        val record: ArtifactRecord,
        val previewLines: List<String> = emptyList(),
    )

    private data class ArtifactIndexEntry(
        val artifactId: String,
        val type: String,
        val summary: String,
        val turn: Int,
        val createdAt: Long,
        val fileName: String,
        val payloadHash: String,
    )

    fun listRecentArtifactRecords(
        sessionId: String,
        beforeTurnInclusive: Int = Int.MAX_VALUE,
        limit: Int = 4,
        types: Set<String> = emptySet(),
    ): List<ArtifactRecord> {
        if (sessionId.isBlank() || limit <= 0) {
            return emptyList()
        }
        return synchronized(lock) {
            val dir = sessionDir(sessionId) ?: return@synchronized emptyList()
            val indexFile = File(dir, INDEX_FILE)
            if (!indexFile.exists()) {
                return@synchronized emptyList()
            }
            indexFile.readLines()
                .asReversed()
                .mapNotNull { line ->
                    runCatching { JSONObject(line) }.getOrNull()?.let { json ->
                        val artifactId = json.optString("artifact_id")
                        val type = json.optString("type")
                        val turn = json.optInt("turn", Int.MAX_VALUE)
                        if (
                            artifactId.isBlank() ||
                            turn > beforeTurnInclusive ||
                            (types.isNotEmpty() && type !in types)
                        ) {
                            null
                        } else {
                            val fileName = json.optString("file_name", "$artifactId.json")
                            ArtifactRecord(
                                artifactId = artifactId,
                                type = type,
                                summary = json.optString("summary"),
                                path = File(dir, fileName).absolutePath,
                                createdAt = json.optLong("created_at", 0L),
                            )
                        }
                    }
                }
                .distinctBy { it.artifactId }
                .take(limit)
        }
    }

    fun recordPlanningObservation(
        sessionId: String,
        turn: Int,
        task: String,
        observation: ScreenObservation,
        taskPlanState: TaskPlanState?,
        activeSkills: List<SkillContext>,
    ): ArtifactRecord? =
        writeArtifact(
            sessionId = sessionId,
            turn = turn,
            type = "planning_observation",
            summary = buildPlanningSummary(observation, taskPlanState, activeSkills),
            payload =
                JSONObject().apply {
                    put("task", task)
                    put("turn", turn)
                    put("observation", observation.toArtifactJson())
                    put("task_plan_state", taskPlanState?.toArtifactJson() ?: JSONObject())
                    put("active_skills", JSONArray(activeSkills.map { it.toArtifactJson() }))
                },
        )

    fun recordUiXmlSnapshot(
        sessionId: String,
        turn: Int,
        task: String,
        observation: ScreenObservation,
    ): ArtifactRecord? =
        writeArtifact(
            sessionId = sessionId,
            turn = turn,
            type = "ui_xml_snapshot",
            summary = "sig=${observation.signature} page=${observation.pageState} elements=${observation.elements.size}",
            payload =
                JSONObject().apply {
                    put("task", task)
                    put("turn", turn)
                    put("signature", observation.signature)
                    put("page_state", observation.pageState)
                    put("xml_snapshot", ArtifactStorePreviewSupport.buildObservationXmlSnapshot(observation))
                },
        )

    fun recordPlanningScreenshot(
        sessionId: String,
        turn: Int,
        task: String,
        observation: ScreenObservation,
        screenshot: ScreenshotPayload,
        visualContext: VisualPerceptionContext,
    ): ArtifactRecord? =
        writeArtifact(
            sessionId = sessionId,
            turn = turn,
            type = "planning_screenshot",
            summary = ArtifactStorePreviewSupport.buildScreenshotSummary(observation, screenshot, visualContext),
            payload =
                JSONObject().apply {
                    put("task", task)
                    put("turn", turn)
                    put("observation_signature", observation.signature)
                    put("page_state", observation.pageState)
                    put("mime_type", screenshot.mimeType)
                    put("width", screenshot.width)
                    put("height", screenshot.height)
                    put("base64_data", screenshot.base64Data)
                    put("visual_summary", visualContext.summary)
                    put("visual_hints", JSONArray(visualContext.visualHints))
                },
        )

    fun recordPlannerDecision(
        sessionId: String,
        turn: Int,
        task: String,
        decision: AgentDecision,
    ): ArtifactRecord? =
        writeArtifact(
            sessionId = sessionId,
            turn = turn,
            type = "planner_decision",
            summary = "action=${decision.action.label} reason=${decision.reason.take(80)}",
            payload =
                JSONObject().apply {
                    put("task", task)
                    put("turn", turn)
                    put("action", decision.action.label)
                    put("reason", decision.reason)
                    put("raw_response", decision.rawResponse)
                },
        )

    fun recordExecutionTrace(
        sessionId: String,
        turn: Int,
        task: String,
        observation: ScreenObservation,
        decision: AgentDecision,
        result: String,
        taskResult: TaskResultPayload?,
        keepRunning: Boolean,
        recoveryDiagnosis: RecoveryDiagnosis?,
        suggestedRecoveryAction: String,
    ): ArtifactRecord? =
        writeArtifact(
            sessionId = sessionId,
            turn = turn,
            type = "execution_trace",
            summary = "action=${decision.action.label} keepRunning=$keepRunning result=${result.take(80)}",
            payload =
                JSONObject().apply {
                    put("task", task)
                    put("turn", turn)
                    put("observation", observation.toJson())
                    put("action", decision.action.label)
                    put("reason", decision.reason)
                    put("result", result)
                    put("keep_running", keepRunning)
                    put("task_result", taskResult?.toJson() ?: JSONObject())
                    put("recovery", recoveryDiagnosis?.toJson() ?: JSONObject())
                    put("suggested_recovery_action", suggestedRecoveryAction)
                },
        )

    fun recordTaskResultSummary(
        sessionId: String,
        turn: Int,
        task: String,
        taskResult: TaskResultPayload,
    ): ArtifactRecord? =
        writeArtifact(
            sessionId = sessionId,
            turn = turn,
            type = "task_result_summary",
            summary = ArtifactStorePreviewSupport.buildTaskResultSummary(taskResult),
            payload =
                JSONObject().apply {
                    put("task", task)
                    put("turn", turn)
                    put("task_result", taskResult.toArtifactJson())
                },
        )

    fun recordTurnFailure(
        sessionId: String,
        turn: Int,
        task: String,
        observation: ScreenObservation?,
        error: String,
        keepRunning: Boolean,
    ): ArtifactRecord? =
        writeArtifact(
            sessionId = sessionId,
            turn = turn,
            type = "turn_failure",
            summary = "keepRunning=$keepRunning error=${error.take(96)}",
            payload =
                JSONObject().apply {
                    put("task", task)
                    put("turn", turn)
                    put("error", error)
                    put("keep_running", keepRunning)
                    put("observation", observation?.toArtifactJson() ?: JSONObject())
                },
        )

    fun recordVerificationSummary(
        sessionId: String,
        turn: Int,
        task: String,
        actionLabel: String,
        verified: Boolean,
        shouldImmediateReplan: Boolean,
        beforeSignature: String,
        afterSignature: String,
        actionMessage: String,
        taskMessage: String,
        diagnosisSummary: String,
        suggestedRecoveryAction: String,
        finalMessage: String,
    ): ArtifactRecord? =
        writeArtifact(
            sessionId = sessionId,
            turn = turn,
            type = "execution_verification",
            summary =
                buildString {
                    append("action=").append(actionLabel)
                    append(" verified=").append(verified)
                    append(" replan=").append(shouldImmediateReplan)
                    if (finalMessage.isNotBlank()) {
                        append(" msg=").append(finalMessage.take(80))
                    }
                },
            payload =
                JSONObject().apply {
                    put("task", task)
                    put("turn", turn)
                    put("action_label", actionLabel)
                    put("verified", verified)
                    put("should_immediate_replan", shouldImmediateReplan)
                    put("before_signature", beforeSignature)
                    put("after_signature", afterSignature)
                    put("action_message", actionMessage)
                    put("task_message", taskMessage)
                    put("diagnosis_summary", diagnosisSummary)
                    put("suggested_recovery_action", suggestedRecoveryAction)
                    put("final_message", finalMessage)
                },
        )

    fun readArtifactRecord(
        sessionId: String,
        artifactId: String,
    ): ArtifactRecord? {
        if (sessionId.isBlank() || artifactId.isBlank()) {
            return null
        }
        val file =
            synchronized(lock) {
                val dir = sessionDir(sessionId) ?: return null
                File(dir, "$artifactId.json").takeIf(File::exists) ?: return null
            }
        val json = JSONObject(file.readText())
        return ArtifactRecord(
            artifactId = json.optString("artifact_id", artifactId),
            type = json.optString("type"),
            summary = json.optString("summary"),
            path = file.absolutePath,
            createdAt = json.optLong("created_at", 0L),
        )
    }

    fun readArtifactPreviewLines(
        sessionId: String,
        artifactId: String,
    ): List<String> {
        if (sessionId.isBlank() || artifactId.isBlank()) {
            return emptyList()
        }
        val file =
            synchronized(lock) {
                val dir = sessionDir(sessionId) ?: return emptyList()
                File(dir, "$artifactId.json").takeIf(File::exists) ?: return emptyList()
            }
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyList()
        return ArtifactStorePreviewSupport.readPreviewLines(json.optString("type"), json)
    }

    fun readArtifactPayload(
        sessionId: String,
        artifactId: String,
    ): JSONObject? {
        if (sessionId.isBlank() || artifactId.isBlank()) return null
        val file =
            synchronized(lock) {
                val dir = sessionDir(sessionId) ?: return null
                File(dir, "$artifactId.json").takeIf(File::exists) ?: return null
            }
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    fun exportArtifacts(
        sessionId: String,
        limit: Int = 20,
    ): List<JSONObject> {
        if (sessionId.isBlank() || limit <= 0) return emptyList()
        return synchronized(lock) {
            val dir = sessionDir(sessionId) ?: return@synchronized emptyList()
            val indexFile = File(dir, INDEX_FILE)
            if (!indexFile.exists()) return@synchronized emptyList()
            indexFile.readLines()
                .asReversed()
                .mapNotNull { line ->
                    val index = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
                    val artifactId = index.optString("artifact_id")
                    readArtifactPayload(sessionId, artifactId)?.let { payload ->
                        JSONObject().apply {
                            put("session_id", sessionId)
                            put("turn", index.optInt("turn"))
                            put("artifact_id", artifactId)
                            put("type", index.optString("type"))
                            put("summary", index.optString("summary"))
                            put("payload", payload)
                        }
                    }
                }
                .take(limit)
        }
    }

    fun importArtifacts(
        sessionId: String,
        artifacts: List<JSONObject>,
    ) {
        if (sessionId.isBlank() || artifacts.isEmpty()) return
        synchronized(lock) {
            val dir = sessionDir(sessionId) ?: return
            if (!dir.exists()) {
                dir.mkdirs()
            }
            artifacts.forEach { item ->
                val artifactId = item.optString("artifact_id")
                val payload = item.optJSONObject("payload") ?: return@forEach
                if (artifactId.isBlank()) return@forEach
                val hash = payloadHash(payload)
                val existing = findExistingByPayloadHash(dir, item.optString("type"), hash)
                val effectiveArtifactId = existing?.artifactId ?: artifactId
                val file = File(dir, "$effectiveArtifactId.json")
                file.writeText(payload.toString(2))
                if (existing == null) {
                    appendIndexLine(
                        dir = dir,
                        record =
                            JSONObject().apply {
                                put("artifact_id", effectiveArtifactId)
                                put("type", item.optString("type"))
                                put("summary", item.optString("summary"))
                                put("turn", item.optInt("turn"))
                                put("created_at", payload.optLong("created_at"))
                                put("file_name", file.name)
                                put("payload_hash", hash)
                            },
                    )
                }
            }
        }
    }

    fun searchArtifacts(
        query: String,
        sessionId: String = "",
        limit: Int = 12,
        types: Set<String> = emptySet(),
    ): List<ArtifactSearchHit> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val needle = query.trim().lowercase()
        return synchronized(lock) {
            val context = AppRuntimeContext.get() ?: return@synchronized emptyList()
            val root = File(context.filesDir, ARTIFACT_DIR)
            if (!root.exists()) return@synchronized emptyList()
            root.listFiles()
                .orEmpty()
                .asSequence()
                .filter { sessionId.isBlank() || it.name == sessionId }
                .flatMap { dir ->
                    val sid = dir.name
                    val indexFile = File(dir, INDEX_FILE)
                    if (!indexFile.exists()) {
                        emptySequence()
                    } else {
                        indexFile.readLines().asSequence().mapNotNull { line ->
                            val index = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
                            val type = index.optString("type")
                            if (types.isNotEmpty() && type !in types) return@mapNotNull null
                            val artifactId = index.optString("artifact_id")
                            val payload = readArtifactPayload(sid, artifactId)?.toString().orEmpty()
                            val summary = index.optString("summary")
                            if (!summary.lowercase().contains(needle) && !payload.lowercase().contains(needle)) {
                                return@mapNotNull null
                            }
                            val record =
                                ArtifactRecord(
                                    artifactId = artifactId,
                                    type = type,
                                    summary = summary,
                                    path = File(dir, "$artifactId.json").absolutePath,
                                    createdAt = index.optLong("created_at"),
                                )
                            ArtifactSearchHit(
                                sessionId = sid,
                                turn = index.optInt("turn"),
                                record = record,
                                previewLines = readArtifactPreviewLines(sid, artifactId),
                            )
                        }
                    }
                }
                .sortedByDescending { it.record.createdAt }
                .take(limit)
                .toList()
        }
    }

    fun readLifecycleSnapshot(
        sessionId: String,
        policy: ArtifactRetentionPolicy = ArtifactRetentionPolicyStore.read(),
        beforeTurnInclusive: Int = Int.MAX_VALUE,
    ): ArtifactLifecycleSnapshot =
        synchronized(lock) {
            ArtifactStoreRetentionSupport.readLifecycleSnapshot(
                sessionId = sessionId,
                policy = policy,
                beforeTurnInclusive = beforeTurnInclusive,
                resolveSessionDir = ::sessionDir,
            )
        }

    fun garbageCollect(
        keepSessionIds: Set<String>,
    ): Int {
        return synchronized(lock) {
            val context = AppRuntimeContext.get() ?: return@synchronized 0
            val root = File(context.filesDir, ARTIFACT_DIR)
            if (!root.exists()) return@synchronized 0
            var deleted = 0
            root.listFiles()
                .orEmpty()
                .filter { it.isDirectory && it.name !in keepSessionIds }
                .forEach { dir ->
                    if (dir.deleteRecursively()) {
                        deleted += 1
                    }
                }
            deleted
        }
    }

    fun previewRetention(
        policy: ArtifactRetentionPolicy,
        keepSessionIds: Set<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): ArtifactRetentionReport =
        synchronized(lock) {
            ArtifactStoreRetentionSupport.sweepRetention(
                policy = policy,
                keepSessionIds = keepSessionIds,
                nowMs = nowMs,
                applyDeletes = false,
            ) {
                AppRuntimeContext.get()?.let { context ->
                    File(context.filesDir, ARTIFACT_DIR)
                }
            }
        }

    fun applyRetentionPolicy(
        policy: ArtifactRetentionPolicy,
        keepSessionIds: Set<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): ArtifactRetentionReport =
        synchronized(lock) {
            ArtifactStoreRetentionSupport.sweepRetention(
                policy = policy,
                keepSessionIds = keepSessionIds,
                nowMs = nowMs,
                applyDeletes = true,
            ) {
                AppRuntimeContext.get()?.let { context ->
                    File(context.filesDir, ARTIFACT_DIR)
                }
            }
        }

    private fun writeArtifact(
        sessionId: String,
        turn: Int,
        type: String,
        summary: String,
        payload: JSONObject,
    ): ArtifactRecord? {
        if (sessionId.isBlank()) {
            return null
        }
        val now = System.currentTimeMillis()
        val file =
            synchronized(lock) {
                val dir = sessionDir(sessionId) ?: return null
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                val hash = payloadHash(payload)
                val existing = findExistingByPayloadHash(dir, type, hash)
                val artifactId = existing?.artifactId ?: "${type}_${turn}_${UUID.randomUUID().toString().substring(0, 8)}"
                val artifactFile = File(dir, "$artifactId.json")
                artifactFile.writeText(
                    payload.apply {
                        put("artifact_id", artifactId)
                        put("type", type)
                        put("summary", summary)
                        put("created_at", now)
                        put("payload_hash", hash)
                    }.toString(2),
                )
                if (existing == null) {
                    appendIndexLine(
                        dir = dir,
                        record =
                            JSONObject().apply {
                                put("artifact_id", artifactId)
                                put("type", type)
                                put("summary", summary)
                                put("turn", turn)
                                put("created_at", now)
                                put("file_name", artifactFile.name)
                                put("payload_hash", hash)
                            },
                    )
                }
                artifactFile
            }
        val artifactId = runCatching { JSONObject(file.readText()).optString("artifact_id") }.getOrDefault("")
        return ArtifactRecord(
            artifactId = artifactId,
            type = type,
            summary = summary,
            path = file.absolutePath,
            createdAt = now,
        )
    }

    private fun appendIndexLine(
        dir: File,
        record: JSONObject,
    ) {
        val indexFile = File(dir, INDEX_FILE)
        indexFile.appendText(record.toString() + "\n")
    }

    private fun findExistingByPayloadHash(
        dir: File,
        type: String,
        payloadHash: String,
    ): ArtifactRecord? {
        if (payloadHash.isBlank()) return null
        val indexFile = File(dir, INDEX_FILE)
        if (!indexFile.exists()) return null
        return indexFile.readLines()
            .asReversed()
            .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
            .firstNotNullOfOrNull { json ->
                val existingHash = json.optString("payload_hash")
                if (json.optString("type") != type || existingHash != payloadHash) {
                    null
                } else {
                    val artifactId = json.optString("artifact_id")
                    ArtifactRecord(
                        artifactId = artifactId,
                        type = type,
                        summary = json.optString("summary"),
                        path = File(dir, json.optString("file_name", "$artifactId.json")).absolutePath,
                        createdAt = json.optLong("created_at"),
                    )
                }
            }
    }

    private fun payloadHash(
        payload: JSONObject,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toString().toByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun sessionDir(sessionId: String): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, ARTIFACT_DIR), sessionId)
    }

    private fun buildPlanningSummary(
        observation: ScreenObservation,
        taskPlanState: TaskPlanState?,
        activeSkills: List<SkillContext>,
    ): String =
        buildString {
            append("sig=").append(observation.signature)
            append(" page=").append(observation.pageState)
            taskPlanState?.currentStage?.takeIf { it.isNotBlank() }?.let { stage ->
                append(" stage=").append(stage)
            }
            if (activeSkills.isNotEmpty()) {
                append(" skills=").append(activeSkills.joinToString(",") { it.id })
            }
        }

    private fun buildTaskResultSummary(taskResult: TaskResultPayload): String =
        buildString {
            append("intent=").append(taskResult.intentType.ifBlank { "-" })
            append(" title=").append(taskResult.title.take(48).ifBlank { "-" })
            append(" summary=").append(taskResult.summary.take(96).ifBlank { "-" })
            if (taskResult.highlights.isNotEmpty()) {
                append(" highlights=").append(taskResult.highlights.joinToString(" | ").take(96))
            }
        }

    private fun buildTaskResultPreviewLines(json: JSONObject): List<String> {
        val taskResult = json.optJSONObject("task_result") ?: return emptyList()
        return listOfNotNull(
            taskResult.optString("title").takeIf { it.isNotBlank() }?.let { "title=$it" },
            taskResult.optString("summary").takeIf { it.isNotBlank() }?.let { "summary=${it.take(72)}" },
        )
    }

    private fun buildPlanningObservationPreviewLines(json: JSONObject): List<String> {
        val taskPlanState = json.optJSONObject("task_plan_state") ?: JSONObject()
        val activeSkills = json.optJSONArray("active_skills") ?: JSONArray()
        val observation = json.optJSONObject("observation") ?: JSONObject()
        val skillIds =
            buildList {
                for (index in 0 until activeSkills.length()) {
                    activeSkills.optJSONObject(index)
                        ?.optString("id")
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }
        return listOfNotNull(
            taskPlanState.optString("current_stage").takeIf { it.isNotBlank() }?.let { "stage=$it" },
            skillIds.takeIf { it.isNotEmpty() }?.joinToString(",")?.let { "skills=$it" },
            observation.optString("screen_summary").takeIf { it.isNotBlank() }?.let { "screen=${it.take(72)}" },
        )
    }

    private fun buildUiXmlSnapshotPreviewLines(json: JSONObject): List<String> =
        listOfNotNull(
            json.optString("signature").takeIf { it.isNotBlank() }?.let { "sig=$it" },
            json.optString("xml_snapshot").lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }?.let { "xml=${it.take(72)}" },
        )

    private fun buildPlanningScreenshotPreviewLines(json: JSONObject): List<String> =
        listOfNotNull(
            "size=${json.optInt("width", 0)}x${json.optInt("height", 0)}",
            json.optString("visual_summary").takeIf { it.isNotBlank() }?.let { "visual=${it.take(72)}" },
        )

    private fun buildExecutionVerificationPreviewLines(json: JSONObject): List<String> =
        listOfNotNull(
            "verified=${json.optBoolean("verified")} replan=${json.optBoolean("should_immediate_replan")}",
            json.optString("suggested_recovery_action").takeIf { it.isNotBlank() }?.let { "next=$it" },
            json.optString("final_message").takeIf { it.isNotBlank() }?.let { "message=${it.take(72)}" },
        )

    private fun buildPlannerDecisionPreviewLines(json: JSONObject): List<String> =
        listOfNotNull(
            json.optString("action").takeIf { it.isNotBlank() }?.let { "action=$it" },
            json.optString("reason").takeIf { it.isNotBlank() }?.let { "reason=${it.take(72)}" },
        )

    private fun buildExecutionTracePreviewLines(json: JSONObject): List<String> =
        listOfNotNull(
            "keepRunning=${json.optBoolean("keep_running")}",
            json.optString("suggested_recovery_action").takeIf { it.isNotBlank() }?.let { "next=$it" },
            json.optString("result").takeIf { it.isNotBlank() }?.let { "result=${it.take(72)}" },
        )

    private fun buildTurnFailurePreviewLines(json: JSONObject): List<String> =
        listOfNotNull(
            "keepRunning=${json.optBoolean("keep_running")}",
            json.optString("error").takeIf { it.isNotBlank() }?.let { "error=${it.take(72)}" },
        )

    private fun buildScreenshotSummary(
        observation: ScreenObservation,
        screenshot: ScreenshotPayload,
        visualContext: VisualPerceptionContext,
    ): String =
        buildString {
            append("sig=").append(observation.signature)
            append(" page=").append(observation.pageState)
            append(" size=").append(screenshot.width).append("x").append(screenshot.height)
            if (visualContext.summary.isNotBlank()) {
                append(" visual=").append(visualContext.summary.take(80))
            }
        }

    private fun buildObservationXmlSnapshot(observation: ScreenObservation): String =
        buildString {
            append("<screen package=\"").append(escapeXml(observation.packageName)).append("\" ")
            append("page_state=\"").append(escapeXml(observation.pageState)).append("\" ")
            append("signature=\"").append(escapeXml(observation.signature)).append("\">\n")
            observation.elements.forEach { element ->
                append("  <element")
                append(" id=\"").append(escapeXml(element.id)).append("\"")
                append(" text=\"").append(escapeXml(element.text)).append("\"")
                append(" view_id=\"").append(escapeXml(element.viewId)).append("\"")
                append(" class=\"").append(escapeXml(element.className)).append("\"")
                append(" bounds=\"").append(escapeXml(element.bounds)).append("\"")
                append(" clickable=\"").append(element.clickable).append("\"")
                append(" editable=\"").append(element.editable).append("\"")
                append(" scrollable=\"").append(element.scrollable).append("\"")
                append(" enabled=\"").append(element.enabled).append("\"")
                append(" focused=\"").append(element.focused).append("\"")
                append(" selected=\"").append(element.selected).append("\"")
                append(" />\n")
            }
            append("</screen>")
        }

    private fun escapeXml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun ScreenObservation.toJson(): JSONObject =
        JSONObject().apply {
            put("package_name", packageName)
            put("page_state", pageState)
            put("signature", signature)
            put("screen_summary", screenSummary)
            put("top_texts", JSONArray(topTexts))
            put("primary_editable_id", primaryEditableId.orEmpty())
            put("focused_element_id", focusedElementId.orEmpty())
            put("default_scrollable_id", defaultScrollableId.orEmpty())
            put("primary_interrupt_action_id", primaryInterruptActionId.orEmpty())
            put(
                "interruptive_hints",
                JSONArray().apply {
                    interruptiveHints.forEach { hint ->
                        put(
                            JSONObject().apply {
                                put("element_id", hint.elementId)
                                put("text", hint.text)
                                put("reason", hint.reason)
                            },
                        )
                    }
                },
            )
            put(
                "elements",
                JSONArray().apply {
                    elements.forEach { element ->
                        put(
                            JSONObject().apply {
                                put("id", element.id)
                                put("text", element.text)
                                put("view_id", element.viewId)
                                put("class_name", element.className)
                                put("bounds", element.bounds)
                                put("clickable", element.clickable)
                                put("editable", element.editable)
                                put("scrollable", element.scrollable)
                                put("enabled", element.enabled)
                                put("focused", element.focused)
                                put("selected", element.selected)
                            },
                        )
                    }
                },
            )
        }

    private fun TaskPlanState.toJson(): JSONObject =
        JSONObject().apply {
            put("plan_type", planType)
            put("current_stage", currentStage)
            put("stage_summary", stageSummary)
            put("next_objective", nextObjective)
            put("completion_signal", completionSignal)
            put("current_subgoal_id", currentSubgoalId)
            put("waiting_for_external", waitingForExternal)
            put("waiting_for_event", waitingForEvent)
            put("suspendable", suspendable)
            put("suspend_reason", suspendReason)
            put(
                "steps",
                JSONArray().apply {
                    steps.forEach { step ->
                        put(
                            JSONObject().apply {
                                put("id", step.id)
                                put("title", step.title)
                                put("status", step.status)
                                put("evidence", step.evidence)
                            },
                        )
                    }
                },
            )
            put(
                "resume_context",
                JSONObject().apply {
                    put("active", resumeContext.active)
                    put("source", resumeContext.source)
                    put("resume_event", resumeContext.resumeEvent)
                    put("resume_hint", resumeContext.resumeHint)
                    put("resumed_subgoal_id", resumeContext.resumedSubgoalId)
                    put("resume_attempt", resumeContext.resumeAttempt)
                    put("user_correction", resumeContext.userCorrection)
                },
            )
        }

    private fun SkillContext.toJson(): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("title", title)
            put("description", description)
            put("instructions", instructions)
            put("layer", layer.name)
            put("risk_level", riskLevel.name)
            put("required_tools", JSONArray(requiredTools))
            put("parameters", JSONArray(parameters))
        }

    private fun RecoveryDiagnosis.toJson(): JSONObject =
        JSONObject().apply {
            put("category", category.name)
            put("summary", summary)
            put("suggested_action", suggestedAction?.label.orEmpty())
        }

    private fun TaskResultPayload.toJson(): JSONObject =
        JSONObject().apply {
            put("intent_type", intentType)
            put("title", title)
            put("summary", summary)
            put("highlights", JSONArray(highlights))
            put(
                "fields",
                JSONArray().apply {
                    fields.forEach { field ->
                        put(
                            JSONObject().apply {
                                put("key", field.key)
                                put("label", field.label)
                                put("value", field.value)
                            },
                        )
                    }
                },
            )
        }
}
