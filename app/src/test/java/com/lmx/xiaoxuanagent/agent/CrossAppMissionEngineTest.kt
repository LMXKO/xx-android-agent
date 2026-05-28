package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossAppMissionEngineTest {
    @Test
    fun `decompose builds two shopping legs for explicit jd vs taobao compare`() {
        val mission = CrossAppMissionEngine.decompose("比较京东和淘宝上 iPhone 16 的价格", InstalledPackageChecker.ASSUME_ALL_INSTALLED)
        assertNotNull(mission)
        mission!!
        assertEquals(2, mission.legs.size)
        assertEquals(
            setOf("jd_assistant", "shopping_search"),
            mission.legs.map { it.profileId }.toSet(),
        )
        assertTrue(mission.legs.all { it.intentType == "shopping" })
        assertEquals(2, mission.legs.map { it.targetPackageName }.toSet().size)
        assertTrue(mission.legs.all { it.targetPackageName.isNotBlank() })
        assertTrue(mission.declaredHandoffFields.containsAll(setOf("query", "price")))
    }

    @Test
    fun `decompose uses default app pair for compare without explicit apps`() {
        val mission = CrossAppMissionEngine.decompose("帮我比价 AirPods Pro", InstalledPackageChecker.ASSUME_ALL_INSTALLED)
        assertNotNull(mission)
        assertEquals(2, mission!!.legs.size)
    }

    @Test
    fun `decompose returns null for ordinary single app task`() {
        assertNull(CrossAppMissionEngine.decompose("在京东搜索耳机"))
        assertNull(CrossAppMissionEngine.decompose("打开微信给张三发消息"))
        assertNull(CrossAppMissionEngine.decompose(""))
    }

    @Test
    fun `recordAndAdvance accumulates blackboard and advances index`() {
        val mission = CrossAppMissionEngine.decompose("比较京东和淘宝上 iPhone 的价格", InstalledPackageChecker.ASSUME_ALL_INSTALLED)!!
        assertEquals(0, mission.activeLegIndex)
        assertTrue(mission.hasNextLeg())

        val advanced = mission.recordAndAdvance(payload("京东", "¥5999"))
        assertEquals(1, advanced.activeLegIndex)
        assertEquals(1, advanced.blackboard.size)
        assertEquals("done", advanced.legs[0].status)
        assertFalse(advanced.hasNextLeg())
    }

    @Test
    fun `composeFinalResult merges both legs price evidence and flags cheaper`() {
        val mission = CrossAppMissionEngine.decompose("比较京东和淘宝上 iPhone 的价格", InstalledPackageChecker.ASSUME_ALL_INSTALLED)!!
        val afterLeg0 = mission.recordAndAdvance(payload("京东", "¥5999"))
        val finalResult = CrossAppMissionEngine.composeFinalResult(afterLeg0, payload("淘宝", "¥5799"))

        assertEquals("cross_app_compare", finalResult.intentType)
        assertTrue(finalResult.summary.contains("5999"))
        assertTrue(finalResult.summary.contains("5799"))
        assertTrue(finalResult.summary.contains("更便宜"))
        assertEquals(2, finalResult.fields.size)
    }

    @Test
    fun `parseLlmDecomposition builds legs from model json and maps apps to profiles`() {
        val content =
            """
            {"multi_app":true,"handoff_fields":["query","price"],
             "legs":[{"app":"京东","sub_task":"在京东搜索 iPhone 看价格"},
                     {"app":"shopping_search","sub_task":"在淘宝搜索 iPhone 看价格"}]}
            """.trimIndent()
        val mission = CrossAppMissionEngine.parseLlmDecomposition(content, "比较京东和淘宝 iPhone 价格")
        assertNotNull(mission)
        mission!!
        assertEquals(2, mission.legs.size)
        assertEquals("jd_assistant", mission.legs[0].profileId)
        assertEquals("shopping_search", mission.legs[1].profileId)
        assertTrue(mission.legs.all { it.targetPackageName.isNotBlank() })
        assertTrue(mission.declaredHandoffFields.contains("price"))
    }

    @Test
    fun `parseLlmDecomposition resolves newly added apps by alias`() {
        // 快递 → 菜鸟 + 微信，"告诉家人" 类两 App 任务
        val content =
            """
            {"multi_app":true,"handoff_fields":["tracking","contact"],
             "legs":[{"app":"菜鸟","sub_task":"查这个运单的物流状态"},
                     {"app":"微信","sub_task":"把物流状态告诉家人"}]}
            """.trimIndent()
        val mission = CrossAppMissionEngine.parseLlmDecomposition(content, "查快递然后告诉家人")
        assertNotNull(mission)
        mission!!
        assertEquals("cainiao_assistant", mission.legs[0].profileId)
        assertEquals("wechat_assistant", mission.legs[1].profileId)
        assertTrue(mission.legs.all { it.targetPackageName.isNotBlank() })
    }

    @Test
    fun `parseLlmDecomposition tolerates markdown code fences`() {
        val content =
            """
            ```json
            {"multi_app":true,"legs":[
              {"app":"jd_assistant","sub_task":"在京东搜索"},
              {"app":"shopping_search","sub_task":"在淘宝搜索"}
            ]}
            ```
            """.trimIndent()
        val mission = CrossAppMissionEngine.parseLlmDecomposition(content, "比价")
        assertNotNull(mission)
        assertEquals(2, mission!!.legs.size)
    }

    @Test
    fun `parseLlmDecomposition tolerates app_id and task field names`() {
        val content =
            """
            {"multi_app":true,"legs":[
              {"app_id":"jd_assistant","task":"搜 iPhone"},
              {"appId":"shopping_search","description":"搜 iPhone"}
            ]}
            """.trimIndent()
        val mission = CrossAppMissionEngine.parseLlmDecomposition(content, "比价 iPhone")
        assertNotNull(mission)
        assertEquals("jd_assistant", mission!!.legs[0].profileId)
        assertEquals("shopping_search", mission.legs[1].profileId)
        assertTrue(mission.legs[0].subTask.contains("iPhone"))
    }

    @Test
    fun `parseLlmDecomposition infers multi_app when omitted but legs has two entries`() {
        val content =
            """
            {"legs":[
              {"app":"cainiao_assistant","sub_task":"查快递"},
              {"app":"wechat_assistant","sub_task":"告诉家人"}
            ]}
            """.trimIndent()
        val mission = CrossAppMissionEngine.parseLlmDecomposition(content, "查快递然后告诉家人")
        assertNotNull(mission)
        assertEquals(2, mission!!.legs.size)
    }

    @Test
    fun `parseLlmDecomposition accepts steps array as legs synonym`() {
        val content =
            """
            {"multi_app":true,"steps":[
              {"app":"jd_assistant","sub_task":"搜"},
              {"app":"shopping_search","sub_task":"搜"}
            ]}
            """.trimIndent()
        val mission = CrossAppMissionEngine.parseLlmDecomposition(content, "比价")
        assertNotNull(mission)
        assertEquals(2, mission!!.legs.size)
    }

    @Test
    fun `parseLlmDecomposition returns null for single-app or unparseable`() {
        assertNull(CrossAppMissionEngine.parseLlmDecomposition("""{"multi_app":false}""", "在京东搜耳机"))
        assertNull(CrossAppMissionEngine.parseLlmDecomposition("not json", "x"))
        // 只有一条可解析腿 → 不算多 App
        assertNull(
            CrossAppMissionEngine.parseLlmDecomposition(
                """{"multi_app":true,"legs":[{"app":"jd_assistant","sub_task":"搜"}]}""",
                "x",
            ),
        )
    }

    @Test
    fun `rewriteSubTaskWithHandoff injects entity and price from previous payload`() {
        val mission = CrossAppMissionEngine.decompose("比较京东和淘宝上 iPhone 的价格", InstalledPackageChecker.ASSUME_ALL_INSTALLED)!!
        val leg0Payload = payload("iPhone 16 Pro 256GB 黑色", "¥8999")
        val original = mission.legs[1].subTask

        val rewritten =
            CrossAppMissionEngine.rewriteSubTaskWithHandoff(
                mission = mission,
                nextSubTask = original,
                previousPayload = leg0Payload,
            )

        assertTrue("应注入实体名: $rewritten", rewritten.contains("iPhone 16 Pro 256GB 黑色"))
        assertTrue("应注入价格参考: $rewritten", rewritten.contains("¥8999"))
    }

    @Test
    fun `rewriteSubTaskWithHandoff returns original when payload is null`() {
        val mission = CrossAppMissionEngine.decompose("比较京东和淘宝上 iPhone 的价格", InstalledPackageChecker.ASSUME_ALL_INSTALLED)!!
        val original = mission.legs[1].subTask
        assertEquals(
            original,
            CrossAppMissionEngine.rewriteSubTaskWithHandoff(mission, original, previousPayload = null),
        )
    }

    @Test
    fun `rewriteSubTaskWithHandoff respects declaredHandoffFields whitelist`() {
        val mission =
            CrossAppMissionEngine.decompose("比较京东和淘宝上 iPhone 的价格", InstalledPackageChecker.ASSUME_ALL_INSTALLED)!!
                .copy(declaredHandoffFields = setOf("query")) // 不允许 price
        val original = mission.legs[1].subTask

        val rewritten =
            CrossAppMissionEngine.rewriteSubTaskWithHandoff(
                mission = mission,
                nextSubTask = original,
                previousPayload = payload("iPhone 16 Pro", "¥8999"),
            )

        assertTrue("应注入实体名", rewritten.contains("iPhone 16 Pro"))
        assertFalse("不允许的字段不应被注入: $rewritten", rewritten.contains("¥8999"))
    }

    @Test
    fun `decompose filters out apps whose primary and alternates are all uninstalled`() {
        // 模拟设备里只装了京东，淘宝没装也没等价电商
        val checker =
            InstalledPackageChecker { pkg ->
                pkg == "com.jingdong.app.mall"
            }
        val mission = CrossAppMissionEngine.decompose("比较京东和淘宝上 iPhone 的价格", checker)
        // 只有 1 个 leg 可用 → 不算多 App，应回 null
        assertNull(mission)
    }

    @Test
    fun `decompose picks installed alternate when primary missing`() {
        // 模拟设备没装 Chrome，但装了 Edge（alternatePackageNames 第一个）
        val mission =
            CrossAppMissionEngine.decompose(
                "在 chrome 和淘宝上比价 AirPods",
                InstalledPackageChecker { pkg ->
                    pkg == "com.microsoft.emmx" || pkg == "com.taobao.taobao"
                },
            )
        assertNotNull(mission)
        val browserLeg = mission!!.legs.firstOrNull { it.profileId == "browser_assistant" }
        assertNotNull(browserLeg)
        assertEquals("com.microsoft.emmx", browserLeg!!.targetPackageName)
    }

    @Test
    fun `parseLlmDecomposition skips legs whose apps are not installed`() {
        // 模型给出 jd + gmail，但 gmail 等所有邮件 App 都没装
        val content =
            """
            {"multi_app":true,"legs":[
              {"app":"jd_assistant","sub_task":"搜"},
              {"app":"gmail_assistant","sub_task":"发邮件"}
            ]}
            """.trimIndent()
        val checker =
            InstalledPackageChecker { pkg ->
                pkg == "com.jingdong.app.mall" || pkg == "com.taobao.taobao"
            }
        val mission = CrossAppMissionEngine.parseLlmDecomposition(content, "x", checker)
        // gmail 一腿被过滤后只剩 1 腿 → 整体 null
        assertNull(mission)
    }

    private fun payload(
        title: String,
        price: String,
    ): TaskResultPayload =
        TaskResultPayload(
            intentType = "shopping",
            title = title,
            summary = "$title 商品信息",
            highlights = listOf("$title 价格 $price", "评价不错"),
            fields = listOf(TaskResultField(key = "price", label = "价格", value = price)),
        )
}
