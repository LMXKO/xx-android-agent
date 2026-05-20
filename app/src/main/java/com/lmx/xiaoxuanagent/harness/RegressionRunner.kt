package com.lmx.xiaoxuanagent.harness

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.ReplaySessionSnapshot
import com.lmx.xiaoxuanagent.runtime.ReplayStore
import com.lmx.xiaoxuanagent.runtime.SessionPlatformFacade
import com.lmx.xiaoxuanagent.runtime.SessionPlatformSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionReplayVerification
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class BenchmarkScenario(
    val id: String,
    val title: String,
    val suite: String = "core",
    val persona: String = "operator",
    val maturity: String = "ga",
    val profileId: String,
    val task: String,
    val intentType: String,
    val priority: Int,
    val tags: List<String> = emptyList(),
    val riskLevel: String = "medium",
    val requiredCapabilities: List<String> = emptyList(),
    val goldenKeywords: List<String> = emptyList(),
    val expectedStageHints: List<String> = emptyList(),
)

data class BenchmarkOracle(
    val expectedStatus: String = "COMPLETED",
    val expectedProfileId: String = "",
    val expectedIntentType: String = "",
    val requiredResultKeywords: List<String> = emptyList(),
    val maxTurns: Int = 0,
)

data class RegressionFixture(
    val scenarioId: String,
    val sessionId: String,
    val summary: String = "",
    val status: String = "",
    val oracle: BenchmarkOracle = BenchmarkOracle(),
    val source: String = "derived_fixture",
    val updatedAtMs: Long = 0L,
)

data class RegressionBacklogItem(
    val scenario: BenchmarkScenario,
    val reason: String,
    val urgencyScore: Int,
)

data class RegressionPlanItem(
    val scenario: BenchmarkScenario,
    val reason: String,
    val urgencyScore: Int,
    val matchedSessionId: String = "",
    val matchedSummary: String = "",
    val matchedStatus: String = "",
    val matchScore: Int = 0,
    val matchType: String = "",
    val oracleSummary: String = "",
)

data class RegressionExecutionResult(
    val scenarioId: String,
    val status: String,
    val summary: String,
    val sessionId: String = "",
    val mismatches: List<String> = emptyList(),
)

data class RegressionRunSnapshot(
    val generatedAtMs: Long = 0L,
    val plannedItems: List<RegressionPlanItem> = emptyList(),
    val results: List<RegressionExecutionResult> = emptyList(),
)

object RegressionRunner {
    private const val REGRESSION_DIR = "harness"
    private const val REGRESSION_FILE = "regression_runner.json"
    private const val FIXTURE_FILE = "benchmark_fixtures.json"
    private val lock = Any()

    fun scenarioCatalog(): List<BenchmarkScenario> =
        HarnessScenarioRegistry.all().flatMap { scenario ->
            val tasks = scenario.canonicalTasks.ifEmpty { listOf(scenario.title) }
            tasks.mapIndexed { index, task ->
                BenchmarkScenario(
                    id = "${scenario.id}_$index",
                    title = scenario.title,
                    suite = scenario.suite,
                    persona = scenario.persona,
                    maturity = scenario.maturity,
                    profileId = scenario.expectedProfileId.orEmpty(),
                    task = task,
                    intentType = scenario.intentType.ifBlank { scenario.id },
                    priority = scenario.priority,
                    tags = scenario.tags,
                    riskLevel = scenario.riskLevel,
                    requiredCapabilities = scenario.requiredCapabilities,
                    goldenKeywords = scenario.goldenKeywords,
                    expectedStageHints = scenario.expectedStageHints,
                )
            }
        }

