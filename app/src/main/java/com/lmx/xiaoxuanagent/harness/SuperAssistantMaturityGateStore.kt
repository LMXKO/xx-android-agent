package com.lmx.xiaoxuanagent.harness

import com.lmx.xiaoxuanagent.agent.AgentActionToolType
import com.lmx.xiaoxuanagent.agent.AgentToolCatalog
import com.lmx.xiaoxuanagent.agent.ConnectedAppCatalog
import com.lmx.xiaoxuanagent.agent.ConnectedAppExecutionReceiptStore
import com.lmx.xiaoxuanagent.runtime.PlatformTraceSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionGroundingHealthSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionPermissionProductSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionPlatformSnapshot
import kotlin.math.roundToInt

data class SuperAssistantEntrySurfaceSignal(
    val enabledSurfaceCount: Int = 0,
    val readySurfaceCount: Int = 0,
    val blockedSurfaceCount: Int = 0,
    val voiceReady: Boolean = false,
    val recentEntryCount: Int = 0,
    val pendingActionCount: Int = 0,
    val summary: String = "",
)

data class SuperAssistantMaturitySignal(
    val domain: String = "",
    val title: String = "",
    val status: String = "attention",
    val score: Int = 0,
    val summary: String = "",
    val detailLines: List<String> = emptyList(),
    val recommendedCommand: String = "",
)

data class SuperAssistantMaturitySnapshot(
    val status: String = "unknown",
    val score: Int = 0,
    val passedCount: Int = 0,
    val attentionCount: Int = 0,
    val blockedCount: Int = 0,
    val summary: String = "",
    val lines: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val signals: List<SuperAssistantMaturitySignal> = emptyList(),
)

object SuperAssistantMaturityGateStore {
    fun evaluate(
        profileId: String = "",
        packageName: String = "",
        focusSessionId: String = "",
        aggregate: HarnessStore.AggregateSnapshot? = HarnessStore.readAggregateSnapshot(),
        screenAutomation: ScreenAutomationCapabilitySnapshot? =
            profileId.takeIf { it.isNotBlank() }?.let {
                ScreenAutomationCapabilityStore.snapshot(profileId = it, packageName = packageName)
            },
        releaseGate: ScreenAutomationReleaseGateSnapshot? =
            profileId.takeIf { it.isNotBlank() }?.let {
                ScreenAutomationReleaseGateStore.snapshot(profileId = it, packageName = packageName)
            },
        focusPlatformSnapshot: SessionPlatformSnapshot? = null,
        traceSnapshot: PlatformTraceSnapshot = PlatformTraceSnapshot(),
        groundingHealth: SessionGroundingHealthSnapshot = SessionGroundingHealthSnapshot(),
        permissionProduct: SessionPermissionProductSnapshot = SessionPermissionProductSnapshot(),
        entrySurfaceSignal: SuperAssistantEntrySurfaceSignal = SuperAssistantEntrySurfaceSignal(),
        connectedReceiptLines: List<String> = ConnectedAppExecutionReceiptStore.lines(limit = 4),
    ): SuperAssistantMaturitySnapshot {
        val signals =
            listOf(
                visualSignal(screenAutomation, focusPlatformSnapshot, traceSnapshot, groundingHealth),
                connectorFirstSignal(profileId, packageName, connectedReceiptLines),
                resumeSignal(aggregate, focusPlatformSnapshot),
                permissionSignal(permissionProduct),
                entrySignal(entrySurfaceSignal),
                regressionSignal(aggregate, releaseGate),
            )
        val passed = signals.count { it.status == STATUS_STRONG }
        val blocked = signals.count { it.status == STATUS_BLOCKED }
        val attention = signals.size - passed - blocked
        val status =
            when {
                blocked > 0 -> "red"
                attention > 0 -> "yellow"
                else -> "green"
            }
        val score = signals.map { it.score }.average().takeIf { !it.isNaN() }?.roundToInt() ?: 0
        val header =
            "super_assistant | status=$status | score=$score | passed=$passed | attention=$attention | blocked=$blocked"
        val signalLines =
            signals.map { signal ->
                "maturity.${signal.domain} | ${signal.status} | score=${signal.score} | ${signal.summary.ifBlank { "-" }}"
            }
        val recommendedCommands =
            signals
                .mapNotNull { it.recommendedCommand.takeIf(String::isNotBlank) }
                .distinct()
        return SuperAssistantMaturitySnapshot(
            status = status,
            score = score,
            passedCount = passed,
            attentionCount = attention,
            blockedCount = blocked,
            summary =
                buildString {
                    append("score=").append(score)
                    append(" status=").append(status)
                    append(" domains=").append(passed).append('/').append(signals.size)
                    if (blocked > 0) append(" blocked=").append(blocked)
                    if (attention > 0) append(" attention=").append(attention)
                    focusSessionId.takeIf { it.isNotBlank() }?.let { append(" focus=").append(it) }
                },
            lines = listOf(header) + signalLines,
            recommendedCommands = recommendedCommands,
            signals = signals,
        )
    }

