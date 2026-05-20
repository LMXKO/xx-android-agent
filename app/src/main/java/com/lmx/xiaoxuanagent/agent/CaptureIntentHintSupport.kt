package com.lmx.xiaoxuanagent.agent

import android.content.Intent
import android.provider.MediaStore

internal data class CaptureIntentHint(
    val mode: String = "default",
    val lensFacing: String = "rear",
    val completionHint: String = "press_shutter_then_review",
    val detailLines: List<String> = emptyList(),
)

internal object CaptureIntentHintSupport {
    fun forNote(
        note: String,
    ): CaptureIntentHint {
        val normalized = note.lowercase()
        val mode =
            when {
                normalized.contains("自拍") || normalized.contains("selfie") -> "selfie"
                normalized.contains("扫码") || normalized.contains("二维码") || normalized.contains("qr") -> "qr"
                normalized.contains("证件") || normalized.contains("文档") || normalized.contains("scan") -> "document"
                normalized.contains("留证") || normalized.contains("报销") || normalized.contains("发票") -> "evidence"
                else -> "default"
            }
        val lensFacing = if (mode == "selfie") "front" else "rear"
        val completionHint =
            when (mode) {
                "document" -> "align_document_then_shoot"
                "evidence" -> "capture_evidence_then_review"
                "qr" -> "aim_code_then_shoot"
                else -> "press_shutter_then_review"
            }
        return CaptureIntentHint(
            mode = mode,
            lensFacing = lensFacing,
            completionHint = completionHint,
            detailLines = listOf("capture_mode=$mode", "lens=$lensFacing", "capture_completion=$completionHint"),
        )
    }

    fun apply(
        baseIntent: Intent,
        hint: CaptureIntentHint,
    ): Intent =
        baseIntent.apply {
            if (hint.lensFacing == "front") {
                putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                putExtra("android.intent.extras.CAMERA_FACING", 1)
            }
            if (hint.mode == "document") {
                putExtra(MediaStore.EXTRA_SCREEN_ORIENTATION, 0)
            }
            if (hint.mode == "qr") {
                putExtra("android.intent.extra.quickCapture", true)
            }
        }
}
