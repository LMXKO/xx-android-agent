package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentTurnRecord
import com.lmx.xiaoxuanagent.agent.PlannerArtifactHint
import com.lmx.xiaoxuanagent.agent.SkillContext
import com.lmx.xiaoxuanagent.agent.TaskPlanState
import com.lmx.xiaoxuanagent.agent.UiElementObservation
import com.lmx.xiaoxuanagent.agent.VisualGroundingObservation
import com.lmx.xiaoxuanagent.agent.VisualPerceptionContext
import com.lmx.xiaoxuanagent.agent.VisualTextObservation

enum class PlannerCompactionPolicy {
    LIGHT,
    BALANCED,
    ARTIFACT_HEAVY,
}

data class PlannerCompactMeta(
    val policy: PlannerCompactionPolicy,
    val originalElementCount: Int,
    val compactElementCount: Int,
    val originalHistoryCount: Int,
    val compactHistoryCount: Int,
    val originalTopTextCount: Int,
    val compactTopTextCount: Int,
    val originalVisualHintCount: Int,
    val compactVisualHintCount: Int,
    val originalArtifactHintCount: Int,
    val compactArtifactHintCount: Int,
)

data class PlannerCompactContext(
    val policyTag: String,
    val governanceHints: List<String>,
    val topTexts: List<String>,
    val visualHints: List<String>,
    val ocrTexts: List<VisualTextObservation>,
    val groundedTexts: List<VisualGroundingObservation>,
    val elements: List<UiElementObservation>,
    val activeSkills: List<SkillContext>,
    val recentHistory: List<AgentTurnRecord>,
    val historyDigest: List<String>,
    val compactArtifactHints: List<PlannerArtifactHint>,
    val memoryBuckets: Map<String, List<String>>,
    val meta: PlannerCompactMeta,
)

data class PlannerRuntimeCompactionPlan(
    val trigger: String,
    val mode: String,
    val targetBudget: Int,
    val shouldPreferCompactWindow: Boolean,
    val retryHint: String = "",
    val injectedLines: List<String> = emptyList(),
)

object CompactService {
    private const val MAX_TOP_TEXTS = 8
    private const val MAX_ELEMENTS = 20
    private const val MAX_VISUAL_HINTS = 5
    private const val MAX_OCR_TEXTS = 6
    private const val MAX_GROUNDED_TEXTS = 6
    private const val MAX_ACTIVE_SKILLS = 4
    private const val MAX_MEMORY_ITEMS_PER_BUCKET = 4
    private const val MAX_HISTORY_TURNS = 3

    fun buildRuntimeCompactionPlan(
        turn: Int,
        history: List<AgentTurnRecord>,
        taskPlanState: TaskPlanState,
        compactSnapshot: SessionConversationCompactSnapshot?,
        workingMemorySnapshot: SessionWorkingMemorySnapshot?,
        memoryPolicySnapshot: SessionMemoryPolicySnapshot?,
    ): PlannerRuntimeCompactionPlan {
        val recentHistory = history.takeLast(3)
        val recentFailure = recentHistory.lastOrNull()?.let { turnRecord ->
            turnRecord.recoveryCategory.isNotBlank() ||
                turnRecord.result.contains("失败") ||
                turnRecord.result.contains("阻止")
        } ?: false
        val repeatedAction =
            recentHistory.size >= 3 &&
                recentHistory.map { it.action }.distinct().size == 1
        val trigger =
            when {
                taskPlanState.resumeContext.active -> "resume_boundary"
                memoryPolicySnapshot?.notebookUpdateQueued == true -> "notebook_boundary"
                recentFailure -> "failure_retry"
                repeatedAction -> "loop_retry"
                compactSnapshot != null -> compactSnapshot.trigger.ifBlank { "microcompact" }
                else -> "bootstrap"
            }
        val mode =
            when (trigger) {
                "resume_boundary", "notebook_boundary", "failure_retry", "loop_retry" -> "reactive"
                "post_boundary", "auto_compact" -> "auto"
                else -> compactSnapshot?.mode.orEmpty().ifBlank { "micro" }
            }
        val targetBudget =
            when (mode) {
                "auto" -> 2
                "reactive" -> 3
                else -> 4
            }
        val retryHint =
            when {
                taskPlanState.resumeContext.active ->
                    "优先续跑 resume_context.resumed_subgoal_id，先做最小纠偏，不要重新展开整段任务。"
                recentFailure ->
                    recentHistory.lastOrNull()?.suggestedRecoveryAction?.takeIf { it.isNotBlank() }
                        ?: "不要重复上一轮失败动作，先按 recovery 或 boundary digest 纠偏。"
                repeatedAction ->
                    "最近动作重复，避免继续同一动作，优先采用 fallback tool 或回退恢复。"
                else -> ""
            }
        val injectedLines =
            buildList {
                add("trigger=$trigger")
                add("mode=$mode")
                add("target_budget=$targetBudget")
                add("prefer_compact_window=${mode != "micro"}")
                compactSnapshot?.conversationSummary?.takeIf { it.isNotBlank() }?.let {
                    add("conversation=${it.take(120)}")
                }
                compactSnapshot?.boundarySummary?.takeIf { it.isNotBlank() }?.let {
                    add("boundary=${it.take(120)}")
                }
                workingMemorySnapshot?.nextFocusHint?.takeIf { it.isNotBlank() }?.let {
                    add("focus=${it.take(96)}")
                }
                memoryPolicySnapshot?.lastReason?.takeIf { it.isNotBlank() }?.let {
                    add("policy_reason=${it.take(96)}")
                }
                retryHint.takeIf { it.isNotBlank() }?.let { add("retry_hint=${it.take(120)}") }
                if (turn > 0) {
                    add("turn=$turn")
                }
            }
        return PlannerRuntimeCompactionPlan(
            trigger = trigger,
            mode = mode,
            targetBudget = targetBudget,
            shouldPreferCompactWindow = mode != "micro",
            retryHint = retryHint,
            injectedLines = injectedLines.take(8),
        )
    }

