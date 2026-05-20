package com.lmx.xiaoxuanagent.runtime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.lmx.xiaoxuanagent.BuildConfig
import com.lmx.xiaoxuanagent.assistantos.AssistantOsController
import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTask
import com.lmx.xiaoxuanagent.assistantos.AssistantShellIntentRouter
import com.lmx.xiaoxuanagent.entry.AssistantEntryController
import com.lmx.xiaoxuanagent.agent.IndexedScreenObservation
import com.lmx.xiaoxuanagent.agent.ResumeContext
import com.lmx.xiaoxuanagent.agent.TaskResultPayload
import com.lmx.xiaoxuanagent.harness.HarnessStore
import com.lmx.xiaoxuanagent.safety.PendingSafetyConfirmation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update

object DebugAgentStore : DebugAgentRuntimeCallbacks by DebugAgentRuntimeCallbacksImpl {
    private const val MAX_LOG_LINES = 80
    private const val TAG = "TbAgent"
    private const val STATUS_IDLE = AgentUiStatus.IDLE
    private const val STATUS_RUNNING = AgentUiStatus.RUNNING
    private const val STATUS_PLANNING = AgentUiStatus.PLANNING
    private const val STATUS_PAUSED = AgentUiStatus.PAUSED
    private const val STATUS_AWAITING_CONFIRMATION = AgentUiStatus.AWAITING_CONFIRMATION
    private const val STATUS_WAITING = AgentUiStatus.WAITING
    private const val STATUS_STOPPED = AgentUiStatus.STOPPED
    private const val STATUS_FAILED = AgentUiStatus.FAILED
    private const val STATUS_COMPLETED = AgentUiStatus.COMPLETED
    private const val STATUS_MAX_TURNS_REACHED = AgentUiStatus.MAX_TURNS_REACHED
    private const val STATUS_WAITING_EXTERNAL = AgentUiStatus.WAITING_EXTERNAL
    private const val ACCESSIBILITY_SERVICE_ID =
        "com.lmx.xiaoxuanagent/com.lmx.xiaoxuanagent.accessibility.AgentAccessibilityService"

    private val _uiState = MutableStateFlow(DebugUiState())
    val uiState: StateFlow<DebugUiState> = _uiState.asStateFlow()

    private val _pendingCommand = MutableStateFlow<VerificationCommand?>(null)
    val pendingCommand: StateFlow<VerificationCommand?> = _pendingCommand.asStateFlow()

    private val _openAccessibilitySettings = MutableStateFlow(false)
    val openAccessibilitySettings: StateFlow<Boolean> = _openAccessibilitySettings.asStateFlow()

    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var dashboardSnapshot = DebugDashboardSnapshot()
    @Volatile
    private var dashboardRefreshJob: Job? = null

    init {
        SessionRuntime.installBridge(
            CompositeSessionBridge(
                DebugSessionBridge,
                SessionBridgeProtocolBridge,
                RemoteBridgeProtocolBridge,
            ),
        )
        storeScope.launch {
            uiState.collectLatest { state ->
                AssistantEntryController.syncNotification(state)
            }
        }
    }

    fun submit(command: VerificationCommand) {
        val query = when (command) {
            is VerificationCommand.VerifySearchInput -> command.query
            VerificationCommand.VerifyResultClick -> _uiState.value.query
        }
        updateUiInternal {
            it.withResultPanel(
                it.result.copy(
                    lastAction = command.label,
                    lastResult = "",
                    lastError = "",
                    hint = "等待无障碍服务执行 ${command.label}",
                ),
            ).copy(
                query = query,
                plannerMode = plannerLabel(),
            )
        }
        _pendingCommand.value = command
        appendLog("手动命令入队: ${command.label}, query=${query.ifBlank { "-" }}")
    }

    fun updateSnapshot(snapshot: RuntimeSnapshot) {
        var shouldLog = false
        updateUiInternal {
            val nextHint = if (it.agentRunning) it.result.hint else snapshot.hint
            shouldLog =
                it.foregroundPackage != snapshot.foregroundPackage ||
                    it.pageState != snapshot.pageState ||
                    it.observationSignature != snapshot.observationSignature
            if (
                it.foregroundPackage == snapshot.foregroundPackage &&
                it.pageState == snapshot.pageState &&
                it.observationSignature == snapshot.observationSignature &&
                it.visibleElementCount == snapshot.visibleElementCount &&
                it.result.hint == nextHint
            ) {
                return@updateUiInternal it
            }
            it
                .copy(
                    foregroundPackage = snapshot.foregroundPackage,
                    pageState = snapshot.pageState,
                    lastEventType = snapshot.eventType,
                    observationSignature = snapshot.observationSignature,
                    visibleElementCount = snapshot.visibleElementCount,
                    plannerMode = plannerLabel(),
                )
                .withResultPanel(
                    it.result.copy(
                        hint = nextHint,
                    ),
                )
        }
        if (shouldLog) {
            appendLog(
                "页面更新: event=${snapshot.eventType}, pkg=${snapshot.foregroundPackage.ifBlank { "-" }}, " +
                    "state=${snapshot.pageState.label}, sig=${snapshot.observationSignature}, elements=${snapshot.visibleElementCount}",
            )
        }
    }

