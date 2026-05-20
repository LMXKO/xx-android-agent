package com.lmx.xiaoxuanagent.skills

import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.AgentTurnRecord
import com.lmx.xiaoxuanagent.agent.RecoveryCategory
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.TaskIntentParser
import com.lmx.xiaoxuanagent.agent.TaskIntentType
import com.lmx.xiaoxuanagent.agent.UiElementObservation

object SkillGuardPolicies {
    fun rewriteLaunchAppIfAlreadyForeground(
        decision: AgentDecision,
        observation: ScreenObservation,
    ): AgentDecision? {
        val action = decision.action as? AgentAction.LaunchApp ?: return null
        if (action.packageName != observation.packageName) return null
        return decision.copy(
            action = AgentAction.Wait,
            reason = "通用守护：当前前台已经是目标应用 ${action.packageName}，先等待并继续观察页面。",
        )
    }

    fun rewriteSetTextToCurrentTarget(
        task: String,
        observation: ScreenObservation,
        decision: AgentDecision,
    ): AgentDecision? {
        val currentText =
            when (val action = decision.action) {
                is AgentAction.SetText -> action.text
                is AgentAction.PopulatePrimaryInput -> action.text
                else -> return null
            }
        val constraints = TaskIntentParser.parse(task)
        val targetText =
            when (constraints.intentType) {
                TaskIntentType.MESSAGING -> resolveMessagingInputTarget(constraints, observation, currentText)
                else -> constraints.entryQuery
            }
        if (targetText.isBlank() || currentText == targetText) return null
        return decision.copy(
            action = AgentAction.PopulatePrimaryInput(targetText),
            reason = "通用守护：输入文本统一改写为主输入语义动作，当前目标词为 $targetText。",
        )
    }

    fun rewriteRepeatedBacktrackClick(
        observation: ScreenObservation,
        decision: AgentDecision,
        history: List<AgentTurnRecord>,
    ): AgentDecision? {
        val clickAction = decision.action as? AgentAction.Click ?: return null
        val target = observation.elements.firstOrNull { it.id == clickAction.elementId }
        if (target != null && shouldAllowImmediateRetap(target)) {
            return null
        }
        val repeatedTargets = recentlyBacktrackedElementIds(history)
        if (clickAction.elementId !in repeatedTargets) return null
        val replacementAction =
            observation.defaultScrollableId?.let { AgentAction.ScrollForMore("down") } ?: AgentAction.Wait
        return decision.copy(
            action = replacementAction,
            reason =
                "通用守护：目标 ${target?.text?.ifBlank { clickAction.elementId } ?: clickAction.elementId} " +
                    "刚触发过一次返回纠正，本轮先${if (replacementAction is AgentAction.ScrollForMore) "继续探索" else "等待重观察"}，避免再次陷入往返循环。",
        )
    }

    fun rewriteRepeatedStalledAction(
        observation: ScreenObservation,
        decision: AgentDecision,
        history: List<AgentTurnRecord>,
    ): AgentDecision? {
        val latestFailure = history.lastOrNull()?.recoveryCategory ?: return null
        if (latestFailure != RecoveryCategory.NO_PROGRESS.name) return null
        val latestAction = history.lastOrNull()?.action.orEmpty()
        if (latestAction != decision.action.label) return null
        val fallbackAction =
            observation.defaultScrollableId?.let { AgentAction.ScrollForMore("down") } ?: AgentAction.Wait
        return decision.copy(
            action = fallbackAction,
            reason =
                "通用守护：上一轮同动作没有产生进展，本轮先${if (fallbackAction is AgentAction.ScrollForMore) "切换为继续探索" else "等待页面稳定"}，避免重复空转。",
        )
    }

    fun rewriteRepeatedOverscroll(
        observation: ScreenObservation,
        decision: AgentDecision,
        history: List<AgentTurnRecord>,
    ): AgentDecision? {
        val latestFailure = history.lastOrNull()?.recoveryCategory ?: return null
        if (latestFailure != RecoveryCategory.OVERSCROLLED.name) return null
        val currentAction = decision.action
        val direction =
            when (currentAction) {
                is AgentAction.Scroll -> currentAction.direction
                is AgentAction.ScrollForMore -> currentAction.direction
                else -> return null
            }
        if (direction.equals("up", ignoreCase = true)) return null
        if (observation.defaultScrollableId == null) return null
        return decision.copy(
            action = AgentAction.ScrollForMore("up"),
            reason = "通用守护：上一轮已判定滚动过头，本轮先使用语义回拉而不是继续向下滚动。",
        )
    }

    private fun resolveMessagingInputTarget(
        constraints: com.lmx.xiaoxuanagent.agent.TaskConstraints,
        observation: ScreenObservation,
        currentText: String,
    ): String {
        val recipient = constraints.recipientName.orEmpty()
        val message = constraints.messageBody.orEmpty()
        val semantics =
            (observation.topTexts + observation.elements.mapNotNull { it.text.takeIf(String::isNotBlank) })
                .joinToString(" ")
        val hasSearchCue =
            listOf("搜索", "查找", "联系人", "通讯录", "添加朋友").any { semantics.contains(it) }
        val hasComposeCue =
            listOf("发送", "按住说话", "输入消息", "发消息", "回车发送").any { semantics.contains(it) } ||
                observation.primaryEditableId != null
        val conversationVerified =
            recipient.isBlank() || semantics.contains(recipient)

        if (message.isNotBlank() && currentText == message && conversationVerified) {
            return message
        }
        if (recipient.isNotBlank() && currentText == recipient) {
            return recipient
        }

        val inComposeStage =
            message.isNotBlank() &&
                conversationVerified &&
                hasComposeCue &&
                !hasSearchCue

        return when {
            inComposeStage -> message
            recipient.isNotBlank() -> recipient
            message.isNotBlank() -> message
            else -> constraints.entryQuery
        }
    }

    private fun recentlyBacktrackedElementIds(
        history: List<AgentTurnRecord>,
    ): Set<String> {
        if (history.size < 2) return emptySet()
        return history
            .windowed(size = 2, step = 1, partialWindows = false)
            .mapNotNull { window ->
                val first = window[0]
                val second = window[1]
                val elementId = extractClickedElementId(first.action) ?: return@mapNotNull null
                if (second.action != AgentAction.Back.label) {
                    return@mapNotNull null
                }
                elementId
            }
            .toSet()
    }

    private fun extractClickedElementId(
        actionLabel: String,
    ): String? =
        Regex("""^(?:click|long_click)\(([^)]+)\)$""")
            .find(actionLabel)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun shouldAllowImmediateRetap(
        element: UiElementObservation,
    ): Boolean {
        val semantic = listOf(element.text, element.viewId, element.className).joinToString(" ")
        return listOf(
            "搜索",
            "查找",
            "关闭",
            "跳过",
            "允许",
            "导航",
            "路线",
        ).any { keyword -> semantic.contains(keyword, ignoreCase = true) }
    }
}
