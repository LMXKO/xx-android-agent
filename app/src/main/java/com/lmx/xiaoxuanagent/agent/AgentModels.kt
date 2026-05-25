package com.lmx.xiaoxuanagent.agent

data class UiElementObservation(
    val id: String,
    val text: String,
    val viewId: String,
    val className: String,
    val bounds: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val selected: Boolean,
    val parentId: String = "",
    val containerId: String = "",
    val depth: Int = 0,
    val rowIndex: Int = -1,
    val columnIndex: Int = -1,
    val collectionPosition: String = "",
    val accessibilityLabel: String = "",
    val accessibilityUniqueId: String = "",
    val paneTitle: String = "",
    val containerTitle: String = "",
    val stateDescription: String = "",
    val descriptorTokens: List<String> = emptyList(),
    val visualDescriptorTokens: List<String> = emptyList(),
    val interactionRegions: List<InteractionRegion> = emptyList(),
    val visualSignature: String = "",
    val spatialSignature: String = "",
    val roleHint: String = "",
    val neighborTexts: List<String> = emptyList(),
    val source: String = "tree",
    val searchScopeId: String = "",
)

data class InteractionRegion(
    val leftFraction: Float,
    val topFraction: Float,
    val rightFraction: Float,
    val bottomFraction: Float,
    val score: Float = 0f,
    val label: String = "",
)

data class InterruptiveHint(
    val elementId: String,
    val text: String,
    val reason: String,
)

data class ScreenObservation(
    val packageName: String,
    val pageState: String,
    val signature: String,
    val screenSummary: String,
    val topTexts: List<String>,
    val primaryEditableId: String?,
    val focusedElementId: String?,
    val defaultScrollableId: String?,
    val primaryInterruptActionId: String?,
    val interruptiveHints: List<InterruptiveHint>,
    val elements: List<UiElementObservation>,
    val structureHints: List<String> = emptyList(),
)

data class ScreenshotPayload(
    val mimeType: String,
    val base64Data: String,
    val width: Int,
    val height: Int,
)

data class VisualTextObservation(
    val text: String,
    val bounds: String,
    val confidence: Float,
)

data class VisualGroundingObservation(
    val text: String,
    val bounds: String,
    val matchedElementId: String?,
    val matchedElementText: String,
    val overlapScore: Float,
    val matchedContainerId: String = "",
)

data class VisualObjectObservation(
    val id: String,
    val type: String,
    val label: String,
    val bounds: String,
    val confidence: Float,
    val source: String = "",
    val candidateTier: String = "primary",
    val roleHint: String = "",
    val contextHints: List<String> = emptyList(),
    val descriptorTokens: List<String> = emptyList(),
    val textFree: Boolean = false,
    val tapPattern: String = "",
    val tapHotspotX: Float = 0.5f,
    val tapHotspotY: Float = 0.5f,
    val interactionRegions: List<InteractionRegion> = emptyList(),
    val visualSignature: String = "",
    val spatialSignature: String = "",
    val matchedElementId: String? = null,
    val matchedContainerId: String = "",
    val accessibilityUniqueId: String = "",
    val accessibilityLabel: String = "",
    val collectionPosition: String = "",
)

data class ScreenParserRegion(
    val id: String,
    val type: String,
    val label: String = "",
    val bounds: String,
    val confidence: Float,
    val source: String = "",
    val parserLayer: String = "",
    val searchTier: String = "primary",
    val roleHint: String = "",
    val contextHints: List<String> = emptyList(),
    val descriptorTokens: List<String> = emptyList(),
    val textFree: Boolean = false,
    val tapPattern: String = "",
    val tapHotspotX: Float = 0.5f,
    val tapHotspotY: Float = 0.5f,
    val interactionRegions: List<InteractionRegion> = emptyList(),
    val visualSignature: String = "",
    val spatialSignature: String = "",
    val matchedElementIds: List<String> = emptyList(),
    val matchedContainerIds: List<String> = emptyList(),
    val accessibilityUniqueIds: List<String> = emptyList(),
    val accessibilityLabels: List<String> = emptyList(),
)

