package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.runtime.SessionRuntime

internal object SessionRuntimeProfileIdSupport {
    fun resolve(
        @Suppress("UNUSED_PARAMETER") taskPlanState: TaskPlanState,
        targetPackageName: String,
    ): String =
        SessionRuntime.State.runtimeState().session.profileId
            .ifBlank { SessionRuntime.currentTaskProfile().id }
            .ifBlank { targetPackageName }
}
