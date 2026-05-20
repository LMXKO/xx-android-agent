package com.lmx.xiaoxuanagent.skills

import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.AgentSemanticActionComposer
import com.lmx.xiaoxuanagent.agent.AgentTurnRecord
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.SkillLayer
import com.lmx.xiaoxuanagent.agent.TaskIntentParser
import com.lmx.xiaoxuanagent.agent.TaskIntentType
import com.lmx.xiaoxuanagent.agent.TaskPlanState
import com.lmx.xiaoxuanagent.agent.UiElementObservation

private data class SubgoalTargetCandidate(
    val elementId: String,
    val label: String,
    val score: Int,
)

object SubgoalAlignmentSkill : ExecutableSkill {
    override val spec: SkillSpec =
        SkillSpec(
            id = "subgoal_alignment",
            title = "子目标对齐",
            description = "在选择候选入口、结果项或联系人时，优先点击更贴近当前子目标的元素。",
            instructions = "点击前先判断当前子目标更像搜索入口、结果候选、路线入口还是内容对象；避免误点综合/筛选/更多/无关候选。",
            keywords = emptyList(),
            alwaysOn = true,
            priority = 21,
            layer = SkillLayer.GUARD,
            requiredTools = listOf("gui.click", "gui.scroll"),
        )

    override fun refineDecision(
        context: SkillDecisionContext,
        decision: AgentDecision,
    ): AgentDecision {
        val clickAction = decision.action as? AgentAction.Click ?: return decision
        val observation = context.observation
        val target = observation.elements.firstOrNull { it.id == clickAction.elementId } ?: return decision
        val rankedCandidates = rankSubgoalCandidates(context)
        if (rankedCandidates.isEmpty()) return decision

        val rejectedLabels = recentRejectedTargetLabels(context.history)
        val bestCandidate =
            rankedCandidates.firstOrNull { it.label !in rejectedLabels }
                ?: rankedCandidates.first()
        val currentCandidate =
            rankedCandidates.firstOrNull { it.elementId == target.id } ?: scoreSubgoalCandidate(
                element = target,
                context = context,
                rejectedLabels = rejectedLabels,
            )

        if (bestCandidate.elementId == target.id) {
            return decision
        }

        val targetRejected = target.text.isNotBlank() && target.text in rejectedLabels
        val minBestScore = if (targetRejected) 32 else 72
        val shouldRewrite =
            targetRejected ||
                currentCandidate == null ||
                currentCandidate.score < 40 ||
                bestCandidate.score - currentCandidate.score >= 65
        if (!shouldRewrite || bestCandidate.score < minBestScore) {
            return decision
        }

        return decision.copy(
            action = AgentAction.OpenBestCandidate(
                targetText = bestCandidate.label,
                roleHint = AgentSemanticActionComposer.roleHintForStage(context.taskPlanState?.currentStage.orEmpty()),
            ),
            reason =
                "技能改写：当前子目标更匹配 ${bestCandidate.label}，" +
                    "替换原点击目标 ${target.text.ifBlank { target.id }}，减少误点和无关步骤。",
        )
    }

    override fun extractParameters(context: SkillDecisionContext): List<String> {
        val constraints = TaskIntentParser.parse(context.task)
        return listOfNotNull(
            "intent=${constraints.intentType.name.lowercase()}",
            context.taskPlanState?.currentStage?.takeIf { it.isNotBlank() }?.let { "stage=$it" },
            context.taskPlanState?.currentSubgoalId?.takeIf { it.isNotBlank() }?.let { "subgoal=$it" },
            constraints.entryQuery.takeIf { it.isNotBlank() }?.let { "entry=${it.take(18)}" },
        )
    }

    private fun rankSubgoalCandidates(
        context: SkillDecisionContext,
    ): List<SubgoalTargetCandidate> {
        val rejectedLabels = recentRejectedTargetLabels(context.history)
        return context.observation.elements
            .mapNotNull { element ->
                scoreSubgoalCandidate(
                    element = element,
                    context = context,
                    rejectedLabels = rejectedLabels,
                )
            }
            .sortedByDescending { it.score }
    }
}

private val genericControlKeywords =
    listOf(
        "综合",
        "销量",
        "筛选",
        "排序",
        "更多",
        "更多选项",
        "全部",
        "返回",
        "关闭",
        "取消",
        "设置",
        "首页",
        "我的",
        "购物车",
        "店铺",
        "品牌",
        "分类",
        "客服",
        "关注",
        "扫一扫",
        "拍照",
        "相机",
    )

private val entryStageKeywords =
    listOf("搜索", "查找", "输入", "目的地", "联系人", "会话", "收件人")

private val routeStageKeywords =
    listOf("路线", "导航", "到这去", "开始导航", "出发")

private val detailStageKeywords =
    listOf("详情", "评价", "评论", "参数", "规格", "简介", "高赞", "热评")