data class VirtualUiTarget(
    val id: String,
    val text: String,
    val bounds: String,
    val source: String,
    val candidateTier: String = "primary",
    val objectType: String = "",
    val matchedElementId: String? = null,
    val matchedContainerId: String = "",
    val confidence: Float = 0f,
    val roleHint: String = "",
    val contextHints: List<String> = emptyList(),
    val descriptorTokens: List<String> = emptyList(),
    val textFree: Boolean = false,
    val tapPattern: String = "",
    val tapHotspotX: Float = 0.5f,
    val tapHotspotY: Float = 0.5f,
    val interactionRegions: List<InteractionRegion> = emptyList(),
    val visualSignature: String = "",
    val spatialSignature: String = "",
    val accessibilityUniqueId: String = "",
    val accessibilityLabel: String = "",
    val collectionPosition: String = "",
    val parserRegionId: String = "",
    val searchScopeId: String = "",
)

data class VisualPerceptionContext(
    val captureAvailable: Boolean = false,
    val ocrTexts: List<VisualTextObservation> = emptyList(),
    val groundedTexts: List<VisualGroundingObservation> = emptyList(),
    val visualObjects: List<VisualObjectObservation> = emptyList(),
    val parserRegions: List<ScreenParserRegion> = emptyList(),
    val visualHints: List<String> = emptyList(),
    val summary: String = "",
)

data class TaskPlanStep(
    val id: String,
    val title: String,
    val status: String,
    val evidence: String = "",
)

data class TaskWaitCondition(
    val id: String,
    val title: String,
    val type: String,
    val status: String,
    val reason: String = "",
    val resumeEvent: String = "",
    val resumeHint: String = "",
    val blockingNodeId: String = "",
    val sourceHints: List<String> = emptyList(),
)

data class ResumeContext(
    val active: Boolean = false,
    val source: String = "",
    val resumeEvent: String = "",
    val resumeHint: String = "",
    val resumedSubgoalId: String = "",
    val resumeAttempt: Int = 0,
    val userCorrection: String = "",
)

data class TaskPlanState(
    val planType: String = "generic",
    val currentStage: String = "observe",
    val stageSummary: String = "",
    val nextObjective: String = "",
    val completionSignal: String = "",
    val steps: List<TaskPlanStep> = emptyList(),
    val currentSubgoalId: String = "",
    val taskGraph: List<TaskGraphNode> = emptyList(),
    val taskStack: List<TaskStackFrame> = emptyList(),
    val waitConditions: List<TaskWaitCondition> = emptyList(),
    val waitingForExternal: Boolean = false,
    val waitingForEvent: String = "",
    val suspendable: Boolean = false,
    val suspendReason: String = "",
    val orchestrationSummary: String = "",
    val resumeContext: ResumeContext = ResumeContext(),
)

data class IndexedScreenObservation(
    val observation: ScreenObservation,
    val nodesById: Map<String, android.view.accessibility.AccessibilityNodeInfo>,
    val virtualTargetsById: Map<String, VirtualUiTarget> = emptyMap(),
    val searchElementsById: Map<String, UiElementObservation> = emptyMap(),
    val screenshot: ScreenshotPayload? = null,
    val visualContext: VisualPerceptionContext = VisualPerceptionContext(),
)

