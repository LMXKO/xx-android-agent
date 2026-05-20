package com.lmx.xiaoxuanagent.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.lmx.xiaoxuanagent.runtime.AppPageState

object ShoppingPageDetector {
    fun detect(root: AccessibilityNodeInfo): AppPageState {
        val nodes = root.allNodes()
        val texts = nodes.map { it.readableText() }.filter { it.isNotBlank() }
        val viewIds = nodes.mapNotNull { it.viewIdResourceName }.filter { it.isNotBlank() }
        val editableCount = nodes.count { it.isEditable }
        val scrollableCount = nodes.count { it.isScrollable }
        val clickableCount = nodes.count { it.isClickable }

        fun containsAny(vararg values: String): Boolean =
            values.any { target -> texts.any { it.contains(target, ignoreCase = true) } }

        fun containsViewId(vararg values: String): Boolean =
            values.any { target -> viewIds.any { it.contains(target, ignoreCase = true) } }

        val scores =
            linkedMapOf(
                AppPageState.ProductDetail to scoreProductDetail(
                    texts = texts,
                    viewIds = viewIds,
                    clickableCount = clickableCount,
                ),
                AppPageState.ProductReview to scoreProductReview(
                    texts = texts,
                    viewIds = viewIds,
                ),
                AppPageState.ProductParam to scoreProductParam(
                    texts = texts,
                    viewIds = viewIds,
                ),
                AppPageState.SearchInput to scoreSearchInput(
                    texts = texts,
                    viewIds = viewIds,
                    editableCount = editableCount,
                ),
                AppPageState.SearchResultWeak to scoreSearchResult(
                    texts = texts,
                    viewIds = viewIds,
                    scrollableCount = scrollableCount,
                    clickableCount = clickableCount,
                ),
                AppPageState.Home to scoreHome(
                    texts = texts,
                    viewIds = viewIds,
                    editableCount = editableCount,
                    clickableCount = clickableCount,
                ),
            )

        val best = scores.maxByOrNull { it.value } ?: return AppPageState.Unknown
        return if (best.value >= 3) {
            best.key
        } else {
            AppPageState.Unknown
        }
    }

    private fun scoreProductDetail(
        texts: List<String>,
        viewIds: List<String>,
        clickableCount: Int,
    ): Int {
        var score = 0
        if (containsAny(texts, "加入购物车", "领券购买", "立即购买")) score += 4
        if (containsAny(texts, "商品", "店铺", "客服", "参数", "评价")) score += 2
        if (containsViewId(viewIds, "open_cart", "toolbar_review", "btn_buy")) score += 3
        if (clickableCount > 20) score += 1
        return score
    }

    private fun scoreProductReview(
        texts: List<String>,
        viewIds: List<String>,
    ): Int {
        var score = 0
        if (containsAny(texts, "问大家", "评价 AI 总结", "全部评价", "好评", "中评", "差评")) score += 4
        if (containsAny(texts, "有图", "最新", "追评")) score += 2
        if (containsViewId(viewIds, "rate", "review", "comment")) score += 2
        return score
    }

    private fun scoreProductParam(
        texts: List<String>,
        viewIds: List<String>,
    ): Int {
        var score = 0
        if (containsAny(texts, "参数", "材质", "尺寸", "品牌", "型号", "规格")) score += 4
        if (containsAny(texts, "连接方式", "颜色分类", "适用")) score += 2
        if (containsViewId(viewIds, "parameter", "sku", "props")) score += 2
        return score
    }

    private fun scoreSearchInput(
        texts: List<String>,
        viewIds: List<String>,
        editableCount: Int,
    ): Int {
        var score = 0
        if (containsAny(texts, "历史搜索", "猜你想搜", "搜宝贝", "搜店铺", "千问")) score += 3
        if (containsAny(texts, "搜索")) score += 1
        if (containsViewId(viewIds, "searchEdit", "search_bar", "search_wrapper")) score += 3
        if (editableCount > 0) score += 3
        return score
    }

    private fun scoreSearchResult(
        texts: List<String>,
        viewIds: List<String>,
        scrollableCount: Int,
        clickableCount: Int,
    ): Int {
        var score = 0
        if (containsAny(texts, "回到列表顶部", "拍立淘", "综合", "销量", "筛选")) score += 3
        if (containsAny(texts, "人付款", "评价", "券后", "店铺")) score += 2
        if (containsViewId(viewIds, "toolbar_backtop", "search_bar_wrapper", "searchEdit")) score += 2
        if (scrollableCount > 0) score += 1
        if (clickableCount > 30) score += 1
        return score
    }

    private fun scoreHome(
        texts: List<String>,
        viewIds: List<String>,
        editableCount: Int,
        clickableCount: Int,
    ): Int {
        var score = 0
        if (containsAny(texts, "首页", "推荐", "搜索栏")) score += 3
        if (containsAny(texts, "扫一扫", "拍立淘", "天猫", "百亿补贴")) score += 2
        if (containsViewId(viewIds, "home", "search_bar_wrapper", "tb_main")) score += 2
        if (editableCount == 0 && clickableCount > 20) score += 1
        return score
    }

    private fun containsAny(
        texts: List<String>,
        vararg values: String,
    ): Boolean = values.any { target -> texts.any { it.contains(target, ignoreCase = true) } }

    private fun containsViewId(
        viewIds: List<String>,
        vararg values: String,
    ): Boolean = values.any { target -> viewIds.any { it.contains(target, ignoreCase = true) } }
}