private fun scoreSubgoalCandidate(
    element: UiElementObservation,
    context: SkillDecisionContext,
    rejectedLabels: Set<String>,
): SubgoalTargetCandidate? {
    if (!element.clickable || !element.enabled) return null
    val constraints = TaskIntentParser.parse(context.task)
    val stage = context.taskPlanState?.currentStage.orEmpty()
    val semantic =
        listOf(element.text, element.viewId, element.className)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    if (semantic.isBlank()) return null

    val cues = buildSubgoalCues(constraints, context.taskPlanState)
    var score = 0
    var matchedCue = false
    cues.forEach { cue ->
        when {
            element.text.equals(cue, ignoreCase = true) -> {
                score += 190
                matchedCue = true
            }

            element.text.contains(cue, ignoreCase = true) -> {
                score += (70 + cue.length * 8)
                matchedCue = true
            }

            semantic.contains(cue, ignoreCase = true) -> {
                score += (42 + cue.length * 5)
                matchedCue = true
            }

            else -> {
                val overlap = sharedCharacterCount(semantic, cue)
                if (overlap >= 2) {
                    score += overlap * 11
                    matchedCue = true
                }
            }
        }
    }

    if (stage in setOf("enter_query", "enter_destination", "find_conversation")) {
        val entryBoost =
            entryStageKeywords.sumOf { keyword ->
                if (semantic.contains(keyword, ignoreCase = true)) keyword.length * 8 else 0
            }
        score += entryBoost
        matchedCue = matchedCue || entryBoost > 0
    }

    if (stage == "confirm_route") {
        val routeBoost =
            routeStageKeywords.sumOf { keyword ->
                if (semantic.contains(keyword, ignoreCase = true)) keyword.length * 10 else 0
            }
        score += routeBoost
        matchedCue = matchedCue || routeBoost > 0
    }

    if (stage in setOf("browse_candidates", "inspect_information", "summarize")) {
        val detailBoost =
            detailStageKeywords.sumOf { keyword ->
                if (semantic.contains(keyword, ignoreCase = true)) keyword.length * 6 else 0
            }
        score += detailBoost
        matchedCue = matchedCue || detailBoost > 0
    }

    val penaltyKeywords =
        genericControlKeywords.filterNot { keyword ->
            stage in setOf("enter_query", "enter_destination", "find_conversation") &&
                keyword in entryStageKeywords
        }.filterNot { keyword ->
            stage == "confirm_route" && keyword in routeStageKeywords
        }.filterNot { keyword ->
            stage in setOf("inspect_information", "summarize") && keyword in detailStageKeywords
        }
    val penalty =
        penaltyKeywords.sumOf { keyword ->
            if (semantic.contains(keyword, ignoreCase = true)) keyword.length * 16 else 0
        }
    score -= penalty

    if (element.text.isNotBlank() && element.text in rejectedLabels) {
        score -= 220
    }
    if (element.text.length <= 1 && !matchedCue) {
        score -= 40
    }
    if (!matchedCue && constraints.intentType == TaskIntentType.GENERIC) {
        return null
    }
    if (score <= 0) {
        return null
    }
    return SubgoalTargetCandidate(
        elementId = element.id,
        label = element.text.ifBlank { semantic.take(24) },
        score = score,
    )
}

private fun buildSubgoalCues(
    constraints: com.lmx.xiaoxuanagent.agent.TaskConstraints,
    taskPlanState: TaskPlanState?,
): List<String> {
    val stage = taskPlanState?.currentStage.orEmpty()
    val base =
        buildList {
            add(constraints.entryQuery)
            add(constraints.normalizedGoal)
            constraints.destination?.let(::add)
            constraints.recipientName?.let(::add)
            constraints.objectiveHint?.let(::add)
            addAll(constraints.keywordHints)
            if (stage in setOf("inspect_information", "summarize")) {
                addAll(detailStageKeywords)
            }
            if (stage == "confirm_route") {
                addAll(routeStageKeywords)
            }
        }
    return base
        .map { it.trim() }
        .filter { it.length >= 2 }
        .distinct()
}

private fun recentRejectedTargetLabels(
    history: List<AgentTurnRecord>,
): Set<String> =
    history
        .takeLast(6)
        .mapNotNull { turn ->
            if (!turn.action.startsWith("click(")) {
                return@mapNotNull null
            }
            if (
                turn.recoveryCategory !in
                setOf(
                    com.lmx.xiaoxuanagent.agent.RecoveryCategory.NO_PROGRESS.name,
                    com.lmx.xiaoxuanagent.agent.RecoveryCategory.REPEATED_BACKTRACK.name,
                    com.lmx.xiaoxuanagent.agent.RecoveryCategory.WRONG_CONTEXT.name,
                )
            ) {
                return@mapNotNull null
            }
            extractClickedTargetLabel(turn.result)
        }
        .toSet()

private fun extractClickedTargetLabel(
    result: String,
): String? =
    Regex("""已(?:通过手势)?点击\s+[^(]+\(([^()]{1,40})\)""")
        .find(result)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotBlank() }

private fun sharedCharacterCount(
    text: String,
    target: String,
): Int {
    val remaining = target.toMutableList()
    var count = 0
    text.forEach { char ->
        val index = remaining.indexOf(char)
        if (index >= 0) {
            remaining.removeAt(index)
            count += 1
        }
    }
    return count
}