    fun plannedRuns(
        aggregate: HarnessStore.AggregateSnapshot,
        limit: Int = 6,
    ): List<RegressionBacklogItem> {
        val catalog = scenarioCatalog()
        val maturitySnapshot = SuperAssistantMaturityGateStore.evaluate(aggregate = aggregate)
        val intentsByName = aggregate.intents.associateBy { it.name }
        val suitesByName = aggregate.suites.associateBy { it.name }
        val personasByName = aggregate.personas.associateBy { it.name }
        val maturitiesByName = aggregate.maturities.associateBy { it.name }
        return catalog
            .map { scenario ->
                val intentBucket = intentsByName[scenario.intentType]
                val suiteBucket = suitesByName[scenario.suite]
                val personaBucket = personasByName[scenario.persona]
                val maturityBucket = maturitiesByName[scenario.maturity]
                val successPenalty =
                    when {
                        intentBucket == null -> 6
                        intentBucket.successRate < 0.5 -> 10
                        intentBucket.successRate < 0.8 -> 5
                        else -> 0
                    }
                val runPenalty =
                    when (intentBucket?.runCount ?: 0) {
                        0 -> 5
                        1 -> 3
                        else -> 0
                    }
                val suitePenalty =
                    when {
                        suiteBucket == null -> 4
                        suiteBucket.runCount < 2 -> 2
                        else -> 0
                    }
                val personaPenalty =
                    when {
                        personaBucket == null -> 3
                        personaBucket.runCount < 2 -> 1
                        else -> 0
                    }
                val maturityPenalty =
                    when {
                        maturityBucket == null -> 3
                        scenario.maturity.equals("beta", ignoreCase = true) &&
                            (maturityBucket.runCount < 2 || maturityBucket.successRate < 0.75) ->
                            3
                        maturityBucket.successRate < 0.7 -> 2
                        else -> 0
                    }
                val screenAutomation = screenAutomationUrgency(scenario)
                val superAssistantMaturity = SuperAssistantMaturityGateStore.urgencyForScenario(scenario, maturitySnapshot)
                val urgency =
                    scenario.priority +
                        successPenalty +
                        runPenalty +
                        suitePenalty +
                        personaPenalty +
                        maturityPenalty +
                        screenAutomation.penalty +
                        superAssistantMaturity.penalty
                RegressionBacklogItem(
                    scenario = scenario,
                    reason =
                        when {
                            intentBucket == null -> "尚无历史回归样本"
                            intentBucket.successRate < 0.5 -> "当前完成率偏低，需要优先回归"
                            suiteBucket == null || personaBucket == null -> "suite/persona 覆盖不足，需要补 benchmark"
                            maturityBucket == null -> "maturity 维度尚未形成稳定样本"
                            intentBucket.runCount < 2 -> "样本量不足，需要补 benchmark"
                            else -> "维持主线稳定性"
                        } +
                            screenAutomation.reason.takeIf { it.isNotBlank() }?.let { " | $it" }.orEmpty() +
                            superAssistantMaturity.reason.takeIf { it.isNotBlank() }?.let { " | $it" }.orEmpty(),
                    urgencyScore = urgency,
                )
            }.sortedByDescending { it.urgencyScore }
            .take(limit)
    }

    fun buildPlan(
        aggregate: HarnessStore.AggregateSnapshot,
        limit: Int = 4,
    ): RegressionRunSnapshot {
        val fixtures = refreshDerivedFixtures(limit = limit * 6).associateBy { it.scenarioId }
        val recentSessions = ReplayStore.readRecentSessionSnapshots(limit = (limit.coerceAtLeast(1) * 6).coerceAtMost(48))
        val plannedItems =
            plannedRuns(aggregate, limit).map { backlog ->
                val fixture = fixtures[backlog.scenario.id]
                if (fixture != null) {
                    RegressionPlanItem(
                        scenario = backlog.scenario,
                        reason = "${backlog.reason} | fixture_oracle",
                        urgencyScore = backlog.urgencyScore,
                        matchedSessionId = fixture.sessionId,
                        matchedSummary = fixture.summary,
                        matchedStatus = fixture.status,
                        matchScore = 200,
                        matchType = "fixture",
                        oracleSummary = fixture.oracle.summaryLine(),
                    )
                } else {
                    val matched =
                        recentSessions.maxByOrNull { session ->
                            matchScore(backlog.scenario, session)
                        }
                    val score = matched?.let { matchScore(backlog.scenario, it) } ?: 0
                    RegressionPlanItem(
                        scenario = backlog.scenario,
                        reason = backlog.reason,
                        urgencyScore = backlog.urgencyScore,
                        matchedSessionId = matched?.sessionId.orEmpty().takeIf { score > 0 }.orEmpty(),
                        matchedSummary = matched?.summaryLine().orEmpty().takeIf { score > 0 }.orEmpty(),
                        matchedStatus = matched?.status.orEmpty().takeIf { score > 0 }.orEmpty(),
                        matchScore = score,
                        matchType = if (score > 0) "recent_replay" else "",
                        oracleSummary = "",
                    )
                }
            }
        val latest = readLatestSnapshot()
        return RegressionRunSnapshot(
            generatedAtMs = System.currentTimeMillis(),
            plannedItems = plannedItems,
            results = latest?.results.orEmpty(),
        )
    }

