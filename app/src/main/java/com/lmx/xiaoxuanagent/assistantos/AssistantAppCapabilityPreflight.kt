package com.lmx.xiaoxuanagent.assistantos

data class AssistantAppCapabilityPreflightInput(
    val accessibilityConnected: Boolean = false,
    val postNotificationPermissionGranted: Boolean = true,
    val notificationControlEnabled: Boolean = true,
    val notificationProviderReady: Boolean = false,
    val notificationListenerEnabled: Boolean = false,
    val overlayFeatureEnabled: Boolean = false,
    val overlayPermissionGranted: Boolean = false,
    val shareSheetEnabled: Boolean = true,
    val shortcutSupported: Boolean = true,
    val widgetEnabled: Boolean = true,
    val tileSupported: Boolean = true,
    val speechRecognizerAvailable: Boolean = true,
    val recordAudioPermissionGranted: Boolean = false,
    val voiceBlockedByApproval: Boolean = false,
    val foregroundServiceDeclared: Boolean = true,
    val voiceForegroundServiceDeclared: Boolean = true,
    val runtimeResidencyLikely: Boolean = true,
    val bootReceiverDeclared: Boolean = true,
    val batteryOptimizationIgnored: Boolean = false,
    val screenshotStatus: String = "",
    val targetPackageName: String = "",
    val targetAppLaunchable: Boolean = true,
    val providerStates: List<AssistantSignalProviderState> = emptyList(),
)

data class AssistantAppCapabilityPreflightItem(
    val capability: String,
    val status: String,
    val summary: String,
    val fallbackSurface: String = "",
    val recommendedCommand: String = "",
    val fragileReason: String = "",
)

data class AssistantAppCapabilityPreflightSnapshot(
    val status: String = "unknown",
    val readyCount: Int = 0,
    val degradedCount: Int = 0,
    val blockedCount: Int = 0,
    val unsupportedCount: Int = 0,
    val summary: String = "",
    val lines: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val items: List<AssistantAppCapabilityPreflightItem> = emptyList(),
)

object AssistantAppCapabilityPreflight {
    fun evaluate(input: AssistantAppCapabilityPreflightInput): AssistantAppCapabilityPreflightSnapshot {
        val notificationProvider =
            input.providerStates.firstOrNull { it.providerId == "notification_listener" }
        val notificationProviderHealthy =
            input.notificationProviderReady ||
                notificationProvider?.let { it.enabled && it.healthScore >= 50 && it.failureCount < 3 } == true
        val items =
            buildList {
                add(accessibilityRuntime(input.accessibilityConnected))
                add(notificationControl(input))
                add(notificationListener(input, notificationProviderHealthy))
                add(overlayControl(input))
                add(shareSheet(input.shareSheetEnabled))
                add(shortcutEntry(input.shortcutSupported))
                add(widgetEntry(input.widgetEnabled))
                add(quickSettingsTile(input.tileSupported))
                add(voiceRuntime(input))
                add(foregroundResidency(input))
                add(bootRestore(input.bootReceiverDeclared))
                add(screenshotEvidence(input.screenshotStatus))
                add(targetAppLaunch(input.targetPackageName, input.targetAppLaunchable))
                add(providerHealth(input.providerStates))
            }
        val ready = items.count { it.status == STATUS_READY }
        val degraded = items.count { it.status == STATUS_DEGRADED }
        val blocked = items.count { it.status == STATUS_BLOCKED }
        val unsupported = items.count { it.status == STATUS_UNSUPPORTED }
        val status =
            when {
                blocked > 0 -> "red"
                degraded > 0 || unsupported > 0 -> "yellow"
                items.isNotEmpty() -> "green"
                else -> "unknown"
            }
        val summary =
            "普通 App 能力预检 status=$status ready=$ready degraded=$degraded blocked=$blocked unsupported=$unsupported"
        return AssistantAppCapabilityPreflightSnapshot(
            status = status,
            readyCount = ready,
            degradedCount = degraded,
            blockedCount = blocked,
            unsupportedCount = unsupported,
            summary = summary,
            lines =
                buildList {
                    add("app_preflight | status=$status | ready=$ready degraded=$degraded blocked=$blocked unsupported=$unsupported")
                    items.forEach { item ->
                        add(
                            buildString {
                                append(item.capability).append(" | ").append(item.status).append(" | ").append(item.summary)
                                if (item.fallbackSurface.isNotBlank()) append(" | fallback=").append(item.fallbackSurface)
                                if (item.fragileReason.isNotBlank()) append(" | reason=").append(item.fragileReason)
                            },
                        )
                    }
                },
            recommendedCommands =
                items.mapNotNull { it.recommendedCommand.takeIf(String::isNotBlank) }
                    .distinct()
                    .take(8),
            items = items,
        )
    }

