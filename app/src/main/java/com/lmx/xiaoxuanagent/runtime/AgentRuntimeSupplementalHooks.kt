package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskStore
import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskType

internal object AgentRuntimeSupplementalHooks {
    val hooks: List<AgentHook> =
        listOf(
            PostSamplingMemoryHook,
            TranscriptCompactionHook,
            MemoryDocumentHook,
            MemoryFileHook,
            WorkerTodoHook,
            WorkerMailboxHook,
            WebResearchCaptureHook,
            TerminalCleanupHook,
        )

    private object PostSamplingMemoryHook : AgentHook {
        override fun onStage(
            stage: AgentHookStage,
            payload: AgentHookPayload,
        ): AgentHookResult {
            if (!AgentHookConfigStore.isEnabled("post_sampling_memory")) return AgentHookResult()
            if (stage != AgentHookStage.POST_SAMPLING) return AgentHookResult()
            return if (SessionMemoryPostSamplingMaintainer.maintainAfterPlan(payload)) {
                AgentHookResult(messages = listOf("post_sampling_memory_hook queued notebook maintenance") + AgentHookConfigStore.stageNotes(stage))
            } else {
                AgentHookResult()
            }
        }
    }

    private object TranscriptCompactionHook : AgentHook {
        override fun onStage(
            stage: AgentHookStage,
            payload: AgentHookPayload,
        ): AgentHookResult {
            if (!AgentHookConfigStore.isEnabled("transcript_compaction")) return AgentHookResult()
            if (stage != AgentHookStage.POST_COMPACT) return AgentHookResult()
            val snapshot = SessionTranscriptCompactionStore.readSnapshot(payload.sessionId) ?: return AgentHookResult()
            if (snapshot.mode.isBlank() || snapshot.compactedTurnCount <= 0) return AgentHookResult()
            val dedupeKey = "transcript_compaction:${payload.sessionId}:${snapshot.turn}"
            AssistantProactiveTaskStore.schedule(
                type = AssistantProactiveTaskType.MEMORY_NUDGE,
                capability = SessionCapabilityKey.READ_SESSION_HISTORY,
                dedupeKey = dedupeKey,
                title = "上下文已压缩",
                summary = snapshot.boundaryDigest.ifBlank { "planner 已切到 compact transcript window。" },
                task = payload.task,
                sessionId = payload.sessionId,
                source = "hook:transcript_compaction",
                priority = 42,
                recommendedCommand = "/timeline --session-id ${payload.sessionId}",
            )
            return AgentHookResult(messages = listOf("transcript_compaction_hook surfaced compact boundary") + AgentHookConfigStore.stageNotes(stage))
        }
    }

    private object MemoryDocumentHook : AgentHook {
        override fun onStage(
            stage: AgentHookStage,
            payload: AgentHookPayload,
        ): AgentHookResult {
            if (!AgentHookConfigStore.isEnabled("memory_document")) return AgentHookResult()
            if (payload.sessionId.isBlank() || payload.task.isBlank()) return AgentHookResult()
            if (stage !in setOf(AgentHookStage.POST_SAMPLING, AgentHookStage.POST_COMPACT)) return AgentHookResult()
            val snapshot =
                SessionMemoryDocumentStore.refresh(
                    sessionId = payload.sessionId,
                    task = payload.task,
                    profileId = payload.metadata["profile_id"].orEmpty(),
                    trigger = "${stage.name.lowercase()}:${payload.actionLabel.ifBlank { payload.metadata["compact_mode"].orEmpty() }}",
                ) ?: return AgentHookResult()
            return AgentHookResult(
                messages = listOf("memory_document_hook refreshed ${snapshot.headline.take(48)}"),
                annotations = mapOf("memory_document" to snapshot.markdownPath),
            )
        }
    }

    private object MemoryFileHook : AgentHook {
        override fun onStage(
            stage: AgentHookStage,
            payload: AgentHookPayload,
        ): AgentHookResult {
            if (!AgentHookConfigStore.isEnabled("memory_file")) return AgentHookResult()
            if (payload.sessionId.isBlank() || payload.task.isBlank()) return AgentHookResult()
            if (stage !in setOf(AgentHookStage.POST_SAMPLING, AgentHookStage.POST_COMPACT, AgentHookStage.STOP_CLEANUP)) {
                return AgentHookResult()
            }
            val session = SessionRuntime.State.runtimeState().session
            val snapshot =
                SessionMemoryFileMaintainer.refreshIfNeeded(
                    sessionId = payload.sessionId,
                    task = payload.task,
                    profileId = payload.metadata["profile_id"].orEmpty(),
                    turn = payload.turn,
                    trigger = "${stage.name.lowercase()}:${payload.metadata["final_status"].orEmpty().ifBlank { payload.actionLabel }}",
                    history = session.history,
                    force = stage == AgentHookStage.STOP_CLEANUP,
                ) ?: return AgentHookResult()
            return AgentHookResult(messages = listOf("memory_file_hook refreshed ${snapshot.headline.take(48)}") + AgentHookConfigStore.stageNotes(stage))
        }
    }

