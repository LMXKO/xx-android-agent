package com.lmx.xiaoxuanagent.agent

data class ConnectedAppDescriptor(
    val appId: String,
    val packageName: String,
    val title: String,
    val operations: List<String>,
    val summary: String,
    val supportCeiling: String = "",
    val finalHandoffSummary: String = "",
    val alternatePackageNames: List<String> = emptyList(),
) {
    /** 主包名 + 备选包名,按优先级返回。launch / 安装预检都用这个。 */
    fun candidatePackageNames(): List<String> =
        (listOf(packageName) + alternatePackageNames).filter { it.isNotBlank() }.distinct()
}

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
                alternatePackageNames = listOf("com.sankuai.meituan.takeoutnew"),
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
            ConnectedAppDescriptor(
                appId = "cainiao_assistant",
                packageName = "com.cainiao.wireless",
                title = "菜鸟 Connected App",
                operations = ConnectedAppGoldenPathRegistry.operations("cainiao_assistant"),
                summary = "查快递与运单状态，归档或转告前交还用户。",
                supportCeiling = "可稳定推进到运单详情/物流轨迹页。",
                finalHandoffSummary = "用户在结果上自行决定下一步（转告、签收、催件）。",
            ),
            ConnectedAppDescriptor(
                appId = "didi_assistant",
                packageName = "com.sdu.didi.psnger",
                title = "滴滴出行 Connected App",
                operations = ConnectedAppGoldenPathRegistry.operations("didi_assistant"),
                summary = "准备打车上下车点与车型选择，下单/支付前交还用户。",
                supportCeiling = "可稳定推进到预估卡片，等待用户确认下单。",
                finalHandoffSummary = "下单、改派、支付前交给用户。",
            ),
            ConnectedAppDescriptor(
                appId = "bilibili_assistant",
                packageName = "tv.danmaku.bili",
                title = "B 站 Connected App",
                operations = ConnectedAppGoldenPathRegistry.operations("bilibili_assistant"),
                summary = "B 站视频/作者/评论调研，找到候选后由用户决定播放或互动。",
                supportCeiling = "可稳定推进到搜索结果、视频详情、评论区。",
                finalHandoffSummary = "点赞、投币、关注、评论前交给用户。",
                alternatePackageNames = listOf("tv.danmaku.bilibilihd", "com.bilibili.app.in"),
            ),
            ConnectedAppDescriptor(
                appId = "xhs_assistant",
                packageName = "com.xingin.xhs",
                title = "小红书 Connected App",
                operations = ConnectedAppGoldenPathRegistry.operations("xhs_assistant"),
                summary = "小红书笔记/作者搜索与种草调研，互动前交还用户。",
                supportCeiling = "可稳定推进到笔记列表与详情页。",
                finalHandoffSummary = "点赞、收藏、评论、私信前交给用户。",
            ),
            ConnectedAppDescriptor(
                appId = "browser_assistant",
                packageName = "com.android.chrome",
                title = "浏览器 Connected App",
                operations = ConnectedAppGoldenPathRegistry.operations("browser_assistant"),
                summary = "通用网页检索与资料调研，整理结果证据再交还用户。",
                supportCeiling = "可稳定推进到搜索结果或目标网页正文。",
                finalHandoffSummary = "登录、表单提交、付款前交给用户。",
                alternatePackageNames =
                    listOf(
                        "com.microsoft.emmx", // Edge
                        "com.brave.browser",
                        "org.mozilla.firefox",
                        "com.huawei.browser", // 华为
                        "com.android.browser",
                        "com.miui.browser", // 小米
                        "com.heytap.browser", // OPPO
                        "com.vivo.browser",
                        "com.UCMobile", // UC
                        "com.tencent.mtt", // QQ 浏览器
                    ),
            ),
            ConnectedAppDescriptor(
                appId = "gmail_assistant",
                packageName = "com.google.android.gm",
                title = "Gmail Connected App",
                operations = ConnectedAppGoldenPathRegistry.operations("gmail_assistant"),
                summary = "邮件检索、阅读和草稿准备，真实发送前交还用户。",
                supportCeiling = "可稳定推进到收件箱、邮件详情、草稿编辑。",
                finalHandoffSummary = "发送、删除、归档前交给用户。",
                alternatePackageNames =
                    listOf(
                        "com.microsoft.office.outlook",
                        "com.netease.mobimail", // 网易邮箱大师
                        "com.tencent.androidqqmail", // QQ 邮箱
                    ),
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
