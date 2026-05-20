package com.lmx.xiaoxuanagent.assistantos

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import com.lmx.xiaoxuanagent.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object AssistantMobileEntryController {
    private const val TAG = "AssistantEntrySync"
    private const val SYNC_COALESCE_MS = 250L
    @Volatile
    private var syncRunning = false
    @Volatile
    private var pendingSyncRequest: EntrySyncRequest? = null
    @Volatile
    private var lastShortcutFingerprint: String = ""
    @Volatile
    private var lastWidgetFingerprint: String = ""
    private val syncLock = Any()
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class EntrySyncRequest(
        val context: Context,
        val reason: String,
        val force: Boolean,
    )

    fun sync(
        context: Context,
        reason: String = "entry_projection_refresh",
        force: Boolean = false,
    ) {
        val appContext = context.applicationContext
        val shouldStart =
            synchronized(syncLock) {
                val next = EntrySyncRequest(appContext, reason, force)
                pendingSyncRequest =
                    pendingSyncRequest?.let { pending ->
                        pending.copy(
                            context = appContext,
                            reason = next.reason,
                            force = pending.force || next.force,
                        )
                    } ?: next
                if (syncRunning) {
                    false
                } else {
                    syncRunning = true
                    true
                }
            }
        if (!shouldStart) {
            return
        }
        syncScope.launch {
            drainSyncRequests()
        }
    }

    private suspend fun drainSyncRequests() {
        while (true) {
            delay(SYNC_COALESCE_MS)
            val request =
                synchronized(syncLock) {
                    pendingSyncRequest?.also {
                        pendingSyncRequest = null
                    } ?: run {
                        syncRunning = false
                        return
                    }
                }
            runCatching {
                performSync(
                    context = request.context,
                    reason = request.reason,
                    force = request.force,
                )
            }.onFailure { error ->
                Log.w(TAG, "entry sync failed, reason=${request.reason}", error)
            }
        }
    }

    private fun performSync(
        context: Context,
        reason: String,
        force: Boolean,
    ) {
        val snapshot = AssistantOsController.snapshot()
        val productShell = AssistantProductShellStore.read()
        syncDynamicShortcuts(
            context = context,
            snapshot = snapshot,
            productShell = productShell,
            reason = reason,
            force = force,
        )
        AssistantHomeWidgetProvider.refreshAll(
            context = context,
            snapshot = snapshot,
            productShell = productShell,
            reason = reason,
            force = force,
        )
    }

    fun buildEntrySurfaceLines(
        snapshot: AssistantOsSnapshot,
        productShell: AssistantProductShellSnapshot,
    ): List<String> =
        buildList {
            add("surface_summary=${productShell.interruptBudget.summary.ifBlank { "-" }}")
            add("overlay_focus=${primaryWidgetSummary(snapshot, productShell)}")
            add("overlay_glance=${overlayGlanceSummary(snapshot, productShell)}")
            snapshot.surfaces.forEach { surface ->
                add(
                    "surface.${surface.surface.name.lowercase()}=" +
                        when {
                            !surface.supported -> "unsupported"
                            !surface.enabled -> "disabled"
                            !surface.available -> "blocked(${surface.summary.ifBlank { "-" }})"
                            else -> "ready(${surface.summary.ifBlank { "ok" }})"
                        },
                )
            }
            add("shortcut_dynamic=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) "ready" else "unsupported"}")
            add("widget=${if (snapshot.isFeatureEnabled(AssistantFeatureFlagKey.ASSISTANT_INBOX)) "ready" else "limited"}")
            add("tile=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) "ready" else "unsupported"}")
            add("voice=${productShell.voiceInteraction.availabilitySummary.ifBlank { if (snapshot.activeSession.awaitingConfirmation) "gated" else "ready" }}")
            add("shortcut_summary=${shortcutSummary(snapshot, productShell)}")
            add("allowed_sources=${productShell.interruptBudget.allowedSources.joinToString(",").ifBlank { "-" }}")
            add("blocked_sources=${productShell.interruptBudget.blockedSources.joinToString(",").ifBlank { "-" }}")
        }

    fun primaryWidgetSummary(
        snapshot: AssistantOsSnapshot,
        productShell: AssistantProductShellSnapshot,
    ): String =
        when {
            snapshot.activeSession.awaitingConfirmation -> "高风险动作待确认"
            snapshot.activeSession.sessionId.isNotBlank() && snapshot.activeSession.statusModel.takeoverReason.name == "PAUSED" ->
                snapshot.activeSession.task.ifBlank { "任务已暂停，可继续" }
            productShell.digestShell.summary.isNotBlank() -> productShell.digestShell.summary
            productShell.routineShell.summary.isNotBlank() -> productShell.routineShell.summary
            else -> "打开小轩查看今日面板"
        }

    fun overlayGlanceSummary(
        snapshot: AssistantOsSnapshot,
        productShell: AssistantProductShellSnapshot,
    ): String =
        when {
            snapshot.activeSession.awaitingConfirmation -> "优先处理审批，再回现场"
            snapshot.activeSession.sessionId.isNotBlank() -> productShell.viewerShell.timelineSummary.ifBlank { "任务进行中，可直接继续或查看 Viewer" }
            productShell.digestShell.summary.isNotBlank() -> productShell.digestShell.summary
            else -> "当前无进行中任务，可从语音、收件箱或今日面板进入"
        }

    fun shortcutSummary(
        snapshot: AssistantOsSnapshot,
        productShell: AssistantProductShellSnapshot,
    ): String =
        buildString {
            append(
                when {
                    snapshot.activeSession.awaitingConfirmation -> "approval"
                    snapshot.activeSession.sessionId.isNotBlank() -> "resume"
                    else -> "today"
                },
            )
            append(" | ").append(productShell.swarmStrategy.mode.ifBlank { "-" })
            append(" | ").append(productShell.onboarding.status.ifBlank { "-" })
        }

    private fun recommendedLandingPage(
        snapshot: AssistantOsSnapshot,
        productShell: AssistantProductShellSnapshot,
    ): String =
        when {
            snapshot.activeSession.awaitingConfirmation -> "approvals"
            snapshot.activeSession.sessionId.isNotBlank() -> "viewer"
            productShell.onboarding.pendingSteps.isNotEmpty() -> "inbox"
            productShell.tips.firstOrNull()?.recommendedPage?.isNotBlank() == true -> productShell.tips.first().recommendedPage
            productShell.voiceInteraction.state in setOf("blocked", "error") -> "entry"
            else -> "today"
        }

    internal fun voiceActionLabel(
        productShell: AssistantProductShellSnapshot,
    ): String =
        when {
            productShell.voiceInteraction.state in setOf("listening", "partial", "executing") -> "停止语音"
            productShell.voiceInteraction.state in setOf("blocked", "error") -> "修复语音"
            else -> "开始语音"
        }

    internal fun voiceActionIntent(
        context: Context,
        source: String,
    ) = AssistantShellIntentRouter.createActionIntent(context, AssistantShellIntentRouter.ACTION_VOICE_TOGGLE, source = source)

    private fun syncDynamicShortcuts(
        context: Context,
        snapshot: AssistantOsSnapshot,
        productShell: AssistantProductShellSnapshot,
        reason: String,
        force: Boolean,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val landingPage = recommendedLandingPage(snapshot, productShell)
        val fingerprint = buildShortcutFingerprint(snapshot, productShell, landingPage)
        if (!force && fingerprint == lastShortcutFingerprint) {
            return
        }
        val shortcuts =
            listOf(
                ShortcutInfo.Builder(context, "assistant_open").apply {
                    setShortLabel(
                        when (landingPage) {
                            "approvals" -> "待确认"
                            "viewer" -> "任务面板"
                            "inbox" -> "收件箱"
                            else -> "控制中心"
                        },
                    )
                    setLongLabel(primaryWidgetSummary(snapshot, productShell))
                    setIcon(Icon.createWithResource(context, R.drawable.ic_notification_agent))
                    setIntent(
                        AssistantShellIntentRouter.createPageIntent(
                            context,
                            landingPage,
                            source = "shortcut",
                        ),
                    )
                }.build(),
                ShortcutInfo.Builder(context, "assistant_inbox").apply {
                    setShortLabel(
                        when {
                            snapshot.activeSession.awaitingConfirmation -> "审批收件箱"
                            snapshot.pendingActions.isNotEmpty() -> "待办收件箱"
                            else -> "收件箱"
                        },
                    )
                    setLongLabel(
                        when {
                            snapshot.activeSession.awaitingConfirmation -> "先处理能力审批与风险确认"
                            else -> "查看小轩收件箱与待跟进事项"
                        },
                    )
                    setIcon(Icon.createWithResource(context, R.drawable.ic_notification_agent))
                    setIntent(
                        AssistantShellIntentRouter.createPageIntent(
                            context,
                            if (snapshot.activeSession.awaitingConfirmation) "approvals" else "inbox",
                            source = "shortcut",
                        ),
                    )
                }.build(),
                ShortcutInfo.Builder(context, "assistant_viewer").apply {
                    setShortLabel(if (snapshot.activeSession.sessionId.isNotBlank()) "任务 Viewer" else "会话总览")
                    setLongLabel("查看当前会话、时间线与图谱")
                    setIcon(Icon.createWithResource(context, R.drawable.ic_notification_agent))
                    setIntent(
                        AssistantShellIntentRouter.createPageIntent(
                            context,
                            if (snapshot.activeSession.sessionId.isNotBlank()) "viewer" else "sessions",
                            source = "shortcut",
                        ),
                    )
                }.build(),
                ShortcutInfo.Builder(context, "assistant_voice").apply {
                    setShortLabel(voiceActionLabel(productShell))
                    setLongLabel(productShell.voiceInteraction.summary.ifBlank { overlayGlanceSummary(snapshot, productShell) })
                    setIcon(Icon.createWithResource(context, R.drawable.ic_notification_agent))
                    setIntent(voiceActionIntent(context, source = "shortcut"))
                }.build(),
                ShortcutInfo.Builder(context, "assistant_resume").apply {
                    setShortLabel(
                        when {
                            snapshot.activeSession.awaitingConfirmation -> "确认继续"
                            snapshot.activeSession.sessionId.isNotBlank() -> "继续任务"
                            productShell.tips.isNotEmpty() -> "处理提醒"
                            else -> "查看会话"
                        },
                    )
                    setLongLabel(primaryWidgetSummary(snapshot, productShell))
                    setIcon(Icon.createWithResource(context, R.drawable.ic_notification_agent))
                    setIntent(
                        when {
                            snapshot.activeSession.awaitingConfirmation ->
                                AssistantShellIntentRouter.createActionIntent(context, AssistantShellIntentRouter.ACTION_APPROVE, source = "shortcut")
                            snapshot.activeSession.sessionId.isNotBlank() ->
                                AssistantShellIntentRouter.createActionIntent(context, AssistantShellIntentRouter.ACTION_RESUME, source = "shortcut")
                            productShell.tips.firstOrNull()?.recommendedPage?.isNotBlank() == true ->
                                AssistantShellIntentRouter.createPageIntent(context, productShell.tips.first().recommendedPage, source = "shortcut")
                            else ->
                                AssistantShellIntentRouter.createPageIntent(context, "sessions", source = "shortcut")
                        },
                    )
                }.build(),
                ShortcutInfo.Builder(context, "assistant_routine").apply {
                    setShortLabel(
                        when {
                            productShell.onboarding.pendingSteps.isNotEmpty() -> "继续引导"
                            productShell.memoryInsight.suggestionSummary.isNotBlank() -> "记忆整理"
                            else -> "今日节律"
                        },
                    )
                    setLongLabel(shortcutSummary(snapshot, productShell))
                    setIcon(Icon.createWithResource(context, R.drawable.ic_notification_agent))
                    setIntent(
                        AssistantShellIntentRouter.createPageIntent(
                            context,
                            when {
                                productShell.onboarding.pendingSteps.isNotEmpty() -> "inbox"
                                productShell.memoryInsight.suggestionSummary.isNotBlank() -> "governance"
                                else -> "routine"
                            },
                            source = "shortcut",
                        ),
                    )
                }.build(),
            )
        runCatching {
            manager.dynamicShortcuts = shortcuts.take(5)
            lastShortcutFingerprint = fingerprint
            Log.d(TAG, "shortcut projection synced, reason=$reason")
        }
    }

    private fun buildShortcutFingerprint(
        snapshot: AssistantOsSnapshot,
        productShell: AssistantProductShellSnapshot,
        landingPage: String,
    ): String =
        listOf(
            landingPage,
            primaryWidgetSummary(snapshot, productShell),
            overlayGlanceSummary(snapshot, productShell),
            shortcutSummary(snapshot, productShell),
            voiceActionLabel(productShell),
            productShell.voiceInteraction.summary,
            productShell.voiceInteraction.state,
            productShell.onboarding.pendingSteps.joinToString("|"),
            productShell.tips.take(3).joinToString("|") { "${it.id}:${it.recommendedPage}:${it.status}" },
            snapshot.activeSession.sessionId,
            snapshot.activeSession.awaitingConfirmation.toString(),
            snapshot.activeSession.targetAppReturnEligible.toString(),
            snapshot.pendingActions.take(3).joinToString("|") { "${it.type.name}:${it.title}" },
        ).joinToString("||")

    internal fun buildWidgetFingerprint(
        snapshot: AssistantOsSnapshot,
        productShell: AssistantProductShellSnapshot,
    ): String =
        listOf(
            primaryWidgetSummary(snapshot, productShell),
            overlayGlanceSummary(snapshot, productShell),
            voiceActionLabel(productShell),
            snapshot.activeSession.sessionId,
            snapshot.activeSession.awaitingConfirmation.toString(),
            snapshot.activeSession.targetAppReturnEligible.toString(),
            productShell.voiceInteraction.state,
        ).joinToString("||")

    internal fun shouldSyncWidgetProjection(
        fingerprint: String,
        force: Boolean,
    ): Boolean {
        if (!force && fingerprint == lastWidgetFingerprint) {
            return false
        }
        lastWidgetFingerprint = fingerprint
        return true
    }
}