    fun compactPlannerContext(
        task: String,
        topTexts: List<String>,
        elements: List<UiElementObservation>,
        history: List<AgentTurnRecord>,
        artifactHints: List<PlannerArtifactHint>,
        memoryBuckets: Map<String, List<String>>,
        activeSkills: List<SkillContext>,
        taskPlanState: TaskPlanState,
        visualContext: VisualPerceptionContext,
    ): PlannerCompactContext {
        val runtimeSignals = memoryBuckets["runtime_compaction_signals"].orEmpty()
        val runtimeMode = runtimeSignalValue(runtimeSignals, "mode")
        val runtimeBudget = runtimeSignalValue(runtimeSignals, "target_budget")?.toIntOrNull()
        val policy = resolvePolicy(elements, artifactHints, taskPlanState, visualContext)
        val budgets =
            resolveBudgets(
                policy = policy,
                elementCount = elements.size,
                taskPlanState = taskPlanState,
                visualContext = visualContext,
                runtimeMode = runtimeMode,
                runtimeBudget = runtimeBudget,
            )
        val focusKeywords = extractFocusKeywords(task, taskPlanState, memoryBuckets)
        val compactTopTexts = topTexts.filter { it.isNotBlank() }.take(budgets.topTexts)
        val compactVisualHints = visualContext.visualHints.filter { it.isNotBlank() }.take(budgets.visualHints)
        val compactOcrTexts = visualContext.ocrTexts.take(budgets.ocrTexts)
        val compactGroundedTexts = visualContext.groundedTexts.take(budgets.groundedTexts)
        val compactElements = compactElements(elements, focusKeywords, budgets.elements)
        val compactSkills = activeSkills.take(MAX_ACTIVE_SKILLS)
        val compactHistory = history.takeLast(budgets.history)
        val compactArtifactHints = artifactHints.take(budgets.artifactHints)
        val historyDigest = buildHistoryDigest(compactHistory)
        val compactMemoryBuckets =
            memoryBuckets.mapValues { (_, values) ->
                values.take(runtimeBucketBudget(runtimeMode, values))
            }

        return PlannerCompactContext(
            policyTag =
                listOfNotNull(
                    policy.name.lowercase(),
                    runtimeMode?.takeIf { it.isNotBlank() }?.let { "runtime_$it" },
                ).joinToString("+"),
            governanceHints =
                (
                    buildGovernanceHints(policy, taskPlanState, history, artifactHints, elements.size) +
                        runtimeSignals.filter { it.startsWith("trigger=") || it.startsWith("retry_hint=") } +
                        buildMissionGovernanceHints()
                ).distinct(),
            topTexts = compactTopTexts,
            visualHints = compactVisualHints,
            ocrTexts = compactOcrTexts,
            groundedTexts = compactGroundedTexts,
            elements = compactElements,
            activeSkills = compactSkills,
            recentHistory = compactHistory,
            historyDigest = historyDigest,
            compactArtifactHints = compactArtifactHints,
            memoryBuckets = compactMemoryBuckets,
            meta =
                PlannerCompactMeta(
                    policy = policy,
                    originalElementCount = elements.size,
                    compactElementCount = compactElements.size,
                    originalHistoryCount = history.size,
                    compactHistoryCount = compactHistory.size,
                    originalTopTextCount = topTexts.size,
                    compactTopTextCount = compactTopTexts.size,
                    originalVisualHintCount = visualContext.visualHints.size,
                    compactVisualHintCount = compactVisualHints.size,
                    originalArtifactHintCount = artifactHints.size,
                    compactArtifactHintCount = compactArtifactHints.size,
                ),
        )
    }