data class PlanningMemoryContext(
    val shortTermNotes: List<String> = emptyList(),
    val longTermMemories: List<String> = emptyList(),
    val userPreferences: List<String> = emptyList(),
    val retrievalMemories: List<String> = emptyList(),
    val resultArtifacts: List<String> = emptyList(),
    val correctionTemplates: List<String> = emptyList(),
    val contacts: List<String> = emptyList(),
    val locations: List<String> = emptyList(),
    val appPreferences: List<String> = emptyList(),
    val safetyRules: List<String> = emptyList(),
    val sessionMemories: List<String> = emptyList(),
    val turnLoopSideband: List<String> = emptyList(),
    val sessionNotebook: List<String> = emptyList(),
    val loopInboxAttachments: List<String> = emptyList(),
    val loopRuntimeSignals: List<String> = emptyList(),
    val loopRuntimeDrain: List<String> = emptyList(),
    val loopRuntimePrefetch: List<String> = emptyList(),
    val loopRuntimeTaskSummary: List<String> = emptyList(),
    val skillPrefetchSignals: List<String> = emptyList(),
    val toolRefreshSignals: List<String> = emptyList(),
    val permissionProductSignals: List<String> = emptyList(),
    val memoryForkSignals: List<String> = emptyList(),
    val toolRuntimeContracts: List<String> = emptyList(),
    val compactionSignals: List<String> = emptyList(),
    val turnAttachments: List<PlanningTurnAttachment> = emptyList(),
)

data class PlanningTurnAttachment(
    val attachmentId: String,
    val source: String,
    val type: String,
    val title: String = "",
    val summary: String = "",
    val priority: Int = 0,
    val detailLines: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val consumedAtMs: Long = 0L,
)

data class PlannerArtifactHint(
    val artifactId: String,
    val type: String,
    val summary: String,
)

enum class SkillLayer {
    GUARD,
    DOMAIN,
    PERSONAL,
}

enum class SkillRiskLevel {
    LOW,
    CONFIRM,
    BLOCK,
}

data class SkillContext(
    val id: String,
    val title: String,
    val description: String,
    val instructions: String,
    val layer: SkillLayer = SkillLayer.GUARD,
    val riskLevel: SkillRiskLevel = SkillRiskLevel.LOW,
    val requiredTools: List<String> = emptyList(),
    val parameters: List<String> = emptyList(),
)

sealed interface AgentAction {
    val label: String
    val toolType: AgentActionToolType
    val toolName: String

    data class LaunchApp(val packageName: String) : AgentAction {
        override val label: String = "launch_app($packageName)"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.launch_app"
    }

    data class Click(val elementId: String) : AgentAction {
        override val label: String = "click($elementId)"
        override val toolType: AgentActionToolType = AgentActionToolType.GUI
        override val toolName: String = "gui.click"
    }

    data class SetText(val elementId: String, val text: String) : AgentAction {
        override val label: String = "set_text($elementId)"
        override val toolType: AgentActionToolType = AgentActionToolType.GUI
        override val toolName: String = "gui.set_text"
    }

    data class Scroll(val elementId: String?, val direction: String) : AgentAction {
        override val label: String = "scroll(${elementId ?: "screen"},$direction)"
        override val toolType: AgentActionToolType = AgentActionToolType.GUI
        override val toolName: String = "gui.scroll"
    }

    data class LongClick(val elementId: String) : AgentAction {
        override val label: String = "long_click($elementId)"
        override val toolType: AgentActionToolType = AgentActionToolType.GUI
        override val toolName: String = "gui.long_click"
    }

    data class CopyText(val elementId: String) : AgentAction {
        override val label: String = "copy_text($elementId)"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "online.clipboard_copy"
    }

    data class PasteClipboard(val elementId: String) : AgentAction {
        override val label: String = "paste_clipboard($elementId)"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "online.clipboard_paste"
    }

    data class ReadSessionHistory(val query: String = "") : AgentAction {
        override val label: String = "read_session_history(${query.take(24)})"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "platform.read_session_history"
    }

    data class SearchArtifacts(
        val query: String,
        val artifactType: String = "",
    ) : AgentAction {
        override val label: String = "search_artifacts(${query.take(24)};type=${artifactType.take(16)})"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "platform.search_artifacts"
    }

    data class RecallMemory(val query: String) : AgentAction {
        override val label: String = "recall_memory(${query.take(24)})"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "platform.recall_memory"
    }

    data object ReadSessionNotebook : AgentAction {
        override val label: String = "read_session_notebook()"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "platform.read_session_notebook"
    }