    fun updateResult(result: String) {
        updateUiInternal {
            it.withResultPanel(
                it.result.copy(
                    lastResult = result,
                    lastError = "",
                    hint = result,
                ),
            )
        }
        _pendingCommand.value = null
        appendLog("手动命令完成: $result")
    }

    fun updateError(error: String) {
        updateUiInternal {
            it.withResultPanel(
                it.result.copy(
                    lastError = error,
                    hint = error,
                ),
            )
        }
        _pendingCommand.value = null
        appendLog("手动命令失败: $error")
    }

    fun consumePendingCommand(): VerificationCommand? {
        val command = _pendingCommand.value
        _pendingCommand.value = null
        return command
    }

    fun consumeAccessibilitySettingsRequest() {
        _openAccessibilitySettings.value = false
    }

    fun setAccessibilityServiceConnected(connected: Boolean) {
        val oldValue = _uiState.value.accessibilityConnected
        updateUiInternal {
            it.copy(accessibilityConnected = connected)
        }
        if (oldValue != connected) {
            appendLog(
                if (connected) {
                    "无障碍服务已连接。"
                } else {
                    "无障碍服务已断开。"
                },
            )
        }
    }

    fun hydrateDashboard() {
        syncAccessibilityServiceConnection()
        refreshDashboardInternal(force = false)
    }

    suspend fun startAgent(
        task: String,
        entrySource: String = "app",
    ): Boolean {
        syncAccessibilityServiceConnection()
        val trimmedTask = task.trim()
        val sessionId = "session_${System.currentTimeMillis()}"
        if (trimmedTask.isBlank()) {
            SessionRuntimePreflightSupport.publishBlocked(
                sessionId = sessionId,
                task = "",
                entrySource = entrySource,
                status = AgentUiStatus.INPUT_REQUIRED,
                error = "请输入任务内容。",
                hint = "请输入任务内容后再启动自动任务。",
                reason = "preflight_input_required",
            )
            appendLog("启动自动任务失败: task 为空。")
            return false
        }
        if (!_uiState.value.accessibilityConnected) {
            SessionRuntimePreflightSupport.publishBlocked(
                sessionId = sessionId,
                task = trimmedTask,
                entrySource = entrySource,
                status = AgentUiStatus.ACCESSIBILITY_REQUIRED,
                error = "无障碍服务未连接，请先开启。",
                hint = "无障碍服务未连接，请先开启后再启动自动任务。",
                reason = "preflight_accessibility_required",
            )
            _openAccessibilitySettings.value = true
            appendLog("启动自动任务失败: 无障碍服务未连接，已请求打开设置。")
            return false
        }
        return SessionRuntimeKernel.startTask(
            sessionId = sessionId,
            task = trimmedTask,
            entrySource = entrySource,
        ).success
    }

    fun refreshEntrySurfaces() {
        AssistantEntryController.syncNotification(_uiState.value)
    }

