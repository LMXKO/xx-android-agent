package com.lmx.xiaoxuanagent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.lmx.xiaoxuanagent.runtime.ConnectedAppLifecycleStore
import java.net.URLEncoder

object PlatformConnectedAppToolService {
    fun readCapabilities(
        appId: String,
    ): List<String> {
        val descriptors =
            if (appId.isBlank()) {
                ConnectedAppCatalog.descriptors()
            } else {
                listOfNotNull(ConnectedAppCatalog.findByAppId(appId))
            }
        return descriptors.flatMap { descriptor ->
            buildList {
                add("${descriptor.appId} | ${descriptor.title} | ${descriptor.summary}")
                val lifecycle = com.lmx.xiaoxuanagent.runtime.ConnectedAppLifecycleStore.snapshot(descriptor.appId)
                add("status | ${descriptor.appId} | connected=${lifecycle.connected} | confirm=${lifecycle.confirmationMode}")
                descriptor.operations.forEach { operation ->
                    val path = ConnectedAppGoldenPathRegistry.find(descriptor.appId, operation)
                    add(
                        buildString {
                            append("op | ").append(descriptor.appId).append(" | ").append(operation)
                            path?.let {
                                append(" | state=").append(it.targetState)
                                append(" | risk=").append(it.riskLevel)
                                append(" | flow=").append(it.flowKind)
                                append(" | boundary=").append(it.automationBoundary)
                                append(" | handoff=").append(it.handoffPoint)
                            }
                        },
                    )
                }
            }
        }.ifEmpty { listOf("connected_app | 未找到匹配能力") }
    }

    fun execute(
        service: AccessibilityService,
        action: AgentAction.ExecuteConnectedAppAction,
    ): AgentExecutionResult {
        val descriptor = ConnectedAppCatalog.findByAppId(action.appId)
            ?: return AgentExecutionResult("未找到 connected app：${action.appId}", keepRunning = false)
        val lifecycle = ConnectedAppLifecycleStore.snapshot(descriptor.appId)
        if (!lifecycle.connected) {
            return AgentExecutionResult(
                "connected app ${descriptor.title} 当前已断开，请在治理页重新连接后再试。",
                keepRunning = false,
            )
        }
        ConnectedAppFocusExecutionSupport.buildPlan(service, action)?.let { plan ->
            return runIntent(
                service = service,
                appId = action.appId,
                operation = action.operation,
                intent = plan.intent,
                successMessage = plan.successMessage,
                detailLines = plan.detailLines,
                groundingSource = plan.groundingSource,
                recommendedWaitMs = plan.recommendedWaitMs,
                receiptState = plan.receiptState,
                handoffRequired = plan.handoffRequired,
            )
        }
        return when (descriptor.appId) {
            "system_settings_assistant" -> executeSettings(service, action)
            "amap_assistant" -> executeAmap(service, action)
            // buildPlan 已对其余连接器提供 golden-path / 拉起兜底；走到这里说明目标 App 无法打开（多半未安装）。
            else -> AgentExecutionResult("未能打开 connected app ${descriptor.title}（可能未安装或被禁用）。", keepRunning = false)
        }
    }

    private fun executeSettings(
        service: AccessibilityService,
        action: AgentAction.ExecuteConnectedAppAction,
    ): AgentExecutionResult {
        val intent =
            when (action.operation) {
                "open_accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                "open_notifications" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                "open_wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
                "open_battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                else -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runIntent(
            service = service,
            appId = action.appId,
            operation = action.operation,
            intent = intent,
            successMessage = "已通过 connected app 打开系统设置：${action.operation}。",
            receiptState = "settings_page_ready",
        )
    }

    private fun executeAmap(
        service: AccessibilityService,
        action: AgentAction.ExecuteConnectedAppAction,
    ): AgentExecutionResult {
        val primary = action.primary.trim().ifBlank { action.note.trim() }
        if (primary.isBlank()) {
            return AgentExecutionResult("地图 connected app 缺少地点或目的地。", keepRunning = false)
        }
        val encoded = URLEncoder.encode(primary, Charsets.UTF_8.name())
        val intent =
            when (action.operation) {
                "start_navigation" ->
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("androidamap://navi?sourceApplication=xiaoxuan&poiname=$encoded&dev=0"),
                    )
                "open_route" ->
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("androidamap://route?sourceApplication=xiaoxuan&dname=$encoded&dev=0&t=0"),
                    )
                else ->
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("geo:0,0?q=$encoded"),
                    )
            }.apply {
                `package` = "com.autonavi.minimap"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return runIntent(
            service = service,
            appId = action.appId,
            operation = action.operation,
            intent = intent,
            successMessage = "已通过 connected app 拉起高德：${action.operation} -> ${primary.take(48)}。",
            detailLines =
                listOf(
                    "connected_app=amap_assistant",
                    "operation=${action.operation}",
                    "primary=${primary.take(64)}",
                    "mode=deep_link_progression",
                    "preferred_fallback=semantic.return_to_target_app>semantic.populate_primary_input>semantic.submit_primary_action",
                ),
            receiptState =
                when (action.operation) {
                    "start_navigation" -> "navigation_ready"
                    "open_route" -> "route_card_ready"
                    else -> "place_search_ready"
                },
            handoffRequired = action.operation == "start_navigation",
        )
    }

    private fun runIntent(
        service: AccessibilityService,
        appId: String = "",
        operation: String = "",
        intent: Intent,
        successMessage: String,
        detailLines: List<String> = emptyList(),
        groundingSource: String = "connected_app",
        recommendedWaitMs: Long = 1_000L,
        receiptState: String = "",
        handoffRequired: Boolean = false,
    ): AgentExecutionResult =
        runCatching {
            service.startActivity(intent)
        }.fold(
            onSuccess = {
                if (appId.isNotBlank()) {
                    ConnectedAppExecutionReceiptStore.record(
                        ConnectedAppExecutionReceipt(
                            appId = appId,
                            operation = operation,
                            state = receiptState,
                            summary = successMessage,
                            detailLines = detailLines,
                            handoffRequired = handoffRequired,
                        ),
                    )
                }
                AgentExecutionResult(
                    message = successMessage,
                    keepRunning = true,
                    requiresObservationCheck = true,
                    recommendedWaitMs = recommendedWaitMs,
                    shouldImmediateReplan = true,
                    groundingSource = groundingSource,
                    toolRuntimeDetailLines = detailLines,
                )
            },
            onFailure = {
                AgentExecutionResult(
                    message = "connected app 执行失败：${it.message.orEmpty()}",
                    keepRunning = false,
                )
            },
        )
}