    data class WriteSessionNote(
        val note: String,
        val tag: String = "",
    ) : AgentAction {
        override val label: String = "write_session_note(${tag.take(16)})"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "platform.write_session_note"
    }

    data class SearchTools(
        val query: String,
    ) : AgentAction {
        override val label: String = "search_tools(${query.take(24)})"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "platform.search_tools"
    }

    data class WebSearch(
        val query: String,
    ) : AgentAction {
        override val label: String = "web_search(${query.take(24)})"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "platform.web_search"
    }

    data class WebFetch(
        val url: String,
    ) : AgentAction {
        override val label: String = "web_fetch(${url.take(32)})"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "platform.web_fetch"
    }

    data class ReadConnectedAppCapabilities(
        val appId: String = "",
    ) : AgentAction {
        override val label: String = "read_connected_app_capabilities(${appId.take(24)})"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "connected.read_app_capabilities"
    }

    data class ExecuteConnectedAppAction(
        val appId: String,
        val operation: String,
        val primary: String = "",
        val secondary: String = "",
        val note: String = "",
    ) : AgentAction {
        override val label: String = "execute_connected_app_action(${appId.take(16)}:${operation.take(20)})"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "connected.execute_app_action"
    }

    data object ReadTodoBoard : AgentAction {
        override val label: String = "read_todo_board()"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "platform.read_todo_board"
    }

    data class WriteTodoBoard(
        val content: String,
        val mode: String = "append",
    ) : AgentAction {
        override val label: String = "write_todo_board(${mode.take(12)})"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "platform.write_todo_board"
    }

    data class DelegateLocalAgent(
        val task: String,
        val summary: String = "",
        val role: String = "general",
    ) : AgentAction {
        override val label: String = "delegate_local_agent(${task.take(24)})"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "platform.delegate_local_agent"
    }

    data object ReadWorkerRoles : AgentAction {
        override val label: String = "read_worker_roles()"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "platform.read_worker_roles"
    }

    data class ReadWorkerMailbox(
        val target: String = "",
        val includeConsumed: Boolean = false,
        val limit: Int = 12,
    ) : AgentAction {
        override val label: String = "read_worker_mailbox(${target.take(24)})"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "platform.read_worker_mailbox"
    }

    data class ReplyWorkerMessage(
        val messageId: String,
        val decision: String = "",
        val note: String = "",
    ) : AgentAction {
        override val label: String = "reply_worker_message(${messageId.take(24)})"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "platform.reply_worker_message"
    }

    data class AcknowledgeWorkerMessage(
        val messageId: String,
        val note: String = "",
    ) : AgentAction {
        override val label: String = "ack_worker_message(${messageId.take(24)})"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "platform.ack_worker_message"
    }

    data object ReadSessionMemoryFile : AgentAction {
        override val label: String = "read_session_memory_file()"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "platform.read_session_memory_file"
    }

    data class ReadWorkerStatus(
        val target: String = "",
        val includeChildren: Boolean = true,
    ) : AgentAction {
        override val label: String = "read_worker_status(${target.take(24)})"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "platform.read_worker_status"
    }

    data class MergeWorkerResult(
        val messageId: String,
        val note: String = "",
    ) : AgentAction {
        override val label: String = "merge_worker_result(${messageId.take(24)})"
        override val toolType: AgentActionToolType = AgentActionToolType.ONLINE
        override val toolName: String = "platform.merge_worker_result"
    }

    data class TapPoint(val x: Int, val y: Int) : AgentAction {
        override val label: String = "tap_point($x,$y)"
        override val toolType: AgentActionToolType = AgentActionToolType.GUI
        override val toolName: String = "gui.tap_point"
    }

    data class Swipe(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Long = 350L,
    ) : AgentAction {
        override val label: String = "swipe($startX,$startY->$endX,$endY,$durationMs)"
        override val toolType: AgentActionToolType = AgentActionToolType.GUI
        override val toolName: String = "gui.swipe"
    }