    /** 把当前 mission 的黑板内容打成 governance hints，让长任务的"上一腿证据"在 compact 后仍可见。 */
    internal fun buildMissionGovernanceHints(
        mission: com.lmx.xiaoxuanagent.agent.CrossAppMission? = SessionRuntimeStore.read().session.mission,
    ): List<String> {
        if (mission == null) return emptyList()
        return buildList {
            add("mission_id=${mission.missionId}")
            add(
                "mission_progress=${mission.activeLegIndex + 1}/${mission.legs.size}: " +
                    mission.legs.joinToString(" → ") { it.profileId },
            )
            mission.blackboard.forEachIndexed { index, payload ->
                val priceField = payload.fields.firstOrNull { it.key.equals("price", ignoreCase = true) }?.value
                add(
                    "leg${index}_result: ${payload.title.take(48)}" +
                        (priceField?.takeIf { it.isNotBlank() }?.let { " | price=$it" } ?: "") +
                        payload.highlights.firstOrNull()?.let { " | " + it.take(48) }.orEmpty(),
                )
            }
        }
    }

    private fun compactElements(
        elements: List<UiElementObservation>,
        focusKeywords: List<String>,
        limit: Int,
    ): List<UiElementObservation> =
        elements
            .asSequence()
            .map { element -> element to elementPriority(element, focusKeywords) }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { it.id }
            .take(limit)
            .toList()

    private fun buildHistoryDigest(
        history: List<AgentTurnRecord>,
    ): List<String> =
        history.takeLast(3).map { turn ->
            buildString {
                append(turn.action)
                append(" -> ")
                append(turn.result.take(72))
                turn.recoveryCategory.takeIf { it.isNotBlank() }?.let {
                    append(" | recovery=").append(it.lowercase())
                }
                turn.suggestedRecoveryAction.takeIf { it.isNotBlank() }?.let {
                    append(" | next=").append(it.take(40))
                }
            }
        }

    private fun resolvePolicy(
        elements: List<UiElementObservation>,
        artifactHints: List<PlannerArtifactHint>,
        taskPlanState: TaskPlanState,
        visualContext: VisualPerceptionContext,
    ): PlannerCompactionPolicy {
        val visualHeavy =
            visualContext.captureAvailable &&
                (
                    visualContext.groundedTexts.isNotEmpty() ||
                        visualContext.ocrTexts.isNotEmpty() ||
                        visualContext.visualHints.isNotEmpty()
                )
        return when {
            artifactHints.isNotEmpty() && taskPlanState.currentStage == "summarize" ->
                PlannerCompactionPolicy.ARTIFACT_HEAVY
            taskPlanState.resumeContext.active || taskPlanState.waitingForExternal ->
                PlannerCompactionPolicy.ARTIFACT_HEAVY
            elements.size <= 8 && visualHeavy ->
                PlannerCompactionPolicy.LIGHT
            else ->
                PlannerCompactionPolicy.BALANCED
        }
    }

    private fun resolveBudgets(
        policy: PlannerCompactionPolicy,
        elementCount: Int,
        taskPlanState: TaskPlanState,
        visualContext: VisualPerceptionContext,
        runtimeMode: String?,
        runtimeBudget: Int?,
    ): PlannerCompactBudgets {
        val denseLayout = elementCount >= 28
        val visualHeavy =
            visualContext.captureAvailable &&
                (visualContext.groundedTexts.isNotEmpty() || visualContext.ocrTexts.isNotEmpty())
        val runtimeAdjustedBudget = runtimeBudget?.coerceIn(2, MAX_MEMORY_ITEMS_PER_BUCKET)
        return when (policy) {
            PlannerCompactionPolicy.LIGHT ->
                PlannerCompactBudgets(
                    topTexts = 6,
                    visualHints = if (visualHeavy) 5 else 3,
                    ocrTexts = if (visualHeavy) 6 else 4,
                    groundedTexts = if (visualHeavy) 6 else 4,
                    elements = if (denseLayout) 12 else 14,
                    history = if (runtimeMode == "reactive") 1 else 2,
                    artifactHints = 3,
                )

            PlannerCompactionPolicy.ARTIFACT_HEAVY ->
                PlannerCompactBudgets(
                    topTexts = 6,
                    visualHints = 4,
                    ocrTexts = 4,
                    groundedTexts = 4,
                    elements = if (taskPlanState.resumeContext.active) 14 else 16,
                    history = if (runtimeMode == "auto") 1 else 2,
                    artifactHints = runtimeAdjustedBudget?.plus(3) ?: 6,
                )

            PlannerCompactionPolicy.BALANCED ->
                PlannerCompactBudgets(
                    topTexts = MAX_TOP_TEXTS,
                    visualHints = MAX_VISUAL_HINTS,
                    ocrTexts = MAX_OCR_TEXTS,
                    groundedTexts = MAX_GROUNDED_TEXTS,
                    elements = if (denseLayout) 18 else MAX_ELEMENTS,
                    history =
                        when (runtimeMode) {
                            "auto" -> 1
                            "reactive" -> 2
                            else -> MAX_HISTORY_TURNS
                        },
                    artifactHints = runtimeAdjustedBudget?.plus(2) ?: 4,
                )
        }
    }

