package com.lmx.xiaoxuanagent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlatformCommandPaletteTest {
    @Test
    fun `parse supports quoted memory governance arguments`() {
        val command =
            SessionPlatformCommandPalette.parse(
                """/upsert-memory contact "张 三" "同事,朋友" --note "住在 上海 徐汇" --profile-id profile_wechat""",
            )
                ?: error("command should parse")

        assertEquals(SessionCapabilityKey.UPSERT_MEMORY_ENTRY, command.capability)
        assertEquals("contact", command.payload["type"])
        assertEquals("张 三", command.payload["primary"])
        assertEquals("同事,朋友", command.payload["secondary"])
        assertEquals("住在 上海 徐汇", command.payload["note"])
        assertEquals("profile_wechat", command.payload["profile_id"])
    }

    @Test
    fun `parse supports key value worker message arguments`() {
        val command =
            SessionPlatformCommandPalette.parse(
                "/post-worker-message control worker_42 --title \"补齐 provider policy\" --summary \"先处理失败冷却，再收路由覆盖\"",
            )
                ?: error("command should parse")

        assertEquals(SessionCapabilityKey.POST_WORKER_MESSAGE, command.capability)
        assertEquals("control", command.payload["type"])
        assertEquals("worker_42", command.payload["recipient_worker_id"])
        assertEquals("补齐 provider policy", command.payload["title"])
        assertEquals("先处理失败冷却，再收路由覆盖", command.payload["summary"])
    }

    @Test
    fun `parse supports semantic provider policy flags`() {
        val command =
            SessionPlatformCommandPalette.parse(
                "/set-provider-policy --enabled true --prefer-text-on-artifact-heavy-stage false --prefer-text-on-resume true " +
                    "--stage-overrides \"summarize:openai_text_primary\" --package-overrides \"com.taobao.taobao:openai_vision_primary\"",
            )
                ?: error("command should parse")

        assertEquals(SessionCapabilityKey.UPSERT_PROVIDER_POLICY, command.capability)
        assertEquals("true", command.payload["enabled"])
        assertEquals("false", command.payload["prefer_text_on_artifact_heavy_stage"])
        assertEquals("true", command.payload["prefer_text_on_resume"])
        assertEquals("summarize:openai_text_primary", command.payload["stage_overrides"])
        assertEquals("com.taobao.taobao:openai_vision_primary", command.payload["package_overrides"])
    }

    @Test
    fun `tokenize preserves quoted fragments and escaped spaces`() {
        val tokens =
            SessionPlatformCommandParser.tokenize(
                """/memory-governance --note "a b c" --entry-ref foo\ bar""",
            )

        assertEquals(listOf("/memory-governance", "--note", "a b c", "--entry-ref", "foo bar"), tokens)
        assertTrue(tokens.none { it.contains('"') })
    }

    @Test
    fun `parse supports today shell commands from registry`() {
        val command = SessionPlatformCommandPalette.parse("/today") ?: error("command should parse")

        assertEquals(SessionCapabilityKey.READ_PRODUCT_SHELL, command.capability)
        assertEquals("today", command.payload["section"])
    }

    @Test
    fun `resolve help command supports aliases and command lookup`() {
        val resolution = SessionPlatformCommandPalette.resolve("/h /viewer")

        assertTrue(resolution.command != null)
        assertEquals(SessionCapabilityKey.READ_COMMAND_CATALOG, resolution.command?.capability)
        assertEquals("/viewer", resolution.command?.payload?.get("command"))
    }

    @Test
    fun `resolve suggests similar commands for unknown slash command`() {
        val resolution = SessionPlatformCommandPalette.resolve("/view")

        assertTrue(resolution.command == null)
        assertTrue(resolution.summary.contains("未识别命令"))
        assertTrue(resolution.lines.any { it.contains("/viewer") })
    }

    @Test
    fun `resolve validates policy mutations before execution`() {
        val resolution = SessionPlatformCommandPalette.resolve("/set-quiet-hours")

        assertTrue(resolution.command == null)
        assertTrue(resolution.summary.contains("quiet hours"))
        assertTrue(resolution.lines.any { it.contains("/set-quiet-hours") })
    }

    @Test
    fun `resolve supports natural language start session intent`() {
        val resolution = SessionPlatformCommandPalette.resolve("帮我回微信并整理重点")

        assertTrue(resolution.command != null)
        assertEquals(SessionCapabilityKey.START_SESSION, resolution.command?.capability)
        assertEquals("帮我回微信并整理重点", resolution.command?.task)
        assertTrue(resolution.summary.contains("自然语言意图"))
    }

    @Test
    fun `resolve supports natural language safety approval intent`() {
        val resolution = SessionPlatformCommandPalette.resolve("批准 session_123 这笔转账，金额确认无误")

        assertTrue(resolution.command != null)
        assertEquals(SessionCapabilityKey.APPROVE_SAFETY, resolution.command?.capability)
        assertEquals("session_123", resolution.command?.sessionId)
        assertTrue(resolution.command?.userCorrection?.contains("金额确认无误") == true)
    }

    @Test
    fun `resolve supports natural language memory recall intent`() {
        val resolution = SessionPlatformCommandPalette.resolve("查记忆 张三 的电话")

        assertTrue(resolution.command != null)
        assertEquals(SessionCapabilityKey.RECALL_MEMORY, resolution.command?.capability)
        assertEquals("张三 的电话", resolution.command?.query)
    }

    @Test
    fun `parse supports routine policy updates`() {
        val command =
            SessionPlatformCommandPalette.parse(
                "/set-routine-policy --focus-theme \"消息收口\" --review-window 09:00-11:30 --preferred-surfaces notification,widget",
            )
                ?: error("command should parse")

        assertEquals(SessionCapabilityKey.UPSERT_PRODUCT_POLICY, command.capability)
        assertEquals("routine", command.payload["policy_type"])
        assertEquals("消息收口", command.payload["focus_theme"])
        assertEquals("09:00-11:30", command.payload["review_window"])
        assertEquals("notification,widget", command.payload["preferred_surfaces"])
    }

    @Test
    fun `parse supports compensation commands`() {
        val inspect =
            SessionPlatformCommandPalette.parse(
                "/compensations --session-id session_123 --limit 5",
            )
                ?: error("inspect command should parse")
        val run =
            SessionPlatformCommandPalette.parse(
                "/run-compensation --session-id session_123 --turn 4 --step-id restore_text_input",
            )
                ?: error("run command should parse")

        assertEquals(SessionCapabilityKey.READ_SESSION_COMPENSATIONS, inspect.capability)
        assertEquals("session_123", inspect.sessionId)
        assertEquals("5", inspect.payload["limit"])

        assertEquals(SessionCapabilityKey.RUN_SESSION_COMPENSATION, run.capability)
        assertEquals("session_123", run.sessionId)
        assertEquals("4", run.payload["turn"])
        assertEquals("restore_text_input", run.payload["step_id"])
    }

    @Test
    fun `parse supports provider registry tiers and capabilities`() {
        val command =
            SessionPlatformCommandPalette.parse(
                "/set-provider-registry --provider-id semantic_local_text --backend semantic_local --latency-tier fast --cost-tier low --capabilities structured,resume,semantic_navigation",
            )
                ?: error("command should parse")

        assertEquals(SessionCapabilityKey.UPSERT_PROVIDER_REGISTRY, command.capability)
        assertEquals("semantic_local", command.payload["backend"])
        assertEquals("fast", command.payload["latency_tier"])
        assertEquals("low", command.payload["cost_tier"])
        assertEquals("structured,resume,semantic_navigation", command.payload["capabilities"])
    }

    @Test
    fun `registry help and suggest expose new viewer and governance commands`() {
        val viewerHelp = SessionPlatformCommandRegistry.helpLines("/viewer")
        val governanceSuggestions = SessionPlatformCommandRegistry.suggest("govern", limit = 4)

        assertTrue(viewerHelp.any { it.contains("usage=/viewer") })
        assertFalse(governanceSuggestions.isEmpty())
        assertTrue(governanceSuggestions.any { it.contains("/governance") })
    }
}
