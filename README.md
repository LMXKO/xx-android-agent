# xx-android-agent

面向真实 Android 手机上的开源自动化助手。

`xx-android-agent` 可以读取当前屏幕、规划操作步骤，并通过无障碍执行点击、输入、滑动、返回等动作；在需要用户确认的最后一步，它会停下来交给你接管。

## Highlights

- 前台屏幕识别 + 无障碍执行
- LLM 驱动的任务路由与多步规划
- 优先走 Intent / 连接器 / 系统能力，GUI 自动化作为兜底
- 对登录、支付、提交、删除等高风险动作默认保留人工确认

## 定位

它不是系统级虚拟手机，也不是无限开放的通用 agent。
它的目标是把“普通 App 能做到但很脆”的手机任务，尽量做稳。

## 快速开始

1. 用 Android Studio 打开仓库，等待 Gradle Sync 完成
2. 复制 `.env.example` 为 `.env`
3. 填入你的 OpenAI 兼容接口、模型名和其他参数
4. 安装到真机或模拟器

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
| `AGENT_MODEL` | 必填，主规划模型 |
| `AGENT_ROUTE_MODEL` | 可选，路由模型；可与主模型相同 |
| `AGENT_WEB_SEARCH_BASE_URL` | 可选，网页检索接口地址 |
| `AGENT_MAX_TURNS` | 可选，单任务最大轮次 |
| `AGENT_PLANNER_CONNECT_TIMEOUT_MS` | 可选，规划请求连接超时 |
| `AGENT_PLANNER_READ_TIMEOUT_MS` | 可选，规划请求读取超时 |
| `AGENT_ROUTE_CONNECT_TIMEOUT_MS` | 可选，路由请求连接超时 |
| `AGENT_ROUTE_READ_TIMEOUT_MS` | 可选，路由请求读取超时 |

优先级：

1. Gradle `-P` 属性
2. 环境变量
3. `.env`
4. 默认值

## 权限

这个 App 需要的权限比较多，原因是它要做真实手机自动化：

- 无障碍：读屏和执行操作
- 悬浮窗：提供前台控制入口
- 通知：接收任务状态和接管信息
- 联系人 / 短信 / 通话记录 / 定位：支持消息、通信、地图类任务
- 麦克风：支持语音入口

## 安全边界

- 发消息、支付、登录、删除、提交这类动作默认会在最后一步交给用户
- 默认不启用 `cleartextTraffic`
- 默认不启用 `backup`
- 公开仓库里不包含密钥、账号信息或个人数据
- `ref/`、`doc/`、`model/`、`tmp/`、`tb/` 这类目录不纳入开源包

## 分发注意

这个项目使用了无障碍服务和通知监听等能力。如果你准备发到应用商店，需要额外确认平台政策和权限说明。

## 许可证

MIT
