package com.lmx.xiaoxuanagent.assistantos

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.lmx.xiaoxuanagent.R
import com.lmx.xiaoxuanagent.accessibility.ForegroundAutomationPhase
import com.lmx.xiaoxuanagent.accessibility.ForegroundAutomationSession

object AssistantTaskSupervisionNotification {
    fun build(
        context: Context,
        channelId: String,
        reason: String,
        residencySummary: String,
    ): Notification {
        val shell = AssistantProductShellStore.read()
        val os = AssistantOsController.snapshot()
        val automation = ForegroundAutomationSession.snapshot(os)
        val openIntent = createActivityIntent(context, AssistantShellIntentRouter.createActionIntent(context, AssistantShellIntentRouter.ACTION_OPEN, source = "runtime_service"))
        val notification =
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification_agent)
                .setContentTitle(titleFor(automation))
                .setContentText(summaryFor(automation, residencySummary))
                .setSubText(reason.take(48))
                .setOnlyAlertOnce(true)
                .setOngoing(os.activeSession.sessionId.isNotBlank() || shell.voiceInteraction.continuousMode)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(openIntent)
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        buildList {
                            add(summaryFor(automation, residencySummary))
                            automation.currentStep.takeIf { it.isNotBlank() }?.let { add("当前步骤: $it") }
                            automation.nextStep.takeIf { it.isNotBlank() }?.let { add("下一步: $it") }
                            automation.connectorState.takeIf { it.isNotBlank() }?.let { add("结构化阶段: $it") }
                            automation.connectorSummary.takeIf { it.isNotBlank() }?.let { add("结构化进展: $it") }
                            automation.handoffSummary.takeIf { it.isNotBlank() }?.let { add("交接: $it") }
                            automation.executionPath.takeIf { it.isNotBlank() }?.let { add("执行路径: $it") }
                            automation.releaseGateStatus.takeIf { it.isNotBlank() && it != "ungated" }?.let { add("发布门禁: $it") }
                            automation.productSupportSummary.takeIf { it.isNotBlank() }?.let { add("支持范围: $it") }
                            automation.productSupport.supportCeiling.takeIf { it.isNotBlank() }?.let { add("支持上限: $it") }
                            automation.productSupport.consumerPermissionHint.takeIf { it.isNotBlank() }?.let { add("授权建议: $it") }
                            add(automation.policy.handoffSummary.ifBlank { "模式: ${automation.policy.supervisionMode}" })
                        }.joinToString("\n"),
                    ),
                )
                .addAction(0, "打开", openIntent)
                .apply {
                    when (automation.phase) {
                        ForegroundAutomationPhase.HANDOFF -> {
                            addAction(0, "确认", createActionIntent(context, AssistantShellIntentRouter.ACTION_APPROVE))
                            addAction(0, "拒绝", createActionIntent(context, AssistantShellIntentRouter.ACTION_REJECT))
                            addAction(0, "回现场", createActionIntent(context, AssistantShellIntentRouter.ACTION_RETURN_TARGET))
                        }

                        ForegroundAutomationPhase.SUSPENDED -> {
                            addAction(0, "继续", createActionIntent(context, AssistantShellIntentRouter.ACTION_RESUME))
                            addAction(0, "回现场", createActionIntent(context, AssistantShellIntentRouter.ACTION_RETURN_TARGET))
                            addAction(0, "停止", createActionIntent(context, AssistantShellIntentRouter.ACTION_STOP))
                        }

                        ForegroundAutomationPhase.WAITING_USER -> {
                            addAction(0, "回现场", createActionIntent(context, AssistantShellIntentRouter.ACTION_RETURN_TARGET))
                            addAction(0, "停止", createActionIntent(context, AssistantShellIntentRouter.ACTION_STOP))
                        }

                        ForegroundAutomationPhase.PLANNING,
                        ForegroundAutomationPhase.ACTING,
                        ForegroundAutomationPhase.OBSERVING,
                        -> {
                            addAction(0, "暂停", createActionIntent(context, AssistantShellIntentRouter.ACTION_PAUSE))
                            addAction(0, "回现场", createActionIntent(context, AssistantShellIntentRouter.ACTION_RETURN_TARGET))
                            addAction(0, "停止", createActionIntent(context, AssistantShellIntentRouter.ACTION_STOP))
                        }

                        else -> {
                            if (shell.voiceInteraction.availabilitySummary == "voice_ready") {
                                addAction(0, "语音", createActionIntent(context, AssistantShellIntentRouter.ACTION_VOICE_TOGGLE))
                            }
                        }
                    }
                }
                .build()
        return notification
    }

    private fun titleFor(
        automation: com.lmx.xiaoxuanagent.accessibility.ForegroundAutomationSessionSnapshot,
    ): String =
        when (automation.phase) {
            ForegroundAutomationPhase.HANDOFF -> "小轩待你完成最后一步"
            ForegroundAutomationPhase.WAITING_USER -> "小轩等待用户处理"
            ForegroundAutomationPhase.SUSPENDED -> "小轩自动化已暂停"
            ForegroundAutomationPhase.PLANNING -> "小轩正在规划屏幕操作"
            ForegroundAutomationPhase.ACTING,
            ForegroundAutomationPhase.OBSERVING,
            -> if (automation.executionPath == "connected_app_first") "小轩正在通过精选能力推进任务" else "小轩正在操作应用"
            ForegroundAutomationPhase.COMPLETE -> "小轩已完成当前任务"
            ForegroundAutomationPhase.IDLE -> "小轩后台助手待命中"
        }

    private fun summaryFor(
        automation: com.lmx.xiaoxuanagent.accessibility.ForegroundAutomationSessionSnapshot,
        residencySummary: String,
    ): String =
        automation.supervisionSummary.ifBlank {
            listOf(
                automation.task.takeIf { it.isNotBlank() }?.let { "任务: $it" },
                automation.connectorState.takeIf { it.isNotBlank() }?.let { "阶段: $it" },
                automation.currentStep.takeIf { it.isNotBlank() }?.let { "步骤: $it" },
                residencySummary,
            ).filterNotNull().joinToString(" | ")
        }

    private fun createActionIntent(
        context: Context,
        action: String,
    ): PendingIntent =
        createActivityIntent(
            context,
            AssistantShellIntentRouter.createActionIntent(context, action, source = "runtime_service"),
        )

    private fun createActivityIntent(
        context: Context,
        intent: android.content.Intent,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            intent.action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
