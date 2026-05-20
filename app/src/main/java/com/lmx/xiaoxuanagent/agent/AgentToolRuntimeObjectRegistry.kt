package com.lmx.xiaoxuanagent.agent

private fun placeholderRuntimeAction(
    toolName: String,
): AgentAction =
    when (toolName) {
        AgentAction.LaunchApp("").toolName -> AgentAction.LaunchApp("")
        AgentAction.Click("").toolName -> AgentAction.Click("")
        AgentAction.SetText("", "").toolName -> AgentAction.SetText("", "")
        AgentAction.Scroll(null, "down").toolName -> AgentAction.Scroll(null, "down")
        AgentAction.LongClick("").toolName -> AgentAction.LongClick("")
        AgentAction.CopyText("").toolName -> AgentAction.CopyText("")
        AgentAction.PasteClipboard("").toolName -> AgentAction.PasteClipboard("")
        AgentAction.ReadSessionHistory("").toolName -> AgentAction.ReadSessionHistory("")
        AgentAction.SearchArtifacts("").toolName -> AgentAction.SearchArtifacts("")
        AgentAction.RecallMemory("").toolName -> AgentAction.RecallMemory("")
        AgentAction.ReadSessionNotebook.toolName -> AgentAction.ReadSessionNotebook
        AgentAction.WriteSessionNote("").toolName -> AgentAction.WriteSessionNote("")
        AgentAction.SearchTools("").toolName -> AgentAction.SearchTools("")
        AgentAction.WebSearch("").toolName -> AgentAction.WebSearch("")
        AgentAction.WebFetch("").toolName -> AgentAction.WebFetch("")
        AgentAction.ReadConnectedAppCapabilities().toolName -> AgentAction.ReadConnectedAppCapabilities()
        AgentAction.ExecuteConnectedAppAction("", "").toolName -> AgentAction.ExecuteConnectedAppAction("", "")
        AgentAction.ReadTodoBoard.toolName -> AgentAction.ReadTodoBoard
        AgentAction.WriteTodoBoard("").toolName -> AgentAction.WriteTodoBoard("")
        AgentAction.DelegateLocalAgent("").toolName -> AgentAction.DelegateLocalAgent("")
        AgentAction.ReadWorkerRoles.toolName -> AgentAction.ReadWorkerRoles
        AgentAction.ReadWorkerMailbox().toolName -> AgentAction.ReadWorkerMailbox()
        AgentAction.ReplyWorkerMessage("").toolName -> AgentAction.ReplyWorkerMessage("")
        AgentAction.AcknowledgeWorkerMessage("").toolName -> AgentAction.AcknowledgeWorkerMessage("")
        AgentAction.ReadSessionMemoryFile.toolName -> AgentAction.ReadSessionMemoryFile
        AgentAction.ReadWorkerStatus().toolName -> AgentAction.ReadWorkerStatus()
        AgentAction.MergeWorkerResult("").toolName -> AgentAction.MergeWorkerResult("")
        AgentAction.TapPoint(0, 0).toolName -> AgentAction.TapPoint(0, 0)
        AgentAction.Back.toolName -> AgentAction.Back
        AgentAction.Home.toolName -> AgentAction.Home
        AgentAction.Notifications.toolName -> AgentAction.Notifications
        AgentAction.QuickSettings.toolName -> AgentAction.QuickSettings
        AgentAction.Recents.toolName -> AgentAction.Recents
        AgentAction.OpenSettings("").toolName -> AgentAction.OpenSettings("")
        AgentAction.ShareText("").toolName -> AgentAction.ShareText("")
        AgentAction.CreateAlarm("", "").toolName -> AgentAction.CreateAlarm("", "")
        AgentAction.CreateTimer("").toolName -> AgentAction.CreateTimer("")
        AgentAction.OpenStopwatch.toolName -> AgentAction.OpenStopwatch
        AgentAction.InsertCalendarEvent("", "", "").toolName -> AgentAction.InsertCalendarEvent("", "", "")
        AgentAction.DialNumber("").toolName -> AgentAction.DialNumber("")
        AgentAction.DraftSms("").toolName -> AgentAction.DraftSms("")
        AgentAction.ReadNotifications().toolName -> AgentAction.ReadNotifications()
        AgentAction.ReplyNotification("", "").toolName -> AgentAction.ReplyNotification("", "")
        AgentAction.MediaControl("").toolName -> AgentAction.MediaControl("")
        AgentAction.AdjustVolume().toolName -> AgentAction.AdjustVolume()
        AgentAction.OpenDevicePanel("").toolName -> AgentAction.OpenDevicePanel("")
        AgentAction.CaptureScreenshot().toolName -> AgentAction.CaptureScreenshot()
        AgentAction.CapturePhoto().toolName -> AgentAction.CapturePhoto()
        AgentAction.PressEnter.toolName -> AgentAction.PressEnter
        AgentAction.FocusPrimaryInput.toolName -> AgentAction.FocusPrimaryInput
        AgentAction.PopulatePrimaryInput("").toolName -> AgentAction.PopulatePrimaryInput("")
        AgentAction.SubmitPrimaryAction("").toolName -> AgentAction.SubmitPrimaryAction("")
        AgentAction.DismissInterrupt("").toolName -> AgentAction.DismissInterrupt("")
        AgentAction.OpenBestCandidate("", "").toolName -> AgentAction.OpenBestCandidate("", "")
        AgentAction.ScrollForMore("down").toolName -> AgentAction.ScrollForMore("down")
        AgentAction.ReturnToTargetApp("").toolName -> AgentAction.ReturnToTargetApp("")
        AgentAction.NavigateBack(false, "").toolName -> AgentAction.NavigateBack(false, "")
        AgentAction.Wait.toolName -> AgentAction.Wait
        AgentAction.Finish("").toolName -> AgentAction.Finish("")
        AgentAction.Fail("").toolName -> AgentAction.Fail("")
        else -> AgentAction.Wait
    }

