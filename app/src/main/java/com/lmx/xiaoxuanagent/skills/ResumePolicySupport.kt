package com.lmx.xiaoxuanagent.skills

import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.AgentSemanticActionComposer
import com.lmx.xiaoxuanagent.agent.AgentTurnRecord
import com.lmx.xiaoxuanagent.agent.RecoveryCategory
import com.lmx.xiaoxuanagent.agent.ResumeContext
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.TaskIntentParser
import com.lmx.xiaoxuanagent.agent.UiElementObservation
import com.lmx.xiaoxuanagent.runtime.RuntimeResumePolicy

internal val resumeAlignmentStages =
    setOf("enter_query", "find_conversation", "enter_destination")

internal val resumeContinuationStages =
    setOf("submit_query", "confirm_route", "confirm_send")

internal fun isResumeEntryAlignmentSource(
    resumeContext: ResumeContext,
): Boolean {
    if (!resumeContext.active) return false
    return resumeContext.source == "cold_start_resume" ||
        resumeContext.source == "resume_snapshot_continue" ||
        resumeContext.source == "resume_snapshot_correction_continue"
}

internal fun latestResumeAlignmentAttempt(
    history: List<AgentTurnRecord>,
): ResumeAlignmentAttempt? =
    history
        .asReversed()
        .firstNotNullOfOrNull { turn ->
            if (!turn.decisionReason.contains("恢复入口对齐")) {
                return@firstNotNullOfOrNull null
            }
            SkillActionParser.parseActionLabel(turn.action)?.let { action ->
                ResumeAlignmentAttempt(
                    action = action,
                    observationSignature = turn.observationSignature,
                )
            }
        }

internal fun latestResumeContinuationAttempt(
    history: List<AgentTurnRecord>,
): ResumeAlignmentAttempt? =
    history
        .asReversed()
        .firstNotNullOfOrNull { turn ->
            if (!turn.decisionReason.contains("恢复继续后已回到")) {
                return@firstNotNullOfOrNull null
            }
            SkillActionParser.parseActionLabel(turn.action)?.let { action ->
                ResumeAlignmentAttempt(
                    action = action,
                    observationSignature = turn.observationSignature,
                )
            }
        }

internal fun resumeContinuationFailureProfile(
    history: List<AgentTurnRecord>,
): ResumeContinuationFailureProfile {
    val turns =
        history
            .asReversed()
            .filter { turn ->
                turn.decisionReason.contains("恢复继续后已回到") ||
                    turn.decisionReason.contains("恢复继续阶段")
            }
            .take(4)
            .toList()
    if (turns.isEmpty()) {
        return ResumeContinuationFailureProfile()
    }
    val latestTurn = turns.first()
    var consecutiveNoProgress = 0
    var consecutiveEmptyObservation = 0
    turns.forEach { turn ->
        when (turn.recoveryCategory) {
            RecoveryCategory.NO_PROGRESS.name -> {
                if (consecutiveEmptyObservation > 0) return@forEach
                consecutiveNoProgress += 1
            }

            RecoveryCategory.EMPTY_OBSERVATION.name -> {
                if (consecutiveNoProgress > 0) return@forEach
                consecutiveEmptyObservation += 1
            }

            else -> return@forEach
        }
    }
    return ResumeContinuationFailureProfile(
        latestAction = SkillActionParser.parseActionLabel(latestTurn.action),
        latestRecoveryCategory = latestTurn.recoveryCategory,
        consecutiveNoProgressCount = consecutiveNoProgress,
        consecutiveEmptyObservationCount = consecutiveEmptyObservation,
    )
}

internal fun resumeContinuationClickElementId(
    action: AgentAction?,
): String? =
    when (action) {
        is AgentAction.Click -> action.elementId
        is AgentAction.LongClick -> action.elementId
        else -> null
    }

internal fun resumeMessageBodyVisible(
    observation: ScreenObservation,
    messageBody: String,
): Boolean = messageBody.isNotBlank() && resumePolicyObservationSemantics(observation).contains(messageBody, ignoreCase = true)

