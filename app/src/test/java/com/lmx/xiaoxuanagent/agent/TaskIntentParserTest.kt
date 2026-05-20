package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskIntentParserTest {
    @Test
    fun `navigation task extracts destination instead of full sentence`() {
        val constraints = TaskIntentParser.parse("去浦东新区多长时间")

        assertEquals(TaskIntentType.NAVIGATION, constraints.intentType)
        assertEquals("浦东新区", constraints.destination)
        assertEquals("浦东新区", constraints.entryQuery)
    }

    @Test
    fun `shopping task keeps product entity as entry query`() {
        val constraints = TaskIntentParser.parse("打开闲鱼看二手内存条价格")

        assertEquals(TaskIntentType.SHOPPING, constraints.intentType)
        assertEquals("二手内存条", constraints.entryQuery)
        assertEquals("价格", constraints.objectiveHint)
    }

    @Test
    fun `content task strips high level objective suffix from query`() {
        val constraints = TaskIntentParser.parse("打开b站，看下勇哥说餐饮最新一期点赞最高的评论")

        assertEquals(TaskIntentType.CONTENT, constraints.intentType)
        assertEquals("勇哥说餐饮", constraints.entryQuery)
        assertTrue(constraints.objectiveHint?.contains("评论") == true)
    }

    @Test
    fun `messaging task extracts recipient and message body`() {
        val constraints = TaskIntentParser.parse("去微信给韩威发一条晚安的消息")

        assertEquals(TaskIntentType.MESSAGING, constraints.intentType)
        assertEquals("韩威", constraints.recipientName)
        assertEquals("晚安", constraints.messageBody)
        assertEquals("韩威", constraints.entryQuery)
    }
}
