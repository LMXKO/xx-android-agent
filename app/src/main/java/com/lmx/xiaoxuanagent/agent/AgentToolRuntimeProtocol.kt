package com.lmx.xiaoxuanagent.agent

data class AgentToolRuntimeEnvelope(
    val descriptor: AgentToolDescriptor? = null,
    val toolName: String = "",
    val readOnly: Boolean = false,
    val valid: Boolean = true,
    val prompt: String = "",
    val summary: String = "",
    val detailLines: List<String> = emptyList(),
    val invalidReason: String = "",
)

object AgentToolRuntimeProtocol {
    private val writeFamilies =
        setOf(
            "write_input",
            "clipboard_write",
            "confirm_submit",
            "confirm_share",
            "confirm_create_alarm",
            "confirm_calendar_write",
            "session_notebook_write",
            "todo_write",
            "delegate_worker",
            "worker_mailbox_reply",
            "worker_mailbox_ack",
            "worker_result_merge",
            "finish_task",
            "fail_task",
        )

    fun evaluate(
        action: AgentAction,
        observation: ScreenObservation,
    ): AgentToolRuntimeEnvelope {
        val descriptor = AgentToolCatalog.find(action.toolName)
        val validationError = validate(action)
        val readOnly = isReadOnly(descriptor)
        val prompt =
            buildString {
                append(descriptor?.progressLabel?.ifBlank { descriptor.title }.orEmpty().ifBlank { action.toolName })
                append(" | ")
                append(
                    when {
                        validationError.isNotBlank() -> "input_invalid"
                        readOnly -> "read_only"
                        else -> "state_mutation"
                    },
                )
                descriptor?.inputContract?.takeIf { it.isNotBlank() }?.let { append(" | input=").append(it) }
                descriptor?.takeIf { it.requiresUserInteraction }?.let { append(" | user_interaction") }
            }
        val summary =
            buildString {
                append("tool=").append(action.toolName)
                append(" | perm=").append(descriptor?.permissionFamily.orEmpty().ifBlank { "general" })
                append(" | interrupt=").append(descriptor?.interruptBehavior.orEmpty().ifBlank { "cancel" })
                append(" | read_only=").append(readOnly)
                append(" | concurrent=").append(descriptor?.concurrencySafe ?: false)
                append(" | defer=").append(descriptor?.shouldDefer ?: false)
                append(" | always_load=").append(descriptor?.alwaysLoad ?: false)
                append(" | page=").append(observation.pageState.ifBlank { "-" })
                if (validationError.isNotBlank()) {
                    append(" | invalid")
                }
            }
        val detailLines =
            buildList {
                add("package=${observation.packageName.ifBlank { "-" }}")
                add("page=${observation.pageState.ifBlank { "-" }}")
                descriptor?.permissionFamily?.takeIf { it.isNotBlank() }?.let { add("permission_family=$it") }
                descriptor?.interruptBehavior?.takeIf { it.isNotBlank() }?.let { add("interrupt_behavior=$it") }
                descriptor?.inputContract?.takeIf { it.isNotBlank() }?.let { add("input_contract=$it") }
                descriptor?.resultContract?.takeIf { it.isNotBlank() }?.let { add("result_contract=$it") }
                descriptor?.fallbackTools?.takeIf { it.isNotEmpty() }?.let { add("fallback=${it.joinToString(",")}") }
                add("concurrency_safe=${descriptor?.concurrencySafe ?: false}")
                add("requires_user_interaction=${descriptor?.requiresUserInteraction ?: false}")
                add("should_defer=${descriptor?.shouldDefer ?: false}")
                add("always_load=${descriptor?.alwaysLoad ?: false}")
                add("read_only=$readOnly")
                validationError.takeIf { it.isNotBlank() }?.let { add("validation_error=$it") }
            }
        return AgentToolRuntimeEnvelope(
            descriptor = descriptor,
            toolName = action.toolName,
            readOnly = readOnly,
            valid = validationError.isBlank(),
            prompt = prompt.take(180),
            summary = summary.take(180),
            detailLines = detailLines.take(8),
            invalidReason = validationError.take(160),
        )
    }

    private fun isReadOnly(
        descriptor: AgentToolDescriptor?,
    ): Boolean {
        if (descriptor == null) return false
        if (descriptor.irreversible) return false
        return descriptor.permissionFamily !in writeFamilies
    }