    fun dashboardLines(
        aggregate: HarnessStore.AggregateSnapshot,
        limit: Int = 4,
    ): List<String> {
        val snapshot = buildPlan(aggregate, limit)
        val planned =
            snapshot.plannedItems.take(limit).map { item ->
                buildString {
                    append("regression | ").append(item.scenario.title)
                    append(" | ").append(item.reason)
                    append(" | score=").append(item.urgencyScore)
                    append(" | matched=").append(item.matchedSessionId.ifBlank { "-" })
                    item.matchType.takeIf { it.isNotBlank() }?.let { append(" | type=").append(it) }
                    item.oracleSummary.takeIf { it.isNotBlank() }?.let { append(" | oracle=").append(it.take(64)) }
                    if (item.matchScore > 0) {
                        append(" | match_score=").append(item.matchScore)
                    }
                }
            }
        val recentRun =
            snapshot.results.take(limit).map { result ->
                buildString {
                    append("last_run | ").append(result.scenarioId)
                    append(" | ").append(result.status)
                    append(" | ").append(result.summary)
                }
            }
        val maturityLines = SuperAssistantMaturityGateStore.dashboardLines(limit = 3)
        return (maturityLines + screenAutomationDashboardLines(limit = 2) + planned + recentRun)
            .ifEmpty { listOf("暂无回归执行计划。") }
    }

    fun readLatestSnapshot(): RegressionRunSnapshot? =
        synchronized(lock) {
            val file = snapshotFile() ?: return@synchronized null
            if (!file.exists()) return@synchronized null
            runCatching { JSONObject(file.readText()).toSnapshot() }.getOrNull()
        }

    fun runPlan(
        aggregate: HarnessStore.AggregateSnapshot,
        limit: Int = 4,
    ): RegressionRunSnapshot {
        val fixtures = refreshDerivedFixtures(limit = limit * 6).associateBy { it.scenarioId }
        val planned = buildPlan(aggregate, limit).plannedItems
        val results =
            planned.map { item ->
                val sessionId = item.matchedSessionId
                when {
                    sessionId.isBlank() ->
                        RegressionExecutionResult(
                            scenarioId = item.scenario.id,
                            status = "unmatched",
                            summary = "未找到 fixture 或可复用 replay session",
                        )

                    item.matchType == "fixture" -> {
                        val fixture = fixtures[item.scenario.id]
                        if (fixture == null) {
                            RegressionExecutionResult(
                                scenarioId = item.scenario.id,
                                sessionId = sessionId,
                                status = "fixture_missing",
                                summary = "fixture 缺失，无法执行 oracle benchmark",
                            )
                        } else {
                            verificationToFixtureResult(
                                scenarioId = item.scenario.id,
                                fixture = fixture,
                                verification = SessionPlatformFacade.runDeterministicReplay(sessionId),
                                snapshot = SessionPlatformFacade.readSessionSnapshot(sessionId),
                            )
                        }
                    }

                    else -> verificationToReplayResult(item.scenario.id, sessionId, SessionPlatformFacade.runDeterministicReplay(sessionId))
                }
            }
        val snapshot =
            RegressionRunSnapshot(
                generatedAtMs = System.currentTimeMillis(),
                plannedItems = planned,
                results = results,
            )
        persist(snapshot)
        return snapshot
    }

