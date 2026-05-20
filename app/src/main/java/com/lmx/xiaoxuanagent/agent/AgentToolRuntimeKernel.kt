package com.lmx.xiaoxuanagent.agent

data class AgentToolRuntimeInspection(
    val descriptor: AgentToolDescriptor? = null,
    val valid: Boolean = true,
    val invalidReason: String = "",
    val readOnly: Boolean = false,
    val permissionFamily: String = "",
    val interruptBehavior: String = "",
    val concurrencySafe: Boolean = false,
    val requiresUserInteraction: Boolean = false,
    val shouldDefer: Boolean = false,
    val alwaysLoad: Boolean = false,
    val progressLabel: String = "",
    val summary: String = "",
    val prompt: String = "",
    val detailLines: List<String> = emptyList(),
)

object AgentToolRuntimeKernel {
    fun inspect(
        action: AgentAction,
        observation: ScreenObservation,
    ): AgentToolRuntimeInspection {
        val contract = AgentToolRuntimeRegistry.resolve(action = action, observation = observation)
        val protocol = AgentToolRuntimeProtocol.evaluate(action = action, observation = observation)
        return AgentToolRuntimeInspection(
            descriptor = protocol.descriptor,
            valid = protocol.valid,
            invalidReason = protocol.invalidReason,
            readOnly = contract.readOnly,
            permissionFamily = contract.permissionFamily,
            interruptBehavior = contract.interruptBehavior,
            concurrencySafe = contract.concurrencySafe,
            requiresUserInteraction = contract.requiresUserInteraction,
            shouldDefer = contract.shouldDefer,
            alwaysLoad = contract.alwaysLoad,
            progressLabel = contract.progressLabel,
            summary = contract.protocolSummary,
            prompt = contract.protocolPrompt,
            detailLines =
                (
                    contract.detailLines +
                        listOf(
                            "concurrency_safe=${contract.concurrencySafe}",
                            "requires_user_interaction=${contract.requiresUserInteraction}",
                            "should_defer=${contract.shouldDefer}",
                            "always_load=${contract.alwaysLoad}",
                        )
                ).distinct().take(10),
        )
    }

    fun invalidResult(
        inspection: AgentToolRuntimeInspection,
    ): AgentExecutionResult =
        AgentExecutionResult(
            message = inspection.invalidReason.ifBlank { "工具运行时校验失败。" },
            keepRunning = true,
            shouldImmediateReplan = true,
            toolRuntimePrompt = inspection.prompt,
            toolRuntimeSummary = inspection.summary,
            toolRuntimeDetailLines = inspection.detailLines,
            toolRuntimeState = "rejected",
            toolRuntimeRejectedMessage = inspection.invalidReason.ifBlank { "工具运行时校验失败。" },
        )

    fun decorateResult(
        result: AgentExecutionResult,
        inspection: AgentToolRuntimeInspection,
    ): AgentExecutionResult =
        result.copy(
            toolRuntimePrompt = inspection.prompt,
            toolRuntimeSummary = inspection.summary,
            toolRuntimeDetailLines = (inspection.detailLines + result.toolRuntimeDetailLines).distinct().take(10),
            toolRuntimeState = result.toolRuntimeState.ifBlank { if (result.keepRunning) "running" else "completed" },
        )
}
