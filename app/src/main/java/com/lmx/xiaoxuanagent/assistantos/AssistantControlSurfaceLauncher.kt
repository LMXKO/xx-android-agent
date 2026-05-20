package com.lmx.xiaoxuanagent.assistantos

import android.content.Context
import android.content.Intent
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext

object AssistantControlSurfaceLauncher {
    fun bringToFront(
        reason: String,
        page: String = "",
    ) {
        val context = AppRuntimeContext.get() ?: return
        refreshRuntimeNotification(context, reason)
        val intent =
            if (page.isBlank()) {
                AssistantShellIntentRouter.createActionIntent(
                    context = context,
                    action = AssistantShellIntentRouter.ACTION_OPEN,
                    source = reason.ifBlank { "runtime_control_surface" },
                )
            } else {
                AssistantShellIntentRouter.createPageIntent(
                    context = context,
                    page = page,
                    source = reason.ifBlank { "runtime_control_surface" },
                )
            }
        runCatching {
            context.startActivity(
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        }
    }

    fun refreshRuntimeNotification(
        reason: String,
    ) {
        refreshRuntimeNotification(AppRuntimeContext.get() ?: return, reason)
    }

    private fun refreshRuntimeNotification(
        context: Context,
        reason: String,
    ) {
        runCatching {
            AssistantRuntimeServiceController.refresh(context, reason)
        }
    }
}
