package com.lmx.xiaoxuanagent

import android.content.Context
import android.view.View
import android.widget.TextView
import com.lmx.xiaoxuanagent.accessibility.ForegroundAutomationPhase
import com.lmx.xiaoxuanagent.accessibility.ForegroundAutomationSessionSnapshot
import com.lmx.xiaoxuanagent.runtime.DebugUiState

data class MainMinimalHomeViews(
    val assistantShellSection: View,
    val assistantWorkbenchSection: View,
    val assistantControlCenterSection: View,
    val providerRouterSection: View,
    val memoryGovernanceSection: View,
    val legacyStatusSection: View,
    val legacyMemorySection: View,
    val legacyTakeoverSection: View,
    val legacyHistorySection: View,
    val legacyRoutingSection: View,
    val legacySkillSection: View,
    val homeStatusTitleText: TextView,
    val homeStatusSummaryText: TextView,
    val homeStatusDetailText: TextView,
)

object MainMinimalHomeSupport {
    fun apply(views: MainMinimalHomeViews) {
        views.assistantShellSection.visibility = View.GONE
        views.assistantWorkbenchSection.visibility = View.GONE
        views.assistantControlCenterSection.visibility = View.GONE
        views.providerRouterSection.visibility = View.GONE
        views.memoryGovernanceSection.visibility = View.GONE
        views.legacyStatusSection.visibility = View.GONE
        views.legacyMemorySection.visibility = View.GONE
        views.legacyTakeoverSection.visibility = View.GONE
        views.legacyHistorySection.visibility = View.GONE
        views.legacyRoutingSection.visibility = View.GONE
        views.legacySkillSection.visibility = View.GONE
    }

    fun render(
        context: Context,
        views: MainMinimalHomeViews,
        state: DebugUiState,
        automation: ForegroundAutomationSessionSnapshot,
    ) {
        views.homeStatusTitleText.updateTextIfChanged(resolveTitle(context, state, automation.phase))
        views.homeStatusSummaryText.updateTextIfChanged(resolveSummary(context, state, automation))
        val detailLines =
            buildList {
                if (automation.task.isNotBlank()) {
                    add("任务：${automation.task}")
                }
                if (automation.targetPackageName.isNotBlank()) {
                    add("应用：${formatPackageLabel(automation.targetPackageName)}")
                }
                if (automation.currentStep.isNotBlank()) {
                    add("当前：${automation.currentStep}")
                }
                automation.nextStep
                    .ifBlank { automation.handoffSummary }
                    .takeIf { it.isNotBlank() }
                    ?.let { add("下一步：$it") }
            }.take(3)
        if (detailLines.isEmpty()) {
            views.homeStatusDetailText.updateTextIfChanged("")
            views.homeStatusDetailText.visibility = View.GONE
        } else {
            views.homeStatusDetailText.updateTextIfChanged(detailLines.joinToString(separator = "\n"))
            views.homeStatusDetailText.visibility = View.VISIBLE
        }
    }

    private fun resolveTitle(
        context: Context,
        state: DebugUiState,
        phase: ForegroundAutomationPhase,
    ): String =
        when {
            !state.accessibilityConnected -> context.getString(R.string.home_status_accessibility)
            state.safety.awaitingConfirmation || phase == ForegroundAutomationPhase.HANDOFF ->
                context.getString(R.string.home_status_handoff)
            phase == ForegroundAutomationPhase.WAITING_USER -> context.getString(R.string.home_status_waiting)
            phase == ForegroundAutomationPhase.SUSPENDED -> context.getString(R.string.home_status_paused)
            phase == ForegroundAutomationPhase.PLANNING -> context.getString(R.string.home_status_planning)
            phase == ForegroundAutomationPhase.ACTING -> context.getString(R.string.home_status_acting)
            phase == ForegroundAutomationPhase.OBSERVING -> context.getString(R.string.home_status_observing)
            phase == ForegroundAutomationPhase.COMPLETE -> context.getString(R.string.home_status_complete)
            else -> context.getString(R.string.home_status_ready)
        }

    private fun resolveSummary(
        context: Context,
        state: DebugUiState,
        automation: ForegroundAutomationSessionSnapshot,
    ): String {
        if (!state.accessibilityConnected) {
            return context.getString(R.string.permission_tip)
        }
        if (state.safety.awaitingConfirmation) {
            return automation.handoffSummary.ifBlank {
                state.safety.pendingConfirmationSummary.ifBlank { automation.supervisionSummary }
            }
        }
        return automation.supervisionSummary.ifBlank {
            context.getString(R.string.home_status_ready_summary)
        }
    }

    private fun formatPackageLabel(packageName: String): String =
        packageName
            .substringAfterLast('.')
            .replace('_', ' ')
            .ifBlank { packageName }
}
