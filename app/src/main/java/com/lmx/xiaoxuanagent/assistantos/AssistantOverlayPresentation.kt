package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.accessibility.ForegroundAutomationPhase
import com.lmx.xiaoxuanagent.accessibility.ForegroundAutomationSession
import com.lmx.xiaoxuanagent.runtime.AgentUiTakeoverReason
import com.lmx.xiaoxuanagent.runtime.isTakeoverReason

internal data class AssistantOverlayAction(
    val label: String,
    val action: String,
    val page: String = "",
)

internal data class AssistantOverlayPresentation(
    val title: String,
    val subtitle: String,
    val contextLine: String,
    val statusChips: List<String> = emptyList(),
    val glanceLines: List<String> = emptyList(),
    val actions: List<AssistantOverlayAction> = emptyList(),
    val secondaryActions: List<AssistantOverlayAction> = emptyList(),
)

internal object AssistantOverlayPresentationFactory {
    fun build(
        snapshot: AssistantOsSnapshot,
        productShell: AssistantProductShellSnapshot,
    ): AssistantOverlayPresentation {
        val activeSession = snapshot.activeSession
        val automation = ForegroundAutomationSession.snapshot(snapshot)
        val isPaused = activeSession.statusModel.isTakeoverReason(AgentUiTakeoverReason.PAUSED)
        val title =
            buildString {
                append("小轩")
                activeSession.statusCode.takeIf { it.isNotBlank() }?.let {
                    append(" · ").append(it.lowercase())
                }
            }
        val subtitle =
            when {
                activeSession.awaitingConfirmation -> "高风险动作待确认"
                automation.active -> automation.supervisionSummary
                productShell.companionShell.summary.isNotBlank() -> productShell.companionShell.summary
                isPaused -> activeSession.task.ifBlank { "任务已暂停，等待继续" }
                activeSession.waitingForExternal -> activeSession.summary.ifBlank { "等待外部事件恢复" }
                productShell.personalFocus.focusTask.isNotBlank() -> productShell.personalFocus.focusTask
                activeSession.task.isNotBlank() -> activeSession.task
                productShell.operatorShell.summary.isNotBlank() -> productShell.operatorShell.summary
                else -> snapshot.pendingActions.firstOrNull()?.title ?: "打开控制中心"
            }.take(32)
        val actions =
            buildList {
                add(AssistantOverlayAction(label = "打开", action = AssistantShellIntentRouter.ACTION_OPEN))
                when {
                    activeSession.awaitingConfirmation -> {
                        add(AssistantOverlayAction(label = "最后一步", action = AssistantShellIntentRouter.ACTION_APPROVE))
                        add(AssistantOverlayAction(label = "拒绝", action = AssistantShellIntentRouter.ACTION_REJECT))
                    }

                    isPaused -> {
                        add(AssistantOverlayAction(label = "继续", action = AssistantShellIntentRouter.ACTION_RESUME))
                        if (activeSession.targetAppReturnEligible) {
                            add(AssistantOverlayAction(label = "回现场", action = AssistantShellIntentRouter.ACTION_RETURN_TARGET))
                        }
                    }

                    activeSession.sessionId.isNotBlank() -> {
                        add(AssistantOverlayAction(label = "暂停", action = AssistantShellIntentRouter.ACTION_PAUSE))
                        if (activeSession.targetAppReturnEligible) {
                            add(AssistantOverlayAction(label = "回现场", action = AssistantShellIntentRouter.ACTION_RETURN_TARGET))
                        }
                    }

                    else -> {
                        if (productShell.voiceInteraction.availabilitySummary == "voice_ready") {
                            add(
                                AssistantOverlayAction(
                                    label =
                                        if (productShell.voiceInteraction.state in setOf("listening", "partial", "executing")) {
                                            "停语音"
                                        } else {
                                            "开语音"
                                        },
                                    action = AssistantShellIntentRouter.ACTION_VOICE_TOGGLE,
                                ),
                            )
                        }
                        productShell.companionShell.recommendedCommands.firstOrNull()?.let { command ->
                            add(
                                AssistantOverlayAction(
                                    label = if (command.contains("memory")) "记忆" else "协同",
                                    action = AssistantShellIntentRouter.ACTION_OPEN,
                                    page = if (command.contains("memory")) "memory" else "viewer",
                                ),
                            )
                        }
                        productShell.tips.firstOrNull()?.let {
                            add(
                                AssistantOverlayAction(
                                    label = it.actionLabel.take(6).ifBlank { "处理" },
                                    action = AssistantShellIntentRouter.ACTION_OPEN,
                                    page = it.recommendedPage,
                                ),
                            )
                        }
                    }
                }
            }.distinctBy { it.action to it.label }.take(3)
        val secondaryActions =
            buildList {
                add(AssistantOverlayAction(label = "今日", action = AssistantShellIntentRouter.ACTION_OPEN, page = "today"))
                add(AssistantOverlayAction(label = "收件箱", action = AssistantShellIntentRouter.ACTION_OPEN, page = "inbox"))
                if (activeSession.sessionId.isNotBlank()) {
                    add(AssistantOverlayAction(label = "Viewer", action = AssistantShellIntentRouter.ACTION_OPEN, page = "viewer"))
                }
                if (activeSession.awaitingConfirmation) {
                    add(AssistantOverlayAction(label = "审批", action = AssistantShellIntentRouter.ACTION_OPEN, page = "approvals"))
                }
                if (productShell.autonomyPlan.mode.isNotBlank() && productShell.autonomyPlan.mode != "idle") {
                    add(AssistantOverlayAction(label = "治理", action = AssistantShellIntentRouter.ACTION_OPEN, page = "governance"))
                }
            }.distinctBy { it.page.ifBlank { it.action } }.take(3)
        val statusChips =
            buildList {
                add(activeSession.statusCode.ifBlank { "idle" }.lowercase())
                if (activeSession.awaitingConfirmation) add("待确认")
                if (isPaused) add("暂停")
                if (activeSession.waitingForExternal) add("外部等待")
                automation.releaseGateStatus.takeIf { it.isNotBlank() && it != "ungated" }?.let { add("gate:$it") }
                automation.executionPath.takeIf { it.isNotBlank() }?.let { add(it.removePrefix("foreground_").removePrefix("connected_")) }
                automation.productSupportTier.takeIf { it.isNotBlank() }?.let { add(if (it == "focused") "精选支持" else "实验支持") }
                productShell.companionShell.mode.takeIf { it.isNotBlank() && it != "idle" }?.let(::add)
                productShell.swarmStrategy.mode.takeIf { it.isNotBlank() }?.let(::add)
                productShell.autonomyPlan.mode.takeIf { it.isNotBlank() && it != "idle" }?.let(::add)
            }.distinct().take(4)
        val glanceLines =
            buildList {
                productShell.companionShell.laneLines.take(2).forEach(::add)
                automation.currentStep.takeIf { it.isNotBlank() }?.let { add("步骤: $it") }
                automation.nextStep.takeIf { it.isNotBlank() }?.let { add("下一步: $it") }
                automation.handoffSummary.takeIf { it.isNotBlank() }?.let { add("交接: $it") }
                automation.productSupportSummary.takeIf { it.isNotBlank() }?.let { add("支持: $it") }
                automation.productSupport.supportCeiling.takeIf { it.isNotBlank() }?.let { add("上限: $it") }
                automation.productSupport.consumerPermissionHint.takeIf { it.isNotBlank() }?.let { add("授权: $it") }
                productShell.digestShell.summary.takeIf { it.isNotBlank() }?.let { add("今日: $it") }
                productShell.personalFocus.focusTask.takeIf { it.isNotBlank() }?.let { add("焦点: $it") }
                productShell.viewerShell.resultSummary.takeIf { it.isNotBlank() }?.let { add("结果: $it") }
                productShell.autonomyPlan.summary.takeIf { it.isNotBlank() }?.let { add("自治: $it") }
                productShell.userModel.summary.takeIf { it.isNotBlank() }?.let { add("记忆: $it") }
                productShell.operatorShell.summary.takeIf { it.isNotBlank() }?.let { add("调度: $it") }
                if (snapshot.pendingActions.isNotEmpty()) {
                    add("收件箱: ${snapshot.pendingActions.size} 条待处理")
                }
            }.distinct().take(4)
        return AssistantOverlayPresentation(
            title = title,
            subtitle = subtitle,
            contextLine =
                buildString {
                    append("focus=").append(productShell.personalFocus.focusTask.ifBlank { activeSession.task.ifBlank { "-" } })
                    append(" | inbox=").append(snapshot.pendingActions.size)
                    append(" | autonomy=").append(productShell.autonomyPlan.mode.ifBlank { "-" })
                    append(" | voice=").append(productShell.voiceInteraction.interactionMode.ifBlank { productShell.voiceInteraction.state.ifBlank { "-" } })
                    append(" | surface=").append(snapshot.surfaces.count { it.available && it.enabled })
                    if (automation.phase == ForegroundAutomationPhase.HANDOFF) {
                        append(" | handoff=ready")
                    }
                },
            statusChips = statusChips,
            glanceLines = glanceLines,
            actions = actions,
            secondaryActions = secondaryActions,
        )
    }
}
