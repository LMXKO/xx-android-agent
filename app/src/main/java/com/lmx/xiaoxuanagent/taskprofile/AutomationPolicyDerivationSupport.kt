package com.lmx.xiaoxuanagent.taskprofile

import com.lmx.xiaoxuanagent.harness.ScreenAutomationCapabilitySnapshot
import com.lmx.xiaoxuanagent.harness.ScreenAutomationReleaseGateSnapshot
import com.lmx.xiaoxuanagent.harness.ScreenAutomationTemplateCapabilitySnapshot

internal fun mergeAutomationPolicy(
    base: AutomationSupportPolicy,
    learned: ScreenAutomationCapabilitySnapshot?,
    stageLearned: ScreenAutomationTemplateCapabilitySnapshot? = null,
    releaseGate: ScreenAutomationReleaseGateSnapshot? = null,
    productSupport: ScreenAutomationProductSupportSnapshot? = null,
): AutomationSupportPolicy {
    if (learned == null && stageLearned == null && releaseGate == null && productSupport == null) return base
    val handoffSummary =
        buildString {
            base.handoffSummary.takeIf { it.isNotBlank() }?.let(::append)
            learned?.note.takeIf { !it.isNullOrBlank() }?.let { note ->
                if (isNotBlank()) append(' ')
                append(note)
            }
            stageLearned?.note.takeIf { !it.isNullOrBlank() }?.let { note ->
                if (isNotBlank()) append(' ')
                append(note)
            }
            releaseGate?.summary.takeIf { !it.isNullOrBlank() }?.let { note ->
                if (isNotBlank()) append(' ')
                append(note)
            }
            productSupport?.summary.takeIf { !it.isNullOrBlank() }?.let { note ->
                if (isNotBlank()) append(' ')
                append(note)
            }
        }
    return base.copy(
        supportsFill = base.supportsFill && !(learned?.restrictFill ?: false) && !(stageLearned?.restrictFill ?: false),
        supportsSubmit =
            base.supportsSubmit &&
                !(learned?.restrictSubmit ?: false) &&
                !(stageLearned?.restrictSubmit ?: false) &&
                releaseGate?.gateStatus != "red" &&
                (productSupport?.allowAutoSubmit != false),
        requiresFinalHandoff =
            base.requiresFinalHandoff ||
                (learned?.preferFinalHandoff ?: false) ||
                (stageLearned?.preferFinalHandoff ?: false) ||
                releaseGate?.gateStatus in setOf("red", "yellow") ||
                productSupport?.allowAutoSubmit == false,
        supervisionMode =
            when {
                productSupport?.focusApp == false -> "foreground_select_app_handoff_only"
                releaseGate?.gateStatus == "red" -> "foreground_release_guarded"
                releaseGate?.gateStatus == "yellow" -> "foreground_release_handoff_first"
                stageLearned?.preferFinalHandoff == true -> "foreground_stage_handoff_first"
                learned?.preferFinalHandoff == true -> "foreground_handoff_first"
                stageLearned?.confidenceBand == "weak" -> "foreground_stage_guarded"
                learned?.confidenceBand == "strong" -> "foreground_takeover_trusted"
                else -> base.supervisionMode
            },
        preferredExecutionPath =
            when {
                productSupport?.connectedFirstRecommended == true && base.connectedAppId.isNotBlank() -> "connected_app_first"
                productSupport?.focusApp == false -> "gui_fallback"
                else -> base.preferredExecutionPath
            },
        releaseGateStatus = releaseGate?.gateStatus ?: base.releaseGateStatus,
        releaseGateSummary = releaseGate?.summary.orEmpty(),
        productSupportTier = productSupport?.tier ?: base.productSupportTier,
        productSupportSummary = productSupport?.summary.orEmpty(),
        handoffSummary = handoffSummary.ifBlank { base.handoffSummary },
    )
}