    fun dashboardLines(
        snapshot: SuperAssistantMaturitySnapshot = evaluate(),
        limit: Int = 4,
    ): List<String> =
        buildList {
            if (snapshot.lines.isNotEmpty()) {
                add(snapshot.lines.first())
                addAll(snapshot.lines.drop(1).take(limit.coerceAtLeast(1)))
            }
        }.ifEmpty { listOf("super_assistant | 暂无成熟度样本") }

    internal fun urgencyForScenario(
        scenario: BenchmarkScenario,
        snapshot: SuperAssistantMaturitySnapshot,
    ): ScreenAutomationUrgency {
        val domains = domainsForScenario(scenario)
        val matchedSignals = snapshot.signals.filter { it.domain in domains }
        if (matchedSignals.isEmpty()) return ScreenAutomationUrgency()
        val penalty =
            matchedSignals.fold(0) { total, signal ->
                total +
                    when (signal.status) {
                        STATUS_BLOCKED -> 6
                        STATUS_ATTENTION -> 3
                        else -> 0
                    }
            }
        if (penalty == 0) return ScreenAutomationUrgency()
        return ScreenAutomationUrgency(
            penalty = penalty,
            reason =
                "super_assistant=" +
                    matchedSignals.joinToString(",") { "${it.domain}:${it.status}/${it.score}" },
        )
    }

    private fun visualSignal(
        screenAutomation: ScreenAutomationCapabilitySnapshot?,
        focusPlatformSnapshot: SessionPlatformSnapshot?,
        traceSnapshot: PlatformTraceSnapshot,
        groundingHealth: SessionGroundingHealthSnapshot,
    ): SuperAssistantMaturitySignal {
        val visualTrace =
            (traceSnapshot.categoryCounts + traceSnapshot.recentLines + traceSnapshot.lines)
                .any { line -> VISUAL_TOKENS.any { line.contains(it, ignoreCase = true) } }
        val visualArtifact =
            focusPlatformSnapshot
                ?.bridgeSnapshot
                ?.recentArtifacts
                .orEmpty()
                .any { artifact ->
                    artifact.type.contains("screenshot", ignoreCase = true) ||
                        artifact.summary.contains("visual", ignoreCase = true)
                }
        val visualGrounding =
            groundingHealth.bucketSummary.any { bucket ->
                VISUAL_TOKENS.any { bucket.contains(it, ignoreCase = true) }
            }
        val failureRate =
            groundingHealth.failureCount.toDouble() / groundingHealth.totalCount.coerceAtLeast(1).toDouble()
        val screenScore =
            when (screenAutomation?.confidenceBand) {
                "strong" -> 24
                "monitor" -> 15
                "new" -> 8
                "weak" -> -15
                else -> 0
            }
        val score =
            clampScore(
                20 +
                    screenScore +
                    (if (visualTrace) 18 else 0) +
                    (if (visualArtifact) 18 else 0) +
                    (if (visualGrounding) 18 else 0) +
                    (if (groundingHealth.totalCount >= 3 && failureRate <= 0.25) 14 else 0),
            )
        val status =
            when {
                screenAutomation?.confidenceBand == "weak" -> STATUS_BLOCKED
                groundingHealth.totalCount >= 3 && failureRate > 0.5 -> STATUS_BLOCKED
                score >= 80 -> STATUS_STRONG
                score >= 45 -> STATUS_ATTENTION
                else -> STATUS_BLOCKED
            }
        return SuperAssistantMaturitySignal(
            domain = "visual",
            title = "视觉与 grounding",
            status = status,
            score = score,
            summary =
                buildString {
                    append("screen=").append(screenAutomation?.confidenceBand ?: "unknown")
                    append(" visual_trace=").append(visualTrace)
                    append(" artifact=").append(visualArtifact)
                    append(" grounding=").append(groundingHealth.totalCount)
                    append(" fail=").append(groundingHealth.failureCount)
                },
            detailLines =
                listOfNotNull(
                    screenAutomation?.note?.takeIf { it.isNotBlank() },
                    groundingHealth.summary.takeIf { it.isNotBlank() },
                    traceSnapshot.categorySummary.takeIf { it.isNotBlank() },
                ),
            recommendedCommand = focusPlatformSnapshot?.sessionId?.takeIf { it.isNotBlank() }?.let { "/grounding-health --session-id $it" }.orEmpty(),
        )
    }