    data object Back : AgentAction {
        override val label: String = "back()"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.back"
    }

    data object Home : AgentAction {
        override val label: String = "home()"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.home"
    }

    data object Notifications : AgentAction {
        override val label: String = "notifications()"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.notifications"
    }

    data object QuickSettings : AgentAction {
        override val label: String = "quick_settings()"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.quick_settings"
    }

    data object Recents : AgentAction {
        override val label: String = "recents()"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.recents"
    }

    data class OpenSettings(val destination: String = "") : AgentAction {
        override val label: String = "open_settings(${destination.trim()})"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.open_settings"
    }

    data class ShareText(val text: String) : AgentAction {
        override val label: String = "share_text(${text.take(24)})"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.share_text"
    }

    data class CreateAlarm(
        val timeLabel: String,
        val message: String = "",
    ) : AgentAction {
        override val label: String = "create_alarm(${timeLabel.trim()})"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.create_alarm"
    }

    data class CreateTimer(
        val durationLabel: String,
        val message: String = "",
    ) : AgentAction {
        override val label: String = "create_timer(${durationLabel.trim()})"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.create_timer"
    }

    data object OpenStopwatch : AgentAction {
        override val label: String = "open_stopwatch()"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.open_stopwatch"
    }

    data class InsertCalendarEvent(
        val title: String,
        val details: String = "",
        val whenLabel: String = "",
    ) : AgentAction {
        override val label: String = "insert_calendar_event(${title.take(24)})"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.insert_calendar_event"
    }

    data class DialNumber(
        val number: String,
        val contactName: String = "",
    ) : AgentAction {
        override val label: String = "dial_number(${contactName.ifBlank { number }.take(20)})"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.dial_number"
    }

    data class DraftSms(
        val recipient: String,
        val body: String = "",
    ) : AgentAction {
        override val label: String = "draft_sms(${recipient.take(20)})"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.draft_sms"
    }

    data class LookupContact(
        val contactName: String,
        val limit: Int = 5,
    ) : AgentAction {
        override val label: String = "lookup_contact(${contactName.take(20)})"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.lookup_contact"
    }

    data class ReadNotifications(
        val packageName: String = "",
        val limit: Int = 6,
    ) : AgentAction {
        override val label: String = "read_notifications(${packageName.take(24)})"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.read_notifications"
    }

    data class ReplyNotification(
        val notificationKey: String,
        val replyText: String,
        val actionHint: String = "",
    ) : AgentAction {
        override val label: String = "reply_notification(${notificationKey.take(24)})"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.reply_notification"
    }

    data class MediaControl(
        val command: String,
    ) : AgentAction {
        override val label: String = "media_control(${command.take(16)})"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.media_control"
    }

    data class AdjustVolume(
        val direction: String = "raise",
        val stream: String = "music",
        val step: Int = 1,
    ) : AgentAction {
        override val label: String = "adjust_volume(${direction.take(12)},${stream.take(12)},$step)"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.adjust_volume"
    }

    data class OpenDevicePanel(
        val panel: String,
    ) : AgentAction {
        override val label: String = "open_device_panel(${panel.take(16)})"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.open_device_panel"
    }

    data class ReadDeviceStatus(
        val target: String = "",
    ) : AgentAction {
        override val label: String = "read_device_status(${target.take(16)})"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.read_device_status"
    }

    data class ReadCurrentLocation(
        val maxAgeMinutes: Int = PlatformCurrentLocationToolService.DEFAULT_MAX_AGE_MINUTES,
    ) : AgentAction {
        override val label: String = "read_current_location($maxAgeMinutes)"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.read_current_location"
    }

    data class SetBrightness(
        val level: String = "",
    ) : AgentAction {
        override val label: String = "set_brightness(${level.take(16)})"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.set_brightness"
    }

    data class SetDoNotDisturb(
        val mode: String = "on",
    ) : AgentAction {
        override val label: String = "set_do_not_disturb(${mode.take(16)})"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.set_do_not_disturb"
    }