internal fun resumeEntryStageLabel(
    stage: String,
): String =
    when (stage) {
        "find_conversation" -> "会话/联系人入口"
        "enter_destination" -> "目的地入口"
        "enter_query" -> "搜索入口"
        "submit_query" -> "查询提交入口"
        "confirm_route" -> "路线确认入口"
        "confirm_send" -> "发送确认入口"
        else -> "继续入口"
    }

internal fun resolveResumeAlignmentTarget(
    task: String,
    stage: String,
): String {
    val constraints = TaskIntentParser.parse(task)
    return when (stage) {
        "find_conversation" -> constraints.recipientName.orEmpty().ifBlank { constraints.entryQuery }
        "enter_destination" -> constraints.destination.orEmpty().ifBlank { constraints.entryQuery }
        "enter_query" -> constraints.entryQuery
        else -> ""
    }.trim()
}

internal fun resumeAlignmentTargetVisible(
    observation: ScreenObservation,
    target: String,
): Boolean {
    if (target.isBlank()) return false
    return (observation.topTexts + observation.elements.map { it.text })
        .any { text -> text.contains(target, ignoreCase = true) }
}

internal fun resolveResumeAlignmentRecoveryAction(
    task: String,
    stage: String,
    observation: ScreenObservation,
    history: List<AgentTurnRecord>,
): AgentAction? {
    val target = resolveResumeAlignmentTarget(task, stage)
    if (target.isBlank()) return null
    val latestAttempt = latestResumeAlignmentAttempt(history)
    val latestClickElementId = resumeContinuationClickElementId(latestAttempt?.action)
    val latestWasPopulate =
        latestAttempt?.action is AgentAction.SetText || latestAttempt?.action is AgentAction.PopulatePrimaryInput
    AgentSemanticActionComposer.entryAlignmentAction(
        targetText = target,
        stage = stage,
        observation = observation,
        preferPopulate = !latestWasPopulate,
    )?.let { semanticAction ->
        if (semanticAction !is AgentAction.OpenBestCandidate) {
            return semanticAction
        }
    }

    return findResumeAlignmentEntryElement(
        observation = observation,
        stage = stage,
        target = target,
        excludedElementId = latestClickElementId,
    )?.let {
        AgentAction.OpenBestCandidate(
            targetText = it.text.ifBlank { target },
            roleHint = AgentSemanticActionComposer.roleHintForStage(stage),
        )
    }
}

internal fun resolveResumeContinuationAction(
    stage: String,
    task: String,
    observation: ScreenObservation,
    history: List<AgentTurnRecord>,
): AgentAction? {
    val latestAttempt = latestResumeContinuationAttempt(history)
    val failureProfile = resumeContinuationFailureProfile(history)
    return when (stage) {
        "submit_query", "confirm_route", "confirm_send" ->
            AgentSemanticActionComposer.continuationAction(
                stage = stage,
                task = task,
                observation = observation,
                preferPopulate = failureProfile.consecutiveNoProgressCount <= 0,
            )
        else -> null
    }?.takeUnless { action ->
        val lastAction = latestAttempt?.action ?: return@takeUnless false
        action.label == lastAction.label
    }
}

internal fun resolveResumeContinuationRecoveryAction(
    stage: String,
    task: String,
    observation: ScreenObservation,
    history: List<AgentTurnRecord>,
): AgentAction? {
    val latestAttempt = latestResumeContinuationAttempt(history)
    val failureProfile = resumeContinuationFailureProfile(history)
    val excludedElementId = resumeContinuationClickElementId(latestAttempt?.action)
    return when (stage) {
        "submit_query" ->
            resolveResumeSubmitQueryRecoveryAction(observation, excludedElementId, failureProfile, latestAttempt)
        "confirm_route" ->
            AgentSemanticActionComposer.continuationAction(
                stage = stage,
                task = task,
                observation = observation,
                preferPopulate = false,
            )
        "confirm_send" ->
            resolveResumeConfirmSendRecoveryAction(observation, task, excludedElementId, failureProfile)
        else -> null
    }
}

internal data class ResumeAlignmentAttempt(
    val action: AgentAction,
    val observationSignature: String,
)

internal data class ResumeActionDirective(
    val action: AgentAction,
    val policy: RuntimeResumePolicy,
)

