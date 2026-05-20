package com.lmx.xiaoxuanagent

import android.view.View
import android.widget.Button
import android.widget.TextView
import com.lmx.xiaoxuanagent.memory.PersonalMemoryEntryType
import com.lmx.xiaoxuanagent.memory.PersonalMemoryGovernanceSnapshot

internal fun renderProviderRouterPanel(
    views: MainAssistantShellViews,
    uiState: MainAssistantShellUiState,
) {
    views.providerRouterText.updateTextIfChanged(
        buildString {
            append("planner_policy_enabled=").append(uiState.providerPolicy.enabled).append('\n')
            append("prefer_text_on_artifact_heavy_stage=").append(uiState.providerPolicy.preferTextOnArtifactHeavyStage).append('\n')
            append("prefer_text_on_resume=").append(uiState.providerPolicy.preferTextOnResume).append('\n')
            append("stage_overrides=").append(
                uiState.providerPolicy.stageOverrides.entries.joinToString(",") { "${it.key}:${it.value}" }.ifBlank { "-" },
            ).append('\n')
            append("package_overrides=").append(
                uiState.providerPolicy.packageOverrides.entries.joinToString(",") { "${it.key}:${it.value}" }.ifBlank { "-" },
            ).append('\n')
            append("provider_failures=").append(
                uiState.providerFailures.entries.joinToString(",") { "${it.key}:${it.value}" }.ifBlank { "-" },
            ).append('\n')
            append("provider_feedback").append('\n')
            uiState.providerFeedbackLines.forEach { line ->
                append("- ").append(line).append('\n')
            }
            append("planner_registry").append('\n')
            uiState.providerRegistryLines.forEach { line ->
                append("- ").append(line).append('\n')
            }
            append("trigger_disabled=").append(uiState.triggerPolicy.disabledPolicyIds.joinToString(",").ifBlank { "-" }).append('\n')
            append("trigger_signal_overrides=").append(
                uiState.triggerPolicy.signalTypeCapabilityOverrides.entries.joinToString(",") {
                    "${it.key}:${it.value.name.lowercase()}"
                }.ifBlank { "-" },
            ).append('\n')
            append("providers").append('\n')
            uiState.signalProviders.forEach { provider ->
                append("- ").append(provider.providerId)
                    .append(" health=").append(provider.healthScore)
                    .append(" accepted=").append(provider.acceptedSignals)
                    .append(" failures=").append(provider.failureCount)
                    .append('\n')
            }
        }.trim(),
    )
}

internal fun renderMemoryGovernancePanel(
    views: MainAssistantShellViews,
    snapshot: PersonalMemoryGovernanceSnapshot,
    selectedMemoryEntryType: PersonalMemoryEntryType,
) {
    views.memoryEntryTypeButton.updateTextIfChanged("类型: ${selectedMemoryEntryType.wireName}")
    views.saveMemoryButton.isEnabled = true
    views.deleteMemoryButton.isEnabled = true
    views.memoryGovernanceText.updateTextIfChanged(
        buildString {
            append(snapshot.summary.ifBlank { "entries=0" }).append('\n')
            append("workspace=").append(snapshot.workspaceSummary.ifBlank { "-" }).append('\n')
            snapshot.entries.take(12).forEach { entry ->
                append("- ").append(entry.type.wireName)
                    .append(" | ").append(entry.title.take(24))
                    .append(" | ").append(entry.summary.take(56))
                    .append(" | ").append(entry.entryId)
                    .append('\n')
            }
        }.trim(),
    )
    views.memoryAuditText.updateTextIfChanged(
        buildString {
            append("audit").append('\n')
            snapshot.auditTrail.take(8).forEach { audit ->
                append("- ").append(audit.action)
                    .append(" | ").append(audit.type.wireName)
                    .append(" | ").append(audit.summary.take(72))
                    .append('\n')
            }
        }.trim(),
    )
    MainAssistantShellBinder.updateMemoryInputHints(views, selectedMemoryEntryType)
}

internal fun TextView.updateTextIfChanged(value: CharSequence) {
    if (text?.toString() != value.toString()) {
        text = value
    }
}

internal fun Button.updateTextIfChanged(value: CharSequence) {
    if (text?.toString() != value.toString()) {
        text = value
    }
}

internal fun renderCard(
    container: View,
    titleView: TextView,
    bodyView: TextView,
    card: MainAssistantShellCard?,
) {
    container.visibility = if (card == null || card.body.isBlank()) View.GONE else View.VISIBLE
    if (card == null) return
    titleView.updateTextIfChanged(card.title)
    bodyView.updateTextIfChanged(card.body)
}

internal fun renderActionButton(
    button: Button,
    action: MainAssistantShellAction?,
    primary: Boolean,
) {
    button.visibility = if (action == null) View.GONE else View.VISIBLE
    if (action == null) return
    button.updateTextIfChanged(action.label)
    updateActionButton(button, primary)
}

internal fun updatePageButton(
    button: Button,
    selected: Boolean,
) {
    button.setBackgroundResource(
        if (selected) {
            R.drawable.primary_action_background
        } else {
            R.drawable.secondary_action_background
        },
    )
    button.setTextColor(
        button.context.getColor(
            if (selected) {
                android.R.color.white
            } else {
                R.color.brand_navy
            },
        ),
    )
}

internal fun updateActionButton(
    button: Button,
    primary: Boolean,
) {
    button.setBackgroundResource(
        if (primary) {
            R.drawable.primary_action_background
        } else {
            R.drawable.secondary_action_background
        },
    )
    button.setTextColor(
        button.context.getColor(
            if (primary) {
                android.R.color.white
            } else {
                R.color.brand_navy
            },
        ),
    )
}
