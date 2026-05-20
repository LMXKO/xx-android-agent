package com.lmx.xiaoxuanagent.taskprofile

import android.view.accessibility.AccessibilityNodeInfo
import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.GenericTaskDecisionPolicy
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.accessibility.ShoppingPageDetector
import com.lmx.xiaoxuanagent.runtime.AppPageState

object ShoppingTaskProfile : TaskProfile {
    override val id: String = "shopping_search"
    override val displayName: String = "购物搜索助手"
    override val packageName: String = "com.taobao.taobao"
    override val routeKeywords: List<String> =
        listOf(
            "淘宝",
            "天猫",
            "商品",
            "购物",
            "买",
            "推荐",
            "评价",
            "比价",
            "价格",
            "旗舰店",
            "零食",
            "衣服",
            "鞋",
            "数码",
        )
    override val capabilitySummary: String = "适合商品搜索、比价、看评价、看参数、挑选商品。"

    override fun detectPageState(
        foregroundPackage: String,
        root: AccessibilityNodeInfo,
    ): AppPageState {
        return if (foregroundPackage == packageName) {
            ShoppingPageDetector.detect(root)
        } else {
            AppPageState.Unknown
        }
    }

    override fun refineDecision(
        task: String,
        observation: ScreenObservation,
        decision: AgentDecision,
    ): AgentDecision = GenericTaskDecisionPolicy.refineDecision(task, observation, decision)

    override fun summarizeTaskConstraints(task: String): String =
        GenericTaskDecisionPolicy.summarizeConstraints(task)
}