internal interface AgentToolRuntimeObject {
    val name: String
    val descriptor: AgentToolDescriptor?
    val previewContract: AgentToolRuntimeContract
    val groupKey: String

    fun contract(
        action: AgentAction,
        observation: ScreenObservation,
    ): AgentToolRuntimeContract

    fun validateInput(
        action: AgentAction,
        observation: ScreenObservation,
    ): AgentToolValidationResult

    fun checkPermissions(
        action: AgentAction,
        observation: ScreenObservation,
    ): AgentToolPermissionDecision

    fun inspect(
        action: AgentAction,
        observation: ScreenObservation,
    ): AgentToolRuntimeInspection

    fun queuedMessage(
        action: AgentAction,
    ): String

    fun progressMessage(
        action: AgentAction,
        observation: ScreenObservation,
    ): String

    fun rejectedMessage(
        action: AgentAction,
        validation: AgentToolValidationResult,
        permission: AgentToolPermissionDecision,
    ): String

    fun errorMessage(
        action: AgentAction,
        throwable: Throwable,
    ): String

    fun execute(
        context: AgentToolExecutionContext,
        action: AgentAction,
    ): AgentExecutionResult
}

internal data class AgentToolValidationResult(
    val valid: Boolean = true,
    val reason: String = "",
    val prompt: String = "",
    val summary: String = "",
    val detailLines: List<String> = emptyList(),
)

internal data class AgentToolPermissionDecision(
    val behavior: String = "allow",
    val summary: String = "",
    val preview: String = "",
    val detailLines: List<String> = emptyList(),
)

private data class AgentToolInputReview(
    val behavior: String = "allow",
    val summary: String = "",
    val detailLines: List<String> = emptyList(),
)