    private object WorkerTodoHook : AgentHook {
        override fun onStage(
            stage: AgentHookStage,
            payload: AgentHookPayload,
        ): AgentHookResult {
            if (!AgentHookConfigStore.isEnabled("worker_todo")) return AgentHookResult()
            if (stage != AgentHookStage.AFTER_ACTION || payload.sessionId.isBlank()) return AgentHookResult()
            if (!payload.actionLabel.startsWith("delegate_local_agent(")) return AgentHookResult()
            SessionTodoBoardStore.upsertItems(
                sessionId = payload.sessionId,
                rawItems = listOf("等待 worker 收口: ${payload.result.ifBlank { payload.actionLabel }.take(72)}"),
                mode = "append",
                source = "hook_worker",
            )
            return AgentHookResult(messages = listOf("worker_todo_hook added todo board checkpoint"))
        }
    }

    private object WorkerMailboxHook : AgentHook {
        override fun onStage(
            stage: AgentHookStage,
            payload: AgentHookPayload,
        ): AgentHookResult {
            if (!AgentHookConfigStore.isEnabled("worker_mailbox")) return AgentHookResult()
            if (stage != AgentHookStage.AFTER_ACTION || payload.sessionId.isBlank()) return AgentHookResult()
            if (!payload.actionLabel.startsWith("read_worker_mailbox(") &&
                !payload.actionLabel.startsWith("reply_worker_message(") &&
                !payload.actionLabel.startsWith("merge_worker_result(")
            ) {
                return AgentHookResult()
            }
            SessionMemoryNotebookStore.appendManualNote(
                sessionId = payload.sessionId,
                task = payload.task,
                profileId = payload.metadata["profile_id"].orEmpty(),
                note = "worker mailbox | ${payload.result.take(160)}",
                tag = "worker_mailbox",
            )
            return AgentHookResult(messages = listOf("worker_mailbox_hook captured mailbox note"))
        }
    }

    private object WebResearchCaptureHook : AgentHook {
        override fun onStage(
            stage: AgentHookStage,
            payload: AgentHookPayload,
        ): AgentHookResult {
            if (!AgentHookConfigStore.isEnabled("web_research_capture")) return AgentHookResult()
            if (stage != AgentHookStage.AFTER_ACTION || payload.sessionId.isBlank()) return AgentHookResult()
            if (!payload.actionLabel.startsWith("web_search(") && !payload.actionLabel.startsWith("web_fetch(")) {
                return AgentHookResult()
            }
            val session = SessionRuntime.State.runtimeState().session
            SessionMemoryNotebookStore.appendManualNote(
                sessionId = payload.sessionId,
                task = payload.task,
                profileId = session.profileId,
                note = "web research | ${payload.result.take(160)}",
                tag = "web_research",
            )
            return AgentHookResult(messages = listOf("web_research_hook captured notebook note"))
        }
    }

    private object TerminalCleanupHook : AgentHook {
        override fun onStage(
            stage: AgentHookStage,
            payload: AgentHookPayload,
        ): AgentHookResult {
            if (!AgentHookConfigStore.isEnabled("terminal_cleanup")) return AgentHookResult()
            if (stage != AgentHookStage.STOP_CLEANUP || payload.sessionId.isBlank()) return AgentHookResult()
            val webSummary =
                PlatformWebResearchTraceStore.readRecent(payload.sessionId, limit = 2)
                    .joinToString(" | ") { it.summary.ifBlank { it.query.ifBlank { it.url } }.take(72) }
            SessionMemoryNotebookStore.appendManualNote(
                sessionId = payload.sessionId,
                task = payload.task,
                profileId = payload.metadata["profile_id"].orEmpty(),
                note = buildString {
                    append("terminal_cleanup | status=").append(payload.metadata["final_status"].orEmpty())
                    payload.result.takeIf { it.isNotBlank() }?.let { append(" | result=").append(it.take(120)) }
                    webSummary.takeIf { it.isNotBlank() }?.let { append(" | web=").append(it.take(120)) }
                },
                tag = "terminal_cleanup",
            )
            return AgentHookResult(messages = listOf("terminal_cleanup_hook stored terminal continuity note") + AgentHookConfigStore.stageNotes(stage))
        }
    }
}
