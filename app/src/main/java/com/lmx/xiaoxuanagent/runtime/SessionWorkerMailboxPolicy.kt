package com.lmx.xiaoxuanagent.runtime

internal enum class SessionWorkerMailboxActionType {
    APPLY_PERMISSION_RESPONSE,
    APPLY_CONTROL_OPERATION,
    ACKNOWLEDGE_ONLY,
    HOLD,
}

internal data class SessionWorkerMailboxAction(
    val message: SessionWorkerMailboxMessage,
    val type: SessionWorkerMailboxActionType,
    val acknowledgements: List<Pair<String, String>> = emptyList(),
)

data class SessionWorkerMailboxSnapshot(
    val target: String = "",
    val totalCount: Int = 0,
    val pendingCount: Int = 0,
    val attentionCount: Int = 0,
    val replyChainCount: Int = 0,
    val permissionRequestCount: Int = 0,
    val permissionResponseCount: Int = 0,
    val permissionPendingCount: Int = 0,
    val controlCount: Int = 0,
    val partialResultCount: Int = 0,
    val statusUpdateCount: Int = 0,
    val hottestRecipient: String = "",
    val priorityMode: String = "balanced",
    val categorySummary: String = "",
    val coordinationSummary: String = "",
    val recentLines: List<String> = emptyList(),
    val lines: List<String> = emptyList(),
)

internal object SessionWorkerMailboxPolicy {
    fun prioritizePending(
        pending: List<SessionWorkerMailboxMessage>,
        limit: Int,
        priorityMode: String,
    ): List<SessionWorkerMailboxMessage> =
        pending
            .sortedWith(priorityComparator(priorityMode))
            .take(limit)

    fun buildProcessingPlan(
        messages: List<SessionWorkerMailboxMessage>,
    ): List<SessionWorkerMailboxAction> {
        if (messages.isEmpty()) return emptyList()
        val latestStatusIds = latestByKey(messages) { it.statusDedupKey() }
        val latestPartialIds = latestByKey(messages) { it.partialDedupKey() }
        val latestControlIds = latestByKey(messages) { it.controlDedupKey() }
        return messages.map { message ->
            when (message.type) {
                SessionWorkerMailboxMessageType.PERMISSION_RESPONSE ->
                    SessionWorkerMailboxAction(
                        message = message,
                        type = SessionWorkerMailboxActionType.APPLY_PERMISSION_RESPONSE,
                    )

                SessionWorkerMailboxMessageType.STATUS_UPDATE ->
                    if (message.messageId in latestStatusIds) {
                        acknowledgeOnly(message, "mailbox_status_snapshot")
                    } else {
                        acknowledgeOnly(message, "mailbox_status_superseded")
                    }

                SessionWorkerMailboxMessageType.PARTIAL_RESULT ->
                    if (message.messageId in latestPartialIds) {
                        acknowledgeOnly(message, "mailbox_partial_result")
                    } else {
                        acknowledgeOnly(message, "mailbox_partial_superseded")
                    }

                SessionWorkerMailboxMessageType.CONTROL ->
                    if (message.messageId in latestControlIds) {
                        SessionWorkerMailboxAction(
                            message = message,
                            type = SessionWorkerMailboxActionType.APPLY_CONTROL_OPERATION,
                        )
                    } else {
                        acknowledgeOnly(message, "mailbox_control_superseded")
                    }

                SessionWorkerMailboxMessageType.PERMISSION_REQUEST ->
                    SessionWorkerMailboxAction(
                        message = message,
                        type = SessionWorkerMailboxActionType.HOLD,
                    )
            }
        }
    }