private data class DescriptorBackedToolRuntimeObject(
    override val descriptor: AgentToolDescriptor?,
    private val executeHandler: (AgentToolExecutionContext, AgentAction) -> AgentExecutionResult,
) : AgentToolRuntimeObject {
    override val name: String = descriptor?.name.orEmpty()
    override val groupKey: String = "${descriptor?.type?.name?.lowercase().orEmpty()}:${descriptor?.permissionFamily.orEmpty().ifBlank { "general" }}"
    override val previewContract: AgentToolRuntimeContract =
        AgentToolRuntimeRegistry.resolveDescriptor(
            descriptor = descriptor,
            actionLabel = descriptor?.summary.orEmpty(),
        )

    override fun contract(
        action: AgentAction,
        observation: ScreenObservation,
    ): AgentToolRuntimeContract =
        AgentToolRuntimeRegistry.resolve(
            action = if (action.toolName == name) action else placeholderRuntimeAction(name),
            observation = observation,
        )

    override fun validateInput(
        action: AgentAction,
        observation: ScreenObservation,
    ): AgentToolValidationResult {
        val protocol =
            AgentToolRuntimeProtocol.evaluate(
                action = if (action.toolName == name) action else placeholderRuntimeAction(name),
                observation = observation,
            )
        return AgentToolValidationResult(
            valid = protocol.valid,
            reason = protocol.invalidReason,
            prompt = protocol.prompt,
            summary = protocol.summary,
            detailLines = protocol.detailLines,
        )
    }

    override fun checkPermissions(
        action: AgentAction,
        observation: ScreenObservation,
    ): AgentToolPermissionDecision {
        val contract = contract(action = action, observation = observation)
        val inputReview = reviewInput(action = action, observation = observation)
        val inputBehavior =
            when {
                contract.permissionPreview.startsWith("runtime_grant") && inputReview.behavior == "ask" -> "allow"
                else -> inputReview.behavior
            }
        val behavior =
            when {
                contract.permissionPreview.startsWith("deny") -> "deny"
                inputBehavior == "deny" -> "deny"
                inputBehavior == "ask" -> "ask"
                contract.permissionPreview.startsWith("ask") || contract.permissionPreview.startsWith("runtime_review") -> "ask"
                else -> "allow"
            }
        return AgentToolPermissionDecision(
            behavior = behavior,
            summary =
                buildString {
                    append("permission=").append(behavior)
                    append(" | preview=").append(contract.permissionPreview.ifBlank { "-" })
                    append(" | family=").append(contract.permissionFamily.ifBlank { "general" })
                    inputReview.summary.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
                },
            preview = inputReview.summary.ifBlank { contract.permissionPreview },
            detailLines = (contract.detailLines + inputReview.detailLines).distinct().take(10),
        )
    }

    override fun inspect(
        action: AgentAction,
        observation: ScreenObservation,
    ): AgentToolRuntimeInspection {
        val normalizedAction = if (action.toolName == name) action else placeholderRuntimeAction(name)
        val validation = validateInput(normalizedAction, observation)
        val permission = checkPermissions(normalizedAction, observation)
        val contract = contract(normalizedAction, observation)
        return AgentToolRuntimeInspection(
            descriptor = descriptor,
            valid = validation.valid,
            invalidReason = validation.reason,
            readOnly = contract.readOnly,
            permissionFamily = contract.permissionFamily,
            interruptBehavior = contract.interruptBehavior,
            concurrencySafe = contract.concurrencySafe,
            requiresUserInteraction = contract.requiresUserInteraction,
            shouldDefer = contract.shouldDefer,
            alwaysLoad = contract.alwaysLoad,
            progressLabel = contract.progressLabel,
            summary = listOf(validation.summary, permission.summary).filter { it.isNotBlank() }.joinToString(" | ").take(180),
            prompt = validation.prompt.ifBlank { progressMessage(normalizedAction, observation) },
            detailLines = (validation.detailLines + permission.detailLines + contract.detailLines).distinct().take(10),
        )
    }

    override fun queuedMessage(
        action: AgentAction,
    ): String =
        buildString {
            val descriptorTitle = descriptor?.title.orEmpty()
            append(descriptor?.progressLabel?.ifBlank { descriptorTitle }.orEmpty().ifBlank { action.toolName })
            append(" 已排队")
        }

    override fun progressMessage(
        action: AgentAction,
        observation: ScreenObservation,
    ): String {
        val contract = contract(action, observation)
        return buildString {
            append(contract.activityDescription.ifBlank { descriptor?.title.orEmpty().ifBlank { action.toolName } })
            append(" | page=").append(observation.pageState.ifBlank { "-" })
            append(" | interrupt=").append(contract.interruptBehavior.ifBlank { "-" })
        }
    }

    override fun rejectedMessage(
        action: AgentAction,
        validation: AgentToolValidationResult,
        permission: AgentToolPermissionDecision,
    ): String =
        when {
            !validation.valid -> validation.reason.ifBlank { "${action.toolName} 输入校验未通过。" }
            permission.behavior == "deny" -> "工具运行时已拒绝 ${action.toolName}：${permission.preview.ifBlank { permission.summary }}"
            permission.behavior == "ask" -> "工具运行时要求确认 ${action.toolName}：${permission.preview.ifBlank { permission.summary }}"
            else -> "${action.toolName} 当前不可执行。"
        }

    override fun errorMessage(
        action: AgentAction,
        throwable: Throwable,
    ): String = "工具 ${action.toolName} 运行异常：${throwable.message ?: throwable.javaClass.simpleName}"

    override fun execute(
        context: AgentToolExecutionContext,
        action: AgentAction,
    ): AgentExecutionResult =
        executeHandler(
            context,
            if (action.toolName == name) {
                action
            } else {
                placeholderRuntimeAction(name)
            },
        )

    private fun reviewInput(
        action: AgentAction,
        observation: ScreenObservation,
    ): AgentToolInputReview {
        val sensitiveKeywords = listOf("密码", "token", "验证码", "校验码", "私钥", "secret")

        fun containsSensitive(raw: String): Boolean =
            sensitiveKeywords.any { keyword -> raw.contains(keyword, ignoreCase = true) }

        fun noteTooLong(raw: String): Boolean = raw.length > 600

        return when (action) {
            is AgentAction.ShareText ->
                when {
                    containsSensitive(action.text) ->
                        AgentToolInputReview(
                            behavior = "ask",
                            summary = "share_sensitive_payload",
                            detailLines = listOf("input_review=share_sensitive_payload"),
                        )
                    noteTooLong(action.text) ->
                        AgentToolInputReview(
                            behavior = "ask",
                            summary = "share_large_payload",
                            detailLines = listOf("input_review=share_large_payload", "input_length=${action.text.length}"),
                        )
                    else -> AgentToolInputReview()
                }

            is AgentAction.SetText ->
                reviewTextMutationPayload(action.text)

            is AgentAction.PopulatePrimaryInput ->
                reviewTextMutationPayload(action.text)

            is AgentAction.WriteSessionNote ->
                when {
                    noteTooLong(action.note) ->
                        AgentToolInputReview(
                            behavior = "deny",
                            summary = "session_note_too_large",
                            detailLines = listOf("input_review=session_note_too_large", "input_length=${action.note.length}"),
                        )
                    containsSensitive(action.note) ->
                        AgentToolInputReview(
                            behavior = "ask",
                            summary = "session_note_sensitive_payload",
                            detailLines = listOf("input_review=session_note_sensitive_payload"),
                        )
                    else -> AgentToolInputReview()
                }

            is AgentAction.WebSearch ->
                PlatformWebPolicySupport.reviewQuery(action.query).toInputReview()

            is AgentAction.WebFetch ->
                PlatformWebPolicySupport.reviewUrl(action.url).toInputReview()

            is AgentAction.ReadConnectedAppCapabilities ->
                AgentToolInputReview()

            is AgentAction.ExecuteConnectedAppAction ->
                when {
                    noteTooLong(action.primary) || noteTooLong(action.secondary) ->
                        AgentToolInputReview(
                            behavior = "deny",
                            summary = "connected_payload_too_large",
                            detailLines = listOf("input_review=connected_payload_too_large"),
                        )
                    containsSensitive(listOf(action.primary, action.secondary, action.note).joinToString(" ")) ->
                        AgentToolInputReview(
                            behavior = "ask",
                            summary = "connected_payload_sensitive",
                            detailLines = listOf("input_review=connected_payload_sensitive", "app_id=${action.appId}", "operation=${action.operation}"),
                        )
                    else -> AgentToolInputReview()
                }

            is AgentAction.WriteTodoBoard ->
                when {
                    noteTooLong(action.content) ->
                        AgentToolInputReview(
                            behavior = "deny",
                            summary = "todo_payload_too_large",
                            detailLines = listOf("input_review=todo_payload_too_large", "input_length=${action.content.length}"),
                        )
                    containsSensitive(action.content) ->
                        AgentToolInputReview(
                            behavior = "ask",
                            summary = "todo_payload_sensitive",
                            detailLines = listOf("input_review=todo_payload_sensitive"),
                        )
                    else -> AgentToolInputReview()
                }

            is AgentAction.DelegateLocalAgent ->
                when {
                    containsSensitive(action.task) ->
                        AgentToolInputReview(
                            behavior = "ask",
                            summary = "delegate_sensitive_payload",
                            detailLines = listOf("input_review=delegate_sensitive_payload"),
                        )
                    noteTooLong(action.task) ->
                        AgentToolInputReview(
                            behavior = "deny",
                            summary = "delegate_payload_too_large",
                            detailLines = listOf("input_review=delegate_payload_too_large", "input_length=${action.task.length}"),
                        )
                    action.role.isNotBlank() && !PlatformWorkerRoleCatalog.exists(action.role) ->
                        AgentToolInputReview(
                            behavior = "deny",
                            summary = "delegate_role_unknown",
                            detailLines = listOf("input_review=delegate_role_unknown", "role=${action.role}"),
                        )
                    else -> AgentToolInputReview()
                }

            is AgentAction.ReadWorkerMailbox ->
                if (action.target.isNotBlank() && containsSensitive(action.target)) {
                    AgentToolInputReview(
                        behavior = "ask",
                        summary = "worker_mailbox_target_sensitive",
                        detailLines = listOf("input_review=worker_mailbox_target_sensitive"),
                    )
                } else {
                    AgentToolInputReview()
                }

            is AgentAction.ReplyWorkerMessage ->
                when {
                    action.decision.equals("reject", ignoreCase = true) || action.decision.equals("deny", ignoreCase = true) ->
                        AgentToolInputReview(
                            behavior = "ask",
                            summary = "worker_reply_reject_confirmation",
                            detailLines = listOf("input_review=worker_reply_reject_confirmation"),
                        )
                    noteTooLong(action.note) ->
                        AgentToolInputReview(
                            behavior = "deny",
                            summary = "worker_reply_too_large",
                            detailLines = listOf("input_review=worker_reply_too_large", "input_length=${action.note.length}"),
                        )
                    else -> AgentToolInputReview()
                }

            is AgentAction.AcknowledgeWorkerMessage -> AgentToolInputReview()

            AgentAction.ReadSessionMemoryFile -> AgentToolInputReview()

            AgentAction.ReadWorkerRoles -> AgentToolInputReview()

            is AgentAction.ReadWorkerStatus ->
                if (action.target.isNotBlank() && containsSensitive(action.target)) {
                    AgentToolInputReview(
                        behavior = "ask",
                        summary = "worker_status_target_sensitive",
                        detailLines = listOf("input_review=worker_status_target_sensitive"),
                    )
                } else {
                    AgentToolInputReview()
                }

            is AgentAction.MergeWorkerResult ->
                if (noteTooLong(action.note)) {
                    AgentToolInputReview(
                        behavior = "deny",
                        summary = "merge_worker_note_too_large",
                        detailLines = listOf("input_review=merge_worker_note_too_large", "input_length=${action.note.length}"),
                    )
                } else {
                    AgentToolInputReview()
                }

            is AgentAction.ReadNotifications -> AgentToolInputReview()

            is AgentAction.ReplyNotification ->
                when {
                    noteTooLong(action.replyText) ->
                        AgentToolInputReview(
                            behavior = "deny",
                            summary = "notification_reply_too_large",
                            detailLines = listOf("input_review=notification_reply_too_large"),
                        )
                    containsSensitive(action.replyText) ->
                        AgentToolInputReview(
                            behavior = "ask",
                            summary = "notification_reply_sensitive",
                            detailLines = listOf("input_review=notification_reply_sensitive"),
                        )
                    else -> AgentToolInputReview()
                }

            is AgentAction.MediaControl -> AgentToolInputReview()

            is AgentAction.AdjustVolume -> AgentToolInputReview()

            is AgentAction.OpenDevicePanel -> AgentToolInputReview()

            is AgentAction.CaptureScreenshot -> AgentToolInputReview()

            is AgentAction.CapturePhoto -> AgentToolInputReview()

            is AgentAction.CreateTimer ->
                if (containsSensitive(action.message)) {
                    AgentToolInputReview(
                        behavior = "ask",
                        summary = "timer_message_sensitive",
                        detailLines = listOf("input_review=timer_message_sensitive"),
                    )
                } else {
                    AgentToolInputReview()
                }

            AgentAction.OpenStopwatch -> AgentToolInputReview()

            is AgentAction.DialNumber -> AgentToolInputReview()

            is AgentAction.DraftSms ->
                when {
                    containsSensitive(action.body) ->
                        AgentToolInputReview(
                            behavior = "ask",
                            summary = "sms_body_sensitive",
                            detailLines = listOf("input_review=sms_body_sensitive"),
                        )
                    else -> AgentToolInputReview()
                }

            is AgentAction.RecallMemory ->
                if (containsSensitive(action.query)) {
                    AgentToolInputReview(
                        behavior = "ask",
                        summary = "memory_query_sensitive",
                        detailLines = listOf("input_review=memory_query_sensitive"),
                    )
                } else {
                    AgentToolInputReview()
                }

            is AgentAction.SearchArtifacts ->
                if (containsSensitive(action.query)) {
                    AgentToolInputReview(
                        behavior = "ask",
                        summary = "artifact_query_sensitive",
                        detailLines = listOf("input_review=artifact_query_sensitive"),
                    )
                } else {
                    AgentToolInputReview()
                }

            is AgentAction.ReadSessionHistory ->
                if (containsSensitive(action.query)) {
                    AgentToolInputReview(
                        behavior = "ask",
                        summary = "history_query_sensitive",
                        detailLines = listOf("input_review=history_query_sensitive"),
                    )
                } else {
                    AgentToolInputReview()
                }

            is AgentAction.PasteClipboard ->
                observation.pageState.takeIf { it.contains("login", ignoreCase = true) || it.contains("密码", ignoreCase = true) }?.let {
                    AgentToolInputReview(
                        behavior = "ask",
                        summary = "paste_into_sensitive_context",
                        detailLines = listOf("input_review=paste_into_sensitive_context", "page=${observation.pageState}"),
                    )
                } ?: AgentToolInputReview()

            else -> AgentToolInputReview()
        }
    }

    private fun reviewTextMutationPayload(
        text: String,
    ): AgentToolInputReview {
        val sensitiveKeywords = listOf("密码", "token", "验证码", "校验码", "私钥", "secret")
        val sensitive = sensitiveKeywords.any { keyword -> text.contains(keyword, ignoreCase = true) }
        return when {
            text.length > 600 ->
                AgentToolInputReview(
                    behavior = "deny",
                    summary = "mutation_payload_too_large",
                    detailLines = listOf("input_review=mutation_payload_too_large", "input_length=${text.length}"),
                )
            sensitive ->
                AgentToolInputReview(
                    behavior = "ask",
                    summary = "mutation_sensitive_payload",
                    detailLines = listOf("input_review=mutation_sensitive_payload"),
                )
            else -> AgentToolInputReview()
        }
    }

    private fun PlatformWebPolicyDecision.toInputReview(): AgentToolInputReview =
        when (behavior) {
            "deny" ->
                AgentToolInputReview(
                    behavior = "deny",
                    summary = summary.ifBlank { "web_policy_denied" },
                    detailLines = detailLines,
                )
            "ask" ->
                AgentToolInputReview(
                    behavior = "ask",
                    summary = summary.ifBlank { "web_policy_review" },
                    detailLines = detailLines,
                )
            else -> AgentToolInputReview(detailLines = detailLines)
        }
}

internal object AgentToolRuntimeObjectRegistry {
    private val runtimeObjects: Map<String, AgentToolRuntimeObject> by lazy {
        AgentToolCatalog.descriptors().associate { descriptor ->
            descriptor.name to
                DescriptorBackedToolRuntimeObject(
                    descriptor = descriptor,
                    executeHandler = ActionExecutor.runtimeHandlerFor(descriptor.name),
                )
        }
    }

    internal fun resolve(
        action: AgentAction,
    ): AgentToolRuntimeObject = runtimeObjects[action.toolName] ?: runtimeObjects[AgentAction.Wait.toolName]!!

    internal fun catalog(): List<AgentToolRuntimeObject> =
        runtimeObjects.values.toList()
}
