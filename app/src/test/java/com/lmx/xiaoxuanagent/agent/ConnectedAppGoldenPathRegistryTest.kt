package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedAppGoldenPathRegistryTest {
    @Test
    fun `high frequency connected apps all expose golden paths`() {
        val expectedAppIds =
            listOf(
                "system_settings_assistant",
                "amap_assistant",
                "wechat_assistant",
                "meituan_assistant",
                "shopping_search",
                "jd_assistant",
                "pdd_assistant",
                "xianyu_assistant",
                "alipay_assistant",
            )

        expectedAppIds.forEach { appId ->
            val paths = ConnectedAppGoldenPathRegistry.paths(appId)
            assertTrue("$appId should expose golden paths", paths.isNotEmpty())
            assertTrue("$appId should expose operations in catalog", ConnectedAppCatalog.findByAppId(appId)?.operations?.isNotEmpty() == true)
            assertTrue("$appId should have fallback chain", paths.all { it.fallbackChain.isNotEmpty() })
            assertTrue("$appId should have verification signals", paths.all { it.verificationSignals.isNotEmpty() })
            assertTrue("$appId should have a flow kind", paths.all { it.flowKind.isNotBlank() })
            assertTrue("$appId should start from entry stage", paths.all { it.flowStages.firstOrNull() == ConnectedAppGoldenPathFlowLogic.STAGE_ENTRY })
            assertTrue("$appId should include verification stage", paths.all { ConnectedAppGoldenPathFlowLogic.STAGE_VERIFICATION in it.flowStages })
            assertTrue("$appId should include recovery stage", paths.all { ConnectedAppGoldenPathFlowLogic.STAGE_RECOVERY in it.flowStages })
            assertTrue("$appId should expose automation boundary", paths.all { it.automationBoundary.isNotBlank() })
            assertTrue("$appId should expose final action policy", paths.all { it.finalActionPolicy.isNotBlank() })
        }
    }

    @Test
    fun `payment and commerce checkout paths require final handoff`() {
        val alipayPayment = ConnectedAppGoldenPathRegistry.find("alipay_assistant", "prepare_payment_handoff")
        val taobaoCheckout = ConnectedAppGoldenPathRegistry.find("shopping_search", "prepare_checkout_handoff")
        val meituanCheckout = ConnectedAppGoldenPathRegistry.find("meituan_assistant", "prepare_checkout_handoff")

        assertNotNull(alipayPayment)
        assertNotNull(taobaoCheckout)
        assertNotNull(meituanCheckout)
        assertEquals("critical", alipayPayment?.riskLevel)
        assertTrue(alipayPayment?.requiresFinalHandoff == true)
        assertTrue(taobaoCheckout?.requiresFinalHandoff == true)
        assertTrue(meituanCheckout?.requiresFinalHandoff == true)
    }

    @Test
    fun `high risk golden paths stop before final user action`() {
        val paths =
            listOfNotNull(
                ConnectedAppGoldenPathRegistry.find("wechat_assistant", "prepare_message_handoff"),
                ConnectedAppGoldenPathRegistry.find("shopping_search", "prepare_checkout_handoff"),
                ConnectedAppGoldenPathRegistry.find("jd_assistant", "prepare_checkout_handoff"),
                ConnectedAppGoldenPathRegistry.find("pdd_assistant", "prepare_checkout_handoff"),
                ConnectedAppGoldenPathRegistry.find("xianyu_assistant", "prepare_used_goods_search"),
                ConnectedAppGoldenPathRegistry.find("alipay_assistant", "prepare_payment_handoff"),
            )

        assertTrue(paths.isNotEmpty())
        assertTrue(paths.all { it.requiresFinalHandoff })
        assertTrue(paths.all { ConnectedAppGoldenPathFlowLogic.STAGE_HANDOFF in it.flowStages })
        assertTrue(paths.all { it.finalActionPolicy.startsWith("user_only") })
        assertTrue(paths.all { it.automationBoundary.startsWith("stop_before") })
    }

    @Test
    fun `low risk discovery paths may complete after target verification`() {
        val mapSearch = ConnectedAppGoldenPathRegistry.find("amap_assistant", "search_place")
        val productSearch = ConnectedAppGoldenPathRegistry.find("shopping_search", "prepare_product_search")

        assertNotNull(mapSearch)
        assertNotNull(productSearch)
        assertEquals("assistant_may_complete_after_verification", mapSearch?.finalActionPolicy)
        assertEquals("assistant_may_complete_after_verification", productSearch?.finalActionPolicy)
        assertTrue(ConnectedAppGoldenPathFlowLogic.STAGE_HANDOFF !in mapSearch!!.flowStages)
        assertTrue(ConnectedAppGoldenPathFlowLogic.STAGE_HANDOFF !in productSearch!!.flowStages)
    }
}