    private fun validate(
        action: AgentAction,
    ): String =
        when (action) {
            is AgentAction.LaunchApp -> requireNotBlank(action.packageName, "package_name")
            is AgentAction.Click -> requireNotBlank(action.elementId, "element_id")
            is AgentAction.SetText ->
                requireSequence(
                    requireNotBlank(action.elementId, "element_id"),
                    requireNotBlank(action.text, "text"),
                )
            is AgentAction.Scroll ->
                requireDirection(action.direction)
            is AgentAction.LongClick -> requireNotBlank(action.elementId, "element_id")
            is AgentAction.CopyText -> requireNotBlank(action.elementId, "element_id")
            is AgentAction.PasteClipboard -> requireNotBlank(action.elementId, "element_id")
            is AgentAction.ReadSessionHistory -> ""
            is AgentAction.SearchArtifacts -> requireNotBlank(action.query, "query")
            is AgentAction.RecallMemory -> requireNotBlank(action.query, "query")
            AgentAction.ReadSessionNotebook -> ""
            is AgentAction.WriteSessionNote -> requireNotBlank(action.note, "note")
            is AgentAction.SearchTools -> requireNotBlank(action.query, "query")
            is AgentAction.WebSearch -> requireNotBlank(action.query, "query")
            is AgentAction.WebFetch -> requireNotBlank(action.url, "url")
            AgentAction.ReadTodoBoard -> ""
            is AgentAction.WriteTodoBoard -> requireNotBlank(action.content, "content")
            is AgentAction.DelegateLocalAgent -> requireNotBlank(action.task, "task")
            is AgentAction.ReadWorkerMailbox -> ""
            is AgentAction.ReplyWorkerMessage -> requireNotBlank(action.messageId, "message_id")
            is AgentAction.AcknowledgeWorkerMessage -> requireNotBlank(action.messageId, "message_id")
            AgentAction.ReadSessionMemoryFile -> ""
            is AgentAction.ReadWorkerStatus -> ""
            is AgentAction.MergeWorkerResult -> requireNotBlank(action.messageId, "message_id")
            is AgentAction.OpenSettings -> ""
            is AgentAction.ShareText -> requireNotBlank(action.text, "text")
            is AgentAction.CreateAlarm -> requireNotBlank(action.timeLabel, "time_label")
            is AgentAction.InsertCalendarEvent -> requireNotBlank(action.title, "title")
            is AgentAction.DialNumber ->
                if (action.number.isBlank() && action.contactName.isBlank()) {
                    "number 与 contact_name 至少提供一项。"
                } else {
                    ""
                }
            is AgentAction.DraftSms -> requireNotBlank(action.recipient, "recipient")
            is AgentAction.LookupContact -> requireNotBlank(action.contactName, "contact_name")
            is AgentAction.ReadCallLog -> ""
            is AgentAction.ReadCurrentLocation -> ""
            is AgentAction.PopulatePrimaryInput -> requireNotBlank(action.text, "text")
            is AgentAction.SubmitPrimaryAction -> ""
            is AgentAction.DismissInterrupt -> ""
            is AgentAction.OpenBestCandidate ->
                if (action.targetText.isBlank() && action.roleHint.isBlank()) {
                    "target_text 与 role_hint 至少提供一项。"
                } else {
                    ""
                }
            is AgentAction.ScrollForMore -> requireDirection(action.direction)
            is AgentAction.ReturnToTargetApp -> requireNotBlank(action.packageName, "package_name")
            is AgentAction.NavigateBack -> ""
            is AgentAction.Finish -> requireNotBlank(action.summary, "summary")
            is AgentAction.Fail -> requireNotBlank(action.reason, "reason")
            else -> ""
        }

    private fun requireSequence(
        vararg errors: String,
    ): String = errors.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun requireNotBlank(
        value: String,
        name: String,
    ): String = if (value.isBlank()) "$name 不能为空。" else ""

    private fun requireDirection(
        value: String,
    ): String {
        val normalized = value.trim().lowercase()
        if (normalized.isBlank()) return ""
        return if (normalized in setOf("up", "down", "left", "right")) {
            ""
        } else {
            "direction 仅支持 up/down/left/right。"
        }
    }
}