    private fun runtimeBucketBudget(
        runtimeMode: String?,
        values: List<String>,
    ): Int {
        val heavyBucket =
            values.any { line ->
                line.startsWith("memory_policy:", ignoreCase = true) ||
                    line.startsWith("session_notebook:", ignoreCase = true) ||
                    line.startsWith("compact_boundary:", ignoreCase = true)
            }
        return when (runtimeMode) {
            "auto" -> if (heavyBucket) 2 else 1
            "reactive" -> if (heavyBucket) 3 else 2
            else -> MAX_MEMORY_ITEMS_PER_BUCKET
        }
    }

    private fun runtimeSignalValue(
        runtimeSignals: List<String>,
        key: String,
    ): String? =
        runtimeSignals.lastOrNull { it.startsWith("$key=") }?.substringAfter('=')

    private fun extractFocusKeywords(
        task: String,
        taskPlanState: TaskPlanState,
        memoryBuckets: Map<String, List<String>>,
    ): List<String> =
        buildList {
            add(task)
            add(taskPlanState.nextObjective)
            add(taskPlanState.currentSubgoalId)
            addAll(memoryBuckets["contacts"].orEmpty())
            addAll(memoryBuckets["locations"].orEmpty())
            addAll(memoryBuckets["session_memories"].orEmpty())
        }.flatMap(::splitKeywords)
            .distinct()
            .take(12)

    private fun splitKeywords(
        raw: String,
    ): List<String> =
        raw.split(Regex("[\\s,，。；;、|:/]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }

    private fun buildGovernanceHints(
        policy: PlannerCompactionPolicy,
        taskPlanState: TaskPlanState,
        history: List<AgentTurnRecord>,
        artifactHints: List<PlannerArtifactHint>,
        elementCount: Int,
    ): List<String> =
        buildList {
            add("policy=${policy.name.lowercase()}")
            if (taskPlanState.resumeContext.active) add("resume_active")
            if (taskPlanState.waitingForExternal) add("waiting_external")
            if (artifactHints.isNotEmpty()) add("artifact_bias")
            if (elementCount >= 28) add("dense_layout")
            val repeatedAction = history.takeLast(3).map { it.action }.distinct().size == 1 && history.size >= 3
            if (repeatedAction) add("loop_risk_recent_actions")
        }

    private fun elementPriority(
        element: UiElementObservation,
        focusKeywords: List<String>,
    ): Int =
        (if (element.editable) 100 else 0) +
            (if (element.focused) 50 else 0) +
            (if (element.clickable) 28 else 0) +
            (if (element.scrollable) 20 else 0) +
            (if (element.enabled) 8 else 0) +
            (if (element.text.isNotBlank()) 18 else 0) +
            (if (element.viewId.isNotBlank()) 8 else 0) +
            (if (element.source == "overflow_tree") 22 else 0) +
            (if (element.source == "overflow_recall") 18 else 0) +
            (if (element.source == "overflow_hidden") 14 else 0) +
            (if (element.accessibilityUniqueId.isNotBlank()) 10 else 0) +
            (if (element.descriptorTokens.isNotEmpty()) 8 else 0) +
            (if (element.visualDescriptorTokens.isNotEmpty()) 10 else 0) +
            (if (element.visualSignature.isNotBlank()) 8 else 0) +
            focusKeywords.fold(0) { total, keyword ->
                total +
                    when {
                        element.text.equals(keyword, ignoreCase = true) -> 120
                        element.text.contains(keyword, ignoreCase = true) -> 70
                        element.viewId.contains(keyword, ignoreCase = true) -> 24
                        else -> 0
                    }
            }

    private data class PlannerCompactBudgets(
        val topTexts: Int,
        val visualHints: Int,
        val ocrTexts: Int,
        val groundedTexts: Int,
        val elements: Int,
        val history: Int,
        val artifactHints: Int,
    )
}
