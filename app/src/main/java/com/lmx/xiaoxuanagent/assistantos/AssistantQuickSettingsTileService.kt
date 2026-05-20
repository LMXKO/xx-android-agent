package com.lmx.xiaoxuanagent.assistantos

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
class AssistantQuickSettingsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val snapshot = AssistantOsController.snapshot()
        val productShell = AssistantProductShellStore.read()
        val intent =
            when {
                snapshot.activeSession.awaitingConfirmation ->
                    AssistantShellIntentRouter.createActionIntent(this, AssistantShellIntentRouter.ACTION_APPROVE, source = "tile")
                snapshot.activeSession.sessionId.isNotBlank() &&
                    snapshot.activeSession.statusModel.takeoverReason.name == "PAUSED" ->
                    AssistantShellIntentRouter.createActionIntent(this, AssistantShellIntentRouter.ACTION_RESUME, source = "tile")
                snapshot.activeSession.sessionId.isNotBlank() ->
                    AssistantShellIntentRouter.createActionIntent(this, AssistantShellIntentRouter.ACTION_PAUSE, source = "tile")
                productShell.voiceInteraction.availabilitySummary == "voice_ready" ->
                    AssistantShellIntentRouter.createActionIntent(this, AssistantShellIntentRouter.ACTION_VOICE_TOGGLE, source = "tile")
                else -> AssistantShellIntentRouter.createPageIntent(this, "today", source = "tile")
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val snapshot = AssistantOsController.snapshot()
        val productShell = AssistantProductShellStore.read()
        tile.label =
            when {
                snapshot.activeSession.awaitingConfirmation -> "小轩待确认"
                snapshot.activeSession.sessionId.isNotBlank() &&
                    snapshot.activeSession.statusModel.takeoverReason.name == "PAUSED" -> "继续小轩"
                snapshot.activeSession.sessionId.isNotBlank() -> "暂停小轩"
                productShell.voiceInteraction.state in setOf("listening", "partial", "executing") -> "停止语音"
                productShell.voiceInteraction.availabilitySummary == "voice_ready" -> "开始语音"
                else -> "打开小轩"
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle =
                when {
                    snapshot.activeSession.awaitingConfirmation -> "高风险动作"
                    snapshot.activeSession.sessionId.isNotBlank() -> snapshot.activeSession.task.ifBlank { "当前有执行中的任务" }
                    productShell.voiceInteraction.summary.isNotBlank() -> productShell.voiceInteraction.summary
                    else -> "查看 today / inbox / command"
                }
        }
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
    }
}
