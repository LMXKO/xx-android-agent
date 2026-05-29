package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖此前零单测的核心定位启发式（候选元素 是动作控件 / 信息状态标签 的判定，以及 action-head 区域提案
 * 的不变量）。这些是"点哪个元素"准不准的关键魔数逻辑，全部为纯函数、不依赖 Android Bitmap/Rect。
 */
class PerceptionGroundingHeuristicsTest {
    private fun el(
        text: String = "",
        viewId: String = "",
        className: String = "android.widget.TextView",
        accessibilityLabel: String = "",
    ): UiElementObservation =
        UiElementObservation(
            id = "e",
            text = text,
            viewId = viewId,
            className = className,
            bounds = "[0,0][10,10]",
            clickable = true,
            editable = false,
            scrollable = false,
            enabled = true,
            focused = false,
            selected = false,
            accessibilityLabel = accessibilityLabel,
        )

    @Test
    fun `button-like and short controls look like action controls`() {
        assertTrue(AgentSemanticCandidatePolicies.looksActionControl(el(text = "随便一段较长的非动作文本内容描述", className = "android.widget.Button")))
        assertTrue(AgentSemanticCandidatePolicies.looksActionControl(el(text = "发送")))
        assertTrue(AgentSemanticCandidatePolicies.looksActionControl(el(text = "一段较长的说明性文本但带有允许这种动作关键词")))
        assertTrue(AgentSemanticCandidatePolicies.looksActionControl(el(text = "一段较长的说明性文本内容描述信息", accessibilityLabel = "允许")))
    }

    @Test
    fun `long plain text is not an action control`() {
        assertFalse(
            AgentSemanticCandidatePolicies.looksActionControl(
                el(text = "这是一段很长的普通信息描述文字没有任何动作关键词在其中出现"),
            ),
        )
    }

    @Test
    fun `informational status labels are recognized`() {
        assertTrue(AgentSemanticCandidatePolicies.looksInformationalStatusLabel(el(text = "你的电脑已登录微信，手机端可继续使用")))
        assertTrue(AgentSemanticCandidatePolicies.looksInformationalStatusLabel(el(text = "Windows 微信已登录")))
        assertFalse(AgentSemanticCandidatePolicies.looksInformationalStatusLabel(el(text = "发送")))
    }

    @Test
    fun `system_action policy accepts a real action button but rejects status text`() {
        val policy = AgentSemanticCandidatePolicies.forRole("system_action")
        assertTrue(policy.accepts(el(text = "允许", className = "android.widget.Button"), emptyList()))
        assertFalse(policy.accepts(el(text = "你的电脑已登录微信，请在手机端确认本次操作"), emptyList()))
    }

    @Test
    fun `conversation policy penalizes groups and boosts plain contacts`() {
        val policy = AgentSemanticCandidatePolicies.forRole("conversation")
        assertEquals(-80, policy.roleBoost(el(text = "韩威粉丝群")))
        assertEquals(14, policy.roleBoost(el(text = "韩威")))
    }

    @Test
    fun `region proposals are capped and sorted by score`() {
        val regions =
            ActionRegionHeadEngine.proposeRegions(
                roleHint = "dismiss",
                objectType = "button",
                tapPattern = "card",
                descriptorTokens = listOf("会话"),
                packageName = "com.x",
                pageState = "LIST",
                textFree = true,
            )
        assertTrue(regions.size <= 10)
        assertEquals(regions.sortedByDescending { it.score }, regions)
        assertTrue(regions.any { it.label == "action_head_corner" })
    }
}