    private fun accessibilityRuntime(connected: Boolean): AssistantAppCapabilityPreflightItem =
        if (connected) {
            ready("accessibility_runtime", "无障碍执行通道已连接。")
        } else {
            blocked(
                capability = "accessibility_runtime",
                summary = "无障碍执行通道未连接，普通 App 无法可靠读取和操作其他 App UI。",
                fallbackSurface = "main_app, notification, share_sheet",
                recommendedCommand = "/entry-surfaces",
                fragileReason = "跨 App UI 操作依赖用户手动开启 AccessibilityService。",
            )
        }

    private fun notificationControl(input: AssistantAppCapabilityPreflightInput): AssistantAppCapabilityPreflightItem =
        when {
            !input.notificationControlEnabled ->
                ready("notification_control", "通知控制台未启用，入口会回落到主 App。", fallbackSurface = "main_app")

            input.postNotificationPermissionGranted ->
                ready("notification_control", "通知控制台具备 POST_NOTIFICATIONS 权限。")

            else ->
                degraded(
                    capability = "notification_control",
                    summary = "缺少通知运行时权限，暂停/继续/确认入口不能稳定出现在通知栏。",
                    fallbackSurface = "main_app, widget, share_sheet",
                    recommendedCommand = "/entry-surfaces",
                    fragileReason = "Android 13+ 通知权限可被用户或 ROM 策略关闭。",
                )
        }

    private fun notificationListener(
        input: AssistantAppCapabilityPreflightInput,
        providerHealthy: Boolean,
    ): AssistantAppCapabilityPreflightItem =
        when {
            input.notificationListenerEnabled && providerHealthy ->
                ready("notification_listener", "通知监听 provider 已启用且健康。")

            !input.notificationListenerEnabled ->
                degraded(
                    capability = "notification_listener",
                    summary = "通知监听授权未开启，外部 App 通知只能走手动入口或最近状态回放。",
                    fallbackSurface = "share_sheet, main_app, inbox",
                    recommendedCommand = "/provider-policy",
                    fragileReason = "NotificationListenerService 需要用户在系统设置里单独授权，且可能被系统回收。",
                )

            else ->
                degraded(
                    capability = "notification_listener",
                    summary = "通知监听 provider 已授权但健康度不足或仍未注册。",
                    fallbackSurface = "share_sheet, main_app, inbox",
                    recommendedCommand = "/provider-policy",
                    fragileReason = "监听服务连接和 provider 状态可能因系统重启、冷启动或失败 cooldown 失效。",
                )
        }

    private fun overlayControl(input: AssistantAppCapabilityPreflightInput): AssistantAppCapabilityPreflightItem =
        when {
            !input.overlayFeatureEnabled ->
                ready("overlay_control", "悬浮入口未启用，当前不要求 overlay 权限。", fallbackSurface = "notification, main_app")

            input.overlayPermissionGranted ->
                ready("overlay_control", "悬浮入口具备 SYSTEM_ALERT_WINDOW 权限。")

            else ->
                degraded(
                    capability = "overlay_control",
                    summary = "悬浮入口已启用但缺少 overlay 权限，会降级到通知或主 App 控制台。",
                    fallbackSurface = "notification, main_app",
                    recommendedCommand = "/entry-surfaces",
                    fragileReason = "悬浮窗权限由用户和 ROM 特殊权限页控制，普通 App 不能静默授予。",
                )
        }

    private fun shareSheet(enabled: Boolean): AssistantAppCapabilityPreflightItem =
        if (enabled) {
            ready("share_sheet", "分享入口已启用，可作为通知/监听失效时的手动输入通道。")
        } else {
            degraded(
                capability = "share_sheet",
                summary = "分享入口被关闭，外部内容只能回到主 App 手动输入。",
                fallbackSurface = "main_app",
                recommendedCommand = "/entry-surfaces",
                fragileReason = "普通 App 最稳的跨应用输入路径之一不可用。",
            )
        }

    private fun shortcutEntry(supported: Boolean): AssistantAppCapabilityPreflightItem =
        if (supported) {
            ready("shortcut_entry", "动态快捷方式入口可用。")
        } else {
            unsupported(
                capability = "shortcut_entry",
                summary = "当前环境不支持动态快捷方式入口。",
                fallbackSurface = "main_app, widget",
                recommendedCommand = "/entry-surfaces",
                fragileReason = "桌面快捷方式依赖 launcher 支持。",
            )
        }