internal data class ResumeContinuationFailureProfile(
    val latestAction: AgentAction? = null,
    val latestRecoveryCategory: String = "",
    val consecutiveNoProgressCount: Int = 0,
    val consecutiveEmptyObservationCount: Int = 0,
)

internal fun resolveResumeAlignmentPrePlanDirective(
    task: String,
    stage: String,
    observation: ScreenObservation,
): ResumeActionDirective? {
    val target = resolveResumeAlignmentTarget(task, stage)
    if (target.isBlank()) return null
    val targetAlreadyVisible = resumeAlignmentTargetVisible(observation, target)
    if (observation.primaryEditableId != null) {
        if (!targetAlreadyVisible) {
            return ResumeActionDirective(
                action = AgentAction.PopulatePrimaryInput(target),
                policy = RuntimeResumePolicy.SET_ALIGNMENT_TARGET,
            )
        }
        if (observation.focusedElementId != observation.primaryEditableId) {
            return ResumeActionDirective(
                action = AgentAction.FocusPrimaryInput,
                policy = RuntimeResumePolicy.FOCUS_PRIMARY_EDITABLE,
            )
        }
        return null
    }
    val entryElement =
        findResumeAlignmentEntryElement(
            observation = observation,
            stage = stage,
            target = target,
        ) ?: return null
    return ResumeActionDirective(
        action = AgentAction.OpenBestCandidate(
            targetText = entryElement.text.ifBlank { target },
            roleHint = AgentSemanticActionComposer.roleHintForStage(stage),
        ),
        policy = RuntimeResumePolicy.CLICK_ALIGNMENT_ENTRY,
    )
}

internal fun resolveResumeAlignmentRecoveryDirective(
    task: String,
    stage: String,
    observation: ScreenObservation,
    history: List<AgentTurnRecord>,
): ResumeActionDirective? =
    resolveResumeAlignmentRecoveryAction(
        task = task,
        stage = stage,
        observation = observation,
        history = history,
    )?.let { action ->
        ResumeActionDirective(
            action = action,
            policy =
                when (action) {
                    is AgentAction.SetText,
                    is AgentAction.PopulatePrimaryInput,
                    -> RuntimeResumePolicy.RECOVER_SET_ALIGNMENT_TARGET
                    is AgentAction.Click,
                    AgentAction.FocusPrimaryInput,
                    -> if (action is AgentAction.Click && action.elementId == observation.primaryEditableId || action == AgentAction.FocusPrimaryInput) {
                            RuntimeResumePolicy.RECOVER_FOCUS_PRIMARY_EDITABLE
                        } else {
                            RuntimeResumePolicy.RECOVER_CLICK_ALIGNMENT_ENTRY
                        }
                    else -> RuntimeResumePolicy.RECOVER_ALIGNMENT_ACTION
                },
        )
    }

internal fun resolveResumeContinuationPrePlanDirective(
    stage: String,
    task: String,
    observation: ScreenObservation,
    history: List<AgentTurnRecord>,
): ResumeActionDirective? =
    resolveResumeContinuationAction(
        stage = stage,
        task = task,
        observation = observation,
        history = history,
    )?.let { action ->
        ResumeActionDirective(
            action = action,
            policy = resumeContinuationActionPolicy(stage, action, recovering = false, observation = observation),
        )
    }

internal fun resolveResumeContinuationRecoveryDirective(
    stage: String,
    task: String,
    observation: ScreenObservation,
    history: List<AgentTurnRecord>,
): ResumeActionDirective? =
    resolveResumeContinuationRecoveryAction(
        stage = stage,
        task = task,
        observation = observation,
        history = history,
    )?.let { action ->
        ResumeActionDirective(
            action = action,
            policy = resumeContinuationActionPolicy(stage, action, recovering = true, observation = observation),
        )
    }

private fun resumePolicyObservationSemantics(
    observation: ScreenObservation,
): String =
    buildList {
        add(observation.pageState)
        add(observation.screenSummary)
        addAll(observation.topTexts)
        addAll(observation.interruptiveHints.map { it.text })
        addAll(observation.elements.mapNotNull { it.text.takeIf(String::isNotBlank) })
    }.joinToString(" ")

