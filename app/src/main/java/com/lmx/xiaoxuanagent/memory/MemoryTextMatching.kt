package com.lmx.xiaoxuanagent.memory

/**
 * 记忆文本匹配原语（无模型、纯函数、可单测）。把此前散落的"精确 token 交集 + 子串 contains"匹配，
 * 升级为统一的 tokenize + 重叠系数相似度：英文按字母数字串、中文按相邻二元组（bigram），
 * 因此"蓝牙耳机"和"无线耳机"能凭共享子串"耳机"产生相似度，而不再是 0 命中。
 */
object MemoryTextMatching {
    fun tokens(text: String): Set<String> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return emptySet()
        val out = linkedSetOf<String>()
        Regex("""[A-Za-z0-9]{2,}""").findAll(trimmed).forEach { out += it.value.lowercase() }
        val han = trimmed.replace(Regex("""[^\p{IsHan}]"""), "")
        when {
            han.length == 1 -> out += han
            han.length >= 2 -> for (i in 0..han.length - 2) out += han.substring(i, i + 2)
        }
        return out
    }

    /** 重叠系数 |A∩B| / min(|A|,|B|)，整体子串包含时给高相似度；范围 0..1。 */
    fun similarity(
        a: String,
        b: String,
    ): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val intersection = ta.count { it in tb }
        val base = intersection.toDouble() / minOf(ta.size, tb.size)
        val la = a.trim().lowercase()
        val lb = b.trim().lowercase()
        if (la.isNotBlank() && lb.isNotBlank() && (la.contains(lb) || lb.contains(la))) {
            return maxOf(base, 0.9)
        }
        return base
    }
}
