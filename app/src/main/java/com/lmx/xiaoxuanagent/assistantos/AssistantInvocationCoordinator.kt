package com.lmx.xiaoxuanagent.assistantos

import android.content.Intent

data class AssistantInvocationRequest(
    val entryMode: String = "",
    val source: String = "",
    val taskSeed: String = "",
    val autoRun: Boolean = false,
    val shouldLaunchVoiceEntry: Boolean = false,
) {
    val active: Boolean
        get() = entryMode.isNotBlank()
}

object AssistantInvocationCoordinator {
    const val ENTRY_MODE_ASSIST = "assist"
    const val ENTRY_MODE_SCREEN_AUTOMATION = "screen_automation"

    fun resolve(
        intent: Intent?,
    ): AssistantInvocationRequest {
        if (intent == null) return AssistantInvocationRequest()
        val payload = AssistantShellIntentRouter.readPayload(intent)
        val explicitEntryMode = payload.entryMode
        val action = intent.action.orEmpty()
        val entryMode =
            when {
                explicitEntryMode.isNotBlank() -> explicitEntryMode
                action == Intent.ACTION_ASSIST -> ENTRY_MODE_ASSIST
                action == Intent.ACTION_VOICE_COMMAND -> AssistantShellIntentRouter.ENTRY_MODE_VOICE
                else -> ""
            }
        if (entryMode.isBlank()) return AssistantInvocationRequest()
        val source =
            payload.actionSource.ifBlank {
                when (entryMode) {
                    ENTRY_MODE_ASSIST -> "system_assist"
                    ENTRY_MODE_SCREEN_AUTOMATION -> "screen_automation"
                    AssistantShellIntentRouter.ENTRY_MODE_VOICE -> "voice"
                    else -> "assistant_invocation"
                }
            }
        val taskSeed = payload.taskSeed.ifBlank { intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim() }
        val autoRun = payload.autoRun || intent.getBooleanExtra(AssistantShellIntentRouter.EXTRA_ASSISTANT_AUTO_RUN, false)
        return AssistantInvocationRequest(
            entryMode = entryMode,
            source = source,
            taskSeed = taskSeed,
            autoRun = autoRun,
            shouldLaunchVoiceEntry = entryMode in setOf(AssistantShellIntentRouter.ENTRY_MODE_VOICE, ENTRY_MODE_ASSIST),
        )
    }

    fun consume(
        intent: Intent?,
    ) {
        if (intent == null) return
        AssistantShellIntentRouter.consumeEntryMode(intent)
        intent.removeExtra(AssistantShellIntentRouter.EXTRA_ASSISTANT_TASK_SEED)
        intent.removeExtra(AssistantShellIntentRouter.EXTRA_ASSISTANT_AUTO_RUN)
        if (intent.action == Intent.ACTION_ASSIST) {
            intent.action = Intent.ACTION_VIEW
        }
    }

    fun entrySource(
        request: AssistantInvocationRequest,
    ): String =
        when (request.entryMode) {
            ENTRY_MODE_ASSIST -> "assist:${request.source.ifBlank { "system" }}"
            ENTRY_MODE_SCREEN_AUTOMATION -> "screen_automation:${request.source.ifBlank { "manual" }}"
            AssistantShellIntentRouter.ENTRY_MODE_VOICE -> request.source.ifBlank { "voice" }
            else -> request.source.ifBlank { "app" }
        }
}