private fun resumeAlignmentStageKeywords(
    stage: String,
): List<String> =
    when (stage) {
        "find_conversation" ->
            listOf("搜索", "查找", "联系人", "通讯录", "会话", "聊天", "发消息", "搜索指定内容")

        "enter_destination" ->
            listOf("搜索地点", "输入目的地", "你要去哪", "目的地", "到哪去", "去哪儿", "前往")

        "enter_query" ->
            listOf("搜索", "搜一搜", "查找", "请输入", "搜索框", "输入关键词")

        else -> emptyList()
    }

internal fun findResumeAlignmentEntryElement(
    observation: ScreenObservation,
    stage: String,
    target: String,
    excludedElementId: String? = null,
): UiElementObservation? {
    if (stage == "find_conversation") {
        val recipientCandidate = findResumeConversationCandidate(observation, target, excludedElementId)
        if (recipientCandidate != null) {
            return recipientCandidate
        }
    }
    val keywords =
        buildList {
            addAll(resumeAlignmentStageKeywords(stage))
            target.takeIf { it.isNotBlank() }?.let(::add)
        }
    return findResumeClickableElement(observation, keywords, excludedElementId)
}

private fun findResumeConversationCandidate(
    observation: ScreenObservation,
    target: String,
    excludedElementId: String? = null,
): UiElementObservation? {
    if (target.isBlank()) return null
    return observation.elements
        .asSequence()
        .filter { it.clickable && it.enabled }
        .filterNot { it.id == excludedElementId }
        .mapNotNull { element ->
            val semantic =
                listOf(element.text, element.viewId, element.className)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
            var score = 0
            if (element.text.equals(target, ignoreCase = true)) {
                score += 180
            } else if (element.text.contains(target, ignoreCase = true)) {
                score += 120
            } else if (semantic.contains(target, ignoreCase = true)) {
                score += 70
            }
            if (looksLikeMessagingSearchEntry(element)) {
                score -= 80
            }
            if (element.selected || element.focused) {
                score += 10
            }
            if (score <= 0) null else element to score
        }.sortedByDescending { it.second }
        .map { it.first }
        .firstOrNull()
}

private fun resolveResumeSubmitQueryAction(
    observation: ScreenObservation,
    failureProfile: ResumeContinuationFailureProfile,
): AgentAction? {
        if (failureProfile.latestRecoveryCategory == RecoveryCategory.NO_PROGRESS.name) {
            observation.primaryEditableId
                ?.takeIf { observation.focusedElementId == it }
                ?.let { return AgentAction.SubmitPrimaryAction("搜索") }
            observation.primaryEditableId?.let { return AgentAction.FocusPrimaryInput }
        }
        if (failureProfile.latestRecoveryCategory == RecoveryCategory.EMPTY_OBSERVATION.name) {
            findResumeSubmitElement(
                observation = observation,
                excludedElementId = resumeContinuationClickElementId(failureProfile.latestAction),
                preferPrimaryAction = true,
            )?.let {
                return AgentAction.OpenBestCandidate(
                    targetText = it.text,
                    roleHint = "submit",
                )
            }
        }
    return findResumeSubmitElement(observation, excludedElementId = null, preferPrimaryAction = true)?.let {
        AgentAction.OpenBestCandidate(
            targetText = it.text,
            roleHint = "submit",
        )
    }
        ?: observation.primaryEditableId
            ?.takeIf { observation.focusedElementId == it }
            ?.let { AgentAction.SubmitPrimaryAction("搜索") }
}

private fun resolveResumeSubmitQueryRecoveryAction(
    observation: ScreenObservation,
    excludedElementId: String?,
    failureProfile: ResumeContinuationFailureProfile,
    latestAttempt: ResumeAlignmentAttempt?,
): AgentAction? {
    if (failureProfile.consecutiveNoProgressCount >= 1) {
        observation.primaryEditableId
            ?.takeIf { observation.focusedElementId == it && latestAttempt?.action != AgentAction.PressEnter }
            ?.let { return AgentAction.SubmitPrimaryAction("搜索") }
        observation.primaryEditableId
            ?.takeIf { observation.focusedElementId != it }
            ?.let { return AgentAction.FocusPrimaryInput }
    }
    return findResumeSubmitElement(
        observation = observation,
        excludedElementId = excludedElementId,
        preferPrimaryAction = failureProfile.consecutiveEmptyObservationCount <= 0,
    )?.let {
        AgentAction.OpenBestCandidate(
            targetText = it.text,
            roleHint = "submit",
        )
    }
}

