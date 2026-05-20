package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class SessionWorkerMailboxMessageType {
    PARTIAL_RESULT,
    PERMISSION_REQUEST,
    PERMISSION_RESPONSE,
    STATUS_UPDATE,
    CONTROL,
}

enum class SessionWorkerMailboxStatus {
    PENDING,
    CONSUMED,
    FAILED,
}

data class SessionWorkerMailboxMessage(
    val messageId: String,
    val type: SessionWorkerMailboxMessageType,
    val status: SessionWorkerMailboxStatus = SessionWorkerMailboxStatus.PENDING,
    val priority: Int = 0,
    val senderSessionId: String = "",
    val senderWorkerId: String = "",
    val senderAgentId: String = "",
    val recipientSessionId: String = "",
    val recipientWorkerId: String = "",
    val recipientAgentId: String = "",
    val rootSessionId: String = "",
    val coordinatorSessionId: String = "",
    val replyToMessageId: String = "",
    val title: String = "",
    val summary: String = "",
    val payload: Map<String, String> = emptyMap(),
    val dedupeKey: String = "",
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val consumedAtMs: Long = 0L,
    val failureReason: String = "",
)

object SessionWorkerMailboxStore {
    private const val MAILBOX_DIR = "runtime"
    private const val MAILBOX_FILE = "worker_mailbox.json"
    private const val MAX_MESSAGES = 320
    private val lock = Any()
    private val messages = ArrayDeque<SessionWorkerMailboxMessage>()
    private var hydrated = false

