package com.lmx.xiaoxuanagent.runtime

internal suspend fun dispatchPlatformWorkerCapability(
    request: SessionCapabilityRequest,
): SessionCapabilityResult =
    when (request.key) {
        SessionCapabilityKey.READ_SIGNAL_PROVIDERS -> {
            val providers =
                SessionPlatformFacade.readSignalProviders(
                    limit = request.payload["limit"]?.toIntOrNull() ?: 8,
                )
            SessionCapabilityResult(
                success = providers.isNotEmpty(),
                summary = if (providers.isEmpty()) "没有 provider 状态。" else "signal provider 状态已读取。",
                payloadLines =
                    providers.map { provider ->
                        "${provider.providerId} | ${provider.providerType.ifBlank { provider.source }} | trust=${provider.trustLevel.name.lowercase()} " +
                            "| health=${provider.healthScore} | priority=${provider.routingPriority} " +
                            "| gate=${provider.lastGateAction.name.lowercase()} | failures=${provider.failureCount} " +
                            "| failure_mode=${provider.lastFailureMode.ifBlank { "-" }} " +
                            "| diagnostics=${provider.diagnosticsSummary.ifBlank { "-" }} " +
                            "| caps=${provider.supportedCapabilities.joinToString(",") { it.name.lowercase() }.ifBlank { "*" }} " +
                            "| signals=${provider.supportedSignalTypes.joinToString(",") { it.name.lowercase() }.ifBlank { "*" }} " +
                            "| tags=${provider.routingTags.joinToString(",").ifBlank { "-" }} " +
                            "| mode=${provider.deliveryMode.ifBlank { "-" }}"
                    },
            )
        }

        SessionCapabilityKey.READ_WORKER_MAILBOX -> {
            val target =
                request.payload["target"].orEmpty().ifBlank {
                    request.sessionId.ifBlank { request.payload["worker_id"].orEmpty() }
                }
            val messages =
                SessionPlatformFacade.readWorkerMailbox(
                    target = target,
                    includeConsumed = request.payload["include_consumed"]?.toBooleanStrictOrNull() ?: false,
                    limit = request.payload["limit"]?.toIntOrNull() ?: 12,
                )
            SessionCapabilityResult(
                success = messages.isNotEmpty(),
                summary = if (messages.isEmpty()) "worker mailbox 为空。" else "worker mailbox 已读取。",
                sessionId = request.sessionId,
                payloadLines =
                    messages.map { message ->
                        "${message.messageId} | p=${message.priority} | ${message.type.name.lowercase()} | ${message.status.name.lowercase()} | ${message.summary.ifBlank { message.title }}"
                    },
            )
        }

        SessionCapabilityKey.POST_WORKER_MESSAGE -> {
            val replyTo = request.payload["reply_to_message_id"].orEmpty()
            val responseDecision = request.payload["decision"].orEmpty()
            val message =
                if (replyTo.isNotBlank() && responseDecision.isNotBlank()) {
                    SessionPlatformFacade.postWorkerPermissionResponse(
                        requestMessageId = replyTo,
                        decision = responseDecision,
                        note = request.payload["note"].orEmpty().ifBlank { request.userCorrection },
                        responderSessionId = request.sessionId,
                        responderWorkerId = request.payload["sender_worker_id"].orEmpty(),
                        responderAgentId = request.payload["sender_agent_id"].orEmpty(),
                    )
                } else {
                    val type =
                        runCatching {
                            SessionWorkerMailboxMessageType.valueOf(
                                request.payload["type"].orEmpty().ifBlank { "STATUS_UPDATE" }.uppercase(),
                            )
                        }.getOrDefault(SessionWorkerMailboxMessageType.STATUS_UPDATE)
                    SessionPlatformFacade.postWorkerMailboxMessage(
                        type = type,
                        senderSessionId = request.sessionId.ifBlank { request.payload["sender_session_id"].orEmpty() },
                        senderWorkerId = request.payload["sender_worker_id"].orEmpty(),
                        senderAgentId = request.payload["sender_agent_id"].orEmpty(),
                        recipientSessionId = request.payload["recipient_session_id"].orEmpty(),
                        recipientWorkerId = request.payload["recipient_worker_id"].orEmpty(),
                        recipientAgentId = request.payload["recipient_agent_id"].orEmpty(),
                        rootSessionId = request.payload["root_session_id"].orEmpty(),
                        coordinatorSessionId = request.payload["coordinator_session_id"].orEmpty(),
                        replyToMessageId = replyTo,
                        title = request.payload["title"].orEmpty(),
                        summary = request.payload["summary"].orEmpty().ifBlank { request.task.ifBlank { request.query } },
                        payload = request.payload,
                        dedupeKey = request.payload["dedupe_key"].orEmpty(),
                    )
                }
            SessionCapabilityResult(
                success = message != null,
                summary = if (message == null) "worker mailbox 消息投递失败。" else "worker mailbox 消息已投递：${message.messageId}",
                sessionId = message?.recipientSessionId.orEmpty(),
                payloadLines =
                    message?.let {
                        listOf(
                            "type=${it.type.name.lowercase()}",
                            "recipient=${it.recipientSessionId.ifBlank { it.recipientWorkerId.ifBlank { it.recipientAgentId.ifBlank { "-" } } }}",
                            "summary=${it.summary.ifBlank { it.title }}",
                        )
                    }.orEmpty(),
            )
        }

        SessionCapabilityKey.ACK_WORKER_MESSAGE -> {
            val acked =
                SessionPlatformFacade.acknowledgeWorkerMailboxMessage(
                    messageId = request.payload["message_id"].orEmpty(),
                    note = request.payload["note"].orEmpty(),
                )
            SessionCapabilityResult(
                success = acked != null,
                summary = if (acked == null) "未找到 mailbox 消息。" else "mailbox 消息已确认：${acked.messageId}",
                sessionId = acked?.recipientSessionId.orEmpty(),
            )
        }

        else -> SessionCapabilityResult(false, "unsupported_platform_worker_capability:${request.key.name.lowercase()}")
    }
