package com.lmx.xiaoxuanagent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

object PlatformMediaControlToolService {
    fun control(
        service: AccessibilityService,
        command: String,
    ): AgentExecutionResult {
        val audioManager = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return AgentExecutionResult("无法获取 AudioManager。", keepRunning = false)
        val keyCode =
            when (command.lowercase()) {
                "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return AgentExecutionResult(
            message = "已发送媒体控制：${command.ifBlank { "toggle" }}。",
            keepRunning = true,
            requiresObservationCheck = false,
            groundingSource = "system_media_control",
            toolRuntimeDetailLines = listOf("receipt=media_command_sent", "music_volume=$current/$max"),
        ).also {
            UtilityExecutionReceiptStore.record(
                toolName = "system.media_control",
                summary = "已发送媒体控制：${command.ifBlank { "toggle" }}。",
                detailLines = listOf("music_volume=$current/$max"),
                handoffRequired = false,
            )
        }
    }

    fun adjustVolume(
        service: AccessibilityService,
        direction: String,
        stream: String,
        step: Int,
    ): AgentExecutionResult {
        val audioManager = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return AgentExecutionResult("无法获取 AudioManager。", keepRunning = false)
        val streamType =
            when (stream.lowercase()) {
                "ring", "ringer" -> AudioManager.STREAM_RING
                "notification" -> AudioManager.STREAM_NOTIFICATION
                else -> AudioManager.STREAM_MUSIC
            }
        val adjustDirection =
            when (direction.lowercase()) {
                "lower", "down", "decrease" -> AudioManager.ADJUST_LOWER
                "mute" -> AudioManager.ADJUST_MUTE
                "unmute" -> AudioManager.ADJUST_UNMUTE
                else -> AudioManager.ADJUST_RAISE
            }
        repeat(step.coerceIn(1, 5)) {
            audioManager.adjustStreamVolume(streamType, adjustDirection, AudioManager.FLAG_SHOW_UI)
        }
        val current = audioManager.getStreamVolume(streamType)
        val max = audioManager.getStreamMaxVolume(streamType)
        return AgentExecutionResult(
            message = "已调节音量：stream=${stream.ifBlank { "music" }} direction=${direction.ifBlank { "raise" }} step=${step.coerceIn(1, 5)}。",
            keepRunning = true,
            requiresObservationCheck = false,
            groundingSource = "system_adjust_volume",
            toolRuntimeDetailLines = listOf("receipt=volume_adjusted", "stream=${stream.ifBlank { "music" }}", "level=$current/$max"),
        ).also {
            UtilityExecutionReceiptStore.record(
                toolName = "system.adjust_volume",
                summary = "已调节音量：stream=${stream.ifBlank { "music" }} direction=${direction.ifBlank { "raise" }}。",
                detailLines = listOf("level=$current/$max"),
                handoffRequired = false,
            )
        }
    }
}
