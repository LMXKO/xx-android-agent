package com.lmx.xiaoxuanagent.memory

import org.json.JSONArray

/**
 * 长期记忆巩固（无模型、纯函数、可单测）。取代"只在批内去重 + 一律删最旧(FIFO)"：
 *  - [findSimilarIndex]：新事实与已有近重复时返回其下标，调用方据此合并强化（bump 时间 + hits）而非新增。
 *  - [evictToCapacity]：超容量时淘汰"最不值得保留"的（命中越多越保、越旧越淘），而非盲删最旧，
 *    使被反复强化的重要事实不会被一条新事实挤掉。
 */
object MemoryConsolidation {
    fun findSimilarIndex(
        facts: JSONArray,
        content: String,
        threshold: Double = 0.82,
    ): Int {
        for (index in 0 until facts.length()) {
            val existing = facts.optJSONObject(index)?.optString("content").orEmpty()
            if (existing.isNotBlank() && MemoryTextMatching.similarity(content, existing) >= threshold) {
                return index
            }
        }
        return -1
    }

    fun evictToCapacity(
        facts: JSONArray,
        max: Int,
        nowMs: Long,
    ) {
        while (facts.length() > max) {
            var worstIndex = 0
            var worstValue = Double.MAX_VALUE
            for (index in 0 until facts.length()) {
                val obj = facts.optJSONObject(index) ?: continue
                val hits = obj.optInt("hits", 1)
                val ageDays = (nowMs - obj.optLong("timestamp", 0L)).coerceAtLeast(0L).toDouble() / (24L * 60 * 60 * 1000)
                val value = hits * 2.0 - ageDays
                if (value < worstValue) {
                    worstValue = value
                    worstIndex = index
                }
            }
            facts.remove(worstIndex)
        }
    }
}
