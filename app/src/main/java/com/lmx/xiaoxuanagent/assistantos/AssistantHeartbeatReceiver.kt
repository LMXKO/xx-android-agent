package com.lmx.xiaoxuanagent.assistantos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext

class AssistantHeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        AppRuntimeContext.init(context)
        AssistantRuntimeServiceController.heartbeat(
            context = context,
            reason = intent?.action.orEmpty().ifBlank { "assistant_heartbeat" },
        )
    }

    companion object {
        const val ACTION_HEARTBEAT = "com.lmx.xiaoxuanagent.ASSISTANT_HEARTBEAT"
        const val REQUEST_CODE = 4101
    }
}
