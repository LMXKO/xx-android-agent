package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentToolCatalog
import com.lmx.xiaoxuanagent.agent.UtilityExecutionReceiptStore

object UtilityGovernanceStore {
    private val trackedTools =
        listOf(
            "system.read_notifications",
            "system.reply_notification",
            "system.media_control",
            "system.adjust_volume",
            "system.open_device_panel",
            "system.read_device_status",
            "system.read_current_location",
            "system.set_brightness",
            "system.set_do_not_disturb",
            "system.set_battery_saver",
            "system.open_power_dialog",
            "system.capture_screenshot",
            "system.capture_photo",
            "system.create_timer",
            "system.open_stopwatch",
            "system.lookup_contact",
            "system.dial_number",
            "system.draft_sms",
        )

    fun toolNames(): List<String> = trackedTools

    fun lines(
        limit: Int = 6,
    ): List<String> =
        trackedTools
            .mapNotNull { toolName -> AgentToolCatalog.find(toolName) }
            .take(limit.coerceAtLeast(1))
            .map { descriptor ->
                val receipt = UtilityExecutionReceiptStore.latest(descriptor.name)
                val lifecycle = UtilityLifecycleStore.snapshot(descriptor.name)
                buildString {
                    append("utility | ").append(descriptor.title)
                    append(" | status=").append(if (lifecycle.enabled) "enabled" else "disabled")
                    append(" | ").append(if (descriptor.requiresUserInteraction) "handoff" else "direct")
                    receipt?.let {
                        append(" | last=").append(it.summary.ifBlank { "-" })
                    } ?: append(" | ready=").append(descriptor.summary.ifBlank { "-" })
                }
            }
}