    private fun refreshDerivedFixtures(
        limit: Int,
    ): List<RegressionFixture> {
        val persisted = readFixtures().associateBy { it.scenarioId }
        val recentSessions = ReplayStore.readRecentSessionSnapshots(limit = limit.coerceAtLeast(12).coerceAtMost(64))
        val derived =
            scenarioCatalog().mapNotNull { scenario ->
                recentSessions
                    .asSequence()
                    .filter { session ->
                        session.status == "COMPLETED" &&
                            matchScore(scenario, session) > 0
                    }.maxByOrNull { session ->
                        matchScore(scenario, session) * 100 + session.turnCount
                    }?.let { session ->
                        buildFixture(scenario, session)
                    }
            }
        val merged =
            (persisted.values + derived)
                .groupBy { it.scenarioId }
                .mapNotNull { (_, fixtures) ->
                    fixtures.maxByOrNull { fixture -> fixture.updatedAtMs }
                }.sortedByDescending { it.updatedAtMs }
        persistFixtures(merged)
        return merged
    }

    private fun buildFixture(
        scenario: BenchmarkScenario,
        session: ReplaySessionSnapshot,
    ): RegressionFixture =
        RegressionFixture(
            scenarioId = scenario.id,
            sessionId = session.sessionId,
            summary = session.summaryLine(),
            status = session.status,
            oracle =
                BenchmarkOracle(
                    expectedStatus = session.status.ifBlank { "COMPLETED" },
                    expectedProfileId = session.profileId.ifBlank { scenario.profileId },
                    expectedIntentType =
                        session.finalTaskResult?.intentType
                            .orEmpty()
                            .ifBlank { session.taskIntent.ifBlank { scenario.intentType } },
                    requiredResultKeywords =
                        (scenario.goldenKeywords +
                            extractKeywords(session.finalTaskResult?.title.orEmpty()) +
                            extractKeywords(session.finalTaskResult?.summary.orEmpty()) +
                            extractKeywords(session.finalMessage))
                            .distinct()
                            .take(4),
                    maxTurns = (session.turnCount + 2).coerceAtLeast(1),
                ),
            source = "derived_from_completed_replay",
            updatedAtMs = session.updatedAt,
        )

    private fun verificationToReplayResult(
        scenarioId: String,
        sessionId: String,
        verification: SessionReplayVerification,
    ): RegressionExecutionResult =
        when {
            !verification.replayable ->
                RegressionExecutionResult(
                    scenarioId = scenarioId,
                    sessionId = sessionId,
                    status = "not_replayable",
                    summary = verification.mismatches.firstOrNull() ?: "deterministic replay 不可用",
                    mismatches = verification.mismatches,
                )

            verification.matches ->
                RegressionExecutionResult(
                    scenarioId = scenarioId,
                    sessionId = sessionId,
                    status = "passed",
                    summary = "replay=${verification.commandCount} expected=${verification.expectedStatus}",
                )

            else ->
                RegressionExecutionResult(
                    scenarioId = scenarioId,
                    sessionId = sessionId,
                    status = "failed",
                    summary =
                        buildString {
                            append("expected=").append(verification.expectedStatus)
                            append(" replayed=").append(verification.replayedStatus)
                            verification.mismatches.firstOrNull()?.let {
                                append(" | ").append(it)
                            }
                        },
                    mismatches = verification.mismatches,
                )
        }

