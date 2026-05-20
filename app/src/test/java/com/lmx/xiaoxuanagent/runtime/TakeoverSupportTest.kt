package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.safety.PendingSafetyConfirmation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeoverSupportTest {
    @Test
    fun `safety approval resume context keeps subgoal and next objective`() {
        val context =
            buildSafetyApprovalResumeContext(
                PendingSafetyConfirmation(
                    approvalKey = "approve_send",
                    title = "发送消息",
                    summary = "即将给联系人发送消息",
                    actionLabel = "点击发送",
                    planStage = "confirm_send",
                    subgoalId = "wechat_send_message",
                    nextObjective = "确认消息已发送成功",
                    targetPackageName = "com.tencent.mm",
                ),
                userCorrection = "刚才点错会话了，重新确认联系人后再发。",
            )

        assertTrue(context.active)
        assertEquals("safety_confirmation_approved", context.source)
        assertEquals("safety_confirmation", context.resumeEvent)
        assertEquals("wechat_send_message", context.resumedSubgoalId)
        assertTrue(context.resumeHint.contains("点击发送"))
        assertTrue(context.resumeHint.contains("确认消息已发送成功"))
        assertTrue(context.resumeHint.contains("刚才点错会话了"))
        assertEquals("刚才点错会话了，重新确认联系人后再发。", context.userCorrection)
    }

    @Test
    fun `pending safety confirmation enrichment fills missing plan context`() {
        val enriched =
            enrichPendingSafetyConfirmation(
                confirmation =
                    PendingSafetyConfirmation(
                        approvalKey = "approve_send",
                        title = "发送消息",
                        summary = "即将给联系人发送消息",
                        actionLabel = "点击发送",
                    ),
                planStage = "confirm_send",
                subgoalId = "wechat_send_message",
                nextObjective = "确认消息已发送成功",
                targetPackageName = "com.tencent.mm",
            )

        assertEquals("confirm_send", enriched.planStage)
        assertEquals("wechat_send_message", enriched.subgoalId)
        assertEquals("确认消息已发送成功", enriched.nextObjective)
        assertEquals("com.tencent.mm", enriched.targetPackageName)
    }

    @Test
    fun `manual resume context keeps human correction`() {
        val context =
            buildManualResumeContext(
                subgoalId = "find",
                nextObjective = "重新定位联系人",
                userCorrection = "不要点公众号，优先找韩威本人。",
            )

        assertEquals("manual_correction_resume", context.source)
        assertEquals("manual_correction", context.resumeEvent)
        assertEquals("find", context.resumedSubgoalId)
        assertTrue(context.resumeHint.contains("不要点公众号"))
        assertEquals("不要点公众号，优先找韩威本人。", context.userCorrection)
    }
}
