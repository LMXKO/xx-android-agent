package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskStore
import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskType

object AgentHookBootstrap {
    @Volatile
    private var registered = false

    fun registerDefaults() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            AgentHookRegistry.register(SessionMemoryHook)
            AgentHookRegistry.register(TaskCompletionFollowUpHook)
            AgentRuntimeSupplementalHooks.hooks.forEach(AgentHookRegistry::register)
            registered = true
        }
    }

    private object SessionMemoryHook : AgentHook {
        override fun onStage(
            stage: AgentHookStage,
            payload: AgentHookPayload,
        ): AgentHookResult {
            if (payload.sessionId.isBlank() || payload.task.isBlank()) return AgentHookResult()
            if (stage !in setOf(AgentHookStage.AFTER_ACTION, AgentHookStage.ON_FAILURE, AgentHookStage.ON_SUSPEND, AgentHookStage.STOP_CLEANUP)) {
                return AgentHookResult()
            }
            val shouldRefreshNotebook =
                stage != AgentHookStage.AFTER_ACTION ||
                    SessionMemoryPolicyStore.readSnapshot(payload.sessionId)?.notebookUpdateQueued == true
            if (!shouldRefreshNotebook) return AgentHookResult()
            SessionMemoryNotebookStore.refreshFromRuntime(
                sessionId = payload.sessionId,
                task = payload.task,
                profileId = payload.metadata["profile_id"].orEmpty(),
            )
            val dedupeKey = "memory_nudge:${payload.sessionId}"
            val alreadyVisible = hasEnabledFollowUp(dedupeKey)
            AssistantProactiveTaskStore.schedule(
                type = AssistantProactiveTaskType.MEMORY_NUDGE,
                capability = SessionCapabilityKey.READ_SESSION_MEMORY_NOTEBOOK,
                dedupeKey = dedupeKey,
                title = "会话记忆待整理",
                summary =
                    payload.result.takeIf { it.isNotBlank() }?.take(96)
                        ?: "session notebook 已排队更新，建议回看最近收口与边界摘要。",
                task = payload.task,
                sessionId = payload.sessionId,
                source = "hook:${stage.name.lowercase()}",
                priority = 45,
                recommendedCommand = "/notebook --session-id ${payload.sessionId}",
                metadata =
                    buildMap {
                        put("session_id", payload.sessionId)
                        put("hook_stage", stage.name.lowercase())
                        payload.metadata["profile_id"]?.takeIf { it.isNotBlank() }?.let { put("profile_id", it) }
                    },
            )
            return if (alreadyVisible) {
                AgentHookResult()
            } else {
                AgentHookResult(messages = listOf("session_memory_hook queued notebook follow-up"))
            }
        }
    }

    private object TaskCompletionFollowUpHook : AgentHook {
        override fun onStage(
            stage: AgentHookStage,
            payload: AgentHookPayload,
        ): AgentHookResult {
            if (stage != AgentHookStage.AFTER_ACTION) return AgentHookResult()
            if (payload.sessionId.isBlank() || payload.task.isBlank()) return AgentHookResult()
            if (payload.metadata["keep_running"] != "false") return AgentHookResult()
            val dedupeKey = "task_completion_follow_up:${payload.sessionId}"
            val alreadyVisible = hasEnabledFollowUp(dedupeKey)
            AssistantProactiveTaskStore.schedule(
                type = AssistantProactiveTaskType.MEMORY_NUDGE,
                capability = SessionCapabilityKey.READ_SESSION_HISTORY,
                dedupeKey = dedupeKey,
                title = "任务完成后回看",
                summary =
                    buildString {
                        append(payload.result.take(96).ifBlank { "任务已收口，建议回看结论与后续事项。" })
                        payload.metadata["recovery_category"]?.takeIf { it.isNotBlank() }?.let {
                            append(" | recovery=").append(it)
                        }
                    },
                task = payload.task,
                sessionId = payload.sessionId,
                source = "hook:after_action_completion",
                priority = 50,
                recommendedCommand = "/history --session-id ${payload.sessionId}",
                metadata =
                    buildMap {
                        put("session_id", payload.sessionId)
                        put("result", payload.result.take(120))
                        payload.metadata["profile_id"]?.takeIf { it.isNotBlank() }?.let { put("profile_id", it) }
                    },
            )
            return if (alreadyVisible) {
                AgentHookResult()
            } else {
                AgentHookResult(messages = listOf("task_completion_hook scheduled follow-up"))
            }
        }
    }

    private fun hasEnabledFollowUp(
        dedupeKey: String,
    ): Boolean =
        AssistantProactiveTaskStore.readAll(limit = 120).any { task ->
            task.enabled && task.dedupeKey == dedupeKey
        }
}
