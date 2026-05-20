package com.lmx.xiaoxuanagent.assistantos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lmx.xiaoxuanagent.R
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.PlatformCapabilityApprovalStore
import com.lmx.xiaoxuanagent.runtime.SessionResumeStore

object AssistantRuntimeServiceController {
    private const val EXTRA_REASON = "assistant_runtime_reason"

    const val ACTION_START = "com.lmx.xiaoxuanagent.action.runtime.START"
    const val ACTION_REFRESH = "com.lmx.xiaoxuanagent.action.runtime.REFRESH"
    const val ACTION_HEARTBEAT = "com.lmx.xiaoxuanagent.action.runtime.HEARTBEAT"

    fun ensureStarted(
        context: Context,
        reason: String,
    ) {
        dispatch(context, ACTION_START, reason)
    }

    fun refresh(
        context: Context,
        reason: String,
    ) {
        dispatch(context, ACTION_REFRESH, reason)
    }

    fun heartbeat(
        context: Context,
        reason: String,
    ) {
        dispatch(context, ACTION_HEARTBEAT, reason)
    }

    private fun dispatch(
        context: Context,
        action: String,
        reason: String,
    ) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, AssistantRuntimeService::class.java).apply {
                this.action = action
                putExtra(EXTRA_REASON, reason)
            },
        )
    }

    internal fun readReason(intent: Intent?): String = intent?.getStringExtra(EXTRA_REASON).orEmpty()
}

class AssistantRuntimeService : Service() {
    private data class AssistantRuntimeResidencyDecision(
        val keepAlive: Boolean,
        val summary: String,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppRuntimeContext.init(this)
        ensureChannel()
        startRuntimeForeground(
            buildNotification(
                reason = "assistant runtime 初始化中",
                residencySummary = "正在恢复后台 assistant runtime。",
            ),
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        AppRuntimeContext.init(this)
        val reason = AssistantRuntimeServiceController.readReason(intent).ifBlank { "assistant_runtime_service" }
        when (intent?.action) {
            AssistantRuntimeServiceController.ACTION_HEARTBEAT -> PersistentAssistantEngine.onHeartbeat()
            AssistantRuntimeServiceController.ACTION_REFRESH -> PersistentAssistantEngine.start(reason = reason)
            else -> PersistentAssistantEngine.onSystemBooted(reason = reason, context = this)
        }
        AssistantMobileEntryController.sync(this)
        val residency = evaluateResidency()
        return if (residency.keepAlive) {
            updateNotification(reason = reason, residencySummary = residency.summary)
            START_STICKY
        } else {
            releaseRuntimeNotification()
            stopSelfResult(startId)
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        releaseRuntimeNotification()
        super.onDestroy()
    }

    private fun updateNotification(
        reason: String,
        residencySummary: String,
    ) {
        runCatching {
            NotificationManagerCompat.from(this).notify(
                NOTIFICATION_ID,
                buildNotification(reason = reason, residencySummary = residencySummary),
            )
        }
    }

    private fun buildNotification(
        reason: String,
        residencySummary: String,
    ): Notification =
        AssistantTaskSupervisionNotification.build(
            context = this,
            channelId = CHANNEL_ID,
            reason = reason,
            residencySummary = residencySummary,
        )

    private fun evaluateResidency(): AssistantRuntimeResidencyDecision {
        val assistant = AssistantOsController.snapshot()
        val voice = AssistantVoiceInteractionStore.read()
        val hasPendingApproval =
            assistant.activeSession.awaitingConfirmation ||
                PlatformCapabilityApprovalStore.readPending(limit = 1).isNotEmpty()
        val hasResumable = SessionResumeStore.readResumableSnapshots(limit = 1).isNotEmpty()
        val hasDueProactive = AssistantProactiveTaskStore.readDue(limit = 1).isNotEmpty()
        val hasDueSignals = AssistantExternalSignalStore.readDue(limit = 1).isNotEmpty()
        return when {
            voice.continuousMode || voice.state in setOf("listening", "partial") ->
                AssistantRuntimeResidencyDecision(true, "持续语音运行中，后台 runtime 保持常驻。")

            hasPendingApproval ->
                AssistantRuntimeResidencyDecision(true, "存在待确认审批，后台 runtime 保持托管。")

            assistant.activeSession.sessionId.isNotBlank() ->
                AssistantRuntimeResidencyDecision(true, "当前有进行中的 session，后台 runtime 持续运行。")

            hasResumable ->
                AssistantRuntimeResidencyDecision(true, "存在可恢复 session，后台 runtime 保持待命。")

            hasDueProactive || hasDueSignals ->
                AssistantRuntimeResidencyDecision(true, "有到期的主动任务或外部信号，后台 runtime 保持在线。")

            else ->
                AssistantRuntimeResidencyDecision(false, "当前无活跃任务，runtime 已降级为 alarm + receiver 待命。")
        }
    }

    private fun releaseRuntimeNotification() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
    }

    private fun startRuntimeForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "小轩后台助手",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "维持 assistant engine、入口编排和长期运行循环"
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "assistant_runtime"
        private const val NOTIFICATION_ID = 7100
    }
}