    private fun connectorFirstSignal(
        profileId: String,
        packageName: String,
        connectedReceiptLines: List<String>,
    ): SuperAssistantMaturitySignal {
        val tools = AgentToolCatalog.descriptors()
        val systemToolCount = tools.count { it.type == AgentActionToolType.SYSTEM }
        val guiToolCount = tools.count { it.type == AgentActionToolType.GUI }
        val connectedToolCount = tools.count { it.name.startsWith("connected.") }
        val connectedApps = ConnectedAppCatalog.descriptors()
        val focusConnected =
            connectedApps.any { descriptor ->
                (profileId.isNotBlank() && descriptor.appId == profileId) ||
                    (packageName.isNotBlank() && descriptor.packageName == packageName)
            }
        val connectedReceipt =
            connectedReceiptLines.any { line ->
                (profileId.isNotBlank() && line.contains(profileId)) ||
                    (packageName.isNotBlank() && line.contains(packageName))
            }
        val catalogReady = systemToolCount >= 8 && connectedToolCount >= 2 && connectedApps.size >= 3
        val score =
            clampScore(
                25 +
                    (if (systemToolCount >= 8) 22 else systemToolCount * 2) +
                    (if (connectedToolCount >= 2) 18 else connectedToolCount * 6) +
                    (if (connectedApps.size >= 3) 15 else connectedApps.size * 4) +
                    (if (focusConnected) 13 else 0) +
                    (if (connectedReceipt) 7 else 0),
            )
        val status =
            when {
                !catalogReady -> STATUS_BLOCKED
                score >= 80 -> STATUS_STRONG
                else -> STATUS_ATTENTION
            }
        return SuperAssistantMaturitySignal(
            domain = "connector_first",
            title = "系统工具与 connector-first",
            status = status,
            score = score,
            summary =
                "system=$systemToolCount gui=$guiToolCount connected_tools=$connectedToolCount " +
                    "apps=${connectedApps.size} focus=$focusConnected receipt=$connectedReceipt",
            detailLines = connectedReceiptLines,
            recommendedCommand = "/tool-contracts",
        )
    }

