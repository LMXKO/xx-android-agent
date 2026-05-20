package com.lmx.xiaoxuanagent.taskprofile

data class AppSemanticHints(
    val aliases: List<String>,
    val semanticTags: List<String>,
    val summary: String,
    val category: String?,
)

object AppSemanticHintsBuilder {
    private val ignoredPackageTokens =
        setOf(
            "com",
            "cn",
            "net",
            "org",
            "android",
            "app",
            "mobile",
            "client",
            "phone",
        )

    fun build(
        label: String,
        packageName: String,
    ): AppSemanticHints {
        val aliases = linkedSetOf<String>()
        val semanticTags = linkedSetOf<String>()

        val normalizedLabel = label.trim()
        if (normalizedLabel.isNotBlank()) {
            aliases += normalizedLabel
            aliases += normalizedLabel.lowercase()
            aliases += normalizedLabel.replace(" ", "")
        }

        packageName.split('.')
            .map { it.trim() }
            .filter { it.isNotBlank() && it !in ignoredPackageTokens }
            .forEach { token ->
                aliases += token
                aliases += token.lowercase()
            }

        val lowerLabel = normalizedLabel.lowercase()
        val lowerPackage = packageName.lowercase()
        when {
            lowerLabel.contains("哔哩") || lowerPackage.contains("bili") -> {
                aliases += listOf("b站", "B站", "哔哩哔哩", "bilibili")
                semanticTags += listOf("视频", "评论", "UP主", "投稿", "弹幕", "社区")
            }

            lowerLabel.contains("微信") || lowerPackage.contains("tencent.mm") -> {
                aliases += listOf("wx", "WeChat")
                semanticTags += listOf("聊天", "消息", "联系人", "群聊", "社交")
            }

            lowerLabel.contains("闲鱼") || lowerPackage.contains("idlefish") -> {
                semanticTags += listOf("二手", "闲置", "转卖", "求购", "交易")
            }

            lowerLabel.contains("淘宝") || lowerPackage.contains("taobao") -> {
                semanticTags += listOf("购物", "商品", "下单", "比价", "评价")
            }

            lowerLabel.contains("高德") || lowerPackage.contains("autonavi") -> {
                semanticTags += listOf("导航", "地图", "路线", "打车", "出行")
            }

            lowerLabel.contains("美团") || lowerPackage.contains("meituan") -> {
                semanticTags += listOf("外卖", "餐厅", "团购", "酒店", "本地生活")
            }

            lowerLabel.contains("支付宝") || lowerPackage.contains("alipay") -> {
                semanticTags += listOf("支付", "账单", "转账", "缴费", "收款")
            }
        }

        val packageTail = packageName.substringAfterLast('.')
        if (packageTail.isNotBlank()) {
            aliases += packageTail
        }

        val summary =
            buildString {
                append("设备上已安装的应用 ").append(normalizedLabel.ifBlank { packageName })
                if (semanticTags.isNotEmpty()) {
                    append("，常见语义包括 ").append(semanticTags.joinToString(" / "))
                } else {
                    append("，可在用户明确点名该应用时直接进入。")
                }
            }

        return AppSemanticHints(
            aliases = aliases.filter { it.isNotBlank() }.distinct().take(12),
            semanticTags = semanticTags.filter { it.isNotBlank() }.distinct().take(8),
            summary = summary,
            category = inferCategory(lowerLabel, lowerPackage, semanticTags.toList()),
        )
    }

    private fun inferCategory(
        lowerLabel: String,
        lowerPackage: String,
        semanticTags: List<String>,
    ): String? {
        val joined = (listOf(lowerLabel, lowerPackage) + semanticTags).joinToString(" ")
        return when {
            listOf("导航", "地图", "路线", "打车", "出行").any { joined.contains(it) } ||
                lowerPackage.contains("map") -> "navigation"

            listOf("聊天", "消息", "联系人", "群聊", "社交").any { joined.contains(it) } -> "messaging"

            listOf("购物", "商品", "二手", "闲置", "求购", "转卖", "评价", "比价").any { joined.contains(it) } -> "shopping"

            listOf("外卖", "餐厅", "团购", "酒店", "本地生活").any { joined.contains(it) } -> "local_service"

            listOf("支付", "账单", "收款", "缴费", "转账").any { joined.contains(it) } -> "payment"

            listOf("视频", "投稿", "弹幕", "社区", "评论").any { joined.contains(it) } -> "content"

            else -> null
        }
    }
}
