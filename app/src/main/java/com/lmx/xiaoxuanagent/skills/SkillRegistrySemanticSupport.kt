package com.lmx.xiaoxuanagent.skills

import com.lmx.xiaoxuanagent.agent.AgentTurnRecord
import com.lmx.xiaoxuanagent.agent.PlanningMemoryContext
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.TaskIntentParser
import com.lmx.xiaoxuanagent.agent.TaskPlanState
import com.lmx.xiaoxuanagent.agent.UiElementObservation

internal data class MessagingTargetCandidate(
    val elementId: String,
    val label: String,
    val score: Int,
)

internal data class MessagingTargetingDiagnosis(
    val recipient: String,
    val conversationVerified: Boolean,
    val hasComposeCue: Boolean,
    val hasSearchCue: Boolean,
    val strongMismatchSignals: List<String>,
    val rankedCandidates: List<MessagingTargetCandidate>,
    val bestCandidate: MessagingTargetCandidate?,
    val currentStage: String,
) {
    val shouldRecoverByBack: Boolean
        get() =
            !conversationVerified &&
                !hasSearchCue &&
                (hasComposeCue || currentStage == "confirm_send") &&
                strongMismatchSignals.isNotEmpty()

    val recoveryReason: String
        get() = strongMismatchSignals.take(3).joinToString("、").ifBlank { "当前会话与目标不一致" }
}

internal data class ContentCandidate(
    val id: String,
    val text: String,
    val score: Int,
)

internal val messagingSearchKeywords =
    listOf(
        "搜索",
        "查找",
        "联系人",
        "通讯录",
        "添加朋友",
        "搜索指定内容",
        "会话",
        "放大镜",
    )

internal val messagingComposeKeywords =
    listOf(
        "发送",
        "发消息",
        "输入消息",
        "输入内容",
        "按住说话",
        "回车发送",
        "聊天信息",
        "语音通话",
        "视频通话",
    )

internal val messagingNegativeKeywords =
    listOf(
        "公众号",
        "订阅号",
        "服务号",
        "官方群",
        "群聊",
        "小程序",
        "视频号",
        "频道",
        "快讯",
        "直播",
        "客服",
        "机器人",
    )

internal val messagingGroupLikeKeywords =
    listOf(
        "群",
        "群聊",
        "官方群",
        "交流群",
        "讨论组",
        "售后群",
        "福利群",
        "通知群",
    )

internal val contentMetaKeywords =
    listOf(
        "综合",
        "推荐",
        "筛选",
        "排序",
        "更多",
        "关注",
        "首页",
        "频道",
        "分区",
        "合集",
        "评论区",
    )

internal fun analyzeMessagingTargeting(
    observation: ScreenObservation,
    recipient: String,
    taskPlanState: TaskPlanState?,
): MessagingTargetingDiagnosis {
    val visibleTexts =
        (observation.topTexts + observation.elements.mapNotNull { it.text.takeIf(String::isNotBlank) })
            .distinct()
    val semantics = visibleTexts.joinToString(" ")
    val conversationVerified =
        recipient.isBlank() || visibleTexts.any { it.contains(recipient, ignoreCase = true) }
    val hasSearchCue =
        messagingSearchKeywords.any { semantics.contains(it, ignoreCase = true) }
    val hasComposeCue =
        messagingComposeKeywords.any { semantics.contains(it, ignoreCase = true) } ||
            (observation.primaryEditableId != null && semantics.contains("消息"))
    val strongMismatchSignals =
        visibleTexts.filter { text ->
            messagingNegativeKeywords.any { keyword ->
                text.contains(keyword, ignoreCase = true) && !text.contains(recipient, ignoreCase = true)
            }
        }
    val rankedCandidates =
        observation.elements
            .mapNotNull { scoreMessagingCandidate(it, recipient) }
            .sortedByDescending { it.score }
    val bestCandidate =
        rankedCandidates.firstOrNull()?.takeIf { it.score >= 80 }
    return MessagingTargetingDiagnosis(
        recipient = recipient,
        conversationVerified = conversationVerified,
        hasComposeCue = hasComposeCue,
        hasSearchCue = hasSearchCue,
        strongMismatchSignals = strongMismatchSignals,
        rankedCandidates = rankedCandidates,
        bestCandidate = bestCandidate,
        currentStage = taskPlanState?.currentStage.orEmpty(),
    )
}