class AssistantHomeWidgetProvider : android.appwidget.AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        refreshAll(context)
    }

    companion object {
        fun refreshAll(
            context: Context,
            snapshot: AssistantOsSnapshot = AssistantOsController.snapshot(),
            productShell: AssistantProductShellSnapshot = AssistantProductShellStore.read(),
            reason: String = "entry_widget_refresh",
            force: Boolean = false,
        ) {
            val manager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, AssistantHomeWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(componentName)
            if (ids.isEmpty()) return
            val fingerprint = AssistantMobileEntryController.buildWidgetFingerprint(snapshot, productShell)
            if (!AssistantMobileEntryController.shouldSyncWidgetProjection(fingerprint, force)) {
                return
            }
            val title =
                when {
                    snapshot.activeSession.awaitingConfirmation -> "小轩待确认"
                    snapshot.activeSession.sessionId.isNotBlank() -> "小轩任务进行中"
                    else -> "小轩今日面板"
                }
            val summary = AssistantMobileEntryController.primaryWidgetSummary(snapshot, productShell)
            val primaryIntent =
                when {
                    snapshot.activeSession.awaitingConfirmation ->
                        AssistantShellIntentRouter.createActionIntent(context, AssistantShellIntentRouter.ACTION_APPROVE, source = "widget")
                    snapshot.activeSession.sessionId.isNotBlank() ->
                        AssistantShellIntentRouter.createActionIntent(context, AssistantShellIntentRouter.ACTION_RESUME, source = "widget")
                    else -> AssistantShellIntentRouter.createPageIntent(context, "today", source = "widget")
                }
            val secondaryIntent =
                if (snapshot.activeSession.targetAppReturnEligible) {
                    AssistantShellIntentRouter.createActionIntent(context, AssistantShellIntentRouter.ACTION_RETURN_TARGET, source = "widget")
                } else {
                    AssistantMobileEntryController.voiceActionIntent(context, source = "widget")
                }
            ids.forEach { appWidgetId ->
                val views = android.widget.RemoteViews(context.packageName, R.layout.assistant_home_widget).apply {
                    setTextViewText(R.id.widgetTitleText, title)
                    setTextViewText(R.id.widgetSummaryText, summary)
                    setTextViewText(
                        R.id.widgetPrimaryButton,
                        when {
                            snapshot.activeSession.awaitingConfirmation -> "确认"
                            snapshot.activeSession.sessionId.isNotBlank() -> "继续"
                            else -> "打开"
                        },
                    )
                    setTextViewText(
                        R.id.widgetSecondaryButton,
                        if (snapshot.activeSession.targetAppReturnEligible) "回现场" else AssistantMobileEntryController.voiceActionLabel(productShell),
                    )
                    setOnClickPendingIntent(
                        R.id.widgetRoot,
                        android.app.PendingIntent.getActivity(
                            context,
                            "widget_open_$appWidgetId".hashCode(),
                            AssistantShellIntentRouter.createPageIntent(context, "today", source = "widget"),
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                    setOnClickPendingIntent(
                        R.id.widgetPrimaryButton,
                        android.app.PendingIntent.getActivity(
                            context,
                            "widget_primary_$appWidgetId".hashCode(),
                            primaryIntent,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                    setOnClickPendingIntent(
                        R.id.widgetSecondaryButton,
                        android.app.PendingIntent.getActivity(
                            context,
                            "widget_secondary_$appWidgetId".hashCode(),
                            secondaryIntent,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
                manager.updateAppWidget(appWidgetId, views)
            }
            Log.d("AssistantEntrySync", "widget projection synced, reason=$reason, count=${ids.size}")
        }
    }
}