    data class SetBatterySaver(
        val mode: String = "on",
    ) : AgentAction {
        override val label: String = "set_battery_saver(${mode.take(16)})"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.set_battery_saver"
    }

    data object OpenPowerDialog : AgentAction {
        override val label: String = "open_power_dialog()"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.open_power_dialog"
    }

    data class CaptureScreenshot(
        val note: String = "",
    ) : AgentAction {
        override val label: String = "capture_screenshot(${note.take(16)})"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.capture_screenshot"
    }

    data class CapturePhoto(
        val note: String = "",
    ) : AgentAction {
        override val label: String = "capture_photo(${note.take(16)})"
        override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM
        override val toolName: String = "system.capture_photo"
    }

    data object PressEnter : AgentAction {
        override val label: String = "press_enter()"
        override val toolType: AgentActionToolType = AgentActionToolType.GUI
        override val toolName: String = "gui.press_enter"
    }

    data object FocusPrimaryInput : AgentAction {
        override val label: String = "focus_primary_input()"
        override val toolType: AgentActionToolType = AgentActionToolType.SEMANTIC
        override val toolName: String = "semantic.focus_primary_input"
    }

    data class PopulatePrimaryInput(val text: String) : AgentAction {
        override val label: String = "populate_primary_input()"
        override val toolType: AgentActionToolType = AgentActionToolType.SEMANTIC
        override val toolName: String = "semantic.populate_primary_input"
    }

    data class SubmitPrimaryAction(val hint: String = "") : AgentAction {
        override val label: String = "submit_primary_action(${hint.trim()})"
        override val toolType: AgentActionToolType = AgentActionToolType.SEMANTIC
        override val toolName: String = "semantic.submit_primary_action"
    }

    data class DismissInterrupt(val hint: String = "") : AgentAction {
        override val label: String = "dismiss_interrupt(${hint.trim()})"
        override val toolType: AgentActionToolType = AgentActionToolType.SEMANTIC
        override val toolName: String = "semantic.dismiss_interrupt"
    }

    data class OpenBestCandidate(
        val targetText: String = "",
        val roleHint: String = "",
    ) : AgentAction {
        override val label: String = "open_best_candidate(target=${targetText.trim()};role=${roleHint.trim()})"
        override val toolType: AgentActionToolType = AgentActionToolType.SEMANTIC
        override val toolName: String = "semantic.open_best_candidate"
    }

    data class ScrollForMore(val direction: String = "down") : AgentAction {
        override val label: String = "scroll_for_more(${direction.trim().ifBlank { "down" }})"
        override val toolType: AgentActionToolType = AgentActionToolType.SEMANTIC
        override val toolName: String = "semantic.scroll_for_more"
    }

    data class ReturnToTargetApp(val packageName: String) : AgentAction {
        override val label: String = "return_to_target_app(${packageName.trim()})"
        override val toolType: AgentActionToolType = AgentActionToolType.SEMANTIC
        override val toolName: String = "semantic.return_to_target_app"
    }

    data class NavigateBack(
        val preferScroll: Boolean = false,
        val hint: String = "",
    ) : AgentAction {
        override val label: String =
            "navigate_back(prefer_scroll=$preferScroll;hint=${hint.trim()})"
        override val toolType: AgentActionToolType = AgentActionToolType.SEMANTIC
        override val toolName: String = "semantic.navigate_back"
    }

    data object Wait : AgentAction {
        override val label: String = "wait()"
        override val toolType: AgentActionToolType = AgentActionToolType.META
        override val toolName: String = "meta.wait"
    }

    data class Finish(val summary: String) : AgentAction {
        override val label: String = "finish()"
        override val toolType: AgentActionToolType = AgentActionToolType.META
        override val toolName: String = "meta.finish"
    }