internal fun shouldRewriteMessagingClick(
    target: UiElementObservation,
    bestCandidate: MessagingTargetCandidate,
    recipient: String,
): Boolean {
    if (target.id == bestCandidate.elementId) return false
    val currentScore = scoreMessagingCandidate(target, recipient)?.score ?: Int.MIN_VALUE
    val targetSemantic = listOf(target.text, target.viewId, target.className).joinToString(" ")
    val targetHasNegative =
        messagingNegativeKeywords.any { keyword ->
            targetSemantic.contains(keyword, ignoreCase = true) &&
                !targetSemantic.contains(recipient, ignoreCase = true)
        }
    return targetHasNegative || currentScore < 60 || bestCandidate.score - currentScore >= 45
}

internal fun scoreMessagingCandidate(
    element: UiElementObservation,
    recipient: String,
): MessagingTargetCandidate? {
    if (!element.clickable || !element.enabled) return null
    val semanticParts =
        listOf(element.text, element.viewId, element.className)
            .filter { it.isNotBlank() }
    if (semanticParts.isEmpty()) return null

    val semantic = semanticParts.joinToString(" ")
    val label = element.text.ifBlank { semantic }
    var score = 0
    var recipientEvidence = false
    if (label.equals(recipient, ignoreCase = true)) {
        score += 220
        recipientEvidence = true
    } else if (label.contains(recipient, ignoreCase = true)) {
        score += 170
        recipientEvidence = true
    } else if (semantic.contains(recipient, ignoreCase = true)) {
        score += 120
        recipientEvidence = true
    }

    val overlap = skillRegistrySharedCharacterCount(label, recipient)
    if (overlap > 0) {
        score += overlap * 24
        if (overlap >= 2) recipientEvidence = true
    }
    if (recipientEvidence && label.length in 2..18) {
        score += 12
    }
    if (recipientEvidence && (element.focused || element.selected)) {
        score += 8
    }

    val negativeHits =
        messagingNegativeKeywords.count { keyword ->
            semantic.contains(keyword, ignoreCase = true) &&
                !semantic.contains(recipient, ignoreCase = true)
        }
    score -= negativeHits * 95
    val groupLikeHits =
        messagingGroupLikeKeywords.count { keyword ->
            semantic.contains(keyword, ignoreCase = true) &&
                !label.equals(recipient, ignoreCase = true) &&
                !label.contains(recipient, ignoreCase = true)
        }
    score -= groupLikeHits * 130
    if (label.length > 24 && !label.contains(recipient, ignoreCase = true)) {
        score -= 40
    }
    if (!recipientEvidence) return null

    return MessagingTargetCandidate(
        elementId = element.id,
        label = label,
        score = score,
    ).takeIf { it.score > 0 }
}

internal fun findMessagingSearchEntry(
    observation: ScreenObservation,
): UiElementObservation? =
    observation.elements
        .asSequence()
        .filter { it.clickable && it.enabled }
        .mapNotNull { element ->
            val semantic = listOf(element.text, element.viewId, element.className).joinToString(" ")
            val score =
                messagingSearchKeywords.fold(0) { acc, keyword ->
                    if (semantic.contains(keyword, ignoreCase = true)) acc + keyword.length else acc
                }
            if (score <= 0) null else element to score
        }
        .sortedByDescending { it.second }
        .map { it.first }
        .firstOrNull()

internal fun skillRegistryLooksLikeMessagingSearchEntry(
    element: UiElementObservation,
): Boolean {
    val semantic = listOf(element.text, element.viewId, element.className).joinToString(" ")
    return messagingSearchKeywords.any { semantic.contains(it, ignoreCase = true) }
}

