package com.lmx.xiaoxuanagent.assistantos

import android.content.Context
import android.content.Intent
import com.lmx.xiaoxuanagent.MainActivity

object AssistantShellIntentRouter {
    private const val ACTION_RETURN_INTENT = "com.lmx.xiaoxuanagent.action.RETURN"
    private const val ACTION_OPEN_PAGE_INTENT = "com.lmx.xiaoxuanagent.action.OPEN_PAGE"
    private const val ACTION_HANDLE_ENTRY_INTENT = "com.lmx.xiaoxuanagent.action.HANDLE_ENTRY"
    private const val ACTION_HANDLE_COMMAND_INTENT = "com.lmx.xiaoxuanagent.action.HANDLE_COMMAND"

    const val EXTRA_RETURN_SUMMARY = "assistant_return_summary"
    const val EXTRA_ASSISTANT_ACTION = "assistant_action"
    const val EXTRA_ASSISTANT_ACTION_SOURCE = "assistant_action_source"
    const val EXTRA_ASSISTANT_SHELL_PAGE = "assistant_shell_page"
    const val EXTRA_ASSISTANT_ENTRY_MODE = "assistant_entry_mode"
    const val EXTRA_ASSISTANT_TASK_SEED = "assistant_task_seed"
    const val EXTRA_ASSISTANT_AUTO_RUN = "assistant_auto_run"

    const val ENTRY_MODE_VOICE = "voice"

    const val ACTION_OPEN = "open"
    const val ACTION_STOP = "stop"
    const val ACTION_PAUSE = "pause"
    const val ACTION_RESUME = "resume"
    const val ACTION_APPROVE = "approve"
    const val ACTION_REJECT = "reject"
    const val ACTION_RETURN_TARGET = "return_target"
    const val ACTION_VOICE_TOGGLE = "voice_toggle"

    data class ShellIntentPayload(
        val action: String = "",
        val actionSource: String = "",
        val page: String = "",
        val entryMode: String = "",
        val returnSummary: String = "",
        val taskSeed: String = "",
        val autoRun: Boolean = false,
    )

    fun createReturnIntent(
        context: Context,
        summary: String,
    ): Intent =
        Intent(context, MainActivity::class.java)
            .setAction("$ACTION_RETURN_INTENT:$summary")
            .putExtra(EXTRA_RETURN_SUMMARY, summary)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )

    fun createActionIntent(
        context: Context,
        action: String,
        source: String = "notification",
    ): Intent =
        Intent(context, MainActivity::class.java)
            .setAction("$ACTION_HANDLE_COMMAND_INTENT:$action:$source")
            .putExtra(EXTRA_ASSISTANT_ACTION, action)
            .putExtra(EXTRA_ASSISTANT_ACTION_SOURCE, source)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )

    fun createPageIntent(
        context: Context,
        page: String,
        source: String = "main_app",
    ): Intent =
        Intent(context, MainActivity::class.java)
            .setAction("$ACTION_OPEN_PAGE_INTENT:$page:$source")
            .putExtra(EXTRA_ASSISTANT_SHELL_PAGE, page)
            .putExtra(EXTRA_ASSISTANT_ACTION_SOURCE, source)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )

    fun createVoiceEntryIntent(
        context: Context,
        source: String = "voice",
    ): Intent =
        createEntryIntent(
            context = context,
            entryMode = ENTRY_MODE_VOICE,
            source = source,
        )

    fun createAssistEntryIntent(
        context: Context,
        source: String = "assist",
        taskSeed: String = "",
        autoRun: Boolean = false,
    ): Intent =
        createEntryIntent(
            context = context,
            entryMode = AssistantInvocationCoordinator.ENTRY_MODE_ASSIST,
            source = source,
            taskSeed = taskSeed,
            autoRun = autoRun,
        )

    fun createScreenAutomationIntent(
        context: Context,
        source: String = "screen_automation",
        taskSeed: String = "",
        autoRun: Boolean = false,
    ): Intent =
        createEntryIntent(
            context = context,
            entryMode = AssistantInvocationCoordinator.ENTRY_MODE_SCREEN_AUTOMATION,
            source = source,
            taskSeed = taskSeed,
            autoRun = autoRun,
        )

    private fun createEntryIntent(
        context: Context,
        entryMode: String,
        source: String,
        taskSeed: String = "",
        autoRun: Boolean = false,
    ): Intent =
        Intent(context, MainActivity::class.java)
            .setAction("$ACTION_HANDLE_ENTRY_INTENT:$entryMode:$source")
            .putExtra(EXTRA_ASSISTANT_ENTRY_MODE, entryMode)
            .putExtra(EXTRA_ASSISTANT_ACTION_SOURCE, source)
            .putExtra(EXTRA_ASSISTANT_TASK_SEED, taskSeed)
            .putExtra(EXTRA_ASSISTANT_AUTO_RUN, autoRun)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )

    fun readPayload(intent: Intent?): ShellIntentPayload {
        if (intent == null) return ShellIntentPayload()
        return ShellIntentPayload(
            action = intent.getStringExtra(EXTRA_ASSISTANT_ACTION).orEmpty().trim(),
            actionSource = intent.getStringExtra(EXTRA_ASSISTANT_ACTION_SOURCE).orEmpty().trim(),
            page = intent.getStringExtra(EXTRA_ASSISTANT_SHELL_PAGE).orEmpty().trim(),
            entryMode = intent.getStringExtra(EXTRA_ASSISTANT_ENTRY_MODE).orEmpty().trim(),
            returnSummary = intent.getStringExtra(EXTRA_RETURN_SUMMARY).orEmpty().trim(),
            taskSeed = intent.getStringExtra(EXTRA_ASSISTANT_TASK_SEED).orEmpty().trim(),
            autoRun = intent.getBooleanExtra(EXTRA_ASSISTANT_AUTO_RUN, false),
        )
    }

    fun consumeReturnSummary(intent: Intent?): String {
        val summary = readPayload(intent).returnSummary
        if (summary.isNotBlank()) {
            intent?.removeExtra(EXTRA_RETURN_SUMMARY)
        }
        return summary
    }

    fun consumePage(intent: Intent?): String {
        val page = readPayload(intent).page
        if (page.isNotBlank()) {
            intent?.removeExtra(EXTRA_ASSISTANT_SHELL_PAGE)
        }
        return page
    }

    fun consumeEntryMode(intent: Intent?): String {
        val entryMode = readPayload(intent).entryMode
        if (entryMode.isNotBlank()) {
            intent?.removeExtra(EXTRA_ASSISTANT_ENTRY_MODE)
        }
        return entryMode
    }

    fun consumeAction(intent: Intent?): String {
        val action = readPayload(intent).action
        if (action.isNotBlank()) {
            intent?.removeExtra(EXTRA_ASSISTANT_ACTION)
        }
        return action
    }

    fun resolveSurface(
        source: String,
    ): AssistantEntrySurface =
        when (source) {
            "overlay" -> AssistantEntrySurface.OVERLAY
            "main_app" -> AssistantEntrySurface.MAIN_APP
            "shortcut" -> AssistantEntrySurface.SHORTCUT
            "widget" -> AssistantEntrySurface.WIDGET
            "tile" -> AssistantEntrySurface.TILE
            "voice" -> AssistantEntrySurface.VOICE
            else -> AssistantEntrySurface.NOTIFICATION
        }
}