    fun buildSnapshot(
        messages: List<SessionWorkerMailboxMessage>,
        priorityMode: String,
        target: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ): SessionWorkerMailboxSnapshot {
        if (messages.isEmpty()) {
            return SessionWorkerMailboxSnapshot(
                target = target,
                priorityMode = priorityMode,
                lines = listOf("mailbox_count=0"),
            )
        }
        val pendingCount = messages.count { it.status == SessionWorkerMailboxStatus.PENDING }
        val replyChainCount = messages.count { it.replyToMessageId.isNotBlank() || it.type == SessionWorkerMailboxMessageType.PERMISSION_RESPONSE }
        val permissionRequestCount = messages.count { it.status == SessionWorkerMailboxStatus.PENDING && it.type == SessionWorkerMailboxMessageType.PERMISSION_REQUEST }
        val permissionResponseCount = messages.count { it.status == SessionWorkerMailboxStatus.PENDING && it.type == SessionWorkerMailboxMessageType.PERMISSION_RESPONSE }
        val permissionPendingCount =
            permissionRequestCount + permissionResponseCount
        val controlCount = messages.count { it.type == SessionWorkerMailboxMessageType.CONTROL }
        val partialResultCount = messages.count { it.type == SessionWorkerMailboxMessageType.PARTIAL_RESULT }
        val statusUpdateCount = messages.count { it.type == SessionWorkerMailboxMessageType.STATUS_UPDATE }
        val attentionMessages = messages.filter { it.requiresAttention(nowMs) }
        val hottestRecipient =
            messages
                .groupBy { it.recipientSessionId.ifBlank { it.recipientWorkerId.ifBlank { it.rootSessionId } } }
                .entries
                .maxByOrNull { it.value.size }
                ?.key
                .orEmpty()
        val categorySummary =
            buildString {
                append("pending=").append(pendingCount)
                append(" permission=").append(permissionPendingCount)
                append(" reply=").append(replyChainCount)
                append(" control=").append(controlCount)
                append(" partial=").append(partialResultCount)
                append(" status=").append(statusUpdateCount)
            }
        val coordinationSummary =
            buildString {
                append("mode=").append(priorityMode.ifBlank { "balanced" })
                append(" attention=").append(attentionMessages.size)
                hottestRecipient.takeIf { it.isNotBlank() }?.let {
                    append(" hottest_recipient=").append(it)
                }
                messages.maxOfOrNull { it.priority }?.let { maxPriority ->
                    append(" max_priority=").append(maxPriority)
                }
            }
        val recentLines =
            messages.take(6).map { message ->
                buildString {
                    append("p=").append(message.priority)
                    append(" ")
                    append(message.type.name.lowercase())
                    append(" | ")
                    append(message.summary.ifBlank { message.title }.take(72))
                    if (message.replyToMessageId.isNotBlank()) {
                        append(" | reply")
                    }
                }
            }
        return SessionWorkerMailboxSnapshot(
            target = target,
            totalCount = messages.size,
            pendingCount = pendingCount,
            attentionCount = attentionMessages.size,
            replyChainCount = replyChainCount,
            permissionRequestCount = permissionRequestCount,
            permissionResponseCount = permissionResponseCount,
            permissionPendingCount = permissionPendingCount,
            controlCount = controlCount,
            partialResultCount = partialResultCount,
            statusUpdateCount = statusUpdateCount,
            hottestRecipient = hottestRecipient,
            priorityMode = priorityMode,
            categorySummary = categorySummary,
            coordinationSummary = coordinationSummary,
            recentLines = recentLines,
            lines =
                buildList {
                    add("mailbox_count=${messages.size}")
                    add(categorySummary)
                    add(coordinationSummary)
                    recentLines.forEach { add("recent=$it") }
                },
        )
    }

    private fun priorityComparator(
        priorityMode: String,
    ): Comparator<SessionWorkerMailboxMessage> {
        val normalizedMode = priorityMode.trim().lowercase()
        return Comparator { left, right ->
            compareValuesBy(
                left,
                right,
                { -priorityScore(it, normalizedMode) },
                { -it.priority },
                { it.createdAtMs },
            )
        }
    }