internal fun skillRegistrySharedCharacterCount(
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

internal fun recentRejectedMessagingLabels(
    history: List<AgentTurnRecord>,
): Set<String> {
    if (history.size < 2) return emptySet()
    val mismatchKeywords =
        listOf(
            "并非目标联系人",
            "不是目标联系人",
            "不是目标会话",
            "错误上下文",
            "错误会话",
            "重新查找目标联系人",
            "重新搜索或查找正确的联系人",
            "返回上一层查找目标联系人",
        )
    return history
        .zipWithNext()
        .mapNotNull { (previous, current) ->
            if (!previous.action.startsWith("click(") || current.action != com.lmx.xiaoxuanagent.agent.AgentAction.Back.label) {
                return@mapNotNull null
            }
            if (mismatchKeywords.none { current.decisionReason.contains(it) }) {
                return@mapNotNull null
            }
            Regex("""点击 [^(]+\((.+?)\)""")
                .find(previous.result)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }
        .takeLast(3)
        .toSet()
}

internal fun findClickableElement(
    observation: ScreenObservation,
    keywords: List<String>,
    excludedElementId: String? = null,
) = observation.elements
    .asSequence()
    .filter { it.clickable && it.enabled }
    .filterNot { it.id == excludedElementId }
    .mapNotNull { element ->
        val semantic =
            listOf(element.text, element.viewId, element.className)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        val score =
            keywords.fold(0) { acc, keyword ->
                if (semantic.contains(keyword, ignoreCase = true)) acc + keyword.length else acc
            }
        if (score <= 0) null else element to score
    }
    .sortedByDescending { it.second }
    .map { it.first }
    .firstOrNull()

internal fun findContentObjectiveTarget(
    context: SkillDecisionContext,
): UiElementObservation? {
    val constraints = TaskIntentParser.parse(context.task)
    val keywords =
        buildList {
            constraints.objectiveHint?.takeIf { it.isNotBlank() }?.let(::add)
            if (constraints.objectiveHint?.contains("评论") == true || context.task.contains("评论")) {
                addAll(listOf("评论", "评论区", "全部评论", "查看全部评论"))
            }
            if (context.task.contains("高赞") || context.task.contains("热评") || context.task.contains("点赞最高")) {
                addAll(listOf("高赞", "热评", "最热", "按热度"))
            }
            if (context.task.contains("最新一期") || context.task.contains("最近一期")) {
                addAll(listOf("最新", "最近", "最新发布"))
            }
            if (context.task.contains("弹幕")) {
                addAll(listOf("弹幕", "查看弹幕"))
            }
        }.distinct()
    if (keywords.isEmpty()) return null
    return findClickableElement(context.observation, keywords)
}

internal fun rankContentCandidates(
    context: SkillDecisionContext,
): List<ContentCandidate> {
    val constraints = TaskIntentParser.parse(context.task)
    val cues =
        listOfNotNull(
            constraints.entryQuery.takeIf { it.isNotBlank() },
            constraints.normalizedGoal.takeIf { it.isNotBlank() },
            constraints.objectiveHint?.takeIf { it.isNotBlank() },
        ) + constraints.keywordHints
    return context.observation.elements
        .asSequence()
        .filter { it.clickable && it.enabled }
        .mapNotNull { element ->
            val semantic = listOf(element.text, element.viewId, element.className).joinToString(" ")
            if (semantic.isBlank()) return@mapNotNull null
            var score = 0
            cues.forEach { cue ->
                when {
                    element.text.equals(cue, ignoreCase = true) -> score += 120
                    element.text.contains(cue, ignoreCase = true) -> score += 70
                    semantic.contains(cue, ignoreCase = true) -> score += 40
                }
            }
            contentResultMemoryCues(context.memoryContext).forEach { cue ->
                when {
                    element.text.equals(cue, ignoreCase = true) -> score += 90
                    element.text.contains(cue, ignoreCase = true) -> score += 54
                    semantic.contains(cue, ignoreCase = true) -> score += 30
                }
            }
            if (element.text.length >= 8) score += 12
            if (listOf("视频", "文章", "作品", "笔记", "投稿", "播放").any { semantic.contains(it, ignoreCase = true) }) {
                score += 18
            }
            contentMetaKeywords.forEach { keyword ->
                if (semantic.contains(keyword, ignoreCase = true)) {
                    score -= keyword.length * 18
                }
            }
            if (score <= 0) {
                null
            } else {
                ContentCandidate(
                    id = element.id,
                    text = element.text.ifBlank { semantic.take(32) },
                    score = score,
                )
            }
        }.sortedByDescending { it.score }
        .toList()
}

internal fun contentResultMemoryCues(
    memoryContext: PlanningMemoryContext,
): List<String> =
    memoryContext.resultArtifacts
        .asSequence()
        .flatMap { memory ->
            memory.split("|")
                .map { it.trim() }
                .asSequence()
                .filter { it.length >= 2 && it.lowercase() != "content" }
        }
        .distinct()
        .take(6)
        .toList()

internal fun shouldRewriteContentClick(
    target: UiElementObservation,
    bestCandidate: ContentCandidate,
): Boolean {
    if (target.id == bestCandidate.id) return false
    val semantic = listOf(target.text, target.viewId, target.className).joinToString(" ")
    val penalty =
        contentMetaKeywords.sumOf { keyword ->
            if (semantic.contains(keyword, ignoreCase = true)) keyword.length * 18 else 0
        }
    val targetScore = if (penalty > 0) -penalty else if (target.text.length >= 8) 20 else 0
    return penalty > 0 || bestCandidate.score - targetScore >= 55
}
