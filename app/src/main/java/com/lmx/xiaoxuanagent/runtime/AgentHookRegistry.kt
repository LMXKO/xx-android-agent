package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.AgentExecutionResult

data class AgentHookPayload(
    val sessionId: String,
    val task: String,
    val turn: Int,
    val observationSignature: String,
    val pageState: String,
    val actionLabel: String = "",
    val result: String = "",
    val metadata: Map<String, String> = emptyMap(),
)

data class AgentHookResult(
    val messages: List<String> = emptyList(),
    val blockReason: String = "",
    val annotations: Map<String, String> = emptyMap(),
    val overrideDecision: AgentDecision? = null,
    val injectedExecutionResult: AgentExecutionResult? = null,
) {
    val shouldBlock: Boolean
        get() = blockReason.isNotBlank()
}

data class AgentHookDispatchResult(
    val messages: List<String> = emptyList(),
    val blockReason: String = "",
    val annotations: Map<String, String> = emptyMap(),
    val overrideDecision: AgentDecision? = null,
    val injectedExecutionResult: AgentExecutionResult? = null,
) {
    val shouldBlock: Boolean
        get() = blockReason.isNotBlank()
}

enum class AgentHookStage {
    BEFORE_PLAN,
    AFTER_PLAN,
    POST_SAMPLING,
    PRE_COMPACT,
    POST_COMPACT,
    BEFORE_ACTION,
    AFTER_ACTION,
    STOP_CLEANUP,
    ON_FAILURE,
    ON_SUSPEND,
    ON_RESUME,
    ON_ERROR,
}

fun interface AgentHook {
    fun onStage(
        stage: AgentHookStage,
        payload: AgentHookPayload,
    ): AgentHookResult
}

object AgentHookRegistry {
    private val lock = Any()
    private val hooks = mutableListOf<AgentHook>()

    fun register(hook: AgentHook) {
        synchronized(lock) {
            hooks += hook
        }
    }

    fun unregister(hook: AgentHook) {
        synchronized(lock) {
            hooks -= hook
        }
    }

    fun dispatch(
        stage: AgentHookStage,
        payload: AgentHookPayload,
    ): AgentHookDispatchResult {
        val snapshot =
            synchronized(lock) {
                hooks.toList()
            }
        if (snapshot.isEmpty()) {
            return AgentHookDispatchResult()
        }
        val messages = mutableListOf<String>()
        val annotations = linkedMapOf<String, String>()
        var blockReason = ""
        var overrideDecision: AgentDecision? = null
        var injectedExecutionResult: AgentExecutionResult? = null
        snapshot.forEach { hook ->
            val result =
                runCatching { hook.onStage(stage, payload) }
                    .getOrElse { error ->
                        AgentHookResult(
                            messages =
                                listOf(
                                    "hook_error stage=${stage.name.lowercase()} " +
                                        "type=${error.javaClass.simpleName} msg=${error.message.orEmpty()}",
                                ),
                        )
                    }
            messages += result.messages
            annotations += result.annotations
            if (blockReason.isBlank() && result.blockReason.isNotBlank()) {
                blockReason = result.blockReason
            }
            if (overrideDecision == null && result.overrideDecision != null) {
                overrideDecision = result.overrideDecision
            } else if (overrideDecision != null && result.overrideDecision != null) {
                messages += "hook_conflict stage=${stage.name.lowercase()} override_decision_ignored=${result.overrideDecision.action.label}"
            }
            if (injectedExecutionResult == null && result.injectedExecutionResult != null) {
                injectedExecutionResult = result.injectedExecutionResult
            } else if (injectedExecutionResult != null && result.injectedExecutionResult != null) {
                messages += "hook_conflict stage=${stage.name.lowercase()} injected_result_ignored"
            }
        }
        return AgentHookDispatchResult(
            messages = messages,
            blockReason = blockReason,
            annotations = annotations,
            overrideDecision = overrideDecision,
            injectedExecutionResult = injectedExecutionResult,
        )
    }
}
