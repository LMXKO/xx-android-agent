package com.lmx.xiaoxuanagent.assistantos

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lmx.xiaoxuanagent.R
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.SessionRuntime
import java.util.Locale

object AssistantVoiceRuntimeController {
    private const val EXTRA_SOURCE = "voice_runtime_source"
    private const val EXTRA_NOTE = "voice_runtime_note"

    const val ACTION_START = "com.lmx.xiaoxuanagent.action.voice.START"
    const val ACTION_STOP = "com.lmx.xiaoxuanagent.action.voice.STOP"
    const val ACTION_TOGGLE = "com.lmx.xiaoxuanagent.action.voice.TOGGLE"
    const val ACTION_SYNC = "com.lmx.xiaoxuanagent.action.voice.SYNC"

    fun start(
        context: Context,
        source: String = "voice",
        note: String = "",
    ) {
        dispatch(context, ACTION_START, source, note)
    }

    fun stop(
        context: Context,
        source: String = "voice",
        note: String = "",
    ) {
        dispatch(context, ACTION_STOP, source, note)
    }

    fun toggle(
        context: Context,
        source: String = "voice",
        note: String = "",
    ) {
        dispatch(context, ACTION_TOGGLE, source, note)
    }

    fun sync(
        context: Context,
        source: String = "voice",
    ) {
        dispatch(context, ACTION_SYNC, source, "")
    }

    private fun dispatch(
        context: Context,
        action: String,
        source: String,
        note: String,
    ) {
        val intent =
            Intent(context, AssistantVoiceRuntimeService::class.java).apply {
                this.action = action
                putExtra(EXTRA_SOURCE, source)
                putExtra(EXTRA_NOTE, note)
            }
        if (action == ACTION_SYNC) {
            context.startService(intent)
        } else {
            ContextCompat.startForegroundService(context, intent)
        }
    }

    internal fun readSource(intent: Intent?): String = intent?.getStringExtra(EXTRA_SOURCE).orEmpty()

    internal fun readNote(intent: Intent?): String = intent?.getStringExtra(EXTRA_NOTE).orEmpty()
}

class AssistantVoiceRuntimeService : Service() {
    private data class VoiceRuntimeCommand(
        val transcript: String,
        val autoAction: String,
        val interactionMode: String,
        val pendingConfirmation: String,
        val spokenSummary: String,
        val lastHeardCommandType: String,
        val autoExecuteEligible: Boolean,
    )

    private val mainHandler = android.os.Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var continuousRequested = false
    private var listening = false
    private var ttsReady = false
    private var deferredSpeech: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppRuntimeContext.init(this)
        ensureChannel()
        ensureTextToSpeech()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        AppRuntimeContext.init(this)
        val source = AssistantVoiceRuntimeController.readSource(intent).ifBlank { "voice_runtime" }
        val note = AssistantVoiceRuntimeController.readNote(intent)
        when (intent?.action) {
            AssistantVoiceRuntimeController.ACTION_STOP -> stopContinuousVoice(note.ifBlank { "持续语音入口已停止监听。" })
            AssistantVoiceRuntimeController.ACTION_SYNC -> {
                syncEnvironment(refreshShell = true)
                if (!shouldKeepRuntimeAlive()) {
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
            }
            AssistantVoiceRuntimeController.ACTION_TOGGLE -> {
                if (continuousRequested || listening) {
                    stopContinuousVoice(note.ifBlank { "持续语音入口已停止监听。" })
                } else {
                    startContinuousVoice(source = source, note = note)
                }
            }

            else -> startContinuousVoice(source = source, note = note)
        }
        return if (shouldKeepRuntimeAlive()) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        continuousRequested = false
        listening = false
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.runCatching {
            stopListening()
            cancel()
            destroy()
        }
        speechRecognizer = null
        textToSpeech?.runCatching {
            stop()
            shutdown()
        }
        textToSpeech = null
        ttsReady = false
        deferredSpeech = ""
        releaseRuntimeNotification()
        super.onDestroy()
    }

