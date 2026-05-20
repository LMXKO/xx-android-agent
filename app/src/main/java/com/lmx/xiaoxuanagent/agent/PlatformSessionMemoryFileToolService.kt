package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.runtime.SessionMemoryFileMaintainer
import com.lmx.xiaoxuanagent.runtime.SessionRuntime

internal object PlatformSessionMemoryFileToolService {
    fun readCurrentFile(
        sessionId: String,
        task: String,
        profileId: String,
    ): AgentExecutionResult {
        val runtimeSession = SessionRuntime.State.runtimeState().session
        val snapshot =
            SessionMemoryFileMaintainer.refreshIfNeeded(
                sessionId = sessionId,
                task = task,
                profileId = profileId,
                turn = runtimeSession.turns,
                trigger = "tool_read",
                history = runtimeSession.history,
                force = true,
            )
        return AgentExecutionResult(
            message = if (snapshot == null) "当前 session memory file 为空。" else "已读取 session memory file。",
            keepRunning = true,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "platform_session_memory_file",
            resolvedTargetText = snapshot?.headline.orEmpty(),
            toolRuntimeDetailLines =
                buildList {
                    snapshot?.headline?.takeIf { it.isNotBlank() }?.let { add("headline: $it") }
                    addAll(snapshot?.previewLines.orEmpty().take(5))
                },
        )
    }
}
