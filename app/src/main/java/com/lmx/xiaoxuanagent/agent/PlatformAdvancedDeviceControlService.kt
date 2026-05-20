package com.lmx.xiaoxuanagent.agent

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings

internal object PlatformAdvancedDeviceControlService {
    fun readStatus(
        service: AccessibilityService,
        target: String,
    ): AgentExecutionResult {
        val normalized = target.trim().lowercase().ifBlank { "overview" }
            val lines =
                when (normalized) {
                    "wifi", "network", "internet" -> PlatformDeviceStateSupport.stateLines(service, "wifi")
                    "bluetooth" -> PlatformDeviceStateSupport.stateLines(service, "bluetooth")
                    "nfc" -> PlatformDeviceStateSupport.stateLines(service, "nfc")
                    "airplane", "airplane_mode" -> PlatformDeviceStateSupport.stateLines(service, "airplane")
                    "flashlight", "torch" -> PlatformDeviceStateSupport.stateLines(service, "flashlight")
                    "volume" -> PlatformDeviceStateSupport.stateLines(service, "volume")
                    "brightness" -> brightnessLines(service)
                "battery", "battery_saver" -> batteryLines(service)
                "dnd", "do_not_disturb" -> dndLines(service)
                else ->
                        PlatformDeviceStateSupport.stateLines(service, "wifi") +
                            PlatformDeviceStateSupport.stateLines(service, "bluetooth") +
                            PlatformDeviceStateSupport.stateLines(service, "airplane") +
                            brightnessLines(service) +
                            batteryLines(service) +
                            dndLines(service)
            }
        return AgentExecutionResult(
            message = "已读取设备状态：$normalized。",
            keepRunning = true,
            requiresObservationCheck = false,
            groundingSource = "system_device_status",
            toolRuntimeDetailLines = listOf("receipt=device_status_read", "target=$normalized") + lines,
        ).also {
            UtilityExecutionReceiptStore.record(
                toolName = "system.read_device_status",
                summary = "已读取设备状态：$normalized。",
                detailLines = lines,
                handoffRequired = false,
            )
        }
    }