private fun resolveResumeConfirmSendAction(
    observation: ScreenObservation,
    task: String,
    failureProfile: ResumeContinuationFailureProfile,
): AgentAction? {
    val constraints = TaskIntentParser.parse(task)
    val messageBody = constraints.messageBody.orEmpty()
    if (messageBody.isNotBlank() && !resumeMessageBodyVisible(observation, messageBody) && observation.primaryEditableId != null) {
        return AgentAction.PopulatePrimaryInput(messageBody)
    }
    if (failureProfile.latestRecoveryCategory == RecoveryCategory.NO_PROGRESS.name) {
        observation.primaryEditableId
            ?.takeIf { observation.focusedElementId == it && messageBody.isNotBlank() }
            ?.let { return AgentAction.SubmitPrimaryAction("发送") }
    }
    return findResumeSendConfirmElement(
        observation = observation,
        task = task,
        excludedElementId = null,
        preferPrimaryAction = failureProfile.consecutiveEmptyObservationCount <= 0,
    )?.let {
        AgentAction.OpenBestCandidate(
            targetText = it.text,
            roleHint = "submit",
        )
    }
}

private fun resolveResumeConfirmSendRecoveryAction(
    observation: ScreenObservation,
    task: String,
    excludedElementId: String?,
    failureProfile: ResumeContinuationFailureProfile,
): AgentAction? {
    val constraints = TaskIntentParser.parse(task)
    val messageBody = constraints.messageBody.orEmpty()
    if (messageBody.isNotBlank() && !resumeMessageBodyVisible(observation, messageBody) && observation.primaryEditableId != null) {
        return AgentAction.PopulatePrimaryInput(messageBody)
    }
    if (failureProfile.consecutiveNoProgressCount >= 1) {
        observation.primaryEditableId
            ?.takeIf { observation.focusedElementId == it && messageBody.isNotBlank() }
            ?.let { return AgentAction.SubmitPrimaryAction("发送") }
    }
    return findResumeSendConfirmElement(
        observation = observation,
        task = task,
        excludedElementId = excludedElementId,
        preferPrimaryAction = failureProfile.consecutiveEmptyObservationCount <= 0,
    )?.let {
        AgentAction.OpenBestCandidate(
            targetText = it.text,
            roleHint = "submit",
        )
    }
}

private fun findResumeSubmitElement(
    observation: ScreenObservation,
    excludedElementId: String? = null,
    preferPrimaryAction: Boolean = true,
): UiElementObservation? =
    findResumeClickableElement(
        observation = observation,
        keywords =
            if (preferPrimaryAction) {
                listOf("搜索", "查找", "确认", "完成", "前往", "查看", "进入")
            } else {
                listOf("确认", "完成", "前往", "进入", "查看", "搜索", "查找")
            },
        excludedElementId = excludedElementId,
    )

private fun findResumeRouteConfirmElement(
    observation: ScreenObservation,
    excludedElementId: String? = null,
    preferPrimaryAction: Boolean = true,
): UiElementObservation? =
    findResumeClickableElement(
        observation = observation,
        keywords =
            if (preferPrimaryAction) {
                listOf("开始导航", "导航", "开始", "到这去", "路线", "确认")
            } else {
                listOf("路线", "到这去", "导航", "开始导航", "确认", "开始")
            },
        excludedElementId = excludedElementId,
    )