    fun syncAccessibilityServiceConnection(): Boolean {
        val context = AppRuntimeContext.get() ?: return _uiState.value.accessibilityConnected
        val accessibilityManager =
            context.getSystemService(AccessibilityManager::class.java)
                ?: return _uiState.value.accessibilityConnected
        val enabledServices =
            accessibilityManager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
            )
        val connected =
            enabledServices.any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo
                val packageName = serviceInfo?.packageName.orEmpty()
                val className = serviceInfo?.name.orEmpty()
                "$packageName/$className" == ACCESSIBILITY_SERVICE_ID
            }
        setAccessibilityServiceConnected(connected)
        return connected
    }

    fun handleMaxTurnsReached() = SessionRuntime.handleMaxTurnsReached()

    fun updateScreenshotStatus(status: String) {
        updateUiInternal {
            it.copy(screenshotStatus = status)
        }
    }

    internal fun plannerLabel(): String =
        if (BuildConfig.AGENT_API_BASE_URL.isBlank() || BuildConfig.AGENT_MODEL.isBlank()) {
            "LLM_UNCONFIGURED"
        } else {
            BuildConfig.AGENT_MODEL
        }

    internal fun updateUiInternal(
        runtimeState: SessionRuntimeState = SessionRuntimeStore.read(),
        reducer: (DebugUiState) -> DebugUiState,
    ) {
        _uiState.update { current ->
            current.withDashboardSnapshot(
                dashboardSnapshot,
            ).let {
                projectDebugRuntimeState(
                    state = reducer(it),
                    runtimeState = runtimeState,
                    timeFormatter = timeFormatter,
                )
            }
        }
    }

    internal fun refreshRuntimeArtifactsFromReplayInternal(
        sessionId: String,
        sessionSnapshot: ReplaySessionSnapshot? = null,
    ) {
        if (sessionId.isBlank()) {
            return
        }
        val replayArtifacts =
            formatReplayArtifactsInternal(
                sessionSnapshot?.recentArtifactGroups
                    ?: ReplayStore.readSessionSnapshot(sessionId)?.recentArtifactGroups.orEmpty(),
            )
        if (replayArtifacts.isEmpty()) {
            return
        }
        updateUiInternal { current ->
            current.copy(recentRuntimeArtifacts = replayArtifacts)
        }
    }

    internal fun formatReplayArtifactsInternal(
        groups: List<ReplayTurnArtifactGroup>,
    ): List<String> =
        groups.flatMap { group ->
            buildList {
                add("turn=${group.turn}")
                group.artifacts.forEach { artifact ->
                    add("  ${artifact.type}: ${artifact.summary.ifBlank { "-" }}")
                    artifact.previewLines.forEach { preview ->
                        add("    $preview")
                    }
                }
            }
        }

    fun appendLog(message: String) {
        val line = "${timeFormatter.format(Date())} | $message"
        Log.d(TAG, message)
        _uiState.update {
            it.copy(recentLogs = (listOf(line) + it.recentLogs).take(MAX_LOG_LINES))
        }
    }

    internal fun refreshDashboardInternal(force: Boolean) {
        val activeJob = dashboardRefreshJob
        if (!force && activeJob?.isActive == true) {
            return
        }
        dashboardRefreshJob?.cancel()
        dashboardRefreshJob =
            storeScope.launch {
                val snapshot = readDebugDashboardSnapshot()
                dashboardSnapshot = snapshot
                _uiState.update { current ->
                    projectDebugRuntimeState(
                        state = current.withDashboardSnapshot(snapshot),
                        runtimeState = SessionRuntimeStore.read(),
                        timeFormatter = timeFormatter,
                    )
                }
            }
    }

    internal fun bringAssistantToFrontInternal(
        reason: String,
        summaryOverride: String? = null,
    ) {
        val context = AppRuntimeContext.get()
        if (context == null) {
            appendLog("结束后回前台失败: context unavailable, reason=$reason")
            return
        }
        if (_uiState.value.foregroundPackage == context.packageName) {
            appendLog("结束后无需切回前台: assistant already foreground, reason=$reason")
            return
        }
        val uiState = _uiState.value
        val summary =
            summaryOverride ?: buildString {
                append(
                    when (reason) {
                        "completed" -> "任务已完成"
                        "error" -> "任务已结束"
                        "max_turns" -> "任务已停止"
                        "loop_protection" -> "任务已停止"
                        "awaiting_confirmation" -> "高风险动作待确认"
                        else -> "任务已返回"
                    },
                )
                val resultPanel = uiState.result
                val detail =
                    when {
                        resultPanel.summary.isNotBlank() -> {
                            buildString {
                                append(resultPanel.title.ifBlank { resultPanel.intentType.ifBlank { "结果" } })
                                append(" | ")
                                append(resultPanel.summary)
                            }
                        }

                        else -> resultPanel.lastResult.ifBlank { resultPanel.hint }
                    }.trim()
                if (detail.isNotBlank()) {
                    append(" | ").append(detail.take(72))
                }
            }
        val intent = AssistantShellIntentRouter.createReturnIntent(context, summary)
        runCatching {
            context.startActivity(intent)
        }.onSuccess {
            appendLog("结束后已切回小轩助手, reason=$reason")
        }.onFailure { throwable ->
            appendLog("结束后回前台异常: reason=$reason, error=${throwable.message.orEmpty()}")
        }
    }

    internal fun formatUpdatedAtLabel(
        updatedAtMs: Long,
    ): String =
        if (updatedAtMs > 0L) {
            timeFormatter.format(Date(updatedAtMs))
        } else {
            "-"
        }
}
