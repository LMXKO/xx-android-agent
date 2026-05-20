package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedAppFallbackSupportTest {
    @Test
    fun `wechat draft ready prefers paste and submit fallback`() {
        val chain =
            ConnectedAppFallbackSupport.fallbackChainFor(
                suggestion = ConnectedAppSuggestion(appId = "wechat_assistant", operation = "prepare_message_handoff"),
                receipt =
                    ConnectedAppExecutionReceipt(
                        appId = "wechat_assistant",
                        operation = "prepare_message_handoff",
                        state = "message_draft_ready",
                    ),
            )

        assertEquals("semantic.return_to_target_app", chain.first())
        assertTrue(chain.contains("online.clipboard_paste"))
        assertTrue(chain.contains("semantic.submit_primary_action"))
    }

    @Test
    fun `meituan search ready prefers input and candidate fallback`() {
        val chain =
            ConnectedAppFallbackSupport.fallbackChainFor(
                suggestion = ConnectedAppSuggestion(appId = "meituan_assistant", operation = "prepare_service_search"),
                receipt =
                    ConnectedAppExecutionReceipt(
                        appId = "meituan_assistant",
                        operation = "prepare_service_search",
                        state = "service_search_ready",
                    ),
            )

        assertTrue(chain.contains("semantic.focus_primary_input"))
        assertTrue(chain.contains("online.clipboard_paste"))
        assertTrue(chain.contains("semantic.open_best_candidate"))
    }

    @Test
    fun `commerce checkout ready uses golden path fallback`() {
        val chain =
            ConnectedAppFallbackSupport.fallbackChainFor(
                suggestion = ConnectedAppSuggestion(appId = "jd_assistant", operation = "prepare_checkout_handoff"),
                receipt =
                    ConnectedAppExecutionReceipt(
                        appId = "jd_assistant",
                        operation = "prepare_checkout_handoff",
                        state = "commerce_checkout_handoff_ready",
                    ),
            )

        assertEquals("semantic.return_to_target_app", chain.first())
        assertTrue(chain.contains("semantic.submit_primary_action"))
    }

    @Test
    fun `alipay payment handoff avoids paste and submit automation`() {
        val chain =
            ConnectedAppFallbackSupport.fallbackChainFor(
                suggestion = ConnectedAppSuggestion(appId = "alipay_assistant", operation = "prepare_payment_handoff"),
                receipt =
                    ConnectedAppExecutionReceipt(
                        appId = "alipay_assistant",
                        operation = "prepare_payment_handoff",
                        state = "payment_handoff_ready",
                    ),
            )

        assertTrue(chain.contains("system.launch_app"))
        assertTrue(chain.contains("semantic.open_best_candidate"))
        assertTrue(!chain.contains("semantic.submit_primary_action"))
        assertTrue(!chain.contains("online.clipboard_paste"))
    }
}
