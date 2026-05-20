package com.lmx.xiaoxuanagent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.MediaStore
import com.lmx.xiaoxuanagent.runtime.ArtifactStore
import com.lmx.xiaoxuanagent.runtime.SessionRuntime

object PlatformCaptureToolService {
    fun captureScreenshot(
        indexedObservation: IndexedScreenObservation,
        note: String,
    ): AgentExecutionResult {
        val screenshot = indexedObservation.screenshot
            ?: return AgentExecutionResult("当前没有可保存的屏幕截图。", keepRunning = false)
        val session = SessionRuntime.State.runtimeState().session
        val artifact =
            ArtifactStore.recordPlanningScreenshot(
                sessionId = session.sessionId,
                turn = session.turns,
                task = session.task,
                observation = indexedObservation.observation,
                screenshot = screenshot,
                visualContext = indexedObservation.visualContext,
            ) ?: return AgentExecutionResult("保存屏幕截图 artifact 失败。", keepRunning = false)
        return AgentExecutionResult(
            message = "已记录屏幕截图${note.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}。",
            keepRunning = true,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "system_capture_screenshot",
            resolvedTargetText = note,
            toolRuntimeDetailLines = listOf("artifact=${artifact.artifactId}", artifact.path, "receipt=screenshot_saved", "handoff=none"),
        ).also {
            UtilityExecutionReceiptStore.record(
                toolName = "system.capture_screenshot",
                summary = "已记录屏幕截图${note.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}。",
                detailLines = listOf("artifact=${artifact.artifactId}", artifact.path),
                handoffRequired = false,
            )
        }
    }

    fun capturePhoto(
        service: AccessibilityService,
        note: String,
    ): AgentExecutionResult =
        runCatching {
            val hint = CaptureIntentHintSupport.forNote(note)
            service.startActivity(
                CaptureIntentHintSupport
                    .apply(Intent(MediaStore.ACTION_IMAGE_CAPTURE), hint)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            val detailLines =
                listOf(
                    "receipt=camera_opened",
                    "mode=handoff_required",
                    "completion=user_shutter_required",
                    "next_step=${hint.completionHint}",
                ) + hint.detailLines
            AgentExecutionResult(
                message = "已打开系统相机${note.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}，请完成拍摄。",
                keepRunning = true,
                requiresObservationCheck = true,
                recommendedWaitMs = 1_000L,
                groundingSource = "system_capture_photo",
                toolRuntimeDetailLines = detailLines,
            ).also {
                UtilityExecutionReceiptStore.record(
                    toolName = "system.capture_photo",
                    summary = "已打开系统相机${note.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}，等待用户按下快门。",
                    detailLines = detailLines,
                    handoffRequired = true,
                )
            }
        }.getOrElse { error ->
            AgentExecutionResult("打开系统相机失败：${error.message.orEmpty()}".trim(), keepRunning = false)
        }
}