    private fun verificationToFixtureResult(
        scenarioId: String,
        fixture: RegressionFixture,
        verification: SessionReplayVerification,
        snapshot: SessionPlatformSnapshot,
    ): RegressionExecutionResult {
        val oracleMismatches = mutableListOf<String>()
        val oracle = fixture.oracle
        if (!verification.replayable) {
            oracleMismatches += verification.mismatches
        } else {
            if (oracle.expectedStatus.isNotBlank() && verification.replayedStatus != oracle.expectedStatus) {
                oracleMismatches += "oracle_status ${verification.replayedStatus} != ${oracle.expectedStatus}"
            }
            if (oracle.expectedProfileId.isNotBlank() && snapshot.state.session.profileId != oracle.expectedProfileId) {
                oracleMismatches += "oracle_profile ${snapshot.state.session.profileId} != ${oracle.expectedProfileId}"
            }
            val resolvedIntentType =
                snapshot.state.resultSnapshot?.intentType
                    .orEmpty()
                    .ifBlank { snapshot.sessionSnapshot?.finalTaskResult?.intentType.orEmpty() }
                    .ifBlank { snapshot.sessionSnapshot?.taskIntent.orEmpty() }
            if (oracle.expectedIntentType.isNotBlank() && resolvedIntentType != oracle.expectedIntentType) {
                oracleMismatches += "oracle_intent ${resolvedIntentType.ifBlank { "-" }} != ${oracle.expectedIntentType}"
            }
            if (oracle.maxTurns > 0 && snapshot.state.session.turns > oracle.maxTurns) {
                oracleMismatches += "oracle_turns ${snapshot.state.session.turns} > ${oracle.maxTurns}"
            }
            val resultCorpus =
                listOf(
                    snapshot.state.resultSnapshot?.title.orEmpty(),
                    snapshot.state.resultSnapshot?.summary.orEmpty(),
                    snapshot.state.resultSnapshot?.lastResult.orEmpty(),
                    snapshot.sessionSnapshot?.finalTaskResult?.title.orEmpty(),
                    snapshot.sessionSnapshot?.finalTaskResult?.summary.orEmpty(),
                    snapshot.sessionSnapshot?.finalMessage.orEmpty(),
                ).joinToString(" ")
            oracle.requiredResultKeywords.forEach { keyword ->
                if (keyword.isNotBlank() && !resultCorpus.contains(keyword, ignoreCase = true)) {
                    oracleMismatches += "oracle_keyword_missing:$keyword"
                }
            }
        }
        val mismatches = verification.mismatches + oracleMismatches
        return if (mismatches.isEmpty()) {
            RegressionExecutionResult(
                scenarioId = scenarioId,
                sessionId = fixture.sessionId,
                status = "passed_fixture",
                summary =
                    "fixture=${fixture.source} replay=${verification.commandCount} oracle=${oracle.summaryLine()}",
            )
        } else {
            RegressionExecutionResult(
                scenarioId = scenarioId,
                sessionId = fixture.sessionId,
                status = if (verification.replayable) "failed_fixture" else "not_replayable",
                summary =
                    buildString {
                        append("fixture=").append(fixture.source)
                        append(" | oracle=").append(oracle.summaryLine())
                        mismatches.firstOrNull()?.let { append(" | ").append(it) }
                    },
                mismatches = mismatches,
            )
        }
    }

    private fun BenchmarkOracle.summaryLine(): String =
        buildString {
            append(expectedStatus.ifBlank { "-" })
            if (expectedIntentType.isNotBlank()) append(":").append(expectedIntentType)
            if (requiredResultKeywords.isNotEmpty()) append(":kw=").append(requiredResultKeywords.joinToString(","))
            if (maxTurns > 0) append(":turn<=").append(maxTurns)
        }

