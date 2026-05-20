package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.runtime.SessionTodoBoardStore

internal object PlatformTodoToolService {
    fun readCurrentBoard(
        sessionId: String,
    ): AgentExecutionResult {
        val snapshot = SessionTodoBoardStore.readSnapshot(sessionId)
        val lines =
            buildList {
                snapshot?.summary?.takeIf { it.isNotBlank() }?.let { add("summary: $it") }
                snapshot?.items.orEmpty().take(6).forEach { item ->
                    add("${item.status} | ${item.text.take(96)}")
                }
            }
        return AgentExecutionResult(
            message = if (snapshot == null) "当前 todo board 为空。" else "已读取当前 todo board。",
            keepRunning = true,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "platform_todo_board_read",
            toolRuntimeDetailLines = lines,
        )
    }

    fun writeCurrentBoard(
        sessionId: String,
        content: String,
        mode: String,
    ): AgentExecutionResult {
        val snapshot =
            SessionTodoBoardStore.upsertItems(
                sessionId = sessionId,
                rawItems = content.lineSequence().toList(),
                mode = mode,
                source = "tool:$mode",
            )
        return AgentExecutionResult(
            message = if (snapshot == null) "写入 todo board 失败。" else "已更新 todo board。",
            keepRunning = snapshot != null,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "platform_todo_board_write",
            resolvedTargetText = content.take(96),
            toolRuntimeDetailLines = snapshot?.items.orEmpty().take(6).map { "${it.status} | ${it.text.take(96)}" },
        )
    }
}
