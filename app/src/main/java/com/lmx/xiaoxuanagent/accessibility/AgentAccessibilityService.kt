package com.lmx.xiaoxuanagent.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.util.Log
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.lmx.xiaoxuanagent.agent.ActionExecutor
import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.AgentExecutionResult
import com.lmx.xiaoxuanagent.agent.AgentPlanner
import com.lmx.xiaoxuanagent.agent.IndexedScreenObservation
import com.lmx.xiaoxuanagent.agent.ObservationAugmenter
import com.lmx.xiaoxuanagent.agent.ObservationBuilder
import com.lmx.xiaoxuanagent.agent.OpenAiPlanner
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.PlannerProviderRouter
import com.lmx.xiaoxuanagent.agent.ScreenshotPayload
import com.lmx.xiaoxuanagent.agent.VisualPerceptionContext
import com.lmx.xiaoxuanagent.agent.VisualPerceptionEngine
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.AgentUiTakeoverReason
import com.lmx.xiaoxuanagent.runtime.DebugAgentStore
import com.lmx.xiaoxuanagent.runtime.RuntimeSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionExecutionCoordinatorStore
import com.lmx.xiaoxuanagent.runtime.SessionRuntime
import com.lmx.xiaoxuanagent.runtime.SessionRuntimeServiceDriver
import com.lmx.xiaoxuanagent.runtime.SessionRuntimeStore
import com.lmx.xiaoxuanagent.runtime.SessionRuntimeTurnDependencies
import com.lmx.xiaoxuanagent.runtime.SessionTargetAppLauncher
import com.lmx.xiaoxuanagent.AppLaunchResolver
import com.lmx.xiaoxuanagent.runtime.AppPageState
import com.lmx.xiaoxuanagent.runtime.VerificationCommand
import com.lmx.xiaoxuanagent.runtime.isTakeoverReason
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