    private fun matchScore(
        scenario: BenchmarkScenario,
        session: ReplaySessionSnapshot,
    ): Int {
        var score = 0
        if (scenario.intentType.isNotBlank() && scenario.intentType == session.taskIntent) {
            score += 80
        }
        if (scenario.profileId.isNotBlank() && scenario.profileId == session.profileId) {
            score += 24
        }
        score += keywordOverlapScore(scenario.task, session.task)
        score += keywordOverlapScore(scenario.suite, session.task)
        score += keywordOverlapScore(scenario.persona, session.summaryLine())
        score += keywordOverlapScore(scenario.tags.joinToString(" "), session.task)
        score += keywordOverlapScore(scenario.goldenKeywords.joinToString(" "), session.finalMessage)
        score += keywordOverlapScore(scenario.goldenKeywords.joinToString(" "), session.finalTaskResult?.summary.orEmpty())
        if (scenario.requiredCapabilities.any { capability ->
                capability.contains("resume", ignoreCase = true) && session.resume.active
            }) {
            score += 18
        }
        if (scenario.expectedStageHints.any { hint ->
                session.latestTurn?.planStage?.contains(hint, ignoreCase = true) == true ||
                    session.recentTurns.any { it.planStage.contains(hint, ignoreCase = true) }
            }) {
            score += 12
        }
        if (session.finalTaskResult?.intentType == scenario.intentType) {
            score += 20
        }
        if (session.status == "COMPLETED") {
            score += 8
        }
        if (scenario.maturity.equals("ga", ignoreCase = true) && session.status == "COMPLETED") {
            score += 4
        }
        if (scenario.riskLevel.equals("high", ignoreCase = true) && session.pendingSafetyConfirmation.isNotBlank()) {
            score += 10
        }
        return score
    }

    private fun keywordOverlapScore(
        left: String,
        right: String,
    ): Int {
        if (left.isBlank() || right.isBlank()) return 0
        val leftTokens = extractKeywords(left)
        val rightTokens = extractKeywords(right)
        return leftTokens.intersect(rightTokens).size * 6
    }

    private fun extractKeywords(
        raw: String,
    ): List<String> =
        raw
            .split(" ", "\n", "\t", ",", "，", "。", "：", ":", "|", "/", "-", "_")
            .mapNotNull { token ->
                token.trim().takeIf { it.length >= 2 }
            }.distinct()

    private fun persist(
        snapshot: RegressionRunSnapshot,
    ) {
        synchronized(lock) {
            val file = snapshotFile() ?: return
            file.parentFile?.mkdirs()
            file.writeText(snapshot.toJson().toString(2))
        }
    }

