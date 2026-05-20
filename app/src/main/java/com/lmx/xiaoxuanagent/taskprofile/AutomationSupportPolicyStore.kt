package com.lmx.xiaoxuanagent.taskprofile

import com.lmx.xiaoxuanagent.harness.ScreenAutomationCapabilityStore
import com.lmx.xiaoxuanagent.harness.ScreenAutomationReleaseGateStore
import com.lmx.xiaoxuanagent.harness.ScreenAutomationTemplateCapabilityStore

data class AutomationSupportPolicy(
    val profileId: String,
    val packageName: String,
    val supportsLaunch: Boolean = true,
    val supportsRead: Boolean = true,
    val supportsFill: Boolean = true,
    val supportsSubmit: Boolean = true,
    val requiresFinalHandoff: Boolean = false,
    val supervisionMode: String = "foreground_takeover",
    val preferredExecutionPath: String = "gui_fallback",
    val connectedAppId: String = "",
    val releaseGateStatus: String = "ungated",
    val releaseGateSummary: String = "",
    val productSupportTier: String = "experimental",
    val productSupportSummary: String = "",
    val handoffSummary: String = "",
)

object AutomationSupportPolicyStore {
    private val defaultPolicy =
        AutomationSupportPolicy(
            profileId = "default",
            packageName = "",
            supportsLaunch = true,
            supportsRead = true,
            supportsFill = true,
            supportsSubmit = true,
            requiresFinalHandoff = false,
            supervisionMode = "foreground_takeover",
            preferredExecutionPath = "gui_fallback",
            productSupportTier = "experimental",
            productSupportSummary = "默认按通用前台自动化处理。",
            handoffSummary = "当前任务可由前台接管模式连续执行。",
        )

    private val policies =
        listOf(
            AutomationSupportPolicy(
                profileId = "system_settings_assistant",
                packageName = "com.android.settings",
                supportsSubmit = false,
                requiresFinalHandoff = false,
                preferredExecutionPath = "connected_app_first",
                connectedAppId = "system_settings_assistant",
                handoffSummary = "系统设置类任务以导航和定位页面为主，通常不需要最终人工确认。",
            ),
            AutomationSupportPolicy(
                profileId = ShoppingTaskProfile.id,
                packageName = ShoppingTaskProfile.packageName,
                supportsSubmit = false,
                requiresFinalHandoff = true,
                handoffSummary = "购物任务可自动完成浏览、筛选与填写，但下单/支付前应交还用户确认。",
            ),
            AutomationSupportPolicy(
                profileId = "xianyu_assistant",
                packageName = "com.taobao.idlefish",
                supportsSubmit = false,
                requiresFinalHandoff = true,
                handoffSummary = "二手交易任务可自动浏览和筛选，但成交确认前应交还用户。",
            ),
            AutomationSupportPolicy(
                profileId = "jd_assistant",
                packageName = "com.jingdong.app.mall",
                supportsSubmit = false,
                requiresFinalHandoff = true,
                handoffSummary = "京东任务适合自动搜索、比价和浏览，订单确认前应人工接手。",
            ),
            AutomationSupportPolicy(
                profileId = "pdd_assistant",
                packageName = "com.xunmeng.pinduoduo",
                supportsSubmit = false,
                requiresFinalHandoff = true,
                handoffSummary = "拼多多任务适合自动搜索和挑选，最终下单前应交给用户。",
            ),
            AutomationSupportPolicy(
                profileId = "meituan_assistant",
                packageName = "com.sankuai.meituan",
                supportsSubmit = false,
                requiresFinalHandoff = true,
                preferredExecutionPath = "connected_app_first",
                connectedAppId = "meituan_assistant",
                handoffSummary = "外卖和到店任务可自动挑选与填写，但支付或最后确认前应交还用户。",
            ),
            AutomationSupportPolicy(
                profileId = "amap_assistant",
                packageName = "com.autonavi.minimap",
                supportsSubmit = false,
                requiresFinalHandoff = true,
                preferredExecutionPath = "connected_app_first",
                connectedAppId = "amap_assistant",
                handoffSummary = "地图任务适合自动搜地点和配路线，真正开始导航前交给用户确认。",
            ),
            AutomationSupportPolicy(
                profileId = "wechat_assistant",
                packageName = "com.tencent.mm",
                supportsSubmit = false,
                requiresFinalHandoff = true,
                preferredExecutionPath = "connected_app_first",
                connectedAppId = "wechat_assistant",
                handoffSummary = "聊天任务可自动找会话和填内容，真正发送前需用户点最后一步。",
            ),
            AutomationSupportPolicy(
                profileId = "alipay_assistant",
                packageName = "com.eg.android.AlipayGphone",
                supportsSubmit = false,
                requiresFinalHandoff = true,
                handoffSummary = "支付和账单任务可自动找入口与填写，但转账/付款前必须交还用户。",
            ),
        )

    private val profilePolicies = policies.associateBy { it.profileId }
    private val packagePolicies = policies.filter { it.packageName.isNotBlank() }.associateBy { it.packageName }

    fun staticPolicy(
        profileId: String,
        packageName: String = "",
    ): AutomationSupportPolicy =
        profilePolicies[profileId]
            ?: packagePolicies[packageName]
            ?: defaultPolicy.copy(packageName = packageName)

    fun resolve(
        profileId: String,
        packageName: String = "",
        taskIntent: String = "",
        planStage: String = "",
        pageState: String = "",
        actionTemplate: String = "",
    ): AutomationSupportPolicy =
        mergeAutomationPolicy(
            base = staticPolicy(profileId = profileId, packageName = packageName),
            learned = ScreenAutomationCapabilityStore.snapshot(profileId = profileId, packageName = packageName, taskIntent = taskIntent),
            stageLearned =
                ScreenAutomationTemplateCapabilityStore.snapshot(
                    profileId = profileId,
                    packageName = packageName,
                    taskIntent = taskIntent,
                    planStage = planStage,
                    pageState = pageState,
                    actionTemplate = actionTemplate,
                ),
            releaseGate = ScreenAutomationReleaseGateStore.snapshot(profileId = profileId, packageName = packageName, taskIntent = taskIntent),
            productSupport = ScreenAutomationProductSupportStore.snapshot(profileId = profileId, packageName = packageName),
        )

    fun resolve(
        profile: TaskProfile,
    ): AutomationSupportPolicy = resolve(profileId = profile.id, packageName = profile.packageName)

    fun summaryLine(
        policy: AutomationSupportPolicy,
    ): String =
        buildString {
            append("automation=").append(policy.supervisionMode)
            append(" | path=").append(policy.preferredExecutionPath)
            append(" | fill=").append(policy.supportsFill)
            append(" | submit=").append(policy.supportsSubmit)
            append(" | handoff=").append(policy.requiresFinalHandoff)
            policy.connectedAppId.takeIf { it.isNotBlank() }?.let { append(" | connected=").append(it) }
            policy.releaseGateStatus.takeIf { it.isNotBlank() && it != "ungated" }?.let { append(" | gate=").append(it) }
            append(" | tier=").append(policy.productSupportTier)
            ScreenAutomationCapabilityStore
                .snapshot(profileId = policy.profileId, packageName = policy.packageName)
                ?.let { snapshot ->
                    append(" | learned=").append(snapshot.confidenceBand)
                    append(" | recent=").append((snapshot.recentSuccessRate * 100.0).toInt()).append('%')
                }
        }
}