    private fun resumeSignal(
        aggregate: HarnessStore.AggregateSnapshot?,
        focusPlatformSnapshot: SessionPlatformSnapshot?,
    ): SuperAssistantMaturitySignal {
        val health = focusPlatformSnapshot?.healthSummary
        val bridge = focusPlatformSnapshot?.bridgeSnapshot
        val replayResumeActive = focusPlatformSnapshot?.sessionSnapshot?.resume?.active == true
        val resumeSnapshotPresent = health?.resumableSnapshotPresent == true || focusPlatformSnapshot?.resumeSnapshot != null
        val continuationSuccess =
            (aggregate?.resumeContinuationSuccessCount ?: 0) +
                (aggregate?.resumeContinuationRecoveryCount ?: 0) +
                (aggregate?.takeoverResumeSuccessCount ?: 0)
        val continuationAttempt = aggregate?.resumeContinuationAttemptCount ?: 0
        val score =
            clampScore(
                20 +
                    (if (resumeSnapshotPresent) 24 else 0) +
                    (if (bridge?.resumable == true || focusPlatformSnapshot?.resumeSnapshot?.resumable == true || replayResumeActive) 18 else 0) +
                    (if (health?.bridgeFeedPresent == true) 10 else 0) +
                    (if (health?.runtimeLedgerPresent == true) 10 else 0) +
                    (if (health?.deterministicReplayReady == true) 10 else 0) +
                    (if (continuationSuccess > 0) 16 else if (continuationAttempt > 0) 7 else 0),
            )
        val status =
            when {
                score >= 82 -> STATUS_STRONG
                score >= 45 -> STATUS_ATTENTION
                else -> STATUS_BLOCKED
            }
        return SuperAssistantMaturitySignal(
            domain = "resume",
            title = "长任务恢复与续跑",
            status = status,
            score = score,
            summary =
                buildString {
                    append("snapshot=").append(resumeSnapshotPresent)
                    append(" bridge=").append(bridge?.resumable == true)
                    append(" replay_resume=").append(replayResumeActive)
                    append(" cont=").append(continuationSuccess).append('/').append(continuationAttempt)
                },
            detailLines =
                listOfNotNull(
                    health?.summary?.takeIf { it.isNotBlank() },
                    focusPlatformSnapshot?.resumeSnapshot?.lastTransition?.takeIf { it.isNotBlank() },
                ),
            recommendedCommand = focusPlatformSnapshot?.sessionId?.takeIf { it.isNotBlank() }?.let { "/resume --session-id $it" }.orEmpty(),
        )
    }

    private fun permissionSignal(
        permissionProduct: SessionPermissionProductSnapshot,
    ): SuperAssistantMaturitySignal {
        val policyRuleCount = permissionProduct.askCount + permissionProduct.denyCount + permissionProduct.allowCount
        val strongPolicy = permissionProduct.askCount >= 3 && permissionProduct.denyCount >= 1
        val surfaced = permissionProduct.cardCount > 0 && permissionProduct.tabCount >= 3
        val score =
            clampScore(
                20 +
                    (if (strongPolicy) 35 else policyRuleCount * 6) +
                    (if (surfaced) 22 else 0) +
                    (if (permissionProduct.pendingCount > 0 || permissionProduct.recentCount > 0) 10 else 0) +
                    (if (permissionProduct.sourceCount > 1) 8 else 0),
            )
        val status =
            when {
                score >= 80 -> STATUS_STRONG
                score >= 45 -> STATUS_ATTENTION
                else -> STATUS_BLOCKED
            }
        return SuperAssistantMaturitySignal(
            domain = "permission",
            title = "权限治理与审批产品面",
            status = status,
            score = score,
            summary =
                "allow=${permissionProduct.allowCount} ask=${permissionProduct.askCount} deny=${permissionProduct.denyCount} " +
                    "pending=${permissionProduct.pendingCount} cards=${permissionProduct.cardCount}",
            detailLines = permissionProduct.lines.take(4),
            recommendedCommand =
                "/permission-product" +
                    permissionProduct.sessionId.takeIf { it.isNotBlank() }?.let { " --session-id $it" }.orEmpty(),
        )
    }

    private fun entrySignal(
        entrySurfaceSignal: SuperAssistantEntrySurfaceSignal,
    ): SuperAssistantMaturitySignal {
        val ready = entrySurfaceSignal.readySurfaceCount
        val enabled = entrySurfaceSignal.enabledSurfaceCount
        val score =
            clampScore(
                20 +
                    ready * 10 +
                    (if (entrySurfaceSignal.voiceReady) 10 else 0) +
                    (if (entrySurfaceSignal.recentEntryCount > 0) 8 else 0) +
                    (if (entrySurfaceSignal.pendingActionCount > 0) 5 else 0) -
                    entrySurfaceSignal.blockedSurfaceCount * 5,
            )
        val status =
            when {
                ready >= 5 && entrySurfaceSignal.blockedSurfaceCount <= 1 -> STATUS_STRONG
                ready >= 2 -> STATUS_ATTENTION
                else -> STATUS_BLOCKED
            }
        return SuperAssistantMaturitySignal(
            domain = "entry",
            title = "多入口监督与接管",
            status = status,
            score = score,
            summary =
                entrySurfaceSignal.summary.ifBlank {
                    "ready=$ready/$enabled blocked=${entrySurfaceSignal.blockedSurfaceCount} voice=${entrySurfaceSignal.voiceReady}"
                },
            recommendedCommand = "/assistant-os",
        )
    }

