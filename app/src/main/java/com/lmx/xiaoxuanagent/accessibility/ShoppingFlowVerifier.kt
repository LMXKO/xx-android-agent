package com.lmx.xiaoxuanagent.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.lmx.xiaoxuanagent.runtime.AppPageState

object ShoppingFlowVerifier {
    fun verifySearchInput(root: AccessibilityNodeInfo, query: String): String {
        val page = ShoppingPageDetector.detect(root)
        val nodes = root.allNodes()

        return when (page) {
            AppPageState.Home -> {
                val searchEntry = findFirstSearchEntry(nodes)
                if (searchEntry?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    "已点击首页搜索入口，请等待进入搜索输入页后再次点击按钮。"
                } else {
                    "未能定位首页搜索入口。"
                }
            }

            AppPageState.SearchResultWeak -> {
                val searchEntry = findResultPageSearchEntry(nodes)
                if (searchEntry?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    "当前在搜索结果页，已尝试点击顶部搜索栏，请等待进入搜索输入页后再次点击按钮。"
                } else {
                    "当前在搜索结果页，但未能定位顶部搜索栏。"
                }
            }

            AppPageState.SearchInput -> {
                val inputNode = findSearchInput(nodes)
                if (inputNode == null) {
                    "未找到可输入的搜索节点。"
                } else {
                    val focused = inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val typed = inputNode.setTextValue(query)
                    val submit = findSearchSubmit(nodes)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    buildString {
                        append("搜索输入页处理结果：")
                        append(" focus=").append(focused)
                        append(" setText=").append(typed)
                        append(" submit=").append(submit)
                    }
                }
            }

            else -> "当前页面不是首页、搜索结果页或搜索输入页，当前状态=${page.label}"
        }
    }

    fun verifyResultClick(root: AccessibilityNodeInfo): String {
        val page = ShoppingPageDetector.detect(root)
        if (page != AppPageState.SearchResultWeak) {
            return "当前页面不是搜索结果页，当前状态=${page.label}"
        }

        val nodes = root.allNodes()
        val candidate = findResultCandidate(nodes)
        if (candidate?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
            return "已尝试点击结果页首个候选区域，请观察是否进入详情页。"
        }

        val scroller = nodes.firstOrNull { it.isScrollable }
        if (scroller?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true) {
            return "未找到稳定候选区域，已执行一次向下滚动，请重新观察页面。"
        }

        return "结果页点击失败，且无法继续滚动。"
    }

    private fun findFirstSearchEntry(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        return nodes.firstOrNull { node ->
            if (!node.isClickable) return@firstOrNull false
            val text = node.readableText()
            val bounds = node.boundsRect()
            text == "搜索栏" &&
                bounds.top < 320 &&
                bounds.left in 150..320 &&
                bounds.width() > 500 &&
                bounds.right < 980
        } ?: nodes.firstOrNull { node ->
            if (!node.isClickable) return@firstOrNull false
            val text = node.readableText()
            val bounds = node.boundsRect()
            text.isNotBlank() &&
                !text.contains("扫一扫") &&
                !text.contains("拍立淘") &&
                !text.contains("搜索") &&
                bounds.top < 320 &&
                bounds.left in 150..320 &&
                bounds.width() > 500 &&
                bounds.right < 980
        }
    }

    private fun findSearchInput(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        return nodes.firstOrNull { it.isEditable } ?: nodes.firstOrNull {
            val text = it.readableText()
            (it.className?.contains("EditText") == true || text.contains("搜宝贝") || text.contains("千问")) &&
                (it.isFocusable || it.isClickable)
        }
    }

    private fun findSearchSubmit(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        return nodes.firstOrNull { it.isClickable && it.readableText() == "搜索" }
    }

    private fun findResultPageSearchEntry(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        return nodes.firstOrNull {
            it.viewIdResourceName == "com.taobao.taobao:id/search_bar_wrapper" && it.isClickable
        } ?: nodes.firstOrNull {
            it.viewIdResourceName == "com.taobao.taobao:id/searchEdit" && it.isClickable
        } ?: nodes.firstOrNull {
            it.isClickable &&
                it.boundsRect().top < 320 &&
                it.readableText().isNotBlank() &&
                !it.readableText().contains("返回上一页") &&
                !it.readableText().contains("更多")
        }
    }

    private fun findResultCandidate(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        return nodes
            .filter { it.isClickable }
            .filterNot(::isResultChromeNode)
            .filter { node ->
                val bounds = node.boundsRect()
                val width = bounds.width()
                val height = bounds.height()
                bounds.top > 420 &&
                    bounds.bottom < 2500 &&
                    bounds.left > 180 &&
                    width > 260 &&
                    height > 180
            }
            .sortedWith(
                compareByDescending<AccessibilityNodeInfo> { scoreResultCandidate(it) }
                    .thenBy { it.boundsRect().top }
                    .thenBy { it.boundsRect().left },
            )
            .firstOrNull()
    }

    private fun isResultChromeNode(node: AccessibilityNodeInfo): Boolean {
        val text = node.readableText()
        val viewId = node.viewIdResourceName.orEmpty()
        val chromeKeywords = listOf(
            "返回上一页",
            "拍立淘",
            "更多",
            "满意度调研",
            "打开我的购物车",
            "帮我挑",
            "当前看图模式",
            "综合",
            "销量",
            "全部",
            "天猫",
            "店铺",
            "上新",
            "直播",
            "筛选",
            "加购",
            "进店",
            "回到列表顶部",
        )
        if (chromeKeywords.any { text.contains(it) }) {
            return true
        }
        return viewId in setOf(
            "com.taobao.taobao:id/search_bar_wrapper",
            "com.taobao.taobao:id/searchEdit",
            "com.taobao.taobao:id/btn_go_back",
            "com.taobao.taobao:id/photoBtn",
            "com.taobao.taobao:id/uik_action_overflow",
            "com.taobao.taobao:id/toolbar_review",
            "com.taobao.taobao:id/open_cart",
            "com.taobao.taobao:id/toolbar_backtop",
        )
    }

    private fun scoreResultCandidate(node: AccessibilityNodeInfo): Int {
        val text = node.readableText()
        val bounds = node.boundsRect()
        var score = 0

        if (text.contains("元")) score += 100
        if (text.contains("人付款")) score += 100
        if (text.length >= 12) score += 30
        if (bounds.width() >= 400) score += 20
        if (bounds.height() >= 300) score += 20
        if (bounds.left in 220..900) score += 10

        return score
    }
}