    private fun widgetEntry(enabled: Boolean): AssistantAppCapabilityPreflightItem =
        if (enabled) {
            ready("widget_entry", "桌面组件入口已启用。")
        } else {
            degraded(
                capability = "widget_entry",
                summary = "桌面组件入口未启用，冷启动恢复少一个稳定入口。",
                fallbackSurface = "main_app, notification",
                recommendedCommand = "/entry-surfaces",
                fragileReason = "普通 App 的后台恢复需要多个用户可见入口互为备份。",
            )
        }

    private fun quickSettingsTile(supported: Boolean): AssistantAppCapabilityPreflightItem =
        if (supported) {
            ready("quick_settings_tile", "快捷设置 Tile 支持可用。")
        } else {
            unsupported(
                capability = "quick_settings_tile",
                summary = "当前系统版本不支持快捷设置 Tile。",
                fallbackSurface = "notification, widget",
                recommendedCommand = "/entry-surfaces",
                fragileReason = "Quick Settings Tile 依赖系统版本和用户手动添加。",
            )
        }

    private fun voiceRuntime(input: AssistantAppCapabilityPreflightInput): AssistantAppCapabilityPreflightItem =
        when {
            !input.voiceForegroundServiceDeclared ->
                blocked(
                    capability = "voice_runtime",
                    summary = "语音前台服务未声明，连续语音入口无法稳定运行。",
                    fallbackSurface = "main_app, text_command",
                    recommendedCommand = "/product-shell --section entry",
                    fragileReason = "连续语音需要 microphone foreground service 承载。",
                )

            input.voiceBlockedByApproval ->
                degraded(
                    capability = "voice_runtime",
                    summary = "当前存在待确认高风险动作，语音自动执行已按安全策略降级。",
                    fallbackSurface = "approval_center, main_app",
                    recommendedCommand = "/safety-center",
                    fragileReason = "确认步骤必须交给用户，不能由普通 App 或语音静默越权。",
                )

            !input.speechRecognizerAvailable ->
                unsupported(
                    capability = "voice_runtime",
                    summary = "设备没有可用 SpeechRecognizer，语音入口降级为文字入口。",
                    fallbackSurface = "main_app, share_sheet",
                    recommendedCommand = "/product-shell --section entry",
                    fragileReason = "系统语音识别服务可能缺失、被禁用或不可联网。",
                )

            !input.recordAudioPermissionGranted ->
                degraded(
                    capability = "voice_runtime",
                    summary = "缺少录音权限，语音入口只能展示修复态。",
                    fallbackSurface = "main_app, share_sheet",
                    recommendedCommand = "/product-shell --section entry",
                    fragileReason = "RECORD_AUDIO 是用户授权，普通 App 不能静默打开。",
                )

            else ->
                ready("voice_runtime", "语音识别与录音权限已就绪。")
        }

    private fun foregroundResidency(input: AssistantAppCapabilityPreflightInput): AssistantAppCapabilityPreflightItem =
        when {
            !input.foregroundServiceDeclared ->
                blocked(
                    capability = "foreground_residency",
                    summary = "运行时前台服务未声明，任务执行无法获得普通 App 范围内的驻留保障。",
                    fallbackSurface = "main_app",
                    recommendedCommand = "/product-diagnostics",
                    fragileReason = "后台任务必须依赖前台服务、通知和用户可见入口组合。",
                )

            !input.runtimeResidencyLikely ->
                degraded(
                    capability = "foreground_residency",
                    summary = "运行时驻留信号不稳定，长任务会降级为可恢复会话。",
                    fallbackSurface = "notification, boot_restore, resumable_session",
                    recommendedCommand = "/operator",
                    fragileReason = "后台存活受 Android 后台限制、厂商策略和省电模式影响。",
                )

            !input.batteryOptimizationIgnored ->
                degraded(
                    capability = "foreground_residency",
                    summary = "未加入电池优化白名单，长时间后台执行需要依赖前台通知和恢复入口兜底。",
                    fallbackSurface = "notification, widget, boot_restore",
                    recommendedCommand = "/product-diagnostics",
                    fragileReason = "省电策略和厂商后台管理可能杀掉普通 App 进程。",
                )

            else ->
                ready("foreground_residency", "前台服务与电池优化状态支持长任务驻留。")
        }

