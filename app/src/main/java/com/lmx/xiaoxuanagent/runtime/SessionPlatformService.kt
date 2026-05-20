package com.lmx.xiaoxuanagent.runtime

data class SessionPlatformCommand(
    val capability: SessionCapabilityKey,
    val sessionId: String = "",
    val task: String = "",
    val query: String = "",
    val userCorrection: String = "",
    val payload: Map<String, String> = emptyMap(),
)

data class SessionPlatformCommandResult(
    val success: Boolean,
    val summary: String,
    val sessionId: String = "",
    val workerId: String = "",
    val lines: List<String> = emptyList(),
)

object SessionPlatformCommandPalette {
    fun parse(
        raw: String,
    ): SessionPlatformCommand? {
        return resolve(raw).command
    }

    internal fun resolve(
        raw: String,
    ): SessionPlatformCommandResolution {
        SessionPlatformNaturalIntentInterpreter.resolve(raw)?.let { return it }
        val parsed =
            SessionPlatformCommandParser.parse(raw)
                ?: return SessionPlatformCommandResolution(
                    summary = "未识别输入，请输入 `/命令` 或自然语言任务。",
                    lines = SessionPlatformCommandRegistry.recommendedUsages(limit = 6),
                )
        return SessionPlatformCommandRegistry.resolve(parsed)
    }
}

object SessionPlatformService {
    suspend fun execute(
        command: SessionPlatformCommand,
        entrySource: String = "platform_service",
    ): SessionPlatformCommandResult {
        PlatformTraceStore.record(
            category = "platform_service_execute",
            summary = command.capability.name.lowercase(),
            sessionId = command.sessionId,
            metadata = command.payload + mapOf("task" to command.task, "query" to command.query),
        )
        val result =
            SessionCapabilityBus.dispatch(
                SessionCapabilityRequest(
                    key = command.capability,
                    sessionId = command.sessionId,
                    task = command.task,
                    query = command.query,
                    entrySource = entrySource,
                    userCorrection = command.userCorrection,
                    payload = command.payload,
                ),
            )
        return SessionPlatformCommandResult(
            success = result.success,
            summary = result.summary,
            sessionId = result.sessionId,
            workerId = result.workerId,
            lines = result.payloadLines,
        )
    }

    suspend fun executeCommandInput(
        raw: String,
        entrySource: String = "command_palette",
    ): SessionPlatformCommandResult {
        val receipt = SessionCommandCenterStore.createReceipt(rawInput = raw, entrySource = entrySource)
        val resolution = SessionPlatformCommandPalette.resolve(raw)
        val command =
            resolution.command
                ?: return SessionPlatformCommandCenterStoreFail(
                    receiptId = receipt.receiptId,
                    summary = resolution.summary,
                    lines = resolution.lines,
                )
        SessionCommandCenterStore.markParsed(
            receiptId = receipt.receiptId,
            resolvedCommand = renderResolvedCommand(command),
            capability = command.capability.name,
            summary = "命令已解析，正在执行 ${command.capability.name.lowercase()}。",
        )
        recordCommandExplanation(
            sessionId = command.sessionId,
            phase = "command_parse",
            title = "Command parsed",
            summary = "命令已解析为 ${command.capability.name.lowercase()}。",
            detailLines = listOf("entry_source=$entrySource", "receipt=${receipt.receiptId}", "raw=${raw.take(120)}"),
        )
        return runCatching {
            val result = execute(command, entrySource = entrySource)
            val resolvedSessionId = result.sessionId.ifBlank { command.sessionId }
            if (result.success) {
                SessionCommandCenterStore.markSucceeded(
                    receiptId = receipt.receiptId,
                    summary = result.summary,
                    sessionId = result.sessionId,
                    workerId = result.workerId,
                    lines = result.lines,
                )
                recordCommandExplanation(
                    sessionId = resolvedSessionId,
                    phase = "command_execute",
                    title = "Command succeeded",
                    summary = result.summary,
                    detailLines = listOf("receipt=${receipt.receiptId}", "capability=${command.capability.name.lowercase()}") + result.lines.take(3),
                )
            } else {
                SessionCommandCenterStore.markFailed(
                    receiptId = receipt.receiptId,
                    summary = result.summary,
                    lines = result.lines,
                )
                recordCommandExplanation(
                    sessionId = resolvedSessionId,
                    phase = "command_execute",
                    title = "Command failed",
                    summary = result.summary,
                    detailLines = listOf("receipt=${receipt.receiptId}", "capability=${command.capability.name.lowercase()}") + result.lines.take(3),
                )
            }
            val leadingLines =
                buildList {
                    add("command_id=${receipt.receiptId}")
                    result.sessionId.takeIf { it.isNotBlank() }?.let { add("session=$it") }
                    result.workerId.takeIf { it.isNotBlank() }?.let { add("worker=$it") }
                }
            result.copy(
                lines = leadingLines + result.lines,
            )
        }.getOrElse { error ->
            val summary = error.message.orEmpty().ifBlank { error.javaClass.simpleName }
            SessionCommandCenterStore.markFailed(
                receiptId = receipt.receiptId,
                summary = "命令执行异常：$summary",
                lines = listOf(error.javaClass.simpleName),
            )
            PlatformTraceStore.record(
                category = "platform_service_command_failure",
                summary = "${entrySource.lowercase()} | ${command.capability.name.lowercase()} | ${error.javaClass.simpleName}",
                sessionId = command.sessionId,
                metadata = mapOf("command_id" to receipt.receiptId),
            )
            recordCommandExplanation(
                sessionId = command.sessionId,
                phase = "command_execute",
                title = "Command exception",
                summary = "命令执行异常：$summary",
                detailLines = listOf("receipt=${receipt.receiptId}", "capability=${command.capability.name.lowercase()}", error.javaClass.simpleName),
            )
            SessionPlatformCommandResult(
                success = false,
                summary = "命令执行异常：$summary",
                sessionId = command.sessionId,
                lines = listOf("command_id=${receipt.receiptId}", error.javaClass.simpleName),
            )
        }
    }

    suspend fun executeSlashCommand(
        raw: String,
        entrySource: String = "command_palette",
    ): SessionPlatformCommandResult = executeCommandInput(raw = raw, entrySource = entrySource)

    private fun SessionPlatformCommandCenterStoreFail(
        receiptId: String,
        summary: String,
        lines: List<String>,
    ): SessionPlatformCommandResult {
        SessionCommandCenterStore.markFailed(
            receiptId = receiptId,
            summary = summary,
            lines = lines,
        )
        return SessionPlatformCommandResult(
            success = false,
            summary = summary,
            lines = listOf("command_id=$receiptId") + lines,
        )
    }

    private fun renderResolvedCommand(
        command: SessionPlatformCommand,
    ): String =
        buildString {
            append(command.capability.name.lowercase())
            command.sessionId.takeIf { it.isNotBlank() }?.let { append(" session=").append(it) }
            command.task.takeIf { it.isNotBlank() }?.let { append(" task=").append(it.take(48)) }
            command.query.takeIf { it.isNotBlank() }?.let { append(" query=").append(it.take(48)) }
        }

    private fun recordCommandExplanation(
        sessionId: String,
        phase: String,
        title: String,
        summary: String,
        detailLines: List<String> = emptyList(),
    ) {
        SessionExplanationStore.record(
            sessionId = sessionId,
            phase = phase,
            title = title,
            summary = summary,
            actionLabel = title,
            detailLines = detailLines,
        )
    }
}