    fun postMessage(
        type: SessionWorkerMailboxMessageType,
        senderSessionId: String = "",
        senderWorkerId: String = "",
        senderAgentId: String = "",
        recipientSessionId: String = "",
        recipientWorkerId: String = "",
        recipientAgentId: String = "",
        rootSessionId: String = "",
        coordinatorSessionId: String = "",
        replyToMessageId: String = "",
        title: String = "",
        summary: String = "",
        payload: Map<String, String> = emptyMap(),
        dedupeKey: String = "",
        priority: Int = defaultPriority(type),
    ): SessionWorkerMailboxMessage {
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            hydrateIfNeededUnlocked()
            val existing =
                dedupeKey
                    .takeIf { it.isNotBlank() }
                    ?.let { key ->
                        messages.firstOrNull { it.dedupeKey == key && it.status != SessionWorkerMailboxStatus.FAILED }
                    }
            if (existing != null) {
                return@synchronized existing
            }
            val message =
                SessionWorkerMailboxMessage(
                    messageId = "mail_${now}_${type.name.lowercase()}",
                    type = type,
                    priority = priority,
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
                    createdAtMs = now,
                    updatedAtMs = now,
                )
            messages.addFirst(message)
            dedupeTrimUnlocked()
            persistUnlocked()
            PlatformTraceStore.record(
                category = "worker_mailbox",
                sessionId = senderSessionId.ifBlank { recipientSessionId },
                summary = "${type.name.lowercase()} | ${summary.ifBlank { title }.take(120)}",
                metadata =
                    mapOf(
                        "message_id" to message.messageId,
                        "sender_worker_id" to senderWorkerId,
                        "recipient_worker_id" to recipientWorkerId,
                        "reply_to" to replyToMessageId,
                    ),
            )
            message
        }
    }

    fun postPermissionResponse(
        requestMessageId: String,
        decision: String,
        note: String,
        responderSessionId: String = "",
        responderWorkerId: String = "",
        responderAgentId: String = "",
    ): SessionWorkerMailboxMessage? {
        val request = readById(requestMessageId) ?: return null
        val normalizedDecision =
            when (decision.lowercase()) {
                "allow", "approve", "approved", "yes", "true" -> "approve"
                "deny", "reject", "rejected", "no", "false" -> "reject"
                else -> decision.ifBlank { "approve" }
            }
        return postMessage(
            type = SessionWorkerMailboxMessageType.PERMISSION_RESPONSE,
            senderSessionId = responderSessionId.ifBlank { request.recipientSessionId },
            senderWorkerId = responderWorkerId.ifBlank { request.recipientWorkerId },
            senderAgentId = responderAgentId.ifBlank { request.recipientAgentId },
            recipientSessionId = request.senderSessionId,
            recipientWorkerId = request.senderWorkerId,
            recipientAgentId = request.senderAgentId,
            rootSessionId = request.rootSessionId,
            coordinatorSessionId = request.coordinatorSessionId,
            replyToMessageId = request.messageId,
            title = "权限响应",
            summary = note.ifBlank { "permission $normalizedDecision" },
            payload =
                request.payload +
                    mapOf(
                        "decision" to normalizedDecision,
                        "note" to note,
                        "request_message_id" to request.messageId,
                    ),
            dedupeKey = "permission_response:${request.messageId}:$normalizedDecision",
            priority = 100,
        )
    }

    fun syncPlatformSnapshot(
        snapshot: SessionPlatformSnapshot,
    ) {
        val sessionId = snapshot.sessionId.ifBlank { snapshot.bridgeSnapshot.sessionId }
        if (sessionId.isBlank()) return
        val worker = SessionWorkerStore.readBySessionId(sessionId) ?: return
        val summary =
            snapshot.bridgeSnapshot.resultSummary.ifBlank {
                snapshot.bridgeSnapshot.errorSummary.ifBlank {
                    snapshot.pendingSafetySummary.ifBlank {
                        snapshot.bridgeSnapshot.takeoverSummary.ifBlank {
                            snapshot.healthSummary.summary
                        }
                    }
                }
            }
        if (snapshot.pendingSafetySummary.isNotBlank()) {
            postMessage(
                type = SessionWorkerMailboxMessageType.PERMISSION_REQUEST,
                senderSessionId = worker.sessionId,
                senderWorkerId = worker.workerId,
                senderAgentId = worker.agentId,
                recipientSessionId = worker.parentSessionId,
                recipientWorkerId = worker.parentWorkerId,
                recipientAgentId = worker.parentAgentId,
                rootSessionId = worker.rootSessionId,
                coordinatorSessionId = worker.coordinatorSessionId,
                title = "worker 请求权限",
                summary = snapshot.pendingSafetySummary,
                payload =
                    worker.metadata +
                        mapOf(
                            "action" to "approve_safety",
                            "session_id" to sessionId,
                            "worker_id" to worker.workerId,
                            "status_code" to snapshot.bridgeSnapshot.statusCode,
                            "approval_summary" to snapshot.pendingSafetySummary,
                        ),
                dedupeKey = "permission_request:${worker.workerId}:${snapshot.pendingSafetySummary.hashCode()}",
                priority = 100,
            )
        }
        if (snapshot.bridgeSnapshot.statusCode.isNotBlank() && summary.isNotBlank()) {
            val type =
                if (
                    snapshot.bridgeSnapshot.statusTerminalReason == AgentUiTerminalReason.COMPLETED ||
                    snapshot.bridgeSnapshot.statusTerminalReason == AgentUiTerminalReason.FAILED ||
                    snapshot.bridgeSnapshot.statusTerminalReason == AgentUiTerminalReason.STOPPED ||
                    snapshot.bridgeSnapshot.statusTerminalReason == AgentUiTerminalReason.MAX_TURNS_REACHED
                ) {
                    SessionWorkerMailboxMessageType.PARTIAL_RESULT
                } else {
                    SessionWorkerMailboxMessageType.STATUS_UPDATE
                }
            postMessage(
                type = type,
                senderSessionId = worker.sessionId,
                senderWorkerId = worker.workerId,
                senderAgentId = worker.agentId,
                recipientSessionId = worker.parentSessionId,
                recipientWorkerId = worker.parentWorkerId,
                recipientAgentId = worker.parentAgentId,
                rootSessionId = worker.rootSessionId,
                coordinatorSessionId = worker.coordinatorSessionId,
                title = "worker 状态更新",
                summary = summary,
                payload =
                    mapOf(
                        "status_code" to snapshot.bridgeSnapshot.statusCode,
                        "session_id" to sessionId,
                        "worker_id" to worker.workerId,
                        "result_summary" to snapshot.bridgeSnapshot.resultSummary,
                        "error_summary" to snapshot.bridgeSnapshot.errorSummary,
                    ),
                dedupeKey = "worker_status:${worker.workerId}:${snapshot.bridgeSnapshot.statusCode}:${summary.hashCode()}",
                priority =
                    if (type == SessionWorkerMailboxMessageType.PARTIAL_RESULT) {
                        70
                    } else {
                        40
                    },
            )
        }
    }

    fun processPendingMessages(
        limit: Int = 8,
        priorityMode: String = "balanced",
    ): List<SessionWorkerMailboxMessage> {
        val messages = prioritizedPending(limit = limit, priorityMode = priorityMode)
        SessionWorkerMailboxPolicy.buildProcessingPlan(messages).forEach { action ->
            when (action.type) {
                SessionWorkerMailboxActionType.APPLY_PERMISSION_RESPONSE -> applyPermissionResponse(action.message)
                SessionWorkerMailboxActionType.APPLY_CONTROL_OPERATION -> applyControlMessage(action.message)
                SessionWorkerMailboxActionType.ACKNOWLEDGE_ONLY ->
                    action.acknowledgements.forEach { (messageId, note) ->
                        acknowledge(messageId, note)
                    }

                SessionWorkerMailboxActionType.HOLD -> Unit
            }
        }
        return messages
    }

    fun readById(
        messageId: String,
    ): SessionWorkerMailboxMessage? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            messages.firstOrNull { it.messageId == messageId }
        }

    fun readPending(
        limit: Int = 24,
    ): List<SessionWorkerMailboxMessage> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            prioritizedPendingUnlocked(limit = limit, priorityMode = "balanced")
        }

    fun readMailbox(
        target: String = "",
        includeConsumed: Boolean = false,
        limit: Int = 24,
    ): List<SessionWorkerMailboxMessage> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            messages
                .asSequence()
                .filter { includeConsumed || it.status == SessionWorkerMailboxStatus.PENDING }
                .filter { message ->
                    target.isBlank() ||
                        message.recipientSessionId == target ||
                        message.recipientWorkerId == target ||
                        message.recipientAgentId == target ||
                        message.senderSessionId == target ||
                        message.senderWorkerId == target ||
                        message.senderAgentId == target ||
                        message.rootSessionId == target ||
                        message.coordinatorSessionId == target
                }
                .sortedWith(
                    compareByDescending<SessionWorkerMailboxMessage> { it.priority }
                        .thenByDescending { it.updatedAtMs },
                )
                .take(limit)
                .toList()
        }

    fun readSnapshot(
        target: String = "",
        includeConsumed: Boolean = false,
        limit: Int = 24,
        priorityMode: String = "balanced",
    ): SessionWorkerMailboxSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            SessionWorkerMailboxPolicy.buildSnapshot(
                messages =
                    messages
                        .asSequence()
                        .filter { includeConsumed || it.status == SessionWorkerMailboxStatus.PENDING }
                        .filter { message ->
                            target.isBlank() ||
                                message.recipientSessionId == target ||
                                message.recipientWorkerId == target ||
                                message.recipientAgentId == target ||
                                message.senderSessionId == target ||
                                message.senderWorkerId == target ||
                                message.senderAgentId == target ||
                                message.rootSessionId == target ||
                                message.coordinatorSessionId == target
                        }
                        .sortedWith(
                            compareByDescending<SessionWorkerMailboxMessage> { it.priority }
                                .thenByDescending { it.updatedAtMs },
                        ).take(limit)
                        .toList(),
                priorityMode = priorityMode,
                target = target,
            )
        }

    fun acknowledge(
        messageId: String,
        note: String = "",
    ): SessionWorkerMailboxMessage? =
        mutate(messageId) { current ->
            val now = System.currentTimeMillis()
            current.copy(
                status = SessionWorkerMailboxStatus.CONSUMED,
                payload = current.payload + mapOf("ack_note" to note),
                updatedAtMs = now,
                consumedAtMs = now,
            )
        }

    fun markFailed(
        messageId: String,
        reason: String,
    ): SessionWorkerMailboxMessage? =
        mutate(messageId) { current ->
            current.copy(
                status = SessionWorkerMailboxStatus.FAILED,
                failureReason = reason,
                updatedAtMs = System.currentTimeMillis(),
            )
        }

    fun exportJson(
        limit: Int = 80,
    ): JSONArray =
        JSONArray().apply {
            readMailbox(includeConsumed = true, limit = limit).forEach { put(it.toJson()) }
        }

    fun importJson(
        array: JSONArray?,
    ) {
        if (array == null || array.length() <= 0) return
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                messages.addLast(json.toMessage())
            }
            dedupeTrimUnlocked()
            persistUnlocked()
        }
    }

    private fun mutate(
        messageId: String,
        reducer: (SessionWorkerMailboxMessage) -> SessionWorkerMailboxMessage,
    ): SessionWorkerMailboxMessage? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val index = messages.indexOfFirst { it.messageId == messageId }
            if (index < 0) return null
            val next = reducer(messages.elementAt(index))
            messages.removeAt(index)
            messages.add(index, next)
            persistUnlocked()
            next
        }

    private fun prioritizedPending(
        limit: Int,
        priorityMode: String,
    ): List<SessionWorkerMailboxMessage> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            prioritizedPendingUnlocked(limit = limit, priorityMode = priorityMode)
        }

    private fun prioritizedPendingUnlocked(
        limit: Int,
        priorityMode: String,
    ): List<SessionWorkerMailboxMessage> {
        val pending = messages.filter { it.status == SessionWorkerMailboxStatus.PENDING }
        if (pending.isEmpty()) return emptyList()
        return SessionWorkerMailboxPolicy.prioritizePending(pending = pending, limit = limit, priorityMode = priorityMode)
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = mailboxFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("messages") ?: JSONArray()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                messages.addLast(json.toMessage())
            }
            dedupeTrimUnlocked()
        }
    }

    private fun persistUnlocked() {
        val file = mailboxFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "messages",
                    JSONArray().apply {
                        messages.forEach { put(it.toJson()) }
                    },
                )
            }.toString(2),
        )
    }

    private fun dedupeTrimUnlocked() {
        val merged =
            messages
                .distinctBy { it.messageId }
                .sortedByDescending { it.updatedAtMs }
                .take(MAX_MESSAGES)
        messages.clear()
        merged.forEach(messages::addLast)
    }

    private fun mailboxFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, MAILBOX_DIR), MAILBOX_FILE)
    }

    private fun SessionWorkerMailboxMessage.toJson(): JSONObject =
        JSONObject().apply {
            put("message_id", messageId)
            put("type", type.name)
            put("status", status.name)
            put("priority", priority)
            put("sender_session_id", senderSessionId)
            put("sender_worker_id", senderWorkerId)
            put("sender_agent_id", senderAgentId)
            put("recipient_session_id", recipientSessionId)
            put("recipient_worker_id", recipientWorkerId)
            put("recipient_agent_id", recipientAgentId)
            put("root_session_id", rootSessionId)
            put("coordinator_session_id", coordinatorSessionId)
            put("reply_to_message_id", replyToMessageId)
            put("title", title)
            put("summary", summary)
            put("dedupe_key", dedupeKey)
            put("created_at_ms", createdAtMs)
            put("updated_at_ms", updatedAtMs)
            put("consumed_at_ms", consumedAtMs)
            put("failure_reason", failureReason)
            put(
                "payload",
                JSONObject().apply {
                    payload.forEach { (key, value) -> put(key, value) }
                },
            )
        }

    private fun JSONObject.toMessage(): SessionWorkerMailboxMessage =
        SessionWorkerMailboxMessage(
            messageId = optString("message_id"),
            type =
                runCatching { SessionWorkerMailboxMessageType.valueOf(optString("type")) }
                    .getOrDefault(SessionWorkerMailboxMessageType.STATUS_UPDATE),
            status =
                runCatching { SessionWorkerMailboxStatus.valueOf(optString("status")) }
                    .getOrDefault(SessionWorkerMailboxStatus.PENDING),
            priority =
                optInt(
                    "priority",
                    defaultPriority(
                        runCatching { SessionWorkerMailboxMessageType.valueOf(optString("type")) }
                            .getOrDefault(SessionWorkerMailboxMessageType.STATUS_UPDATE),
                    ),
                ),
            senderSessionId = optString("sender_session_id"),
            senderWorkerId = optString("sender_worker_id"),
            senderAgentId = optString("sender_agent_id"),
            recipientSessionId = optString("recipient_session_id"),
            recipientWorkerId = optString("recipient_worker_id"),
            recipientAgentId = optString("recipient_agent_id"),
            rootSessionId = optString("root_session_id"),
            coordinatorSessionId = optString("coordinator_session_id"),
            replyToMessageId = optString("reply_to_message_id"),
            title = optString("title"),
            summary = optString("summary"),
            payload = optJSONObject("payload").toStringMap(),
            dedupeKey = optString("dedupe_key"),
            createdAtMs = optLong("created_at_ms"),
            updatedAtMs = optLong("updated_at_ms"),
            consumedAtMs = optLong("consumed_at_ms"),
            failureReason = optString("failure_reason"),
        )

    private fun applyPermissionResponse(
        response: SessionWorkerMailboxMessage,
    ) {
        val decision = response.payload["decision"].orEmpty().lowercase()
        val note = response.payload["note"].orEmpty()
        val action = response.payload["action"].orEmpty()
        val applied =
            when {
                action == "approve_safety" && decision == "approve" ->
                    SessionPlatformFacade.approvePendingSafetyForSession(response.recipientSessionId, note)

                action == "approve_safety" && decision == "reject" ->
                    SessionPlatformFacade.rejectPendingSafetyForSession(response.recipientSessionId)

                else -> true
            }
        if (applied) {
            acknowledge(response.messageId, "permission_response_applied")
            response.replyToMessageId.takeIf { it.isNotBlank() }?.let { acknowledge(it, "permission_response_received") }
        } else {
            markFailed(response.messageId, "permission_response_apply_failed")
        }
    }

    private fun applyControlMessage(
        message: SessionWorkerMailboxMessage,
    ) {
        val action = message.payload["control_action"].orEmpty().ifBlank { message.payload["action"].orEmpty() }
        val workerId = message.recipientWorkerId.ifBlank { message.payload["worker_id"].orEmpty() }
        val owner = message.payload["lease_owner"].orEmpty().ifBlank { message.senderSessionId.ifBlank { message.senderWorkerId } }
        val leaseToken = message.payload["lease_token"].orEmpty()
        val ttlMs = message.payload["lease_ttl_ms"]?.toLongOrNull() ?: 90_000L
        val success =
            when (action) {
                "lease_claim", "worker_claim" ->
                    workerId.isNotBlank() &&
                        (
                            SessionWorkerStore.acquireDispatchLease(workerId = workerId, owner = owner, ttlMs = ttlMs) != null ||
                                SessionWorkerStore.renewDispatchLease(workerId = workerId, owner = owner, ttlMs = ttlMs, leaseToken = leaseToken) != null
                        )

                "lease_renew" ->
                    workerId.isNotBlank() &&
                        SessionWorkerStore.renewDispatchLease(workerId = workerId, owner = owner, ttlMs = ttlMs, leaseToken = leaseToken) != null

                "lease_release", "worker_release" ->
                    workerId.isNotBlank() &&
                        SessionWorkerStore.releaseDispatchLease(
                            workerId = workerId,
                            owner = owner,
                            leaseToken = leaseToken,
                            reason = action,
                        ) != null

                "lease_revoke" ->
                    workerId.isNotBlank() &&
                        SessionWorkerStore.revokeDispatchLease(
                            workerId = workerId,
                            owner = owner,
                            reason = message.summary.ifBlank { action },
                        ) != null

                "lease_handoff" ->
                    workerId.isNotBlank() &&
                        SessionWorkerStore.handoffDispatchLease(
                            workerId = workerId,
                            fromOwner = owner,
                            toOwner = message.payload["next_lease_owner"].orEmpty(),
                            ttlMs = ttlMs,
                        ) != null

                "coordinator_claim" ->
                    workerId.isNotBlank() &&
                        SessionWorkerStore.claimWorkerForCoordinator(
                            workerId = workerId,
                            coordinatorSessionId = message.coordinatorSessionId.ifBlank { message.senderSessionId },
                            ttlMs = ttlMs,
                        ) != null

                "coordinator_release" ->
                    workerId.isNotBlank() &&
                        SessionWorkerStore.releaseCoordinatorClaim(
                            workerId = workerId,
                            coordinatorSessionId = message.coordinatorSessionId.ifBlank { message.senderSessionId },
                            reason = action,
                        ) != null

                else -> false
            }
        if (success) {
            acknowledge(message.messageId, "control_message_applied")
            message.replyToMessageId.takeIf { it.isNotBlank() }?.let { acknowledge(it, "control_message_handled") }
        } else {
            markFailed(message.messageId, "control_message_apply_failed:${action.ifBlank { "unknown" }}")
        }
    }

    private fun defaultPriority(
        type: SessionWorkerMailboxMessageType,
    ): Int =
        when (type) {
            SessionWorkerMailboxMessageType.PERMISSION_RESPONSE -> 100
            SessionWorkerMailboxMessageType.PERMISSION_REQUEST -> 90
            SessionWorkerMailboxMessageType.CONTROL -> 80
            SessionWorkerMailboxMessageType.PARTIAL_RESULT -> 70
            SessionWorkerMailboxMessageType.STATUS_UPDATE -> 40
        }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap {
            keys().forEach { key -> put(key, optString(key)) }
        }
    }
}