    private fun snapshotFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, REGRESSION_DIR), REGRESSION_FILE)
    }

    private fun fixtureFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, REGRESSION_DIR), FIXTURE_FILE)
    }

    private fun readFixtures(): List<RegressionFixture> =
        synchronized(lock) {
            val file = fixtureFile() ?: return@synchronized emptyList()
            if (!file.exists()) return@synchronized emptyList()
            runCatching { JSONObject(file.readText()).optJSONArray("fixtures").toFixtures() }.getOrDefault(emptyList())
        }

    private fun persistFixtures(
        fixtures: List<RegressionFixture>,
    ) {
        synchronized(lock) {
            val file = fixtureFile() ?: return
            file.parentFile?.mkdirs()
            file.writeText(
                JSONObject().apply {
                    put(
                        "fixtures",
                        JSONArray().apply {
                            fixtures.forEach { fixture ->
                                put(fixture.toJson())
                            }
                        },
                    )
                }.toString(2),
            )
        }
    }

    private fun RegressionRunSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("generated_at_ms", generatedAtMs)
            put(
                "planned_items",
                JSONArray().apply {
                    plannedItems.forEach { item ->
                        put(
                            JSONObject().apply {
                                put("scenario_id", item.scenario.id)
                                put("scenario_title", item.scenario.title)
                                put("profile_id", item.scenario.profileId)
                                put("task", item.scenario.task)
                                put("intent_type", item.scenario.intentType)
                                put("priority", item.scenario.priority)
                                put("tags", JSONArray(item.scenario.tags))
                                put("risk_level", item.scenario.riskLevel)
                                put("required_capabilities", JSONArray(item.scenario.requiredCapabilities))
                                put("golden_keywords", JSONArray(item.scenario.goldenKeywords))
                                put("expected_stage_hints", JSONArray(item.scenario.expectedStageHints))
                                put("reason", item.reason)
                                put("urgency_score", item.urgencyScore)
                                put("matched_session_id", item.matchedSessionId)
                                put("matched_summary", item.matchedSummary)
                                put("matched_status", item.matchedStatus)
                                put("match_score", item.matchScore)
                                put("match_type", item.matchType)
                                put("oracle_summary", item.oracleSummary)
                            },
                        )
                    }
                },
            )
            put(
                "results",
                JSONArray().apply {
                    results.forEach { result ->
                        put(
                            JSONObject().apply {
                                put("scenario_id", result.scenarioId)
                                put("status", result.status)
                                put("summary", result.summary)
                                put("session_id", result.sessionId)
                                put("mismatches", JSONArray(result.mismatches))
                            },
                        )
                    }
                },
            )
        }

    private fun RegressionFixture.toJson(): JSONObject =
        JSONObject().apply {
            put("scenario_id", scenarioId)
            put("session_id", sessionId)
            put("summary", summary)
            put("status", status)
            put("source", source)
            put("updated_at_ms", updatedAtMs)
            put(
                "oracle",
                JSONObject().apply {
                    put("expected_status", oracle.expectedStatus)
                    put("expected_profile_id", oracle.expectedProfileId)
                    put("expected_intent_type", oracle.expectedIntentType)
                    put("required_result_keywords", JSONArray(oracle.requiredResultKeywords))
                    put("max_turns", oracle.maxTurns)
                },
            )
        }

    private fun JSONObject.toSnapshot(): RegressionRunSnapshot =
        RegressionRunSnapshot(
            generatedAtMs = optLong("generated_at_ms"),
            plannedItems = optJSONArray("planned_items").toPlanItems(),
            results = optJSONArray("results").toExecutionResults(),
        )

    private fun JSONArray?.toPlanItems(): List<RegressionPlanItem> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(
                    RegressionPlanItem(
                        scenario =
                            BenchmarkScenario(
                                id = item.optString("scenario_id"),
                                title = item.optString("scenario_title"),
                                profileId = item.optString("profile_id"),
                                task = item.optString("task"),
                                intentType = item.optString("intent_type"),
                                priority = item.optInt("priority"),
                                tags = item.optJSONArray("tags").toStringList(),
                                riskLevel = item.optString("risk_level", "medium"),
                                requiredCapabilities = item.optJSONArray("required_capabilities").toStringList(),
                                goldenKeywords = item.optJSONArray("golden_keywords").toStringList(),
                                expectedStageHints = item.optJSONArray("expected_stage_hints").toStringList(),
                            ),
                        reason = item.optString("reason"),
                        urgencyScore = item.optInt("urgency_score"),
                        matchedSessionId = item.optString("matched_session_id"),
                        matchedSummary = item.optString("matched_summary"),
                        matchedStatus = item.optString("matched_status"),
                        matchScore = item.optInt("match_score"),
                        matchType = item.optString("match_type"),
                        oracleSummary = item.optString("oracle_summary"),
                    ),
                )
            }
        }
    }

    private fun JSONArray?.toFixtures(): List<RegressionFixture> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val oracleJson = item.optJSONObject("oracle") ?: JSONObject()
                add(
                    RegressionFixture(
                        scenarioId = item.optString("scenario_id"),
                        sessionId = item.optString("session_id"),
                        summary = item.optString("summary"),
                        status = item.optString("status"),
                        source = item.optString("source").ifBlank { "derived_fixture" },
                        updatedAtMs = item.optLong("updated_at_ms"),
                        oracle =
                            BenchmarkOracle(
                                expectedStatus = oracleJson.optString("expected_status", "COMPLETED"),
                                expectedProfileId = oracleJson.optString("expected_profile_id"),
                                expectedIntentType = oracleJson.optString("expected_intent_type"),
                                requiredResultKeywords = oracleJson.optJSONArray("required_result_keywords").toStringList(),
                                maxTurns = oracleJson.optInt("max_turns"),
                            ),
                    ),
                )
            }
        }
    }

    private fun JSONArray?.toExecutionResults(): List<RegressionExecutionResult> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(
                    RegressionExecutionResult(
                        scenarioId = item.optString("scenario_id"),
                        status = item.optString("status"),
                        summary = item.optString("summary"),
                        sessionId = item.optString("session_id"),
                        mismatches = item.optJSONArray("mismatches").toStringList(),
                    ),
                )
            }
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }
}