    private fun startContinuousVoice(
        source: String,
        note: String,
    ) {
        AssistantOsController.recordEntry(
            surface = AssistantEntrySurface.VOICE,
            action = "voice_runtime_start",
            summary = source.ifBlank { note }.ifBlank { "voice_runtime_service" }.take(80),
        )
        if (SessionRuntime.State.runtimeState().safety.awaitingConfirmation) {
            AssistantVoiceInteractionStore.markBlocked(
                reason = "voice_gated_by_approval",
                summary = "当前还有审批待处理，持续语音入口先保持阻断。",
            )
            AssistantVoiceInteractionStore.updateRuntimeMode(
                runtimeOwner = "voice_runtime_service",
                continuousMode = false,
            )
            refreshShell("voice_runtime_blocked")
            updateNotification()
            stopSelf()
            return
        }
        val sync = syncEnvironment(refreshShell = false)
        if (sync.availabilitySummary != "voice_ready") {
            AssistantVoiceInteractionStore.markBlocked(
                reason = sync.availabilitySummary.ifBlank { "voice_unavailable" },
                summary = sync.summary.ifBlank { "持续语音入口当前不可用。" },
            )
            AssistantVoiceInteractionStore.updateRuntimeMode(
                runtimeOwner = "voice_runtime_service",
                continuousMode = false,
            )
            refreshShell("voice_runtime_unavailable")
            updateNotification()
            stopSelf()
            return
        }
        continuousRequested = true
        AssistantVoiceInteractionStore.markListening(
            activeSessionId = SessionRuntime.State.runtimeState().session.sessionId,
            note = note.ifBlank { "语音入口已切到后台持续监听。" },
            runtimeOwner = "voice_runtime_service",
            continuousMode = true,
        )
        val recognizer =
            speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { created ->
                created.setRecognitionListener(buildRecognitionListener())
                speechRecognizer = created
            }
        runCatching {
            listening = true
            startForeground(NOTIFICATION_ID, buildNotification())
            recognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "说出你想让小轩执行的任务")
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                },
            )
        }.onFailure {
            listening = false
            continuousRequested = false
            AssistantVoiceInteractionStore.markError(
                "启动持续语音失败: ${it.message.orEmpty().ifBlank { "unknown" }}",
            )
            refreshShell("voice_runtime_start_failed")
            updateNotification()
            releaseRuntimeNotification()
            stopSelf()
        }
        updateNotification()
        refreshShell("voice_runtime_started")
    }

    private fun stopContinuousVoice(
        summary: String,
    ) {
        continuousRequested = false
        listening = false
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.runCatching {
            stopListening()
            cancel()
        }
        AssistantVoiceInteractionStore.reset(summary)
        refreshShell("voice_runtime_stopped")
        releaseRuntimeNotification()
        stopSelf()
    }

    private fun syncEnvironment(
        refreshShell: Boolean,
    ): AssistantVoiceInteractionSnapshot {
        val snapshot =
            AssistantVoiceInteractionStore.syncEnvironment(
                available = SpeechRecognizer.isRecognitionAvailable(this),
                permissionGranted =
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED,
            )
        if (refreshShell) {
            refreshShell("voice_runtime_sync")
            updateNotification()
        }
        return snapshot
    }

    private fun buildRecognitionListener(): RecognitionListener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                AssistantVoiceInteractionStore.markListening(
                    activeSessionId = SessionRuntime.State.runtimeState().session.sessionId,
                    note = "后台持续语音已就绪，开始监听。",
                    runtimeOwner = "voice_runtime_service",
                    continuousMode = true,
                )
                refreshShell("voice_runtime_ready")
                updateNotification()
            }

            override fun onBeginningOfSpeech() {
                AssistantVoiceInteractionStore.markListening(
                    activeSessionId = SessionRuntime.State.runtimeState().session.sessionId,
                    note = "后台持续语音正在监听输入。",
                    runtimeOwner = "voice_runtime_service",
                    continuousMode = true,
                )
                updateNotification()
            }

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                listening = false
            }

            override fun onError(error: Int) {
                listening = false
                val message = voiceRecognitionErrorMessage(error)
                if (continuousRequested && error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    AssistantVoiceInteractionStore.markListening(
                        activeSessionId = SessionRuntime.State.runtimeState().session.sessionId,
                        note = "后台持续语音待命中，等待下一次说话。",
                        runtimeOwner = "voice_runtime_service",
                        continuousMode = true,
                    )
                    restartListening("speech_timeout")
                    updateNotification()
                    return
                }
                AssistantVoiceInteractionStore.markError(message)
                refreshShell("voice_runtime_error")
                updateNotification()
                if (continuousRequested && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    restartListening("error_$error")
                } else {
                    releaseRuntimeNotification()
                    stopSelf()
                }
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val text = readVoiceResult(results)
                if (text.isBlank()) {
                    if (continuousRequested) {
                        restartListening("empty_result")
                    }
                    return
                }
                val command = resolveVoiceRuntimeCommand(text)
                AssistantVoiceInteractionStore.markFinal(
                    transcript = command.transcript,
                    autoAction = command.autoAction,
                    source = "voice_runtime_service",
                    pendingDispatch = true,
                    interactionMode = command.interactionMode,
                    pendingConfirmation = command.pendingConfirmation,
                    spokenSummary = command.spokenSummary,
                    lastHeardCommandType = command.lastHeardCommandType,
                    autoExecuteEligible = command.autoExecuteEligible,
                )
                speakFeedback(command.spokenSummary)
                if (command.autoAction == "voice_disable") {
                    refreshShell("voice_runtime_disable")
                    updateNotification()
                    stopContinuousVoice("持续语音入口已按语音指令停止监听。")
                    return
                }
                refreshShell("voice_runtime_result")
                updateNotification()
                if (continuousRequested) {
                    restartListening("result")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = readVoiceResult(partialResults)
                if (partial.isNotBlank()) {
                    AssistantVoiceInteractionStore.updatePartial(partial)
                    updateNotification()
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

    private fun restartListening(
        reason: String,
    ) {
        if (!continuousRequested) return
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed(
            {
                if (!continuousRequested) return@postDelayed
                startContinuousVoice(source = "voice_runtime_restart", note = "后台持续语音已自动恢复监听。($reason)")
            },
            500L,
        )
    }

    private fun refreshShell(
        reason: String,
    ) {
        AssistantProductShellController.sync(
            assistantSnapshot = AssistantOsController.snapshot(),
            reason = reason,
        )
        AssistantMobileEntryController.sync(this)
    }

    private fun updateNotification() {
        if (!shouldKeepRuntimeAlive()) {
            releaseRuntimeNotification()
            return
        }
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun shouldKeepRuntimeAlive(): Boolean = continuousRequested || listening

    private fun releaseRuntimeNotification() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
    }

    private fun buildNotification(): Notification {
        val snapshot = AssistantVoiceInteractionStore.read()
        val contentIntent =
            PendingIntent.getActivity(
                this,
                7201,
                AssistantShellIntentRouter.createPageIntent(this, "entry", source = "voice_runtime"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val toggleIntent =
            PendingIntent.getService(
                this,
                7202,
                Intent(this, AssistantVoiceRuntimeService::class.java).apply {
                    action = AssistantVoiceRuntimeController.ACTION_TOGGLE
                    putExtra("voice_runtime_source", "voice_notification")
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_agent)
            .setContentTitle(
                when {
                    snapshot.state == "listening" || snapshot.state == "partial" -> "小轩持续语音监听中"
                    snapshot.state == "executing" -> "小轩语音助手执行中"
                    snapshot.state == "error" -> "小轩持续语音需要修复"
                    else -> "小轩持续语音待命"
                },
            )
            .setContentText(
                snapshot.spokenSummary
                    .ifBlank { snapshot.pendingConfirmation }
                    .ifBlank { snapshot.summary.ifBlank { "语音入口正在后台待命。" } },
            )
            .setSubText(snapshot.availabilitySummary.ifBlank { snapshot.state.ifBlank { "-" } })
            .setContentIntent(contentIntent)
            .setOngoing(snapshot.continuousMode || snapshot.state in setOf("listening", "partial"))
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                if (snapshot.continuousMode || snapshot.state in setOf("listening", "partial")) "停止监听" else "开始监听",
                toggleIntent,
            )
            .build()
    }

    private fun ensureTextToSpeech() {
        if (textToSpeech != null) return
        textToSpeech =
            TextToSpeech(applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (!ttsReady) {
                    return@TextToSpeech
                }
                textToSpeech?.language = Locale.SIMPLIFIED_CHINESE
                textToSpeech?.setSpeechRate(1.02f)
                if (deferredSpeech.isNotBlank()) {
                    speakInternal(deferredSpeech)
                    deferredSpeech = ""
                }
            }
    }

    private fun speakFeedback(
        summary: String,
    ) {
        val normalized = summary.trim().take(96)
        if (normalized.isBlank()) return
        AssistantVoiceInteractionStore.markSpokenFeedback(normalized)
        ensureTextToSpeech()
        if (!ttsReady) {
            deferredSpeech = normalized
            return
        }
        speakInternal(normalized)
    }

    private fun speakInternal(
        summary: String,
    ) {
        textToSpeech?.runCatching {
            stop()
            speak(summary, TextToSpeech.QUEUE_FLUSH, null, "assistant_voice_feedback")
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "小轩持续语音",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "在后台维持小轩的持续语音入口"
            },
        )
    }

    private fun readVoiceResult(
        bundle: Bundle?,
    ): String =
        bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

    private fun resolveVoiceRuntimeCommand(
        transcript: String,
    ): VoiceRuntimeCommand {
        val normalized = transcript.trim()
        val compact = normalized.lowercase()
        val action =
            when {
                compact.contains("停止监听") || compact.contains("关闭语音") || compact.contains("别听了") || compact.contains("结束监听") -> "voice_disable"
                compact.contains("确认执行") || compact.contains("确认操作") || compact.contains("批准") || compact.contains("同意") -> "voice_confirm"
                compact.contains("暂停任务") || compact.contains("暂停一下") || compact.contains("先停一下") || compact == "暂停" -> "voice_pause"
                compact.contains("继续任务") || compact.contains("继续执行") || compact.contains("恢复任务") || compact.contains("恢复执行") || compact == "继续" || compact == "恢复" -> "voice_resume"
                compact.contains("停止任务") || compact.contains("结束任务") || compact.contains("取消当前任务") || compact.contains("停掉任务") || compact == "停止" -> "voice_stop"
                compact.contains("记一下") || compact.contains("先记着") || compact.contains("记成草稿") || compact.contains("做个备忘") -> "voice_draft"
                compact.contains("立即执行") || compact.contains("马上执行") || compact.contains("开始处理") || compact.startsWith("帮我") || compact.startsWith("请") || compact.startsWith("打开") || compact.startsWith("发送") || compact.startsWith("回复") || compact.startsWith("去") || compact.startsWith("给") || compact.startsWith("把") -> "voice_execute"
                else -> "voice_draft"
            }
        val interactionMode =
            when (action) {
                "voice_execute" -> "hands_free_execution"
                "voice_pause",
                "voice_resume",
                "voice_stop",
                "voice_confirm",
                "voice_disable",
                -> "hands_free_control"
                else -> "draft_capture"
            }
        val pendingConfirmation =
            when (action) {
                "voice_draft" -> "已记下草稿，说“开始执行”即可直接继续。"
                "voice_execute" -> "准备开始执行这条语音任务。"
                "voice_confirm" -> "准备确认当前待处理动作。"
                else -> ""
            }
        val spokenSummary =
            when (action) {
                "voice_execute" -> "收到，开始处理：${normalized.take(32)}"
                "voice_resume" -> "收到，准备继续当前任务。"
                "voice_pause" -> "收到，准备暂停当前任务。"
                "voice_stop" -> "收到，准备停止当前任务。"
                "voice_confirm" -> "收到，准备确认当前操作。"
                "voice_disable" -> "收到，持续语音将停止监听。"
                else -> "已记下：${normalized.take(32)}。说开始执行即可继续。"
            }
        return VoiceRuntimeCommand(
            transcript = normalized,
            autoAction = action,
            interactionMode = interactionMode,
            pendingConfirmation = pendingConfirmation,
            spokenSummary = spokenSummary,
            lastHeardCommandType = action.removePrefix("voice_"),
            autoExecuteEligible = action == "voice_execute",
        )
    }

    private fun voiceRecognitionErrorMessage(
        error: Int,
    ): String =
        when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "语音识别音频输入异常。"
            SpeechRecognizer.ERROR_CLIENT -> "语音识别客户端被中断。"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限，无法继续语音识别。"
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            -> "语音识别网络暂时不可用。"

            SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到清晰语音内容。"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别器忙碌中，请稍后重试。"
            SpeechRecognizer.ERROR_SERVER -> "语音识别服务暂时不可用。"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "长时间没有说话，已停止监听。"
            else -> "语音识别失败，错误码=$error。"
        }

    companion object {
        private const val CHANNEL_ID = "assistant_voice_runtime"
        private const val NOTIFICATION_ID = 7200
    }
}