    data class Fail(val reason: String) : AgentAction {
        override val label: String = "fail()"
        override val toolType: AgentActionToolType = AgentActionToolType.META
        override val toolName: String = "meta.fail"
    }
}

enum class AgentActionToolType {
    GUI,
    SYSTEM,
    ONLINE,
    SEMANTIC,
    META,
}

data class AgentDecision(
    val action: AgentAction,
    val reason: String,
    val rawResponse: String,
)

enum class RecoveryCategory {
    EMPTY_OBSERVATION,
    PARTIAL_TREE_MISS,
    WRONG_CONTEXT,
    SUBGOAL_MISMATCH,
    TARGET_MISMATCH,
    WRONG_LIST_ITEM,
    VISUAL_FALSE_POSITIVE,
    POPUP_BLOCKING,
    PERMISSION_REQUIRED,
    REPEATED_BACKTRACK,
    OVERSCROLLED,
    NO_PROGRESS,
    FINISH_REJECTED,
}

data class RecoveryDiagnosis(
    val category: RecoveryCategory,
    val summary: String,
    val suggestedAction: AgentAction? = null,
)

data class TaskStageVerificationResult(
    val verified: Boolean,
    val shouldImmediateReplan: Boolean,
    val message: String,
)

data class TaskResultField(
    val key: String,
    val label: String,
    val value: String,
)

data class TaskResultPayload(
    val intentType: String,
    val title: String,
    val summary: String,
    val highlights: List<String> = emptyList(),
    val fields: List<TaskResultField> = emptyList(),
)

data class AgentTurnRecord(
    val observationSignature: String,
    val pageState: String,
    val action: String,
    val result: String,
    val decisionReason: String = "",
    val recoveryCategory: String = "",
    val recoverySummary: String = "",
    val suggestedRecoveryAction: String = "",
)

data class AgentPlanningContext(
    val sessionId: String,
    val task: String,
    val turn: Int,
    val profileId: String,
    val targetPackageName: String,
    val indexedObservation: IndexedScreenObservation,
    val observation: ScreenObservation,
    val history: List<AgentTurnRecord>,
    val memoryContext: PlanningMemoryContext,
    val activeSkills: List<SkillContext>,
    val taskPlanState: TaskPlanState,
)

data class AgentExecutionResult(
    val message: String,
    val keepRunning: Boolean,
    val requiresObservationCheck: Boolean = false,
    val recommendedWaitMs: Long = 0L,
    val shouldImmediateReplan: Boolean = false,
    val resolvedElementId: String? = null,
    val groundingSource: String = "",
    val resolvedTargetText: String = "",
    val resolvedTargetBounds: String = "",
    val resolvedContainerId: String = "",
    val resolvedTargetStableId: String = "",
    val resolvedTargetAccessibilityLabel: String = "",
    val resolvedTargetCollectionPosition: String = "",
    val resolvedTargetVisualSignature: String = "",
    val resolvedTargetSpatialSignature: String = "",
    val resolvedParserRegionId: String = "",
    val resolvedSearchScopeId: String = "",
    val resolvedRegionHitSource: String = "",
    val resolvedRegionHitX: Float = 0f,
    val resolvedRegionHitY: Float = 0f,
    val expectedEntityHint: String = "",
    val targetObjectType: String = "",
    val targetContextHints: List<String> = emptyList(),
    val targetDescriptorTokens: List<String> = emptyList(),
    val targetVisualDescriptorTokens: List<String> = emptyList(),
    val entityFingerprintType: String = "",
    val entityFingerprintAnchors: List<String> = emptyList(),
    val toolRuntimePrompt: String = "",
    val toolRuntimeSummary: String = "",
    val toolRuntimeDetailLines: List<String> = emptyList(),
    val toolRuntimeState: String = "",
    val toolRuntimeQueuedMessage: String = "",
    val toolRuntimeProgressMessage: String = "",
    val toolRuntimeRejectedMessage: String = "",
    val toolRuntimeErrorMessage: String = "",
    val toolRuntimeSuccessMessage: String = "",
)