private fun findResumeSendConfirmElement(
    observation: ScreenObservation,
    task: String,
    excludedElementId: String? = null,
    preferPrimaryAction: Boolean = true,
): UiElementObservation? {
    val constraints = TaskIntentParser.parse(task)
    val messageBody = constraints.messageBody.orEmpty()
    if (messageBody.isNotBlank() && !resumeMessageBodyVisible(observation, messageBody)) {
        return null
    }
    return findResumeClickableElement(
        observation = observation,
        keywords =
            if (preferPrimaryAction) {
                listOf("发送", "发消息", "确认", "完成", "发送给", "回车发送")
            } else {
                listOf("确认", "完成", "发送", "发消息", "发送给", "回车发送")
            },
        excludedElementId = excludedElementId,
    )
}

private fun findResumeClickableElement(
    observation: ScreenObservation,
    keywords: List<String>,
    excludedElementId: String? = null,
): UiElementObservation? =
    observation.elements
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
        }.sortedByDescending { it.second }
        .map { it.first }
        .firstOrNull()

private fun looksLikeMessagingSearchEntry(
    element: UiElementObservation,
): Boolean {
    val semantic = listOf(element.text, element.viewId, element.className).joinToString(" ")
    return listOf("搜索", "查找", "联系人", "通讯录", "添加朋友", "搜索指定内容", "会话", "放大镜")
        .any { semantic.contains(it, ignoreCase = true) }
}

private fun resumeContinuationActionPolicy(
    stage: String,
    action: AgentAction,
    recovering: Boolean,
    observation: ScreenObservation,
): RuntimeResumePolicy =
    when (stage) {
        "submit_query" ->
            when (action) {
                AgentAction.PressEnter ->
                    if (recovering) RuntimeResumePolicy.RECOVER_SUBMIT_QUERY_PRESS_ENTER else RuntimeResumePolicy.SUBMIT_QUERY_PRESS_ENTER
                is AgentAction.Click ->
                    if (action.elementId == observation.primaryEditableId) {
                        if (recovering) RuntimeResumePolicy.RECOVER_SUBMIT_QUERY_FOCUS_INPUT else RuntimeResumePolicy.SUBMIT_QUERY_FOCUS_INPUT
                    } else {
                        if (recovering) RuntimeResumePolicy.RECOVER_SUBMIT_QUERY_CLICK_ACTION else RuntimeResumePolicy.SUBMIT_QUERY_CLICK_ACTION
                    }
                else -> if (recovering) RuntimeResumePolicy.RECOVER_SUBMIT_QUERY_ACTION else RuntimeResumePolicy.SUBMIT_QUERY_ACTION
            }

        "confirm_route" ->
            when (action) {
                is AgentAction.Click ->
                    if (recovering) RuntimeResumePolicy.RECOVER_CONFIRM_ROUTE_CLICK_ACTION else RuntimeResumePolicy.CONFIRM_ROUTE_CLICK_ACTION
                else -> if (recovering) RuntimeResumePolicy.RECOVER_CONFIRM_ROUTE_ACTION else RuntimeResumePolicy.CONFIRM_ROUTE_ACTION
            }

        "confirm_send" ->
            when (action) {
                is AgentAction.SetText ->
                    if (recovering) RuntimeResumePolicy.RECOVER_CONFIRM_SEND_RESTORE_BODY else RuntimeResumePolicy.CONFIRM_SEND_RESTORE_BODY
                AgentAction.PressEnter ->
                    if (recovering) RuntimeResumePolicy.RECOVER_CONFIRM_SEND_PRESS_ENTER else RuntimeResumePolicy.CONFIRM_SEND_PRESS_ENTER
                is AgentAction.Click ->
                    if (action.elementId == observation.primaryEditableId) {
                        if (recovering) RuntimeResumePolicy.RECOVER_CONFIRM_SEND_FOCUS_INPUT else RuntimeResumePolicy.CONFIRM_SEND_FOCUS_INPUT
                    } else {
                        if (recovering) RuntimeResumePolicy.RECOVER_CONFIRM_SEND_CLICK_ACTION else RuntimeResumePolicy.CONFIRM_SEND_CLICK_ACTION
                    }
                else -> if (recovering) RuntimeResumePolicy.RECOVER_CONFIRM_SEND_ACTION else RuntimeResumePolicy.CONFIRM_SEND_ACTION
            }

        else -> RuntimeResumePolicy.NONE
    }
