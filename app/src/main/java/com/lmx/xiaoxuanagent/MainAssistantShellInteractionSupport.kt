package com.lmx.xiaoxuanagent

import android.view.View
import com.lmx.xiaoxuanagent.memory.PersonalMemoryEntryType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class MainAssistantShellCommandExecutionResult(
    val success: Boolean,
    val summary: String,
    val lines: List<String> = emptyList(),
)

internal class MainAssistantShellInteractionController(
    private val scope: CoroutineScope,
    private val views: MainAssistantShellViews,
    private val renderPanels: () -> Unit,
    private val requestRefresh: (String) -> Unit,
    private val executeCommand: suspend (String) -> MainAssistantShellCommandExecutionResult,
    private val handoffAfterCommand: (String) -> Unit,
) {
    var selectedPage: MainAssistantShellPage = MainAssistantShellPage.TODAY
        private set
    var lastContentPage: MainAssistantShellPage = MainAssistantShellPage.TODAY
        private set
    var commandResult: String = ""
        private set
    private var pageActions: List<MainAssistantShellAction> = emptyList()

    fun bind(uiStateProvider: () -> MainAssistantShellUiState) {
        views.shellPageTodayButton.setOnClickListener { switchPage(MainAssistantShellPage.TODAY) }
        views.shellPageInboxButton.setOnClickListener { switchPage(MainAssistantShellPage.INBOX) }
        views.shellPageViewerButton.setOnClickListener { switchPage(MainAssistantShellPage.APPROVALS) }
        views.shellPageApprovalsButton.setOnClickListener { switchPage(MainAssistantShellPage.COMMAND) }
        views.shellPageSessionsButton.setOnClickListener { switchPage(MainAssistantShellPage.WORKBENCH) }
        views.shellPageCommandButton.setOnClickListener { switchPage(MainAssistantShellPage.VIEWER) }
        views.shellPageRoutineButton.setOnClickListener { switchPage(MainAssistantShellPage.SESSIONS) }
        views.shellPageGovernanceButton.setOnClickListener { switchPage(MainAssistantShellPage.GOVERNANCE) }
        views.shellPageEntryButton.setOnClickListener { switchPage(MainAssistantShellPage.MEMORY) }
        views.shellPageMemoryButton.setOnClickListener { switchPage(MainAssistantShellPage.MEMORY) }
        views.shellActionPrimaryButton.setOnClickListener { performAction(pageActions.getOrNull(0), uiStateProvider()) }
        views.shellActionSecondaryButton.setOnClickListener { performAction(pageActions.getOrNull(1), uiStateProvider()) }
        views.shellActionTertiaryButton.setOnClickListener { performAction(pageActions.getOrNull(2), uiStateProvider()) }
        views.shellRunCommandButton.setOnClickListener { runCommand() }
        views.shellFillCommandButton.setOnClickListener {
            fillCommandIntoComposer(uiStateProvider())
        }
    }

    fun setPage(page: MainAssistantShellPage) {
        selectedPage = page
        if (page != MainAssistantShellPage.COMMAND) {
            lastContentPage = page
        }
    }

    fun switchPage(page: MainAssistantShellPage) {
        setPage(page)
        renderPanels()
    }

    fun setCommandResult(value: String) {
        commandResult = value
    }

    fun updateMemoryInputHints(selectedMemoryEntryType: PersonalMemoryEntryType) {
        MainAssistantShellBinder.updateMemoryInputHints(views, selectedMemoryEntryType)
    }

    fun onRenderedPage(actions: List<MainAssistantShellAction>) {
        pageActions = actions
        applyCompactChrome()
    }

    fun fillCommandForCurrentPage(uiState: MainAssistantShellUiState): String =
        MainAssistantShellBinder
            .recommendedCommandsForPage(
                uiState,
                if (selectedPage == MainAssistantShellPage.COMMAND) lastContentPage else selectedPage,
            ).firstOrNull()
            ?: MainAssistantShellBinder
                .recommendedCommandsForPage(uiState, selectedPage)
                .firstOrNull()
            ?: uiState.recommendedCommands.firstOrNull()
            ?: selectedPage.defaultCommand

    private fun fillCommandIntoComposer(uiState: MainAssistantShellUiState) {
        val command = fillCommandForCurrentPage(uiState)
        views.shellCommandInput.setText(command)
        views.shellCommandInput.setSelection(views.shellCommandInput.text?.length ?: 0)
    }

    private fun performAction(
        action: MainAssistantShellAction?,
        uiState: MainAssistantShellUiState,
    ) {
        when (action?.type) {
            MainAssistantShellActionType.RUN_COMMAND -> {
                val command = action.command.trim()
                if (command.isBlank()) return
                views.shellCommandInput.setText(command)
                views.shellCommandInput.setSelection(command.length)
                runCommand()
            }

            MainAssistantShellActionType.FILL_COMMAND -> {
                val command = action.command.trim().ifBlank { fillCommandForCurrentPage(uiState) }
                if (command.isBlank()) return
                views.shellCommandInput.setText(command)
                views.shellCommandInput.setSelection(command.length)
                if (selectedPage != MainAssistantShellPage.COMMAND) {
                    setPage(MainAssistantShellPage.COMMAND)
                    renderPanels()
                }
            }

            MainAssistantShellActionType.SWITCH_PAGE -> {
                action.targetPage?.let(::switchPage)
            }

            MainAssistantShellActionType.REFRESH -> requestRefresh("shell_page_action_refresh")
            null -> Unit
        }
    }

    private fun runCommand() {
        val raw = views.shellCommandInput.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) return
        commandResult = "执行中...\n$raw"
        renderPanels()
        scope.launch {
            val result = executeCommand(raw)
            commandResult =
                buildString {
                    append(if (result.success) "success" else "failed").append(" | ").append(result.summary)
                    if (result.lines.isNotEmpty()) {
                        append('\n')
                        append(result.lines.joinToString(separator = "\n"))
                    }
                }
            if (result.success) {
                handoffAfterCommand(raw)
            }
            requestRefresh("shell_command")
            renderPanels()
        }
    }

    private fun applyCompactChrome() {
        val topLevelPage = selectedPage.topLevelPage()
        val showWorkbenchNav = selectedPage.isWorkbenchFamily()
        val legacyMemoryNavRow = views.shellPageMemoryButton.parent as? View

        views.shellPageTodayButton.updateTextIfChanged(views.shellPageTodayButton.context.getString(R.string.shell_page_today))
        views.shellPageInboxButton.updateTextIfChanged(views.shellPageInboxButton.context.getString(R.string.shell_page_inbox))
        views.shellPageViewerButton.updateTextIfChanged(views.shellPageViewerButton.context.getString(R.string.shell_page_approvals))
        views.shellPageApprovalsButton.updateTextIfChanged(views.shellPageApprovalsButton.context.getString(R.string.shell_page_ask))
        views.shellPageSessionsButton.updateTextIfChanged(views.shellPageSessionsButton.context.getString(R.string.shell_page_workbench))
        views.shellPageCommandButton.updateTextIfChanged(views.shellPageCommandButton.context.getString(R.string.shell_page_viewer))
        views.shellPageRoutineButton.updateTextIfChanged(views.shellPageRoutineButton.context.getString(R.string.shell_page_sessions))
        views.shellPageGovernanceButton.updateTextIfChanged(views.shellPageGovernanceButton.context.getString(R.string.shell_page_governance))
        views.shellPageEntryButton.updateTextIfChanged(views.shellPageEntryButton.context.getString(R.string.shell_page_memory))
        views.shellPageMemoryButton.updateTextIfChanged(views.shellPageMemoryButton.context.getString(R.string.shell_page_memory))

        views.shellPageCommandButton.visibility = if (showWorkbenchNav) View.VISIBLE else View.GONE
        views.shellWorkbenchNavRow.visibility = View.VISIBLE
        views.shellWorkbenchMemoryRow.visibility = if (showWorkbenchNav) View.VISIBLE else View.GONE
        views.shellPageMemoryButton.visibility = View.GONE
        legacyMemoryNavRow?.visibility = View.GONE

        updatePageButton(views.shellPageTodayButton, topLevelPage == MainAssistantShellPage.TODAY)
        updatePageButton(views.shellPageInboxButton, topLevelPage == MainAssistantShellPage.INBOX)
        updatePageButton(views.shellPageViewerButton, topLevelPage == MainAssistantShellPage.APPROVALS)
        updatePageButton(views.shellPageApprovalsButton, topLevelPage == MainAssistantShellPage.COMMAND)
        updatePageButton(views.shellPageSessionsButton, topLevelPage == MainAssistantShellPage.WORKBENCH)
        updatePageButton(views.shellPageCommandButton, selectedPage == MainAssistantShellPage.VIEWER)
        updatePageButton(views.shellPageRoutineButton, selectedPage == MainAssistantShellPage.SESSIONS)
        updatePageButton(views.shellPageGovernanceButton, selectedPage == MainAssistantShellPage.GOVERNANCE)
        updatePageButton(views.shellPageEntryButton, selectedPage == MainAssistantShellPage.MEMORY)
        updatePageButton(views.shellPageMemoryButton, false)
    }
}
