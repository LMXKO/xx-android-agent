package com.lmx.xiaoxuanagent.agent

data class ConnectedAppDescriptor(
    val appId: String,
    val packageName: String,
    val title: String,
    val operations: List<String>,
    val summary: String,
    val supportCeiling: String = "",
    val finalHandoffSummary: String = "",
)

object ConnectedAppCatalog {
    private val descriptors =
        listOf(
            ConnectedAppDescriptor(
                appId = "system_settings_assistant",
                packageName = "com.android.settings",
                title = "系统设置 Connected App",
                operations = ConnectedAppGoldenPathRegistry.operations("system_settings_assistant"),
                summary = "优先通过结构化设置入口打开系统子页面，而不是走 GUI 探索。",
                supportCeiling = "可稳定推进到系统子页面级别。",
                finalHandoffSummary = "通常无需最终确认，可直接让用户继续调整。",
            ),
            ConnectedAppDescriptor(
                appId = "amap_assistant",
                packageName = "com.autonavi.minimap",
                title = "高德地图 Connected App",
                operations = ConnectedAppGoldenPathRegistry.operations("amap_assistant"),
                summary = "优先通过地图 Intent 搜索地点、拉起路线或导航，而不是先走屏幕自动化。",
                supportCeiling = "可稳定推进到地点搜索、路线卡片和导航前。",
                finalHandoffSummary = "开始导航前交给用户确认。",
            ),
            ConnectedAppDescriptor(
                appId = "wechat_assistant",
                packageName = "com.tencent.mm",
                title = "微信 Focus Connected App",
                operations = ConnectedAppGoldenPathRegistry.operations("wechat_assistant"),
                summary = "优先以结构化方式准备会话定位与消息草稿，再把最后发送交还用户。",
                supportCeiling = "可稳定推进到会话定位和消息待发送状态。",
                finalHandoffSummary = "真正发送前交给用户点最后一步。",
            ),
            ConnectedAppDescriptor(
                appId = "meituan_assistant",
                packageName = "com.sankuai.meituan",
                title = "美团 Focus Connected App",
                operations = ConnectedAppGoldenPathRegistry.operations("meituan_assistant"),
                summary = "优先以结构化方式准备搜索、筛选和下单前推进，再把支付交还用户。",
                supportCeiling = "可稳定推进到搜索、筛选和支付前最后一步。",
                finalHandoffSummary = "支付或最终确认前交给用户。",
            ),
            ConnectedAppDescriptor(
                appId = "shopping_search",
                packageName = "com.taobao.taobao",
                title = "淘宝/天猫 Connected App",
                operations = ConnectedAppGoldenPathRegistry.operations("shopping_search"),
                summary = "优先准备商品搜索、详情/评价/参数读取，再把购买/下单前交还用户。",
                supportCeiling = "可稳定推进到商品候选、详情信息和下单前。",
                finalHandoffSummary = "加入购物车、提交订单和支付前交给用户。",
            ),
            ConnectedAppDescriptor(
                appId = "jd_assistant",
                packageName = "com.jingdong.app.mall",
                title = "京东 Connected App",
                operations = ConnectedAppGoldenPathRegistry.operations("jd_assistant"),
                summary = "优先准备商品搜索、自营/参数/评价读取，再把购买/下单前交还用户。",
                supportCeiling = "可稳定推进到商品候选、详情信息和下单前。",
                finalHandoffSummary = "加入购物车、提交订单和支付前交给用户。",
            ),
            ConnectedAppDescriptor(
                appId = "pdd_assistant",
                packageName = "com.xunmeng.pinduoduo",
                title = "拼多多 Connected App",
                operations = ConnectedAppGoldenPathRegistry.operations("pdd_assistant"),
                summary = "优先准备低价/补贴商品搜索和候选比较，再把购买/下单前交还用户。",
                supportCeiling = "可稳定推进到商品候选、详情信息和下单前。",
                finalHandoffSummary = "拼单、提交订单和支付前交给用户。",
            ),
            ConnectedAppDescriptor(
                appId = "xianyu_assistant",
                packageName = "com.taobao.idlefish",
                title = "闲鱼 Connected App",
                operations = ConnectedAppGoldenPathRegistry.operations("xianyu_assistant"),
                summary = "优先准备闲置商品搜索和候选比较，再把联系卖家/出价/下单前交还用户。",
                supportCeiling = "可稳定推进到闲置商品候选和详情页。",
                finalHandoffSummary = "联系卖家、出价和下单前交给用户。",
            ),
            ConnectedAppDescriptor(
                appId = "alipay_assistant",
                packageName = "com.eg.android.AlipayGphone",
                title = "支付宝 Connected App",
                operations = ConnectedAppGoldenPathRegistry.operations("alipay_assistant"),
                summary = "优先准备账单/生活服务查询，支付、转账和付款码展示前交还用户。",
                supportCeiling = "可稳定推进到生活服务或账单查询入口。",
                finalHandoffSummary = "任何付款、转账、收款码和最终提交前交给用户。",
            ),
        )

    fun descriptors(): List<ConnectedAppDescriptor> = descriptors

    fun findByAppId(
        appId: String,
    ): ConnectedAppDescriptor? = descriptors.firstOrNull { it.appId == appId }

    fun findByPackageName(
        packageName: String,
    ): ConnectedAppDescriptor? = descriptors.firstOrNull { it.packageName == packageName }
}
