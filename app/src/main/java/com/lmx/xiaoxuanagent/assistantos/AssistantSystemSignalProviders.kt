package com.lmx.xiaoxuanagent.assistantos

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import com.lmx.xiaoxuanagent.runtime.AppForegroundTracker
import com.lmx.xiaoxuanagent.runtime.DebugAgentStore
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.PlatformTraceStore
import com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey
import com.lmx.xiaoxuanagent.taskprofile.TaskRegistry

object AssistantSystemSignalProviders {
    private const val CLIPBOARD_POLL_INTERVAL_MS = 3_000L
    private const val INTERACTIVE_PROVIDER_GRACE_MS = 1_500L

    private data class AssistantProviderPollingPolicy(
        val allowInteractiveProviders: Boolean,
        val reason: String,
    )

    @Volatile
    private var registered = false
    @Volatile
    private var lastClipboardSignature: String = ""
    @Volatile
    private var lastForegroundPackage: String = ""
    @Volatile
    private var lastBatterySignature: String = ""
    @Volatile
    private var lastNetworkSignature: String = ""
    @Volatile
    private var lastClipboardPollAtMs: Long = 0L

    fun ensureRegistered(
        context: Context,
    ) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            AppRuntimeContext.init(context)
            AssistantSignalProviderStore.markRegistered(
                providerId = "system_broadcast",
                source = "system_broadcast",
                trustLevel = AssistantSignalProviderTrustLevel.SYSTEM,
                providerType = "system",
                supportedCapabilities = listOf(SessionCapabilityKey.REFRESH_ASSISTANT_OS),
                supportedSignalTypes = listOf(AssistantExternalSignalType.SYSTEM_EVENT),
                routingTags = listOf("system", "refresh"),
                preferredEntrySource = "external_signal:system_event",
                deliveryMode = "event",
                routingPriority = 20,
            )
            AssistantSignalProviderStore.markRegistered(
                providerId = "package_broadcast",
                source = "package_broadcast",
                trustLevel = AssistantSignalProviderTrustLevel.HIGH,
                providerType = "package",
                supportedCapabilities = listOf(SessionCapabilityKey.REFRESH_ASSISTANT_OS),
                supportedSignalTypes = listOf(AssistantExternalSignalType.SYSTEM_EVENT),
                routingTags = listOf("package", "refresh"),
                preferredEntrySource = "external_signal:system_event",
                deliveryMode = "event",
                routingPriority = 30,
            )
            AssistantSignalProviderStore.markRegistered(
                providerId = "clipboard_poller",
                source = "clipboard_poller",
                trustLevel = AssistantSignalProviderTrustLevel.MEDIUM,
                providerType = "clipboard",
                supportedCapabilities = listOf(SessionCapabilityKey.REFRESH_ASSISTANT_OS),
                supportedSignalTypes = listOf(AssistantExternalSignalType.CLIPBOARD),
                routingTags = listOf("clipboard", "context"),
                preferredEntrySource = "external_signal:clipboard",
                deliveryMode = "poll",
                routingPriority = 10,
            )
            AssistantSignalProviderStore.markRegistered(
                providerId = "foreground_app",
                source = "foreground_app",
                trustLevel = AssistantSignalProviderTrustLevel.HIGH,
                providerType = "foreground",
                supportedCapabilities = listOf(SessionCapabilityKey.START_SESSION, SessionCapabilityKey.REFRESH_ASSISTANT_OS),
                supportedSignalTypes = listOf(AssistantExternalSignalType.APP_FOREGROUND),
                routingTags = listOf("foreground", "takeover"),
                preferredEntrySource = "external_signal:app_foreground",
                deliveryMode = "poll",
                routingPriority = 60,
            )
            AssistantSignalProviderStore.markRegistered(
                providerId = "battery_status",
                source = "battery_status",
                trustLevel = AssistantSignalProviderTrustLevel.HIGH,
                providerType = "battery",
                supportedCapabilities = listOf(SessionCapabilityKey.REFRESH_ASSISTANT_OS),
                supportedSignalTypes = listOf(AssistantExternalSignalType.SYSTEM_EVENT),
                routingTags = listOf("battery", "power", "health"),
                preferredEntrySource = "external_signal:battery",
                deliveryMode = "event+poll",
                routingPriority = 40,
            )
            AssistantSignalProviderStore.markRegistered(
                providerId = "network_status",
                source = "network_status",
                trustLevel = AssistantSignalProviderTrustLevel.HIGH,
                providerType = "network",
                supportedCapabilities = listOf(SessionCapabilityKey.REFRESH_ASSISTANT_OS),
                supportedSignalTypes = listOf(AssistantExternalSignalType.SYSTEM_EVENT),
                routingTags = listOf("network", "connectivity", "health"),
                preferredEntrySource = "external_signal:network",
                deliveryMode = "poll",
                routingPriority = 40,
            )
            registerReceiver(context, systemReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_LOCALE_CHANGED)
                addAction(Intent.ACTION_MY_PACKAGE_REPLACED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
                addAction(Intent.ACTION_DEVICE_STORAGE_OK)
            })
            registerReceiver(context, packageReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            })
            AssistantContextSignalProviders.ensureRegistered(context)
            registered = true
            PlatformTraceStore.record(
                category = "external_signal_provider",
                summary = "system providers registered",
            )
        }
    }

    fun pollDynamicProviders(
        context: Context,
        reason: String,
    ) {
        val policy = buildPollingPolicy(reason)
        pollClipboard(context, policy)
        pollForegroundApp(reason)
        pollBatteryState(context, reason)
        pollNetworkState(context, reason)
        if (policy.allowInteractiveProviders) {
            AssistantContextSignalProviders.poll(context, reason)
        }
    }

    private fun buildPollingPolicy(
        reason: String,
    ): AssistantProviderPollingPolicy {
        val now = System.currentTimeMillis()
        val interactive =
            AppForegroundTracker.isAppInForeground() ||
                (now - AppForegroundTracker.lastInteractiveAtMs()) <= INTERACTIVE_PROVIDER_GRACE_MS
        return AssistantProviderPollingPolicy(
            allowInteractiveProviders = interactive,
            reason = reason,
        )
    }

    private val systemReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val action = intent.action.orEmpty()
                val title =
                    when (action) {
                        Intent.ACTION_POWER_CONNECTED -> "电源已连接"
                        Intent.ACTION_POWER_DISCONNECTED -> "电源已断开"
                        Intent.ACTION_TIME_CHANGED -> "系统时间已变更"
                        Intent.ACTION_TIMEZONE_CHANGED -> "系统时区已变更"
                        Intent.ACTION_LOCALE_CHANGED -> "系统语言已变更"
                        Intent.ACTION_MY_PACKAGE_REPLACED -> "小轩已更新"
                        Intent.ACTION_BATTERY_LOW -> "电量偏低"
                        Intent.ACTION_BATTERY_OKAY -> "电量恢复"
                        Intent.ACTION_AIRPLANE_MODE_CHANGED -> "飞行模式变化"
                        Intent.ACTION_DEVICE_STORAGE_LOW -> "存储空间偏低"
                        Intent.ACTION_DEVICE_STORAGE_OK -> "存储空间恢复"
                        else -> "系统事件"
                    }
                val summary =
                    when (action) {
                        Intent.ACTION_POWER_CONNECTED -> "系统检测到设备已接入电源。"
                        Intent.ACTION_POWER_DISCONNECTED -> "系统检测到设备已离开电源。"
                        Intent.ACTION_TIME_CHANGED -> "系统时间发生变化。"
                        Intent.ACTION_TIMEZONE_CHANGED -> "系统时区发生变化。"
                        Intent.ACTION_LOCALE_CHANGED -> "系统语言环境发生变化。"
                        Intent.ACTION_MY_PACKAGE_REPLACED -> "应用已升级，assistant OS 已重新接管。"
                        Intent.ACTION_BATTERY_LOW -> "系统检测到电量进入低电量状态。"
                        Intent.ACTION_BATTERY_OKAY -> "系统检测到电量已脱离低电量状态。"
                        Intent.ACTION_AIRPLANE_MODE_CHANGED -> "飞行模式开关状态发生变化。"
                        Intent.ACTION_DEVICE_STORAGE_LOW -> "系统检测到设备存储空间不足。"
                        Intent.ACTION_DEVICE_STORAGE_OK -> "系统检测到设备存储空间已恢复。"
                        else -> action.ifBlank { "unknown_system_event" }
                    }
                val gate =
                    AssistantSignalProviderStore.evaluateIngress(
                        providerId = "system_broadcast",
                        source = "system_broadcast",
                        trustLevel = AssistantSignalProviderTrustLevel.SYSTEM,
                    )
                if (!gate.allows) {
                    return
                }
                AssistantExternalSignalStore.recordProviderSignal(
                    type = AssistantExternalSignalType.SYSTEM_EVENT,
                    capability = SessionCapabilityKey.REFRESH_ASSISTANT_OS,
                    title = title,
                    summary = summary,
                    source = "system_broadcast",
                    signalKey = action.ifBlank { title },
                    payload = mapOf("action" to action),
                )
                AssistantSignalProviderStore.markSignalObserved(
                    providerId = "system_broadcast",
                    source = "system_broadcast",
                    trustLevel = AssistantSignalProviderTrustLevel.SYSTEM,
                    reason = summary,
                )
                when (action) {
                    Intent.ACTION_BATTERY_LOW,
                    Intent.ACTION_BATTERY_OKAY,
                    -> {
                        lastBatterySignature = action
                        AssistantSignalProviderStore.markProbeResult(
                            providerId = "battery_status",
                            success = true,
                            reason = action.substringAfterLast('.').lowercase(),
                        )
                    }

                    Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                        AssistantSignalProviderStore.markProbeResult(
                            providerId = "network_status",
                            success = true,
                            reason = "airplane_mode_changed",
                        )
                    }
                }
            }
        }

    private val packageReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val action = intent.action.orEmpty()
                val packageName = intent.data?.schemeSpecificPart.orEmpty()
                if (packageName.isBlank()) return
                val gate =
                    AssistantSignalProviderStore.evaluateIngress(
                        providerId = "package_broadcast",
                        source = "package_broadcast",
                        trustLevel = AssistantSignalProviderTrustLevel.HIGH,
                    )
                if (!gate.allows) {
                    return
                }
                AssistantExternalSignalStore.recordProviderSignal(
                    type = AssistantExternalSignalType.SYSTEM_EVENT,
                    capability = SessionCapabilityKey.REFRESH_ASSISTANT_OS,
                    title = "应用包事件",
                    summary = "${action.substringAfterLast('.')} | $packageName",
                    source = "package_broadcast",
                    signalKey = "$action:$packageName",
                    payload =
                        mapOf(
                            "action" to action,
                            "package_name" to packageName,
                        ),
                )
                AssistantSignalProviderStore.markSignalObserved(
                    providerId = "package_broadcast",
                    source = "package_broadcast",
                    trustLevel = AssistantSignalProviderTrustLevel.HIGH,
                    reason = packageName,
                )
            }
        }

    private fun registerReceiver(
        context: Context,
        receiver: BroadcastReceiver,
        filter: IntentFilter,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private fun pollClipboard(
        context: Context,
        policy: AssistantProviderPollingPolicy,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastClipboardPollAtMs < CLIPBOARD_POLL_INTERVAL_MS) {
            return
        }
        lastClipboardPollAtMs = now
        if (!policy.allowInteractiveProviders) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = "clipboard_poller",
                success = true,
                reason = "clipboard_skipped_noninteractive",
            )
            return
        }
        val foregroundPackage = DebugAgentStore.uiState.value.foregroundPackage
        if (foregroundPackage != context.packageName) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = "clipboard_poller",
                success = true,
                reason = "clipboard_skipped_background",
            )
            return
        }
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip =
            runCatching { manager.primaryClip }.getOrElse {
                AssistantSignalProviderStore.markProbeResult(
                    providerId = "clipboard_poller",
                    success = false,
                    reason = "clipboard_read_failed",
                    cooldownMs = 60_000L,
                )
                return
            } ?: run {
                AssistantSignalProviderStore.markProbeResult(
                    providerId = "clipboard_poller",
                    success = true,
                    reason = "clipboard_idle",
                )
                return
            }
        val text =
            buildString {
                for (index in 0 until clip.itemCount) {
                    clip.getItemAt(index)?.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }?.let {
                        if (isNotEmpty()) append('\n')
                        append(it)
                    }
                }
            }.trim()
        if (text.isBlank()) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = "clipboard_poller",
                success = true,
                reason = "clipboard_idle",
            )
            return
        }
        val signature = text.take(240)
        if (signature == lastClipboardSignature) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = "clipboard_poller",
                success = true,
                reason = "clipboard_unchanged",
            )
            return
        }
        val gate =
            AssistantSignalProviderStore.evaluateIngress(
                providerId = "clipboard_poller",
                source = "clipboard_poller",
                trustLevel = AssistantSignalProviderTrustLevel.MEDIUM,
            )
        if (!gate.allows) return
        lastClipboardSignature = signature
        AssistantExternalSignalStore.recordProviderSignal(
            type = AssistantExternalSignalType.CLIPBOARD,
            capability = SessionCapabilityKey.REFRESH_ASSISTANT_OS,
            title = "剪贴板更新",
            summary = signature.replace('\n', ' ').take(96),
            query = signature.take(120),
            source = "clipboard_poller",
            signalKey = signature,
            payload =
                mapOf(
                    "clipboard_preview" to signature.take(240),
                ),
        )
        AssistantSignalProviderStore.markSignalObserved(
            providerId = "clipboard_poller",
            source = "clipboard_poller",
            trustLevel = AssistantSignalProviderTrustLevel.MEDIUM,
            reason = signature.take(48),
        )
        AssistantSignalProviderStore.markProbeResult(
            providerId = "clipboard_poller",
            success = true,
            reason = "clipboard_signal_observed",
        )
    }

    private fun pollForegroundApp(
        reason: String,
    ) {
        val packageName = DebugAgentStore.uiState.value.foregroundPackage.ifBlank { return }
        if (packageName == AppRuntimeContext.get()?.packageName || packageName == lastForegroundPackage) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = "foreground_app",
                success = true,
                reason = "foreground_unchanged",
            )
            return
        }
        val gate =
            AssistantSignalProviderStore.evaluateIngress(
                providerId = "foreground_app",
                source = "foreground_app",
                trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            )
        if (!gate.allows) return
        lastForegroundPackage = packageName
        val profile = TaskRegistry.allProfiles().firstOrNull { it.packageName == packageName }
        AssistantExternalSignalStore.recordProviderSignal(
            type = AssistantExternalSignalType.APP_FOREGROUND,
            capability = if (profile != null) SessionCapabilityKey.START_SESSION else SessionCapabilityKey.REFRESH_ASSISTANT_OS,
            title = "前台应用切换",
            summary = profile?.displayName ?: packageName.substringAfterLast('.'),
            task = profile?.let { "观察并处理 ${it.displayName} 当前前台任务" }.orEmpty(),
            source = "foreground_app",
            signalKey = packageName,
            payload =
                buildMap {
                    put("package_name", packageName)
                    put("reason", reason)
                    profile?.id?.let { put("profile_id", it) }
                },
        )
        AssistantSignalProviderStore.markSignalObserved(
            providerId = "foreground_app",
            source = "foreground_app",
            trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            reason = packageName,
        )
        AssistantSignalProviderStore.markProbeResult(
            providerId = "foreground_app",
            success = true,
            reason = "foreground_signal_observed",
        )
    }

    private fun pollBatteryState(
        context: Context,
        reason: String,
    ) {
        val batteryManager = context.getSystemService(BatteryManager::class.java) ?: return
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceAtLeast(0)
        val chargingStatus =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val isCharging =
            chargingStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                chargingStatus == BatteryManager.BATTERY_STATUS_FULL
        val bucket =
            when {
                level in 0..15 -> "critical"
                level in 16..35 -> "low"
                level in 36..79 -> "normal"
                else -> "high"
            }
        val signature = "$bucket:$isCharging"
        if (signature == lastBatterySignature) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = "battery_status",
                success = true,
                reason = "battery_unchanged",
            )
            return
        }
        lastBatterySignature = signature
        AssistantExternalSignalStore.recordProviderSignal(
            type = AssistantExternalSignalType.SYSTEM_EVENT,
            capability = SessionCapabilityKey.REFRESH_ASSISTANT_OS,
            title = "电池状态变化",
            summary = "battery=$bucket level=$level charging=$isCharging",
            source = "battery_status",
            signalKey = signature,
            payload =
                mapOf(
                    "battery_bucket" to bucket,
                    "battery_level" to level.toString(),
                    "charging" to isCharging.toString(),
                    "poll_reason" to reason,
                ),
        )
        AssistantSignalProviderStore.markSignalObserved(
            providerId = "battery_status",
            source = "battery_status",
            trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            reason = "battery=$bucket",
        )
        AssistantSignalProviderStore.markProbeResult(
            providerId = "battery_status",
            success = true,
            reason = "battery_signal_observed",
        )
    }

    private fun pollNetworkState(
        context: Context,
        reason: String,
    ) {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        val connected = capabilities != null
        val transport =
            when {
                capabilities == null -> "offline"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
                else -> "other"
            }
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val metered = connectivityManager.isActiveNetworkMetered
        val signature = "$transport:$validated:$metered"
        if (signature == lastNetworkSignature) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = "network_status",
                success = true,
                reason = "network_unchanged",
            )
            return
        }
        lastNetworkSignature = signature
        AssistantExternalSignalStore.recordProviderSignal(
            type = AssistantExternalSignalType.SYSTEM_EVENT,
            capability = SessionCapabilityKey.REFRESH_ASSISTANT_OS,
            title = "网络状态变化",
            summary = "network=$transport validated=$validated metered=$metered",
            source = "network_status",
            signalKey = signature,
            payload =
                mapOf(
                    "connected" to connected.toString(),
                    "transport" to transport,
                    "validated" to validated.toString(),
                    "metered" to metered.toString(),
                    "poll_reason" to reason,
                ),
        )
        AssistantSignalProviderStore.markSignalObserved(
            providerId = "network_status",
            source = "network_status",
            trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            reason = transport,
        )
        AssistantSignalProviderStore.markProbeResult(
            providerId = "network_status",
            success = true,
            reason = "network_signal_observed",
        )
    }
}
