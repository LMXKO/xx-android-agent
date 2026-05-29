package com.lmx.xiaoxuanagent.memory

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTextMatchingTest {
    @Test
    fun `shares similarity via common cjk substring`() {
        // 共享"耳机"二元组 -> 相似度 > 0（旧的精确 token 交集会得 0）
        assertTrue(MemoryTextMatching.similarity("蓝牙耳机", "无线耳机") > 0.0)
    }

    @Test
    fun `substring containment yields high similarity`() {
        assertTrue(MemoryTextMatching.similarity("iphone 16", "iphone 16 pro 256g") >= 0.9)
    }

    @Test
    fun `identical text is fully similar`() {
        assertEquals(1.0, MemoryTextMatching.similarity("喜欢喝美式咖啡", "喜欢喝美式咖啡"), 0.0001)
    }

    @Test
    fun `unrelated text is dissimilar`() {
        assertEquals(0.0, MemoryTextMatching.similarity("完全无关", "abcd"), 0.0001)
    }
}

class MemoryConsolidationTest {
    private fun fact(
        content: String,
        hits: Int,
        timestamp: Long,
    ): JSONObject =
        JSONObject().apply {
            put("content", content)
            put("hits", hits)
            put("timestamp", timestamp)
        }

    @Test
    fun `findSimilarIndex matches a near-duplicate fact`() {
        val facts = JSONArray().apply { put(fact("喜欢喝美式咖啡", 1, 0L)) }
        assertEquals(0, MemoryConsolidation.findSimilarIndex(facts, "喜欢喝美式咖啡"))
        assertEquals(-1, MemoryConsolidation.findSimilarIndex(facts, "完全无关的另一件事 xyz"))
    }

    @Test
    fun `evictToCapacity keeps reinforced and recent facts over old weak ones`() {
        val now = 1_000L * 24 * 60 * 60 * 1000 // 固定 now，避免依赖真实时钟
        val day = 24L * 60 * 60 * 1000
        val facts =
            JSONArray().apply {
                put(fact("强化过的老事实", hits = 5, timestamp = now - 10 * day)) // value = 10 - 10 = 0
                put(fact("刚记的新事实", hits = 1, timestamp = now)) // value = 2 - 0 = 2
                put(fact("很旧很弱的事实", hits = 1, timestamp = now - 20 * day)) // value = 2 - 20 = -18
            }
        MemoryConsolidation.evictToCapacity(facts, max = 1, nowMs = now)
        assertEquals(1, facts.length())
        assertEquals("刚记的新事实", facts.optJSONObject(0).optString("content"))
    }
}