class AgentAccessibilityService : AccessibilityService(), CurrentScreenObservationSource {
    companion object {
        private const val TAG = "TbAgent"
        private const val MAX_SCREENSHOT_EDGE = 960
        private const val JPEG_QUALITY = 70
        private const val PLANNER_TIMEOUT_MS = 95_000L
        @Volatile
        private var activeService: AgentAccessibilityService? = null

        fun current(): AgentAccessibilityService? = activeService
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val deviceActionMutex = Mutex()
    private val planner: AgentPlanner =
        PlannerProviderRouter(
            visionPlanner = OpenAiPlanner("openai_vision_primary"),
            textPlanner = OpenAiPlanner("openai_text_primary"),
        )
    private val runtimeDriver: SessionRuntimeServiceDriver by lazy {
        SessionRuntimeServiceDriver(
            owner = "accessibility_service",
            serviceScope = serviceScope,
            dependencies =
                SessionRuntimeTurnDependencies(
                    planner = planner,
                    plannerTimeoutMs = PLANNER_TIMEOUT_MS,
                    captureScreenshotPayload = { captureScreenshotPayload() },
                    buildVisualContext = { screenshot, observation -> buildVisualContext(screenshot, observation) },
                    observeCurrentScreen = { observeCurrentScreen() },
                    executeAction = { indexedObservation, action ->
                        deviceActionMutex.withLock {
                            ActionExecutor.execute(
                                service = this,
                                indexedObservation = indexedObservation,
                                action = action,
                            )
                        }
                    },
                    logLine = DebugAgentStore::appendLog,
                    updateScreenshotStatus = DebugAgentStore::updateScreenshotStatus,
                ),
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        AppRuntimeContext.init(applicationContext)
        DebugAgentStore.setAccessibilityServiceConnected(true)
        SessionTargetAppLauncher.register { packageName, reason ->
            bringTargetAppToForeground(packageName, reason)
        }
        Log.d(TAG, "service connected")
        DebugAgentStore.appendLog("无障碍服务 onServiceConnected。")
        SessionExecutionCoordinatorStore.sync(reason = "accessibility_service_connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = eventTypeName(event)
        val eventPackage = event?.packageName?.toString().orEmpty()
        val interestedEvent =
            DebugAgentStore.uiState.value.agentRunning ||
                DebugAgentStore.pendingCommand.value != null ||
                eventPackage == SessionRuntime.Planning.currentTaskProfile().packageName
        if (interestedEvent) {
            Log.d(
                TAG,
                "event received type=$eventType eventPkg=${eventPackage.ifBlank { "-" }} " +
                    "running=${DebugAgentStore.uiState.value.agentRunning} pending=${DebugAgentStore.pendingCommand.value?.label ?: "-"}",
            )
        }

        val quickSkipReason = quickSkipObservationReason(eventPackage)
        if (quickSkipReason != null) {
            DebugAgentStore.updateSnapshot(
                RuntimeSnapshot(
                    foregroundPackage = eventPackage,
                    pageState = AppPageState.Unknown,
                    eventType = eventType,
                    observationSignature = quickSkipReason,
                    visibleElementCount = 0,
                    hint = quickSkipReason,
                ),
            )
            return
        }

        val root = resolveBestRoot(eventPackage)
        if (root == null) {
            if (interestedEvent) {
                Log.w(
                    TAG,
                    "active root is null, type=$eventType eventPkg=${eventPackage.ifBlank { "-" }}",
                )
                DebugAgentStore.appendLog(
                    "收到事件但活动窗口 root 为空: type=$eventType, eventPkg=${eventPackage.ifBlank { "-" }}",
                )
            }
            return
        }
        val foregroundPackage = root.packageName?.toString().orEmpty()
        val skipReason = skipObservationReason(foregroundPackage)
        if (skipReason != null) {
            DebugAgentStore.updateSnapshot(
                RuntimeSnapshot(
                    foregroundPackage = foregroundPackage,
                    pageState = AppPageState.Unknown,
                    eventType = eventType,
                    observationSignature = skipReason,
                    visibleElementCount = 0,
                    hint = skipReason,
                ),
            )
            return
        }
        val pageState = detectPageState(foregroundPackage, root)
        val indexedObservation = ObservationBuilder.buildIndexed(root, pageState)

        DebugAgentStore.updateSnapshot(
            RuntimeSnapshot(
                foregroundPackage = foregroundPackage,
                pageState = pageState,
                eventType = eventType,
                observationSignature = indexedObservation.observation.signature,
                visibleElementCount = indexedObservation.observation.elements.size,
                hint =
                    "已识别页面状态 ${pageState.label}，signature=${indexedObservation.observation.signature}，" +
                        indexedObservation.observation.screenSummary,
            ),
        )

        if (foregroundPackage == SessionRuntime.Planning.currentTaskProfile().packageName && DebugAgentStore.pendingCommand.value != null) {
            handleManualCommand(root)
        }

        runtimeDriver.onObservation(indexedObservation)
    }

    private fun quickSkipObservationReason(
        eventPackage: String,
    ): String? {
        if (eventPackage.isBlank()) {
            return null
        }
        if (DebugAgentStore.pendingCommand.value != null) {
            return null
        }
        val assistantPackage = applicationContext.packageName
        val runtimeSession = SessionRuntimeStore.session()
        if (
            runtimeSession.running &&
            runtimeSession.statusModel.isTakeoverReason(AgentUiTakeoverReason.WAITING_EXTERNAL)
        ) {
            val targetPackage = runtimeSession.targetPackageName
            if (
                targetPackage.isNotBlank() &&
                targetPackage != eventPackage
            ) {
                return "waiting_external_skip:$eventPackage"
            }
        }
        if (eventPackage != assistantPackage) {
            return null
        }
        val currentTaskPackage = SessionRuntime.Planning.currentTaskProfile().packageName
        if (currentTaskPackage == assistantPackage) {
            return null
        }
        if (runtimeSession.targetPackageName == assistantPackage) {
            return null
        }
        return "assistant_shell_skip"
    }

    private fun skipObservationReason(
        foregroundPackage: String,
    ): String? {
        val pendingCommand = DebugAgentStore.pendingCommand.value
        if (pendingCommand != null) {
            return null
        }
        val assistantPackage = applicationContext.packageName
        val runtimeSession = SessionRuntimeStore.session()
        if (
            runtimeSession.running &&
            runtimeSession.statusModel.isTakeoverReason(AgentUiTakeoverReason.WAITING_EXTERNAL)
        ) {
            val targetPackage = runtimeSession.targetPackageName
            if (
                targetPackage.isNotBlank() &&
                targetPackage != foregroundPackage
            ) {
                return "waiting_external_skip:$foregroundPackage"
            }
        }
        if (foregroundPackage != assistantPackage) {
            return null
        }
        val currentTaskPackage = SessionRuntime.Planning.currentTaskProfile().packageName
        if (currentTaskPackage == assistantPackage) {
            return null
        }
        if (runtimeSession.targetPackageName == assistantPackage) {
            return null
        }
        return "assistant_shell_skip"
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        DebugAgentStore.setAccessibilityServiceConnected(false)
        if (activeService === this) {
            activeService = null
            SessionTargetAppLauncher.unregister()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * 把目标 App 拉到前台。后台恢复（心跳/boot）成功后由 [SessionTargetAppLauncher] 调用——
     * 无障碍服务在后台不受 Android 后台启动 Activity 限制，可直接 startActivity，
     * 复用 [ActionExecutor.executeLaunchApp] 同款 NEW_TASK Intent 机制。
     */
    fun bringTargetAppToForeground(
        packageName: String,
        reason: String,
    ): Boolean {
        if (packageName.isBlank() || packageName == applicationContext.packageName) {
            return false
        }
        val intent =
            AppLaunchResolver.resolve(packageManager, packageName) ?: run {
                DebugAgentStore.appendLog("后台恢复：无法解析目标 App 启动 Intent pkg=$packageName")
                return false
            }
        return try {
            startActivity(intent)
            DebugAgentStore.appendLog("后台恢复：已自动拉起目标 App pkg=$packageName reason=$reason")
            true
        } catch (error: Exception) {
            DebugAgentStore.appendLog("后台恢复：拉起目标 App 失败 pkg=$packageName err=${error.message}")
            false
        }
    }

    suspend fun executeExternalAction(
        action: AgentAction,
        preferredPackageName: String = "",
    ): AgentExecutionResult {
        val indexedObservation =
            observeCurrentScreen(preferredPackageName.takeIf { it.isNotBlank() })
                ?: if (action.toolType == com.lmx.xiaoxuanagent.agent.AgentActionToolType.SYSTEM) {
                    emptyIndexedObservation(preferredPackageName)
                } else {
                    return AgentExecutionResult("当前无法获取屏幕观察，补偿动作未执行。", keepRunning = false)
                }
        return deviceActionMutex.withLock {
            ActionExecutor.execute(
                service = this,
                indexedObservation = indexedObservation,
                action = action,
            )
        }
    }

    override suspend fun inspectCurrentScreen(
        preferredPackageName: String,
    ): IndexedScreenObservation? =
        observeCurrentScreen(
            preferredPackageName = preferredPackageName.takeIf { it.isNotBlank() },
            preferTaskProfileFallback = false,
        )

    private fun handleManualCommand(root: android.view.accessibility.AccessibilityNodeInfo) {
        when (val command = DebugAgentStore.consumePendingCommand()) {
            is VerificationCommand.VerifySearchInput -> {
                if (command.query.isBlank()) {
                    DebugAgentStore.updateError("query 不能为空。")
                    return
                }
                DebugAgentStore.appendLog("执行手动命令: 验证搜索输入, query=${command.query}")
                DebugAgentStore.updateResult(ShoppingFlowVerifier.verifySearchInput(root, command.query))
            }

            VerificationCommand.VerifyResultClick -> {
                DebugAgentStore.appendLog("执行手动命令: 验证结果页点击")
                DebugAgentStore.updateResult(ShoppingFlowVerifier.verifyResultClick(root))
            }

            null -> Unit
        }
    }

    private suspend fun observeCurrentScreen(
        preferredPackageName: String? = null,
        preferTaskProfileFallback: Boolean = true,
    ): IndexedScreenObservation? {
        val preferredPackage =
            when {
                !preferredPackageName.isNullOrBlank() -> preferredPackageName
                preferTaskProfileFallback -> SessionRuntime.Planning.currentTaskProfile().packageName
                else -> null
            }
        val root = resolveBestRoot(preferredPackage) ?: return null
        val foregroundPackage = root.packageName?.toString().orEmpty()
        val pageState = detectPageState(foregroundPackage, root)
        val indexedObservation = ObservationBuilder.buildIndexed(root, pageState)
        val screenshot = captureScreenshotPayload()
        val visualContext = buildVisualContext(screenshot, indexedObservation.observation)
        return if (hasActionableVisualSignals(visualContext)) {
            ObservationAugmenter.augmentIndexedObservation(
                indexedObservation = indexedObservation,
                visualContext = visualContext,
                blockedTexts = buildTaskEchoBlockedTexts(SessionRuntime.Planning.currentTask()),
            )
        } else {
            indexedObservation
        }
    }

    private fun emptyIndexedObservation(
        packageNameHint: String,
    ): IndexedScreenObservation =
        IndexedScreenObservation(
            observation =
                ScreenObservation(
                    packageName = packageNameHint,
                    pageState = "UNKNOWN",
                    signature = "",
                    screenSummary = "",
                    topTexts = emptyList(),
                    primaryEditableId = null,
                    focusedElementId = null,
                    defaultScrollableId = null,
                    primaryInterruptActionId = null,
                    interruptiveHints = emptyList(),
                    elements = emptyList(),
                ),
            nodesById = emptyMap(),
        )

    private fun resolveBestRoot(
        preferredPackage: String?,
    ): AccessibilityNodeInfo? {
        val directRoot = rootInActiveWindow
        val directScore = scoreRoot(directRoot, preferredPackage)

        val bestWindowRoot =
            windows.orEmpty()
                .asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { window ->
                    val root = window.root ?: return@mapNotNull null
                    RootCandidate(
                        root = root,
                        score =
                            scoreRoot(root, preferredPackage) +
                                (if (window.isActive) 120 else 0) +
                                (if (window.isFocused) 80 else 0),
                    )
                }
                .maxByOrNull { it.score }

        return when {
            bestWindowRoot == null -> directRoot
            directRoot == null -> bestWindowRoot.root
            bestWindowRoot.score > directScore -> bestWindowRoot.root
            else -> directRoot
        }
    }

    private fun scoreRoot(
        root: AccessibilityNodeInfo?,
        preferredPackage: String?,
    ): Int {
        if (root == null) return Int.MIN_VALUE
        val pkg = root.packageName?.toString().orEmpty()
        val nodeCount = countNodes(root, maxNodes = 180)
        var score = nodeCount * 3
        if (preferredPackage != null && pkg == preferredPackage) {
            score += 160
        }
        if (pkg.isNotBlank()) {
            score += 24
        }
        return score
    }

    private fun countNodes(
        root: AccessibilityNodeInfo,
        maxNodes: Int,
    ): Int {
        var count = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && count < maxNodes) {
            val node = queue.removeFirst()
            count += 1
            repeat(node.childCount) { index ->
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return count
    }

    private fun buildTaskEchoBlockedTexts(task: String): List<String> =
        buildList {
            val normalizedTask = task.trim()
            if (normalizedTask.length >= 6) {
                add(normalizedTask)
            }
            val compactTask = normalizedTask.replace("\\s+".toRegex(), "")
            if (compactTask.length >= 6 && compactTask != normalizedTask) {
                add(compactTask)
            }
        }

    private fun hasActionableVisualSignals(
        visualContext: VisualPerceptionContext,
    ): Boolean =
        visualContext.captureAvailable &&
            (
                visualContext.groundedTexts.isNotEmpty() ||
                    visualContext.ocrTexts.isNotEmpty() ||
                    visualContext.visualHints.isNotEmpty()
            )

    private suspend fun buildVisualContext(
        screenshot: ScreenshotPayload?,
        observation: com.lmx.xiaoxuanagent.agent.ScreenObservation,
    ): VisualPerceptionContext =
        VisualPerceptionEngine.analyze(
            screenshot = screenshot,
            observation = observation,
        )

    private suspend fun captureScreenshotPayload(): ScreenshotPayload? {
        val screenshotResult = takeAccessibilityScreenshot() ?: run {
            DebugAgentStore.appendLog("截图采集失败：takeScreenshot 返回空。")
            return null
        }

        val hardwareBuffer: HardwareBuffer = screenshotResult.hardwareBuffer
        return try {
            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshotResult.colorSpace)
                ?: run {
                    DebugAgentStore.appendLog("截图采集失败：无法包装 HardwareBuffer。")
                    return null
                }
            val scaledBitmap = scaleBitmap(bitmap)
            val output = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            val bytes = output.toByteArray()
            output.close()
            val payloadWidth = scaledBitmap.width
            val payloadHeight = scaledBitmap.height
            if (scaledBitmap !== bitmap) {
                scaledBitmap.recycle()
            }
            bitmap.recycle()
            ScreenshotPayload(
                mimeType = "image/jpeg",
                base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP),
                width = payloadWidth,
                height = payloadHeight,
            )
        } catch (error: Throwable) {
            DebugAgentStore.appendLog("截图采集异常: ${error.javaClass.simpleName}: ${error.message}")
            null
        } finally {
            hardwareBuffer.close()
        }
    }

    private suspend fun takeAccessibilityScreenshot(): AccessibilityService.ScreenshotResult? =
        suspendCancellableCoroutine { continuation ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            if (continuation.isActive) {
                                continuation.resume(screenshot)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "takeScreenshot failed, errorCode=$errorCode")
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    },
                )
            } catch (error: Throwable) {
                Log.w(TAG, "takeScreenshot threw", error)
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }

    private fun scaleBitmap(source: Bitmap): Bitmap {
        val maxEdge = maxOf(source.width, source.height)
        if (maxEdge <= MAX_SCREENSHOT_EDGE) {
            return source
        }
        val scale = MAX_SCREENSHOT_EDGE.toFloat() / maxEdge.toFloat()
        val targetWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    private fun detectPageState(
        foregroundPackage: String,
        root: android.view.accessibility.AccessibilityNodeInfo,
    ): AppPageState {
        return SessionRuntime.Planning.currentTaskProfile().detectPageState(foregroundPackage, root)
    }

    private fun eventTypeName(event: AccessibilityEvent?): String {
        return when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "VIEW_TEXT_CHANGED"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
            null -> "UNKNOWN"
            else -> "EVENT_${event.eventType}"
        }
    }
    private data class RootCandidate(
        val root: AccessibilityNodeInfo,
        val score: Int,
    )
}
