package com.lmx.xiaoxuanagent.entry

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lmx.xiaoxuanagent.MainActivity
import com.lmx.xiaoxuanagent.R
import com.lmx.xiaoxuanagent.assistantos.AssistantMobileEntryController
import com.lmx.xiaoxuanagent.assistantos.AssistantOsController
import com.lmx.xiaoxuanagent.assistantos.AssistantProductShellStore
import com.lmx.xiaoxuanagent.assistantos.AssistantShellIntentRouter
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.DebugUiState

object AssistantEntryController {
    private const val CHANNEL_ID = "assistant_status"
    private const val CHANNEL_NAME = "小轩运行状态"
    private const val NOTIFICATION_ID = 2001
    @Volatile
    private var lastNotificationFingerprint: String = ""

    fun syncNotification(state: DebugUiState) {
        val context = AppRuntimeContext.get() ?: return
        if (!AssistantOsController.shouldShowNotificationControlCenter()) {
            cancelNotification()
            return
        }
        val osSnapshot = AssistantOsController.snapshot()
        val productShell = AssistantProductShellStore.read()
        val title = buildTitle(state)
        val summary = buildSummary(state)
        val subText = "权限 ${osSnapshot.permissionMode.name.lowercase()} · 安全 ${osSnapshot.safetyMode.name.lowercase()}"
        val notificationFingerprint =
            listOf(
                title,
                summary,
                subText,
                state.agentRunning.toString(),
                state.safety.awaitingConfirmation.toString(),
                state.status.isWaitingExternal.toString(),
                state.status.isPaused.toString(),
                state.status.isExecuting.toString(),
                productShell.voiceInteraction.state,
                productShell.voiceInteraction.availabilitySummary,
                productShell.onboarding.pendingSteps.size.toString(),
                productShell.tips.firstOrNull()?.recommendedPage.orEmpty(),
            ).joinToString("|")
        if (notificationFingerprint == lastNotificationFingerprint) {
            return
        }
        lastNotificationFingerprint = notificationFingerprint
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_agent)
                .setContentTitle(title)
                .setContentText(summary)
                .setSubText(subText)
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        listOf(
                            summary,
                            state.safety.pendingConfirmationSummary.takeIf { state.safety.awaitingConfirmation },
                            state.result.lastResult.takeIf { it.isNotBlank() && it != summary },
                            AssistantOsController.buildControlCenterSummary().lineSequence().take(3).joinToString("\n"),
                        ).filterNotNull().joinToString("\n"),
                    ),
                )
                .setContentIntent(createContentIntent(context))
                .setOngoing(state.agentRunning)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setShowWhen(false)
                .apply {
                    val statusPanel = state.status
                    addAction(0, context.getString(R.string.notification_open), createActionIntent(context, AssistantShellIntentRouter.ACTION_OPEN))
                    when {
                        state.safety.awaitingConfirmation -> {
                            addAction(0, context.getString(R.string.notification_approve), createActionIntent(context, AssistantShellIntentRouter.ACTION_APPROVE))
                            addAction(0, context.getString(R.string.notification_reject), createActionIntent(context, AssistantShellIntentRouter.ACTION_REJECT))
                            addAction(0, context.getString(R.string.notification_stop), createActionIntent(context, AssistantShellIntentRouter.ACTION_STOP))
                        }

                        statusPanel.isWaitingExternal -> {
                            addAction(0, context.getString(R.string.notification_return), createActionIntent(context, AssistantShellIntentRouter.ACTION_RETURN_TARGET))
                            addAction(0, context.getString(R.string.notification_stop), createActionIntent(context, AssistantShellIntentRouter.ACTION_STOP))
                        }

                        statusPanel.isPaused -> {
                            addAction(0, context.getString(R.string.notification_resume), createActionIntent(context, AssistantShellIntentRouter.ACTION_RESUME))
                            addAction(0, context.getString(R.string.notification_return), createActionIntent(context, AssistantShellIntentRouter.ACTION_RETURN_TARGET))
                            addAction(0, context.getString(R.string.notification_stop), createActionIntent(context, AssistantShellIntentRouter.ACTION_STOP))
                        }

                        state.agentRunning -> {
                            addAction(0, context.getString(R.string.notification_pause), createActionIntent(context, AssistantShellIntentRouter.ACTION_PAUSE))
                            addAction(0, context.getString(R.string.notification_return), createActionIntent(context, AssistantShellIntentRouter.ACTION_RETURN_TARGET))
                            addAction(0, context.getString(R.string.notification_stop), createActionIntent(context, AssistantShellIntentRouter.ACTION_STOP))
                        }

                        productShell.voiceInteraction.availabilitySummary == "voice_ready" -> {
                            addAction(
                                0,
                                if (productShell.voiceInteraction.state in setOf("listening", "partial", "executing")) "停止语音" else "开始语音",
                                createActionIntent(context, AssistantShellIntentRouter.ACTION_VOICE_TOGGLE),
                            )
                            if (productShell.onboarding.pendingSteps.isNotEmpty()) {
                                addAction(0, "继续引导", createPageIntent(context, "inbox"))
                            } else if (productShell.tips.firstOrNull()?.recommendedPage?.isNotBlank() == true) {
                                addAction(0, "处理提醒", createPageIntent(context, productShell.tips.first().recommendedPage))
                            } else {
                                addAction(0, "今日面板", createPageIntent(context, "today"))
                            }
                        }

                        productShell.onboarding.pendingSteps.isNotEmpty() -> {
                            addAction(0, "继续引导", createPageIntent(context, "inbox"))
                            addAction(0, "今日面板", createPageIntent(context, "today"))
                        }
                    }
                }
                .build()
        runCatching {
            manager.notify(NOTIFICATION_ID, notification)
        }
        AssistantMobileEntryController.sync(context)
    }

    fun cancelNotification() {
        val context = AppRuntimeContext.get() ?: return
        lastNotificationFingerprint = ""
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        AssistantMobileEntryController.sync(context)
    }

    private fun buildTitle(state: DebugUiState): String =
        with(state.status) {
            when {
                state.safety.awaitingConfirmation -> "小轩等待确认"
                isResumeSnapshotState(state) && isPaused -> "小轩恢复任务中"
                isResumeSnapshotState(state) && isExecuting -> "小轩恢复执行中"
                isWaitingExternal -> "小轩等待外部处理"
                isExecuting -> "小轩执行中"
                else -> "小轩已就绪"
            }
        }

    private fun buildSummary(state: DebugUiState): String =
        with(state.status) {
            when {
                state.safety.awaitingConfirmation ->
                    state.safety.pendingConfirmationActionLabel.ifBlank { "检测到高风险动作，等待确认" }

                isResumeSnapshotState(state) ->
                    listOf(
                        state.takeover.latestTakeoverResumeHint.takeIf { it.isNotBlank() },
                        state.result.hint.takeIf { it.isNotBlank() },
                        state.query.takeIf { it.isNotBlank() }?.let { "任务: $it" },
                    ).filterNotNull().firstOrNull().orEmpty().ifBlank { "已恢复任务上下文，准备继续执行" }

                isWaitingExternal ->
                    state.takeover.suspendReason.ifBlank { "等待外部处理后自动恢复" }

                isExecuting ->
                    listOf(
                        state.query.takeIf { it.isNotBlank() }?.let { "任务: $it" },
                        state.result.lastAction.takeIf { it.isNotBlank() }?.let { "动作: $it" },
                    ).filterNotNull().joinToString(" | ").ifBlank { "正在运行任务" }

                state.result.lastError.isNotBlank() -> state.result.lastError
                state.result.lastResult.isNotBlank() -> state.result.lastResult
                else -> "等待新的任务"
            }
        }

    private fun isResumeSnapshotState(state: DebugUiState): Boolean {
        val takeoverType = state.takeover.latestTakeoverType
        return takeoverType == "cold_start_resume" || takeoverType.startsWith("resume_snapshot")
    }

    private fun createContentIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            MainActivity.REQUEST_OPEN,
            AssistantShellIntentRouter.createActionIntent(context, AssistantShellIntentRouter.ACTION_OPEN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createActionIntent(
        context: Context,
        action: String,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            action.hashCode(),
            AssistantShellIntentRouter.createActionIntent(context, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createPageIntent(
        context: Context,
        page: String,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            page.hashCode(),
            AssistantShellIntentRouter.createPageIntent(context, page),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "展示小轩当前任务状态和快捷操作"
            },
        )
    }
}
