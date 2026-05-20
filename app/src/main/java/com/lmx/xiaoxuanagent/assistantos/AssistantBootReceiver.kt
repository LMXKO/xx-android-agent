package com.lmx.xiaoxuanagent.assistantos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AssistantBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        val reason =
            when (intent?.action) {
                Intent.ACTION_BOOT_COMPLETED -> "boot_completed"
                Intent.ACTION_MY_PACKAGE_REPLACED -> "package_replaced"
                else -> "system_broadcast"
            }
        AssistantRuntimeServiceController.ensureStarted(context, reason)
    }
}
