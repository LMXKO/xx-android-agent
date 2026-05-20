package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.runtime.SessionPlatformFacade
import com.lmx.xiaoxuanagent.runtime.SessionWorkerMailboxMessage
import com.lmx.xiaoxuanagent.runtime.SessionWorkerMailboxMessageType
import com.lmx.xiaoxuanagent.runtime.SessionWorkerMailboxStore

internal object PlatformWorkerMailboxToolService {
    fun readMailbox(
        sessionId: String,
        target: String,
        includeConsumed: Boolean,
        limit: Int,
    ): AgentExecutionResult {
        val resolvedTarget = target.ifBlank { sessionId }
        val messages =
            SessionPlatformFacade.readWorkerMailbox(
                target = resolvedTarget,
                includeConsumed = includeConsumed,
                limit = limit.coerceIn(1, 24),
            )
        return AgentExecutionResult(
            message = if (messages.isEmpty()) "worker mailbox 为空。" else "已读取 worker mailbox。",
            keepRunning = true,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "platform_worker_mailbox",
            resolvedTargetText = resolvedTarget,
            toolRuntimeDetailLines = messages.map(::renderMessageLine).take(8),
        )
    }

    fun replyToMessage(
        sessionId: String,
        messageId: String,
        decision: String,
        note: String,
    ): AgentExecutionResult {
        val original = SessionWorkerMailboxStore.readById(messageId)
        if (original == null) {
            return AgentExecutionResult(
                message = "未找到 mailbox 消息：$messageId",
                keepRunning = true,
                shouldImmediateReplan = true,
            )
        }
        val response =
            if (decision.isNotBlank() || original.type == SessionWorkerMailboxMessageType.PERMISSION_REQUEST) {
                SessionPlatformFacade.postWorkerPermissionResponse(
                    requestMessageId = messageId,
                    decision = decision.ifBlank { "approve" },
                    note = note,
                    responderSessionId = sessionId,
                )
            } else {
                SessionPlatformFacade.postWorkerMailboxMessage(
                    type = SessionWorkerMailboxMessageType.CONTROL,
                    senderSessionId = sessionId,
                    recipientSessionId = original.senderSessionId,
                    recipientWorkerId = original.senderWorkerId,
                    recipientAgentId = original.senderAgentId,
                    rootSessionId = original.rootSessionId,
                    coordinatorSessionId = original.coordinatorSessionId,
                    replyToMessageId = original.messageId,
                    title = "planner reply",
                    summary = note.ifBlank { "planner 已回信" },
                    payload =
                        mapOf(
                            "reply_to_message_id" to original.messageId,
                            "source" to "planner_tool",
                            "note" to note,
                        ),
                    dedupeKey = "",
                )
            }
        return AgentExecutionResult(
            message = if (response == null) "worker 回信失败。" else "已回信 worker：${response.messageId}",
            keepRunning = response != null,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "platform_worker_reply",
            resolvedTargetText = original.summary.ifBlank { original.title },
            toolRuntimeDetailLines =
                buildList {
                    add("target_message=${original.messageId}")
                    add("target_type=${original.type.name.lowercase()}")
                    response?.let { add(renderMessageLine(it)) }
                },
        )
    }

    fun acknowledgeMessage(
        messageId: String,
        note: String,
    ): AgentExecutionResult {
        val acked = SessionPlatformFacade.acknowledgeWorkerMailboxMessage(messageId = messageId, note = note)
        return AgentExecutionResult(
            message = if (acked == null) "未找到 mailbox 消息：$messageId" else "已确认 mailbox 消息：$messageId",
            keepRunning = acked != null,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "platform_worker_ack",
            resolvedTargetText = messageId,
            toolRuntimeDetailLines = acked?.let(::renderMessageLine)?.let(::listOf).orEmpty(),
        )
    }

    private fun renderMessageLine(
        message: SessionWorkerMailboxMessage,
    ): String =
        buildString {
            append(message.messageId)
            append(" | ").append(message.type.name.lowercase())
            append(" | ").append(message.status.name.lowercase())
            append(" | p=").append(message.priority)
            append(" | from=").append(message.senderWorkerId.ifBlank { message.senderSessionId.ifBlank { "-" } })
            append(" | to=").append(message.recipientWorkerId.ifBlank { message.recipientSessionId.ifBlank { "-" } })
            append(" | ").append(message.summary.ifBlank { message.title }.take(80))
        }
}
