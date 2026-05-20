package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedAppRouterTest {
    @Test
    fun `wechat focus profile prefers structured handoff for send task`() {
        val suggestion =
            ConnectedAppRouter.suggest(
                task = "去微信给韩威发一条晚安消息",
                profileId = "wechat_assistant",
                targetPackageName = "com.tencent.mm",
            )

        assertEquals("wechat_assistant", suggestion?.appId)
        assertEquals("prepare_message_handoff", suggestion?.operation)
        assertTrue(suggestion?.reason.orEmpty().contains("发送"))
    }

    @Test
    fun `meituan focus profile prefers structured takeout search`() {
        val suggestion =
            ConnectedAppRouter.suggest(
                task = "打开美团搜奶茶外卖",
                profileId = "meituan_assistant",
                targetPackageName = "com.sankuai.meituan",
            )

        assertEquals("meituan_assistant", suggestion?.appId)
        assertEquals("prepare_takeout_search", suggestion?.operation)
    }

    @Test
    fun `meituan hotel task prefers hotel search state`() {
        val suggestion =
            ConnectedAppRouter.suggest(
                task = "打开美团搜杭州酒店",
                profileId = "meituan_assistant",
                targetPackageName = "com.sankuai.meituan",
            )

        assertEquals("meituan_assistant", suggestion?.appId)
        assertEquals("prepare_hotel_search", suggestion?.operation)
    }

    @Test
    fun `meituan takeout task prefers takeout search state`() {
        val suggestion =
            ConnectedAppRouter.suggest(
                task = "打开美团点奶茶外卖",
                profileId = "meituan_assistant",
                targetPackageName = "com.sankuai.meituan",
            )

        assertEquals("meituan_assistant", suggestion?.appId)
        assertEquals("prepare_takeout_search", suggestion?.operation)
    }

    @Test
    fun `wechat share task prefers contact share handoff`() {
        val suggestion =
            ConnectedAppRouter.suggest(
                task = "去微信把这条内容分享给韩威",
                profileId = "wechat_assistant",
                targetPackageName = "com.tencent.mm",
            )

        assertEquals("wechat_assistant", suggestion?.appId)
        assertEquals("prepare_contact_share_handoff", suggestion?.operation)
    }

    @Test
    fun `wechat reply task prefers message reply handoff`() {
        val suggestion =
            ConnectedAppRouter.suggest(
                task = "去微信回复韩威说晚点到",
                profileId = "wechat_assistant",
                targetPackageName = "com.tencent.mm",
            )

        assertEquals("wechat_assistant", suggestion?.appId)
        assertEquals("prepare_message_reply_handoff", suggestion?.operation)
    }

    @Test
    fun `jd commerce research prefers product research path`() {
        val suggestion =
            ConnectedAppRouter.suggest(
                task = "打开京东搜 iPhone 看看评价和参数",
                profileId = "jd_assistant",
                targetPackageName = "com.jingdong.app.mall",
            )

        assertEquals("jd_assistant", suggestion?.appId)
        assertEquals("prepare_product_research", suggestion?.operation)
    }

    @Test
    fun `pdd checkout task prefers checkout handoff path`() {
        val suggestion =
            ConnectedAppRouter.suggest(
                task = "打开拼多多找百亿补贴耳机，下单前给我确认",
                profileId = "pdd_assistant",
                targetPackageName = "com.xunmeng.pinduoduo",
            )

        assertEquals("pdd_assistant", suggestion?.appId)
        assertEquals("prepare_checkout_handoff", suggestion?.operation)
    }

    @Test
    fun `alipay payment task never bypasses handoff`() {
        val suggestion =
            ConnectedAppRouter.suggest(
                task = "打开支付宝给张三转账 100 元",
                profileId = "alipay_assistant",
                targetPackageName = "com.eg.android.AlipayGphone",
            )

        assertEquals("alipay_assistant", suggestion?.appId)
        assertEquals("prepare_payment_handoff", suggestion?.operation)
    }
}
