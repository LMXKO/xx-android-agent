package com.lmx.xiaoxuanagent.runtime

internal object SessionPlatformMailboxFacadeSupport {
    fun readWorkerMailbox(
        target: String,
        includeConsumed: Boolean,
        limit: Int,
    ): List<SessionWorkerMailboxMessage> =
        SessionWorkerMailboxStore.readMailbox(
            target = target,
            includeConsumed = includeConsumed,
            limit = limit,
        )

    fun readWorkerMailboxSnapshot(
        target: String,
        includeConsumed: Boolean,
        limit: Int,
        priorityMode: String,
    ): SessionWorkerMailboxSnapshot =
        SessionWorkerMailboxStore.readSnapshot(
            target = target,
            includeConsumed = includeConsumed,
            limit = limit,
            priorityMode = priorityMode,
        )

    fun postWorkerMailboxMessage(
        type: SessionWorkerMailboxMessageType,
        senderSessionId: String,
        senderWorkerId: String,
        senderAgentId: String,
        recipientSessionId: String,
        recipientWorkerId: String,
        recipientAgentId: String,
        rootSessionId: String,
        coordinatorSessionId: String,
        replyToMessageId: String,
        title: String,
        summary: String,
        payload: Map<String, String>,
        dedupeKey: String,
    ): SessionWorkerMailboxMessage =
        SessionWorkerMailboxStore.postMessage(
            type = type,
            senderSessionId = senderSessionId,
            senderWorkerId = senderWorkerId,
            senderAgentId = senderAgentId,
            recipientSessionId = recipientSessionId,
            recipientWorkerId = recipientWorkerId,
            recipientAgentId = recipientAgentId,
            rootSessionId = rootSessionId,
            coordinatorSessionId = coordinatorSessionId,
            replyToMessageId = replyToMessageId,
            title = title,
            summary = summary,
            payload = payload,
            dedupeKey = dedupeKey,
        )

    fun postWorkerPermissionResponse(
        requestMessageId: String,
        decision: String,
        note: String,
        responderSessionId: String,
        responderWorkerId: String,
        responderAgentId: String,
    ): SessionWorkerMailboxMessage? =
        SessionWorkerMailboxStore.postPermissionResponse(
            requestMessageId = requestMessageId,
            decision = decision,
            note = note,
            responderSessionId = responderSessionId,
            responderWorkerId = responderWorkerId,
            responderAgentId = responderAgentId,
        )

    fun acknowledgeWorkerMailboxMessage(
        messageId: String,
        note: String,
    ): SessionWorkerMailboxMessage? =
        SessionWorkerMailboxStore.acknowledge(messageId = messageId, note = note)

    fun processWorkerMailbox(
        limit: Int,
        priorityMode: String,
    ): List<SessionWorkerMailboxMessage> =
        SessionWorkerMailboxStore.processPendingMessages(limit, priorityMode)

    fun renewWorkerLease(
        workerId: String,
        owner: String,
        ttlMs: Long,
        leaseToken: String,
    ): SessionWorkerRecord? =
        SessionWorkerStore.renewDispatchLease(
            workerId = workerId,
            owner = owner,
            ttlMs = ttlMs,
            leaseToken = leaseToken,
        )

    fun releaseWorkerLease(
        workerId: String,
        owner: String,
        leaseToken: String,
        reason: String,
    ): SessionWorkerRecord? =
        SessionWorkerStore.releaseDispatchLease(
            workerId = workerId,
            owner = owner,
            leaseToken = leaseToken,
            reason = reason,
        )

    fun revokeWorkerLease(
        workerId: String,
        owner: String,
        reason: String,
    ): SessionWorkerRecord? =
        SessionWorkerStore.revokeDispatchLease(
            workerId = workerId,
            owner = owner,
            reason = reason,
        )

    fun handoffWorkerLease(
        workerId: String,
        fromOwner: String,
        toOwner: String,
        ttlMs: Long,
    ): SessionWorkerRecord? =
        SessionWorkerStore.handoffDispatchLease(
            workerId = workerId,
            fromOwner = fromOwner,
            toOwner = toOwner,
            ttlMs = ttlMs,
        )
}
