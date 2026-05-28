# xx-android-agent

面向真实 Android 手机上的开源自动化助手。

`xx-android-agent` 读取当前屏幕、规划下一步、通过无障碍执行点击/输入/滑动/返回；可以**把一个用户目标拆成有序的多 App 步骤**，在多个 App 之间自动衔接，并把长任务的状态持久化，进程被杀或重启后仍能续跑。

## Highlights

- **跨 App 编排**：一个目标 → 有序 leg 序列；上一腿的实体（商品名 / 价格 / 联系人）自动注入下一腿子任务
- **启发式 + LLM 双路分解**：常见模式走启发式，其它任务交给 LLM；分解时会自动跳过未安装的 App
- **长程持久化**：mission 进度、黑板证据进入 resume 快照；进程被杀后由心跳唤醒续跑
- **上下文治理**：Compact 把 mission 进度和上一腿关键证据塞进 governance hints，保证长任务不丢上下文
- **自治为先的确认策略**：默认只在**支付/转账**和**不可逆删除**两类动作上停下来；其余（发送、提交、登录、搜索）自动执行
- **结构化结果抽取**：LLM 从屏幕证据抽取 title / price / fields，启发式回落兜底
- **多包名兜底**：浏览器、邮件等同类 App 各家厂商包名都列了备选，主包名缺失时自动改用同类
- **基础底盘**：前台屏幕识别 + 无障碍执行；优先走 Intent / 连接器 / 系统能力，GUI 自动化作为兜底

## 定位

它不是系统级虚拟手机，也不是无限开放的通用 agent。
它的目标是：**把普通 App 能做到但很脆的手机任务做稳，并把跨 App、长时间的任务做出来。**

## 快速开始

1. 用 Android Studio 打开仓库，等待 Gradle Sync 完成
2. 复制 `.env.example` 为 `.env`
3. 填入你的 OpenAI 兼容接口、模型名和其他参数
4. 安装到真机或模拟器，开启无障碍服务、悬浮窗、通知权限

常用命令：

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew installDebug
```

## 配置

`.env.example` 中的字段如下：

| Key | 说明 |
| --- | --- |
| `AGENT_API_BASE_URL` | 必填，OpenAI 兼容接口地址 |
| `AGENT_API_KEY` | 可选，接口鉴权用 |
| `AGENT_MODEL` | 必填，主规划模型；同时用于跨 App 任务分解与结果抽取 |
| `AGENT_ROUTE_MODEL` | 可选，路由模型；可与主模型相同 |
| `AGENT_WEB_SEARCH_BASE_URL` | 可选，网页检索接口地址 |
| `AGENT_MAX_TURNS` | 可选，单 App 任务最大轮次；跨 App mission 自动按腿数放大 |
| `AGENT_PLANNER_CONNECT_TIMEOUT_MS` | 可选，规划请求连接超时 |
| `AGENT_PLANNER_READ_TIMEOUT_MS` | 可选，规划请求读取超时 |
| `AGENT_ROUTE_CONNECT_TIMEOUT_MS` | 可选，路由请求连接超时 |
| `AGENT_ROUTE_READ_TIMEOUT_MS` | 可选，路由请求读取超时 |

优先级：

1. Gradle `-P` 属性
2. 环境变量
3. `.env`
4. 默认值

## 跨 App Mission 怎么工作

最小例子：「比较京东和淘宝上 AirPods Pro 的价格」。

1. **分解**：`CrossAppMissionEngine` 把目标拆成两条 leg：`jd_assistant → shopping_search`，每条 leg 有自己的子任务、目标 App、目标包名。
2. **逐腿执行**：活跃 leg 的 `profileId / task / targetPackageName` 镜像进标准 session 字段，**完全复用现有单 App 规划/执行/恢复主链**——无需改动 planner、executor、recovery。
3. **结果抽取**：每条 leg 在 `Finish` 时由 LLM 抽出结构化 `TaskResultPayload`（含主价格 / 商品名 / 高亮证据），启发式回落兜底。
4. **数据交接**：上一腿的标题与声明字段（默认 `query` / `price`）自动注入下一腿子任务——`leg1` 实际搜的就是 `leg0` 抽到的那款商品。
5. **持久化**：mission 进入 `RuntimeSession` 序列化和 resume 快照；进程被杀重启后由心跳调度优先恢复带 mission 的会话。
6. **收口**：最后一腿 `Finish` 时合并所有腿的 payload，产出 `cross_app_compare` 总结。

支持的 connected app 见 `agent/ConnectedAppCatalog.kt`（目前 16 个：系统设置、高德、微信、美团、淘宝、京东、拼多多、闲鱼、支付宝、菜鸟、滴滴、B 站、小红书、浏览器、Gmail 等），每个都附带备选包名以兼容不同厂商 ROM。

## 权限

这个 App 需要的权限比较多，原因是它要做真实手机自动化：

- 无障碍：读屏和执行操作
- 悬浮窗：提供前台控制入口
- 通知：接收任务状态和接管信息
- 联系人 / 短信 / 通话记录 / 定位：支持消息、通信、地图类任务
- 麦克风：支持语音入口

## 安全边界

默认采用「最大化自治」策略，让长任务尽量不被打断：

- **会停下来交给用户**：支付、转账、付款（`transaction` 类）以及删除、注销、拉黑等不可逆动作（`destructive` 类）
- **硬阻止**：跨 App 携带密码 / 验证码到目标 App 输入框（凭证泄露）；屏幕注入提示词
- **自动执行**：发送消息、发布内容、回复、提交、登录、搜索、跨 App 携带商品名/价格等非敏感字段
- 持久化权限规则系统支持按 App、动作、联系人、数据类型多维度自定义（详见 `safety/RuntimeSafetyPolicyStore.kt`）
- 默认不启用 `cleartextTraffic`、不启用 `backup`
- 公开仓库里不包含密钥、账号信息或个人数据
- `ref/`、`doc/`、`model/`、`tmp/`、`tb/` 这类目录不纳入开源包

## 当前状态

- 代码层：跨 App 编排、持久化、心跳恢复、上下文治理、自治确认、LLM 抽取与分解、多包名兜底均已落地，单元测试覆盖
- 真机端到端：尚未在真实设备上跑过一次完整的跨 App 链路，欢迎反馈 logcat

## 分发注意

这个项目使用了无障碍服务和通知监听等能力。如果你准备发到应用商店，需要额外确认平台政策和权限说明。

## 许可证

MIT
