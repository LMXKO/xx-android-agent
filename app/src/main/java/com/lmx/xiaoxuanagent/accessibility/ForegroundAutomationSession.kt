package com.lmx.xiaoxuanagent.accessibility

import com.lmx.xiaoxuanagent.agent.TaskIntentParser
import com.lmx.xiaoxuanagent.agent.ConnectedAppExecutionReceiptStore
import com.lmx.xiaoxuanagent.assistantos.AssistantOsController
import com.lmx.xiaoxuanagent.assistantos.AssistantOsSnapshot
import com.lmx.xiaoxuanagent.runtime.AgentUiTakeoverReason
import com.lmx.xiaoxuanagent.runtime.SessionRuntime
import com.lmx.xiaoxuanagent.runtime.SessionRuntimeState
import com.lmx.xiaoxuanagent.taskprofile.AutomationSupportPolicy
import com.lmx.xiaoxuanagent.taskprofile.AutomationSupportPolicyStore
import com.lmx.xiaoxuanagent.taskprofile.ScreenAutomationProductSupportSnapshot
import com.lmx.xiaoxuanagent.taskprofile.ScreenAutomationProductSupportStore
import com.lmx.xiaoxuanagent.taskprofile.TaskRegistry

enum class ForegroundAutomationPhase {
    IDLE,
    OBSERVING,
    PLANNING,
    ACTING,
    WAITING_USER,
    HANDOFF,
    SUSPENDED,
    COMPLETE,
}

data class ForegroundAutomationSessionSnapshot(
    val phase: ForegroundAutomationPhase = ForegroundAutomationPhase.IDLE,
    val sessionId: String = "",
    val task: String = "",
    val targetPackageName: String = "",
    val profileId: String = "",
    val currentStep: String = "",
    val nextStep: String = "",
    val supervisionSummary: String = "",
    val handoffSummary: String = "",
    val executionPath: String = "",
    val releaseGateStatus: String = "",
    val productSupportSummary: String = "",
    val productSupportTier: String = "",
    val connectorState: String = "",
    val connectorSummary: String = "",
    val productSupport: ScreenAutomationProductSupportSnapshot = ScreenAutomationProductSupportSnapshot(),
    val policy: AutomationSupportPolicy =
        AutomationSupportPolicyStore.resolve(
            profileId = "",
            packageName = "",
        ),
) {
    val active: Boolean
        get() = phase != ForegroundAutomationPhase.IDLE
}

object ForegroundAutomationSession {
    fun snapshot(
        assistantSnapshot: AssistantOsSnapshot = AssistantOsController.snapshot(),
        runtimeState: SessionRuntimeState = SessionRuntime.State.runtimeState(),
    ): ForegroundAutomationSessionSnapshot {
        val session = runtimeState.session
        val safety = runtimeState.safety
        val profile =
            TaskRegistry.get(session.profileId)
                ?: TaskRegistry.get(assistantSnapshot.activeSession.sessionId.takeIf { false }.orEmpty())
                ?: TaskRegistry.defaultProfile
        val policy =
            AutomationSupportPolicyStore.resolve(
                profileId = session.profileId.ifBlank { profile.id },
                packageName = session.targetPackageName.ifBlank { profile.packageName },
                taskIntent = TaskIntentParser.parse(session.task).intentType.name.lowercase(),
                planStage = runtimeState.session.planningSnapshot.currentPlanStage,
            )
        val phase =
            when {
                session.sessionId.isBlank() -> ForegroundAutomationPhase.IDLE
                safety.awaitingConfirmation -> ForegroundAutomationPhase.HANDOFF
                session.statusModel.takeoverReason == AgentUiTakeoverReason.PAUSED -> ForegroundAutomationPhase.SUSPENDED
                assistantSnapshot.activeSession.waitingForExternal -> ForegroundAutomationPhase.WAITING_USER
                session.planning -> ForegroundAutomationPhase.PLANNING
                session.running -> ForegroundAutomationPhase.ACTING
                session.statusModel.terminalReason != com.lmx.xiaoxuanagent.runtime.AgentUiTerminalReason.NONE ->
                    ForegroundAutomationPhase.COMPLETE
                else -> ForegroundAutomationPhase.OBSERVING
            }
        val currentStep =
            runtimeState.session.planningSnapshot.currentPlanStage
                .ifBlank { runtimeState.resultSnapshot?.lastAction.orEmpty() }
        val nextStep =
            safety.pendingConfirmation?.finalUserStep
                .orEmpty()
                .ifBlank { runtimeState.session.planningSnapshot.nextObjective }
                .ifBlank { runtimeState.resultSnapshot?.hint.orEmpty() }
        val supervisionSummary =
            when (phase) {
                ForegroundAutomationPhase.HANDOFF ->
                    safety.pendingConfirmation?.completionSummary
                        .orEmpty()
                        .ifBlank { "自动化已推进到最后确认前一步。" }
                ForegroundAutomationPhase.WAITING_USER -> "自动化已暂停，等待你完成外部步骤后继续。"
                ForegroundAutomationPhase.SUSPENDED -> "自动化已暂停，可随时继续或回到目标 App。"
                ForegroundAutomationPhase.PLANNING -> "正在理解当前屏幕并规划下一步。"
                ForegroundAutomationPhase.ACTING ->
                    if (policy.preferredExecutionPath == "connected_app_first") {
                        "正在通过精选能力优先推进任务，必要时才回退到界面自动化。"
                    } else {
                        "正在前台受控接管模式下连续操作应用。"
                    }
                ForegroundAutomationPhase.COMPLETE -> runtimeState.resultSnapshot?.summary.orEmpty().ifBlank { "当前任务已收口。" }
                ForegroundAutomationPhase.OBSERVING -> "正在读取当前屏幕并对齐目标应用。"
                ForegroundAutomationPhase.IDLE -> "当前没有活跃自动化任务。"
            }
        val handoffSummary =
            safety.pendingConfirmation?.handoffReason
                .orEmpty()
                .ifBlank { if (policy.requiresFinalHandoff) policy.handoffSummary else "" }
        val productSupport =
            ScreenAutomationProductSupportStore.snapshot(
                profileId = session.profileId.ifBlank { profile.id },
                packageName = session.targetPackageName.ifBlank { profile.packageName },
            )
        val connectorReceipt = ConnectedAppExecutionReceiptStore.latest(session.profileId.ifBlank { profile.id })
        return ForegroundAutomationSessionSnapshot(
            phase = phase,
            sessionId = session.sessionId,
            task = session.task,
            targetPackageName = session.targetPackageName.ifBlank { profile.packageName },
            profileId = session.profileId.ifBlank { profile.id },
            currentStep = currentStep,
            nextStep = nextStep,
            supervisionSummary = supervisionSummary,
            handoffSummary = handoffSummary,
            executionPath = policy.preferredExecutionPath,
            releaseGateStatus = policy.releaseGateStatus,
            productSupportSummary = productSupport.summary,
            productSupportTier = productSupport.tier,
            connectorState = connectorReceipt?.state.orEmpty(),
            connectorSummary = connectorReceipt?.summary.orEmpty(),
            productSupport = productSupport,
            policy = policy,
        )
    }
}
