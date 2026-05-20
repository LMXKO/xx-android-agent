package com.lmx.xiaoxuanagent.entry

import android.content.Intent

sealed interface AssistantEntryDirective {
    data class AssistantAction(
        val action: String,
    ) : AssistantEntryDirective

    data class SharedText(
        val text: String,
    ) : AssistantEntryDirective

    data class ReturnSummary(
        val summary: String,
    ) : AssistantEntryDirective
}

object EntryRouter {
    fun resolve(
        intent: Intent?,
        actionExtraKey: String,
        returnSummaryKey: String,
    ): List<AssistantEntryDirective> {
        if (intent == null) return emptyList()
        return buildList {
            intent.getStringExtra(actionExtraKey)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { add(AssistantEntryDirective.AssistantAction(it)) }
            intent.getStringExtra(returnSummaryKey)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { add(AssistantEntryDirective.ReturnSummary(it)) }
            if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
                intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { add(AssistantEntryDirective.SharedText(it)) }
            }
        }
    }

    fun shouldAutoReturn(
        intent: Intent?,
        actionExtraKey: String,
        returnSummaryKey: String,
    ): Boolean {
        if (intent == null) return true
        if (!intent.getStringExtra(actionExtraKey).isNullOrBlank()) return false
        if (!intent.getStringExtra(returnSummaryKey).isNullOrBlank()) return false
        return intent.action != Intent.ACTION_SEND
    }
}