    fun setBrightness(
        service: AccessibilityService,
        level: String,
    ): AgentExecutionResult {
        val resolved = resolveBrightnessLevel(level)
        val context = service.applicationContext
        return if (Settings.System.canWrite(context)) {
            runCatching {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, resolved)
                val detailLines = listOf("receipt=brightness_set", "brightness=$resolved")
                UtilityExecutionReceiptStore.record(
                    toolName = "system.set_brightness",
                    summary = "已设置屏幕亮度：$resolved/255。",
                    detailLines = detailLines,
                    handoffRequired = false,
                )
                AgentExecutionResult(
                    message = "已设置屏幕亮度：$resolved/255。",
                    keepRunning = true,
                    requiresObservationCheck = false,
                    groundingSource = "system_brightness",
                    toolRuntimeDetailLines = detailLines,
                )
            }.getOrElse { error ->
                AgentExecutionResult("设置屏幕亮度失败：${error.message.orEmpty()}".trim(), keepRunning = false)
            }
        } else {
            val intent =
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            runCatching {
                service.startActivity(intent)
                val detailLines =
                    listOf(
                        "receipt=brightness_permission_opened",
                        "requested_brightness=$resolved",
                        "mode=handoff_required",
                    )
                UtilityExecutionReceiptStore.record(
                    toolName = "system.set_brightness",
                    summary = "当前缺少修改系统亮度权限，已打开授权页。",
                    detailLines = detailLines,
                    handoffRequired = true,
                )
                AgentExecutionResult(
                    message = "当前缺少修改系统亮度权限，已打开授权页。",
                    keepRunning = true,
                    requiresObservationCheck = true,
                    recommendedWaitMs = 800L,
                    groundingSource = "system_brightness_permission",
                    toolRuntimeDetailLines = detailLines,
                )
            }.getOrElse { error ->
                AgentExecutionResult("打开亮度权限页失败：${error.message.orEmpty()}".trim(), keepRunning = false)
            }
        }
    }

    fun setDoNotDisturb(
        service: AccessibilityService,
        mode: String,
    ): AgentExecutionResult {
        val notificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return AgentExecutionResult("无法获取 NotificationManager。", keepRunning = false)
        val normalized = mode.trim().lowercase().ifBlank { "on" }
        return if (notificationManager.isNotificationPolicyAccessGranted) {
            val filter =
                when (normalized) {
                    "off", "allow_all" -> NotificationManager.INTERRUPTION_FILTER_ALL
                    "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                    else -> NotificationManager.INTERRUPTION_FILTER_NONE
                }
            runCatching {
                notificationManager.setInterruptionFilter(filter)
                val detailLines = listOf("receipt=dnd_updated", "mode=$normalized")
                UtilityExecutionReceiptStore.record(
                    toolName = "system.set_do_not_disturb",
                    summary = "已设置免打扰：$normalized。",
                    detailLines = detailLines,
                    handoffRequired = false,
                )
                AgentExecutionResult(
                    message = "已设置免打扰：$normalized。",
                    keepRunning = true,
                    requiresObservationCheck = false,
                    groundingSource = "system_dnd",
                    toolRuntimeDetailLines = detailLines,
                )
            }.getOrElse { error ->
                AgentExecutionResult("设置免打扰失败：${error.message.orEmpty()}".trim(), keepRunning = false)
            }
        } else {
            runCatching {
                service.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val detailLines =
                    listOf(
                        "receipt=dnd_permission_opened",
                        "requested_mode=$normalized",
                        "mode=handoff_required",
                    )
                UtilityExecutionReceiptStore.record(
                    toolName = "system.set_do_not_disturb",
                    summary = "当前缺少免打扰访问权限，已打开授权页。",
                    detailLines = detailLines,
                    handoffRequired = true,
                )
                AgentExecutionResult(
                    message = "当前缺少免打扰访问权限，已打开授权页。",
                    keepRunning = true,
                    requiresObservationCheck = true,
                    recommendedWaitMs = 800L,
                    groundingSource = "system_dnd_permission",
                    toolRuntimeDetailLines = detailLines,
                )
            }.getOrElse { error ->
                AgentExecutionResult("打开免打扰权限页失败：${error.message.orEmpty()}".trim(), keepRunning = false)
            }
        }
    }

    fun openPowerDialog(
        service: AccessibilityService,
    ): AgentExecutionResult {
        val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
        val detailLines =
            listOf(
                "receipt=power_dialog_opened",
                "mode=handoff_required",
                "completion=user_choose_restart_or_shutdown",
            )
        if (success) {
            UtilityExecutionReceiptStore.record(
                toolName = "system.open_power_dialog",
                summary = "已打开系统电源对话框。",
                detailLines = detailLines,
                handoffRequired = true,
            )
        }
        return AgentExecutionResult(
            message = if (success) "已打开系统电源对话框。" else "打开系统电源对话框失败。",
            keepRunning = success,
            requiresObservationCheck = success,
            recommendedWaitMs = if (success) 700L else 0L,
            groundingSource = "system_power_dialog",
            toolRuntimeDetailLines = if (success) detailLines else emptyList(),
        )
    }

    fun setBatterySaver(
        service: AccessibilityService,
        mode: String,
    ): AgentExecutionResult {
        val normalized = mode.trim().lowercase().ifBlank { "on" }
        val current = batteryLines(service).firstOrNull { it.startsWith("battery_saver=") }.orEmpty().substringAfter('=').toBooleanStrictOrNull()
        val targetEnabled = normalized != "off"
        if (current != null && current == targetEnabled) {
            val detailLines = listOf("receipt=battery_saver_already_$normalized", "battery_saver=$current")
            UtilityExecutionReceiptStore.record(
                toolName = "system.set_battery_saver",
                summary = "省电模式当前已是${if (current) "开启" else "关闭"}状态。",
                detailLines = detailLines,
                handoffRequired = false,
            )
            return AgentExecutionResult(
                message = "省电模式当前已是${if (current) "开启" else "关闭"}状态。",
                keepRunning = true,
                requiresObservationCheck = false,
                groundingSource = "system_battery_saver_status",
                toolRuntimeDetailLines = detailLines,
            )
        }
        return runCatching {
            service.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val detailLines =
                listOf(
                    "receipt=battery_saver_settings_opened",
                    "requested_mode=$normalized",
                    "mode=handoff_required",
                ) + batteryLines(service)
            UtilityExecutionReceiptStore.record(
                toolName = "system.set_battery_saver",
                summary = "已打开省电模式设置页，请用户完成切换。",
                detailLines = detailLines,
                handoffRequired = true,
            )
            AgentExecutionResult(
                message = "已打开省电模式设置页，请用户完成切换。",
                keepRunning = true,
                requiresObservationCheck = true,
                recommendedWaitMs = 800L,
                groundingSource = "system_battery_saver_settings",
                toolRuntimeDetailLines = detailLines,
            )
        }.getOrElse { error ->
            AgentExecutionResult("打开省电模式设置页失败：${error.message.orEmpty()}".trim(), keepRunning = false)
        }
    }

    private fun resolveBrightnessLevel(
        raw: String,
    ): Int {
        val normalized = raw.trim().lowercase()
        val percent = normalized.removeSuffix("%").toIntOrNull()
        if (percent != null) {
            return ((percent.coerceIn(1, 100) / 100.0) * 255.0).toInt().coerceIn(16, 255)
        }
        return when {
            normalized.contains("low") || normalized.contains("暗") || normalized.contains("低") -> 64
            normalized.contains("high") || normalized.contains("亮") || normalized.contains("高") -> 220
            else -> 140
        }
    }

    private fun brightnessLines(
        service: AccessibilityService,
    ): List<String> {
        val context = service.applicationContext
        val current = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)
        return listOf(
            "brightness=$current/255",
            "can_write_settings=${Settings.System.canWrite(context)}",
        )
    }

    private fun batteryLines(
        service: AccessibilityService,
    ): List<String> {
        val batteryManager = service.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val powerManager = service.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val percent = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return listOf(
            "battery_level=$percent",
            "battery_saver=${powerManager?.isPowerSaveMode ?: false}",
        )
    }

    private fun dndLines(
        service: AccessibilityService,
    ): List<String> {
        val notificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val filter = notificationManager?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        return listOf(
            "dnd_access=${notificationManager?.isNotificationPolicyAccessGranted ?: false}",
            "dnd_mode=${renderInterruptionFilter(filter)}",
        )
    }

    private fun renderInterruptionFilter(
        filter: Int,
    ): String =
        when (filter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "on"
            else -> "unknown"
        }
}