    private fun regressionSignal(
        aggregate: HarnessStore.AggregateSnapshot?,
        releaseGate: ScreenAutomationReleaseGateSnapshot?,
    ): SuperAssistantMaturitySignal {
        val catalog = RegressionRunner.scenarioCatalog()
        val superAssistantScenarioCount =
            catalog.count { scenario ->
                scenario.suite in setOf("assistant_os", "runtime") ||
                    scenario.requiredCapabilities.any { it in setOf("resume", "recovery", "structured", "artifact_focus") }
            }
        val runCount = aggregate?.runCount ?: 0
        val successRate = aggregate?.successRate ?: 0.0
        val gateRed = releaseGate?.gateStatus == "red"
        val gateYellow = releaseGate?.gateStatus == "yellow"
        val score =
            clampScore(
                20 +
                    (if (catalog.size >= 10) 18 else catalog.size) +
                    (if (superAssistantScenarioCount >= 5) 16 else superAssistantScenarioCount * 2) +
                    (if (runCount >= 6) 22 else runCount * 3) +
                    (if (successRate >= 0.75) 16 else if (successRate >= 0.5) 8 else 0) +
                    when {
                        gateRed -> -25
                        gateYellow -> -10
                        releaseGate?.gateStatus == "green" -> 8
                        else -> 0
                    },
            )
        val status =
            when {
                gateRed || (runCount >= 3 && successRate < 0.5) -> STATUS_BLOCKED
                score >= 80 -> STATUS_STRONG
                score >= 45 -> STATUS_ATTENTION
                else -> STATUS_BLOCKED
            }
        return SuperAssistantMaturitySignal(
            domain = "regression",
            title = "回归闭环与发布门禁",
            status = status,
            score = score,
            summary =
                "catalog=${catalog.size} super=${superAssistantScenarioCount} runs=$runCount " +
                    "success=${(successRate * 100.0).roundToInt()} gate=${releaseGate?.gateStatus ?: "ungated"}",
            detailLines = releaseGate?.summary?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty(),
            recommendedCommand = "/run-regression",
        )
    }

    private fun domainsForScenario(
        scenario: BenchmarkScenario,
    ): Set<String> =
        buildSet {
            add("regression")
            val haystack =
                (scenario.requiredCapabilities + scenario.tags + listOf(scenario.suite, scenario.intentType, scenario.title))
                    .joinToString(" ")
            if (haystack.contains("semantic", ignoreCase = true) || haystack.contains("visual", ignoreCase = true)) add("visual")
            if (haystack.contains("structured", ignoreCase = true) || haystack.contains("connector", ignoreCase = true)) add("connector_first")
            if (haystack.contains("resume", ignoreCase = true) || haystack.contains("recovery", ignoreCase = true)) add("resume")
            if (haystack.contains("safety", ignoreCase = true) || haystack.contains("governance", ignoreCase = true)) add("permission")
            if (haystack.contains("assistant_os", ignoreCase = true) || haystack.contains("shell", ignoreCase = true) || haystack.contains("routine", ignoreCase = true)) add("entry")
        }

    private fun clampScore(
        value: Int,
    ): Int = value.coerceIn(0, 100)

    private const val STATUS_STRONG = "strong"
    private const val STATUS_ATTENTION = "attention"
    private const val STATUS_BLOCKED = "blocked"

    private val VISUAL_TOKENS =
        listOf(
            "visual",
            "screenshot",
            "planning_screenshot",
            "ocr",
            "ground",
            "detector",
            "parser",
            "vision",
        )
}
