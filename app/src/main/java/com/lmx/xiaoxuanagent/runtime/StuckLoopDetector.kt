package com.lmx.xiaoxuanagent.runtime

/**
 * 卡死/死循环检测（P0-1）。在一个滑动窗口上对每轮指纹 `"<observationSignature>|<actionLabel>"`
 * 做模式识别，取代此前"仅最近 4 个完全相同才判定"的弱逻辑——能更早、更全地识别：
 *
 * - identical_repeat：最近 3 轮完全相同（同屏同动作）。
 * - oscillation：最近 4 轮在两个状态间 A-B-A-B 振荡。
 * - dominant：窗口内最高频指纹出现 ≥ [DOMINANT_THRESHOLD] 次（嘈杂循环）。
 * - low_diversity：窗口已满且只在 ≤ 2 个状态间打转（长时间无实质进展）。
 *
 * 所有判定都包含 observationSignature，因此正常滚动长列表（每次签名都不同）不会误判为卡死。
 */
object StuckLoopDetector {
    const val WINDOW = 8
    private const val DOMINANT_THRESHOLD = 5

    data class Verdict(
        val stuck: Boolean,
        val pattern: String = "",
    )

    fun detect(fingerprints: List<String>): Verdict {
        val recent = fingerprints.takeLast(WINDOW).filter { it.isNotBlank() }
        if (recent.size < 3) return Verdict(false)

        val last3 = recent.takeLast(3)
        if (last3.distinct().size == 1) {
            return Verdict(true, "identical_repeat")
        }

        if (recent.size >= 4) {
            val last4 = recent.takeLast(4)
            if (last4[0] != last4[1] &&
                last4[0] == last4[2] &&
                last4[1] == last4[3]
            ) {
                return Verdict(true, "oscillation")
            }
        }

        val mostFrequent = recent.groupingBy { it }.eachCount().maxByOrNull { it.value }?.value ?: 0
        if (mostFrequent >= DOMINANT_THRESHOLD) {
            return Verdict(true, "dominant")
        }

        if (recent.size >= WINDOW && recent.distinct().size <= 2) {
            return Verdict(true, "low_diversity")
        }

        return Verdict(false)
    }
}