    private fun priorityScore(
        message: SessionWorkerMailboxMessage,
        priorityMode: String,
    ): Int {
        val approvalBoost =
            if (
                priorityMode == "approval_first" &&
                (message.type == SessionWorkerMailboxMessageType.PERMISSION_REQUEST || message.type == SessionWorkerMailboxMessageType.PERMISSION_RESPONSE)
            ) {
                60
            } else {
                0
            }
        val throughputBoost =
            if (
                priorityMode == "high_throughput" &&
                (message.type == SessionWorkerMailboxMessageType.PARTIAL_RESULT || message.type == SessionWorkerMailboxMessageType.STATUS_UPDATE)
            ) {
                30
            } else {
                0
            }
        val replyChainBoost =
            if (
                priorityMode == "reply_chain_first" &&
                (
                    message.replyToMessageId.isNotBlank() ||
                        message.type == SessionWorkerMailboxMessageType.PERMISSION_RESPONSE ||
                        message.type == SessionWorkerMailboxMessageType.CONTROL
                )
            ) {
                40
            } else {
                0
            }
        val mailboxBoost =
            if (priorityMode == "mailbox_first" && message.recipientSessionId.isNotBlank()) {
                20
            } else {
                0
            }
        val staleBoost =
            if (priorityMode == "stale_first") {
                (((System.currentTimeMillis() - message.createdAtMs).coerceAtLeast(0L) / 60_000L).toInt() * 2).coerceAtMost(40)
            } else {
                ((System.currentTimeMillis() - message.createdAtMs).coerceAtLeast(0L) / 60_000L)
                    .toInt()
                    .coerceAtMost(20)
            }
        val controlBoost =
            when (message.type) {
                SessionWorkerMailboxMessageType.PERMISSION_RESPONSE -> 55
                SessionWorkerMailboxMessageType.CONTROL -> 45
                SessionWorkerMailboxMessageType.PERMISSION_REQUEST -> 35
                SessionWorkerMailboxMessageType.PARTIAL_RESULT -> 18
                SessionWorkerMailboxMessageType.STATUS_UPDATE -> 10
            }
        return message.priority + controlBoost + approvalBoost + throughputBoost + replyChainBoost + mailboxBoost + staleBoost
    }

    private fun acknowledgeOnly(
        message: SessionWorkerMailboxMessage,
        note: String,
    ): SessionWorkerMailboxAction =
        SessionWorkerMailboxAction(
            message = message,
            type = SessionWorkerMailboxActionType.ACKNOWLEDGE_ONLY,
            acknowledgements =
                buildList {
                    add(message.messageId to note)
                    message.replyToMessageId.takeIf { it.isNotBlank() }?.let {
                        add(it to "${note}_reply")
                    }
                },
        )

    private fun latestByKey(
        messages: List<SessionWorkerMailboxMessage>,
        keySelector: (SessionWorkerMailboxMessage) -> String,
    ): Set<String> =
        messages
            .groupBy { message -> keySelector(message).ifBlank { message.messageId } }
            .values
            .mapNotNull { grouped ->
                grouped.maxWithOrNull(
                    compareBy<SessionWorkerMailboxMessage> { it.createdAtMs }
                        .thenBy { it.updatedAtMs },
                )?.messageId
            }.toSet()

    private fun SessionWorkerMailboxMessage.statusDedupKey(): String =
        listOf(
            recipientSessionId,
            recipientWorkerId,
            senderWorkerId,
            payload["status_code"].orEmpty(),
        ).joinToString(":")

    private fun SessionWorkerMailboxMessage.partialDedupKey(): String =
        replyToMessageId.ifBlank {
            listOf(
                recipientSessionId,
                recipientWorkerId,
                senderWorkerId,
                payload["session_id"].orEmpty(),
                payload["result_summary"].orEmpty().take(48),
            ).joinToString(":")
        }

    private fun SessionWorkerMailboxMessage.controlDedupKey(): String =
        listOf(
            recipientSessionId,
            recipientWorkerId,
            payload["control_action"].orEmpty().ifBlank { payload["action"].orEmpty() },
            payload["worker_id"].orEmpty(),
            replyToMessageId,
        ).joinToString(":")

    private fun SessionWorkerMailboxMessage.requiresAttention(
        nowMs: Long,
    ): Boolean {
        val staleMinutes = ((nowMs - createdAtMs).coerceAtLeast(0L) / 60_000L).toInt()
        return status == SessionWorkerMailboxStatus.PENDING &&
            (
                type == SessionWorkerMailboxMessageType.PERMISSION_REQUEST ||
                    type == SessionWorkerMailboxMessageType.PERMISSION_RESPONSE ||
                    type == SessionWorkerMailboxMessageType.CONTROL ||
                    replyToMessageId.isNotBlank() ||
                    priority >= 80 ||
                    staleMinutes >= 15
            )
    }
}