    private fun bootRestore(declared: Boolean): AssistantAppCapabilityPreflightItem =
        if (declared) {
            ready("boot_restore", "开机/应用更新后的恢复 receiver 已声明。")
        } else {
            degraded(
                capability = "boot_restore",
                summary = "开机恢复 receiver 未声明，重启后只能等待用户手动打开 App 恢复任务。",
                fallbackSurface = "main_app, widget",
                recommendedCommand = "/product-diagnostics",
                fragileReason = "普通 App 不能保证重启后自动恢复，必须依赖系统广播和用户可见入口。",
            )
        }

    private fun screenshotEvidence(status: String): AssistantAppCapabilityPreflightItem {
        val normalized = status.trim().lowercase()
        val ready =
            normalized.isNotBlank() &&
                normalized != "-" &&
                listOf("ok", "success", "ready", "captured", "saved", "截图成功").any { normalized.contains(it) } &&
                listOf("fail", "error", "异常", "失败").none { normalized.contains(it) }
        return if (ready) {
            ready("screenshot_evidence", "截图证据链最近一次采集成功。")
        } else {
            degraded(
                capability = "screenshot_evidence",
                summary = "缺少最近一次成功截图证据，视觉确认会降级为无障碍树和 replay artifact。",
                fallbackSurface = "accessibility_tree, replay_artifact",
                recommendedCommand = "/viewer",
                fragileReason = "AccessibilityService.takeScreenshot 受系统版本、窗口安全标记和服务状态影响。",
            )
        }
    }

    private fun targetAppLaunch(
        packageName: String,
        launchable: Boolean,
    ): AssistantAppCapabilityPreflightItem =
        when {
            packageName.isBlank() ->
                ready("target_app_launch", "当前没有绑定目标 App。")

            launchable ->
                ready("target_app_launch", "目标 App 可通过 launcher intent 返回现场。")

            else ->
                blocked(
                    capability = "target_app_launch",
                    summary = "目标 App 不可启动，无法打开现场继续执行。",
                    fallbackSurface = "main_app, operator_console",
                    recommendedCommand = "/operator",
                    fragileReason = "普通 App 只能启动已安装且对 launcher 暴露入口的目标应用。",
                )
        }

    private fun providerHealth(providers: List<AssistantSignalProviderState>): AssistantAppCapabilityPreflightItem {
        val unhealthy = providers.filter { it.healthScore < 50 || it.failureCount >= 3 }
        val cooldown = providers.count { it.cooldownUntilMs > System.currentTimeMillis() }
        return if (unhealthy.isEmpty() && cooldown == 0) {
            ready("provider_health", "provider 健康度没有明显压力。")
        } else {
            degraded(
                capability = "provider_health",
                summary = "有 ${unhealthy.size} 个 provider 健康度不足，$cooldown 个 provider 仍在 cooldown。",
                fallbackSurface = "manual_entry, replay, provider_policy",
                recommendedCommand = "/provider-policy",
                fragileReason = "外部信号 provider 会因权限、频率限制、异常或系统回收进入降级。",
            )
        }
    }

    private fun ready(
        capability: String,
        summary: String,
        fallbackSurface: String = "",
    ) = AssistantAppCapabilityPreflightItem(
        capability = capability,
        status = STATUS_READY,
        summary = summary,
        fallbackSurface = fallbackSurface,
    )

    private fun degraded(
        capability: String,
        summary: String,
        fallbackSurface: String,
        recommendedCommand: String,
        fragileReason: String,
    ) = AssistantAppCapabilityPreflightItem(
        capability = capability,
        status = STATUS_DEGRADED,
        summary = summary,
        fallbackSurface = fallbackSurface,
        recommendedCommand = recommendedCommand,
        fragileReason = fragileReason,
    )

    private fun blocked(
        capability: String,
        summary: String,
        fallbackSurface: String,
        recommendedCommand: String,
        fragileReason: String,
    ) = AssistantAppCapabilityPreflightItem(
        capability = capability,
        status = STATUS_BLOCKED,
        summary = summary,
        fallbackSurface = fallbackSurface,
        recommendedCommand = recommendedCommand,
        fragileReason = fragileReason,
    )

    private fun unsupported(
        capability: String,
        summary: String,
        fallbackSurface: String,
        recommendedCommand: String,
        fragileReason: String,
    ) = AssistantAppCapabilityPreflightItem(
        capability = capability,
        status = STATUS_UNSUPPORTED,
        summary = summary,
        fallbackSurface = fallbackSurface,
        recommendedCommand = recommendedCommand,
        fragileReason = fragileReason,
    )

    private const val STATUS_READY = "ready"
    private const val STATUS_DEGRADED = "degraded"
    private const val STATUS_BLOCKED = "blocked"
    private const val STATUS_UNSUPPORTED = "unsupported"
}
