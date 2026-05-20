package com.lmx.xiaoxuanagent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.Settings

object PlatformDeviceControlToolService {
    fun openPanel(
        service: AccessibilityService,
        panel: String,
    ): AgentExecutionResult {
        val normalized = panel.trim().lowercase()
        if (normalized == "torch" || normalized == "flashlight") {
            val toggled = PlatformTorchControlSupport.toggleTorch(service)
            if (toggled) {
                val enabled = !PlatformTorchControlSupport.readTorchState(service)
                PlatformTorchControlSupport.persistTorchState(service, enabled)
                val detailLines =
                    listOf(
                        "receipt=torch_toggled",
                        "panel=$normalized",
                        "mode=action_complete",
                        "flashlight_enabled=$enabled",
                    )
                UtilityExecutionReceiptStore.record(
                    toolName = "system.open_device_panel",
                    summary = "已直接切换手电：${if (enabled) "开启" else "关闭"}。",
                    detailLines = detailLines,
                    handoffRequired = false,
                )
                return AgentExecutionResult(
                    message = "已直接切换手电：${if (enabled) "开启" else "关闭"}。",
                    keepRunning = true,
                    requiresObservationCheck = false,
                    groundingSource = "system_device_toggle",
                    toolRuntimeDetailLines = detailLines,
                )
            }
        }
        val intent =
            when (normalized) {
                "internet", "network", "wifi" -> Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                "nfc" -> Intent(Settings.ACTION_NFC_SETTINGS)
                "battery_saver", "battery", "power_saver" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                "airplane", "airplane_mode" -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
                "volume" -> Intent(Settings.Panel.ACTION_VOLUME)
                "torch", "flashlight" -> Intent(Settings.ACTION_SETTINGS)
                else -> Intent(Settings.ACTION_SETTINGS)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            service.startActivity(intent)
            val stateLines = PlatformDeviceStateSupport.stateLines(service = service, panel = normalized)
            val currentState = stateLines.joinToString(" | ").takeIf { it.isNotBlank() }.orEmpty()
            val detailLines =
                listOf(
                    "receipt=device_panel_opened",
                    "panel=${normalized.ifBlank { "settings" }}",
                    "mode=handoff_required",
                    "completion=user_toggle_or_confirm_required",
                    "preferred_surface=${preferredSurface(normalized)}",
                ) + stateLines
            UtilityExecutionReceiptStore.record(
                toolName = "system.open_device_panel",
                summary = buildString {
                    append("已打开设备控制面板：").append(panel.ifBlank { "settings" })
                    if (currentState.isNotBlank()) append("。当前状态：").append(currentState)
                    else append("。")
                },
                detailLines = detailLines,
                handoffRequired = true,
            )
            AgentExecutionResult(
                message =
                    buildString {
                        append("已打开设备控制面板：").append(panel.ifBlank { "settings" })
                        if (currentState.isNotBlank()) append("。当前状态：").append(currentState)
                        else append("。")
                    },
                keepRunning = true,
                requiresObservationCheck = true,
                recommendedWaitMs = 800L,
                groundingSource = "system_device_panel",
                toolRuntimeDetailLines = detailLines,
            )
        }.getOrElse { error ->
            AgentExecutionResult("打开设备控制面板失败：${error.message.orEmpty()}".trim(), keepRunning = false)
        }
    }

    private fun preferredSurface(
        normalized: String,
    ): String =
        when (normalized) {
            "internet", "network", "wifi" -> "internet_panel"
            "bluetooth" -> "bluetooth_settings"
            "nfc" -> "nfc_settings"
            "battery_saver", "battery", "power_saver" -> "battery_saver_settings"
            "airplane", "airplane_mode" -> "wireless_settings"
            "volume" -> "volume_panel"
            "torch", "flashlight" -> "direct_toggle_or_settings"
            else -> "settings"
        }
}
