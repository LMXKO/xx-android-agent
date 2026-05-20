package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class AssistantVoiceInteractionSnapshot(
    val state: String = "idle",
    val summary: String = "",
    val availabilitySummary: String = "",
    val interactionMode: String = "idle",
    val partialTranscript: String = "",
    val finalTranscript: String = "",
    val runtimeOwner: String = "",
    val continuousMode: Boolean = false,
    val pendingTranscript: String = "",
    val pendingSource: String = "",
    val pendingAutoAction: String = "",
    val pendingConfirmation: String = "",
    val spokenSummary: String = "",
    val lastHeardCommandType: String = "",
    val autoExecuteEligible: Boolean = false,
    val pendingAtMs: Long = 0L,
    val lastSpokenAtMs: Long = 0L,
    val blockedReason: String = "",
    val lastError: String = "",
    val activeSessionId: String = "",
    val transcriptHistory: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

object AssistantVoiceInteractionStore {
    private const val STORE_DIR = "assistantos"
    private const val STORE_FILE = "voice_interaction_state.json"
    private const val MAX_TRANSCRIPTS = 12
    private val lock = Any()
    private var hydrated = false
    private var snapshot = AssistantVoiceInteractionSnapshot()

    fun read(): AssistantVoiceInteractionSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshot
        }

    fun syncEnvironment(
        available: Boolean,
        permissionGranted: Boolean,
    ): AssistantVoiceInteractionSnapshot =
        mutateUnlocked { current ->
            current.copy(
                availabilitySummary =
                    when {
                        !available -> "speech_recognizer_unavailable"
                        !permissionGranted -> "record_audio_permission_required"
                        else -> "voice_ready"
                    },
                summary =
                    when {
                        !available -> "当前设备没有可用的连续语音识别能力。"
                        !permissionGranted -> "语音入口还缺录音权限。"
                        current.state == "idle" -> "语音入口已就绪，可直接开始持续听写。"
                        else -> current.summary
                    },
                interactionMode =
                    when {
                        !available || !permissionGranted -> "repair"
                        current.state == "idle" -> "standby"
                        else -> current.interactionMode
                    },
                updatedAtMs = System.currentTimeMillis(),
            )
        }

    fun markListening(
        activeSessionId: String = "",
        note: String = "正在持续监听语音输入。",
        runtimeOwner: String = "activity",
        continuousMode: Boolean = false,
    ): AssistantVoiceInteractionSnapshot =
        mutateUnlocked { current ->
            current.copy(
                state = "listening",
                summary = note,
                interactionMode = "ambient_listening",
                runtimeOwner = runtimeOwner,
                continuousMode = continuousMode,
                activeSessionId = activeSessionId,
                blockedReason = "",
                lastError = "",
                updatedAtMs = System.currentTimeMillis(),
            )
        }

    fun updatePartial(
        partial: String,
    ): AssistantVoiceInteractionSnapshot =
        mutateUnlocked { current ->
            current.copy(
                state = if (partial.isBlank()) "listening" else "partial",
                partialTranscript = partial,
                summary = if (partial.isBlank()) "正在持续监听语音输入。" else "正在识别: ${partial.take(48)}",
                interactionMode = "ambient_listening",
                updatedAtMs = System.currentTimeMillis(),
            )
        }

    fun markFinal(
        transcript: String,
        autoAction: String = "voice_draft",
        source: String = "voice",
        pendingDispatch: Boolean = false,
        interactionMode: String = defaultInteractionMode(autoAction),
        pendingConfirmation: String = "",
        spokenSummary: String = "",
        lastHeardCommandType: String = autoAction.removePrefix("voice_"),
        autoExecuteEligible: Boolean = autoAction == "voice_execute",
    ): AssistantVoiceInteractionSnapshot =
        mutateUnlocked { current ->
            val now = System.currentTimeMillis()
            val nextHistory =
                (
                    current.transcriptHistory +
                        "${formatTimestamp(now)} | ${transcript.take(120)}"
                ).takeLast(MAX_TRANSCRIPTS)
            current.copy(
                state = "ready",
                summary =
                    when (autoAction) {
                        "voice_execute" -> "已收下语音任务，准备执行。"
                        "voice_resume" -> "已收到继续指令，准备恢复当前任务。"
                        "voice_pause" -> "已收到暂停指令，准备挂起当前任务。"
                        "voice_stop" -> "已收到停止指令，准备结束当前任务。"
                        "voice_confirm" -> "已收到确认指令，准备确认当前动作。"
                        "voice_disable" -> "已收到关闭持续语音指令。"
                        else -> "已收下语音草稿，可直接开始执行。"
                    },
                interactionMode = interactionMode,
                partialTranscript = "",
                finalTranscript = transcript,
                pendingTranscript = if (pendingDispatch) transcript else "",
                pendingSource = if (pendingDispatch) source else "",
                pendingAutoAction = if (pendingDispatch) autoAction else "",
                pendingConfirmation = pendingConfirmation,
                spokenSummary = spokenSummary,
                lastHeardCommandType = lastHeardCommandType,
                autoExecuteEligible = autoExecuteEligible,
                pendingAtMs = if (pendingDispatch) now else 0L,
                transcriptHistory = nextHistory,
                blockedReason = "",
                lastError = "",
                recommendedCommands = listOf("/today", "/entry-surfaces", "/product-shell --section entry"),
                updatedAtMs = now,
            )
        }

    fun markExecuting(
        transcript: String,
        activeSessionId: String,
    ): AssistantVoiceInteractionSnapshot =
        mutateUnlocked { current ->
            current.copy(
                state = "executing",
                summary = "语音任务已转入执行链路。",
                interactionMode = "executing",
                finalTranscript = transcript,
                activeSessionId = activeSessionId,
                updatedAtMs = System.currentTimeMillis(),
            )
        }

    fun markActionHandled(
        autoAction: String,
        summary: String,
        activeSessionId: String = "",
    ): AssistantVoiceInteractionSnapshot =
        mutateUnlocked { current ->
            current.copy(
                state =
                    when (autoAction) {
                        "voice_execute" -> "executing"
                        "voice_disable" -> "idle"
                        else -> "ready"
                    },
                summary = summary,
                interactionMode =
                    when (autoAction) {
                        "voice_execute" -> "executing"
                        "voice_pause" -> "control_paused"
                        "voice_resume" -> "control_resume"
                        "voice_stop" -> "control_stop"
                        "voice_confirm" -> "control_confirm"
                        "voice_disable" -> "standby"
                        else -> current.interactionMode
                    },
                activeSessionId = activeSessionId.ifBlank { current.activeSessionId },
                pendingTranscript = "",
                pendingSource = "",
                pendingAutoAction = "",
                pendingConfirmation = "",
                pendingAtMs = 0L,
                updatedAtMs = System.currentTimeMillis(),
            )
        }

    fun markSpokenFeedback(
        summary: String,
    ): AssistantVoiceInteractionSnapshot =
        mutateUnlocked { current ->
            current.copy(
                spokenSummary = summary,
                lastSpokenAtMs = System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis(),
            )
        }

    fun markBlocked(
        reason: String,
        summary: String,
    ): AssistantVoiceInteractionSnapshot =
        mutateUnlocked { current ->
            current.copy(
                state = "blocked",
                blockedReason = reason,
                summary = summary,
                interactionMode = "blocked",
                updatedAtMs = System.currentTimeMillis(),
            )
        }

    fun markError(
        error: String,
    ): AssistantVoiceInteractionSnapshot =
        mutateUnlocked { current ->
            current.copy(
                state = "error",
                lastError = error,
                summary = error,
                interactionMode = "repair",
                updatedAtMs = System.currentTimeMillis(),
            )
        }

    fun reset(
        summary: String = "语音入口已回到待命状态。",
    ): AssistantVoiceInteractionSnapshot =
        mutateUnlocked { current ->
            current.copy(
                state = "idle",
                summary = summary,
                interactionMode = "standby",
                runtimeOwner = "",
                continuousMode = false,
                partialTranscript = "",
                pendingConfirmation = "",
                spokenSummary = "",
                lastHeardCommandType = "",
                autoExecuteEligible = false,
                activeSessionId = "",
                blockedReason = "",
                lastError = "",
                updatedAtMs = System.currentTimeMillis(),
            )
        }

    fun consumePendingTranscript(): AssistantVoiceInteractionSnapshot =
        mutateUnlocked { current ->
            current.copy(
                pendingTranscript = "",
                pendingSource = "",
                pendingAutoAction = "",
                pendingAtMs = 0L,
                updatedAtMs = System.currentTimeMillis(),
            )
        }

    fun updateRuntimeMode(
        runtimeOwner: String,
        continuousMode: Boolean,
    ): AssistantVoiceInteractionSnapshot =
        mutateUnlocked { current ->
            current.copy(
                runtimeOwner = runtimeOwner,
                continuousMode = continuousMode,
                updatedAtMs = System.currentTimeMillis(),
            )
        }

    fun exportJson(): JSONObject =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshot.toJson()
        }

    fun importJson(
        json: JSONObject?,
    ): AssistantVoiceInteractionSnapshot =
        synchronized(lock) {
            hydrated = true
            snapshot = json?.toSnapshot() ?: AssistantVoiceInteractionSnapshot()
            persistUnlocked()
            snapshot
        }

    internal fun resetForTest() {
        synchronized(lock) {
            hydrated = false
            snapshot = AssistantVoiceInteractionSnapshot()
        }
    }

    private fun mutateUnlocked(
        reducer: (AssistantVoiceInteractionSnapshot) -> AssistantVoiceInteractionSnapshot,
    ): AssistantVoiceInteractionSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshot = reducer(snapshot)
            persistUnlocked()
            snapshot
        }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = storeFile() ?: return
        if (!file.exists()) return
        runCatching {
            snapshot = JSONObject(file.readText()).toSnapshot()
        }
    }

    private fun persistUnlocked() {
        val file = storeFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(snapshot.toJson().toString(2))
    }

    private fun storeFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, STORE_DIR), STORE_FILE)
    }

    private fun AssistantVoiceInteractionSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("state", state)
            put("summary", summary)
            put("availability_summary", availabilitySummary)
            put("interaction_mode", interactionMode)
            put("partial_transcript", partialTranscript)
            put("final_transcript", finalTranscript)
            put("runtime_owner", runtimeOwner)
            put("continuous_mode", continuousMode)
            put("pending_transcript", pendingTranscript)
            put("pending_source", pendingSource)
            put("pending_auto_action", pendingAutoAction)
            put("pending_confirmation", pendingConfirmation)
            put("spoken_summary", spokenSummary)
            put("last_heard_command_type", lastHeardCommandType)
            put("auto_execute_eligible", autoExecuteEligible)
            put("pending_at_ms", pendingAtMs)
            put("last_spoken_at_ms", lastSpokenAtMs)
            put("blocked_reason", blockedReason)
            put("last_error", lastError)
            put("active_session_id", activeSessionId)
            put("transcript_history", JSONArray(transcriptHistory))
            put("recommended_commands", JSONArray(recommendedCommands))
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toSnapshot(): AssistantVoiceInteractionSnapshot =
        AssistantVoiceInteractionSnapshot(
            state = optString("state", "idle"),
            summary = optString("summary"),
            availabilitySummary = optString("availability_summary"),
            interactionMode = optString("interaction_mode", "idle"),
            partialTranscript = optString("partial_transcript"),
            finalTranscript = optString("final_transcript"),
            runtimeOwner = optString("runtime_owner"),
            continuousMode = optBoolean("continuous_mode", false),
            pendingTranscript = optString("pending_transcript"),
            pendingSource = optString("pending_source"),
            pendingAutoAction = optString("pending_auto_action"),
            pendingConfirmation = optString("pending_confirmation"),
            spokenSummary = optString("spoken_summary"),
            lastHeardCommandType = optString("last_heard_command_type"),
            autoExecuteEligible = optBoolean("auto_execute_eligible", false),
            pendingAtMs = optLong("pending_at_ms"),
            lastSpokenAtMs = optLong("last_spoken_at_ms"),
            blockedReason = optString("blocked_reason"),
            lastError = optString("last_error"),
            activeSessionId = optString("active_session_id"),
            transcriptHistory = optJSONArray("transcript_history").toStringList(),
            recommendedCommands = optJSONArray("recommended_commands").toStringList(),
            updatedAtMs = optLong("updated_at_ms"),
        )

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun formatTimestamp(
        timestampMs: Long,
    ): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestampMs))

    private fun defaultInteractionMode(
        autoAction: String,
    ): String =
        when (autoAction) {
            "voice_execute" -> "hands_free_execution"
            "voice_resume",
            "voice_pause",
            "voice_stop",
            "voice_confirm",
            "voice_disable",
            -> "hands_free_control"
            else -> "draft_capture"
        }
}
