package com.lmx.xiaoxuanagent.runtime

internal enum class SessionPlatformCommandCategory {
    SESSION,
    MEMORY,
    WORKER,
    PRODUCT,
    GOVERNANCE,
    DIAGNOSTICS,
}

internal data class SessionPlatformCommandDefinition(
    val name: String,
    val title: String,
    val description: String,
    val usage: String,
    val category: SessionPlatformCommandCategory,
    val aliases: List<String> = emptyList(),
    val argumentHints: List<String> = emptyList(),
    val examples: List<String> = listOf(usage),
    val recommended: Boolean = false,
    val validate: ((SessionPlatformCommandArgs) -> String?)? = null,
    val parse: (SessionPlatformCommandArgs) -> SessionPlatformCommand,
)

internal data class SessionPlatformCommandResolution(
    val command: SessionPlatformCommand? = null,
    val summary: String,
    val lines: List<String> = emptyList(),
)

internal object SessionPlatformCommandRegistry {
    private val commands =
        listOf(
            SessionPlatformCommandDefinition(
                name = "/assistant-sync",
                title = "刷新 Assistant OS",
                description = "刷新 assistant OS 的 trigger、projection 和 viewer bundle。",
                usage = "/assistant-sync",
                category = SessionPlatformCommandCategory.PRODUCT,
                recommended = true,
            ) { SessionPlatformCommand(SessionCapabilityKey.REFRESH_ASSISTANT_OS) },
            SessionPlatformCommandDefinition(
                name = "/help",
                title = "命令帮助",
                description = "查看命令目录、某个命令的帮助，或按前缀/分类给出建议。",
                usage = "/help /viewer",
                category = SessionPlatformCommandCategory.PRODUCT,
                aliases = listOf("/h"),
                argumentHints = listOf("[/command]", "[--prefix /vi]", "[--category governance]"),
                examples =
                    listOf(
                        "/help",
                        "/help /viewer",
                        "/help --prefix /set-",
                        "/help --category governance",
                    ),
            ) { args ->
                val first = args.positional(0)
                val command =
                    args.option("command")
                        .ifBlank { first.takeIf { it.startsWith("/") }.orEmpty() }
                val category =
                    args.option("category")
                        .ifBlank { first.takeIf { it.isNotBlank() && !it.startsWith("/") }.orEmpty() }
                val prefix =
                    args.option("prefix")
                        .ifBlank { if (command.isBlank() && category.isBlank()) first else "" }
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_COMMAND_CATALOG,
                    payload =
                        mapOf(
                            "command" to command,
                            "prefix" to prefix,
                            "category" to category,
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/start",
                title = "启动任务",
                description = "启动一个新的 session。",
                usage = "/start --task \"帮我回消息并整理重点\"",
                category = SessionPlatformCommandCategory.SESSION,
                recommended = true,
                argumentHints = listOf("--task <任务描述>"),
                validate = { args ->
                    requireNonBlank(args.optionOrRest("task", 0), "启动任务需要提供 `--task` 或直接写任务描述。")
                },
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.START_SESSION,
                    task = args.optionOrRest("task", 0),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/resume",
                title = "继续任务",
                description = "恢复挂起或暂停的 session。",
                usage = "/resume --session-id session_123 --user-correction \"回到上一步重新点\"",
                category = SessionPlatformCommandCategory.SESSION,
                recommended = true,
                argumentHints = listOf("--session-id <session_id>", "[--user-correction <纠错>]"),
                validate = { args ->
                    requireNonBlank(args.optionOrPositional("session-id", 0), "继续任务需要提供 `--session-id`。")
                },
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.RESUME_SESSION,
                    sessionId = args.optionOrPositional("session-id", 0),
                    userCorrection = args.optionOrRest("user-correction", 1),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/approve",
                title = "批准风险动作",
                description = "批准当前待确认动作。",
                usage = "/approve --session-id session_123 --user-correction \"金额确认无误\"",
                category = SessionPlatformCommandCategory.SESSION,
                recommended = true,
                argumentHints = listOf("--session-id <session_id>", "[--user-correction <说明>]"),
                validate = { args ->
                    requireNonBlank(args.optionOrPositional("session-id", 0), "批准风险动作需要提供 `--session-id`。")
                },
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.APPROVE_SAFETY,
                    sessionId = args.optionOrPositional("session-id", 0),
                    userCorrection = args.optionOrRest("user-correction", 1),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/reject",
                title = "拒绝风险动作",
                description = "拒绝当前待确认动作。",
                usage = "/reject --session-id session_123",
                category = SessionPlatformCommandCategory.SESSION,
                argumentHints = listOf("--session-id <session_id>"),
                validate = { args ->
                    requireNonBlank(args.optionOrPositional("session-id", 0), "拒绝风险动作需要提供 `--session-id`。")
                },
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.REJECT_SAFETY,
                    sessionId = args.optionOrPositional("session-id", 0),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/stop",
                title = "停止任务",
                description = "停止当前或指定 session。",
                usage = "/stop --session-id session_123 --reason \"用户取消\"",
                category = SessionPlatformCommandCategory.SESSION,
                argumentHints = listOf("--session-id <session_id>", "[--reason <原因>]"),
                validate = { args ->
                    requireNonBlank(args.optionOrPositional("session-id", 0), "停止任务需要提供 `--session-id`。")
                },
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.STOP_SESSION,
                    sessionId = args.optionOrPositional("session-id", 0),
                    payload = mapOf("reason" to args.optionOrRest("reason", 1)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/sessions",
                title = "最近任务",
                description = "读取本地 session history。",
                usage = "/sessions --limit 12",
                category = SessionPlatformCommandCategory.SESSION,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_SESSION_HISTORY,
                    payload = mapOf("limit" to args.optionOrPositional("limit", 0)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/search-sessions",
                title = "检索历史任务",
                description = "按 query 检索本地历史任务。",
                usage = "/search-sessions \"外卖发票\"",
                category = SessionPlatformCommandCategory.SESSION,
                argumentHints = listOf("<query>"),
                validate = { args ->
                    requireNonBlank(args.optionOrRest("query", 0), "检索历史任务需要提供 query。")
                },
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.SEARCH_SESSION_HISTORY,
                    query = args.optionOrRest("query", 0),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/compensations",
                title = "查看补偿计划",
                description = "读取某个 session 的 rollback / compensation 计划。",
                usage = "/compensations --session-id session_123 --limit 6",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
                argumentHints = listOf("--session-id <session_id>", "[--limit <n>]"),
                validate = { args ->
                    requireNonBlank(args.optionOrPositional("session-id", 0), "读取补偿计划需要提供 `--session-id`。")
                },
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_SESSION_COMPENSATIONS,
                    sessionId = args.optionOrPositional("session-id", 0),
                    payload = mapOf("limit" to args.optionOrPositional("limit", 1)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/run-compensation",
                title = "执行补偿动作",
                description = "执行某个 session turn 的补偿步骤。",
                usage = "/run-compensation --session-id session_123 --turn 4 --step-id restore_text_input",
                category = SessionPlatformCommandCategory.GOVERNANCE,
                argumentHints = listOf("--session-id <session_id>", "--turn <n>", "[--step-id <step_id>]"),
                validate = { args ->
                    requireAll(
                        args.optionOrPositional("session-id", 0) to "执行补偿需要提供 `--session-id`。",
                        args.optionOrPositional("turn", 1) to "执行补偿需要提供 `--turn`。",
                    )
                },
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.RUN_SESSION_COMPENSATION,
                    sessionId = args.optionOrPositional("session-id", 0),
                    payload =
                        mapOf(
                            "turn" to args.optionOrPositional("turn", 1),
                            "step_id" to args.option("step-id", "step_id"),
                            "preferred_package" to args.option("preferred-package", "preferred_package"),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/compare",
                title = "对比两个 session",
                description = "查看两个 session 的结果和平台差异。",
                usage = "/compare left_session_id right_session_id",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
                argumentHints = listOf("<left_session_id>", "<right_session_id>"),
                validate = { args ->
                    requireAll(
                        args.optionOrPositional("left-session-id", 0) to "对比 session 需要左侧 session id。",
                        args.optionOrPositional("right-session-id", 1) to "对比 session 需要右侧 session id。",
                    )
                },
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.COMPARE_SESSIONS,
                    sessionId = args.optionOrPositional("left-session-id", 0),
                    payload = mapOf("right_session_id" to args.optionOrPositional("right-session-id", 1)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/artifact",
                title = "检索 Artifact",
                description = "按 query 检索 artifact 索引。",
                usage = "/artifact \"发票 pdf\"",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
                argumentHints = listOf("<query>"),
                validate = { args ->
                    requireNonBlank(args.optionOrRest("query", 0), "检索 artifact 需要提供 query。")
                },
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.SEARCH_ARTIFACTS,
                    query = args.optionOrRest("query", 0),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/memory",
                title = "检索长期记忆",
                description = "通过 recall index 检索长期记忆。",
                usage = "/memory \"上次给张三发过什么\"",
                category = SessionPlatformCommandCategory.MEMORY,
                recommended = true,
                aliases = listOf("/recall"),
                argumentHints = listOf("<query>"),
                validate = { args ->
                    requireNonBlank(args.optionOrRest("query", 0), "检索长期记忆需要提供 query。")
                },
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.RECALL_MEMORY,
                    query = args.optionOrRest("query", 0),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/memory-workspace",
                title = "记忆工作区",
                description = "读取 memory workspace 的 digest 和文件摘要。",
                usage = "/memory-workspace",
                category = SessionPlatformCommandCategory.MEMORY,
            ) { SessionPlatformCommand(SessionCapabilityKey.READ_MEMORY_WORKSPACE) },
            SessionPlatformCommandDefinition(
                name = "/memory-governance",
                title = "记忆治理面",
                description = "读取长期记忆治理条目与审计轨迹。",
                usage = "/memory-governance --limit 16 --audit-limit 8",
                category = SessionPlatformCommandCategory.MEMORY,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_MEMORY_GOVERNANCE,
                    payload =
                        mapOf(
                            "limit" to args.optionOrPositional("limit", 0),
                            "audit_limit" to args.optionOrPositional("audit-limit", 1),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/upsert-memory",
                title = "写入长期记忆",
                description = "新增或更新一条长期记忆治理条目。",
                usage = "/upsert-memory contact \"张三\" \"同事,朋友\" --note \"住在上海徐汇\" --profile-id profile_wechat",
                category = SessionPlatformCommandCategory.MEMORY,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.UPSERT_MEMORY_ENTRY,
                    payload =
                        mapOf(
                            "type" to args.optionOrPositional("type", 0),
                            "primary" to args.optionOrPositional("primary", 1),
                            "secondary" to args.optionOrPositional("secondary", 2),
                            "note" to args.optionOrRest("note", 3),
                            "profile_id" to args.option("profile-id", "profile_id"),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/delete-memory",
                title = "删除长期记忆",
                description = "删除一条长期记忆治理条目。",
                usage = "/delete-memory contact \"张三\"",
                category = SessionPlatformCommandCategory.MEMORY,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.DELETE_MEMORY_ENTRY,
                    payload =
                        mapOf(
                            "type" to args.optionOrPositional("type", 0),
                            "entry_ref" to args.optionOrRest("entry-ref", 1),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/memory-queue",
                title = "记忆后台队列",
                description = "读取后台长期记忆抽取队列。",
                usage = "/memory-queue --limit 12",
                category = SessionPlatformCommandCategory.MEMORY,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_MEMORY_QUEUE,
                    payload =
                        mapOf(
                            "include_completed" to args.optionOrPositional("include-completed", 0),
                            "limit" to args.option("limit"),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/retry-memory-task",
                title = "重试记忆任务",
                description = "重试失败或延后的后台记忆任务。",
                usage = "/retry-memory-task task_id",
                category = SessionPlatformCommandCategory.MEMORY,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.RETRY_MEMORY_TASK,
                    payload = mapOf("task_id" to args.optionOrPositional("task-id", 0)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/product-shell",
                title = "产品壳快照",
                description = "读取完整 product shell 快照。",
                usage = "/product-shell",
                category = SessionPlatformCommandCategory.PRODUCT,
                recommended = true,
            ) { SessionPlatformCommand(SessionCapabilityKey.READ_PRODUCT_SHELL) },
            SessionPlatformCommandDefinition(
                name = "/today",
                title = "今日面板",
                description = "读取 today / routine / digest 面板。",
                usage = "/today",
                category = SessionPlatformCommandCategory.PRODUCT,
                recommended = true,
            ) {
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_PRODUCT_SHELL,
                    payload = mapOf("section" to "today"),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/inbox",
                title = "收件箱面板",
                description = "读取 tips、onboarding、待处理摘要。",
                usage = "/inbox",
                category = SessionPlatformCommandCategory.PRODUCT,
                recommended = true,
            ) {
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_PRODUCT_SHELL,
                    payload = mapOf("section" to "inbox"),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/viewer",
                title = "Viewer 面板",
                description = "读取当前聚焦 session 的 detail、timeline、graph 汇总。",
                usage = "/viewer",
                category = SessionPlatformCommandCategory.PRODUCT,
                recommended = true,
            ) {
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_PRODUCT_SHELL,
                    payload = mapOf("section" to "viewer"),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/approval-center",
                title = "审批中心",
                description = "读取 product shell 里的审批中心与治理控制摘要。",
                usage = "/approval-center",
                category = SessionPlatformCommandCategory.GOVERNANCE,
                recommended = true,
            ) {
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_PRODUCT_SHELL,
                    payload = mapOf("section" to "approvals"),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/timeline",
                title = "时间线",
                description = "读取 viewer timeline 与 trace 线索。",
                usage = "/timeline",
                category = SessionPlatformCommandCategory.PRODUCT,
            ) {
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_PRODUCT_SHELL,
                    payload = mapOf("section" to "timeline"),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/graph",
                title = "Session Graph",
                description = "读取 viewer graph 与 parent-child session graph 摘要。",
                usage = "/graph",
                category = SessionPlatformCommandCategory.PRODUCT,
            ) {
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_PRODUCT_SHELL,
                    payload = mapOf("section" to "graph"),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/governance",
                title = "治理面板",
                description = "读取 consent、privacy、retention 与 explainability 摘要。",
                usage = "/governance",
                category = SessionPlatformCommandCategory.GOVERNANCE,
                recommended = true,
            ) {
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_PRODUCT_SHELL,
                    payload = mapOf("section" to "governance"),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/routine-center",
                title = "Routine Center",
                description = "读取 routine、digest、quiet hours、interrupt policy 的统一产品面。",
                usage = "/routine-center",
                category = SessionPlatformCommandCategory.PRODUCT,
                recommended = true,
            ) {
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_PRODUCT_SHELL,
                    payload = mapOf("section" to "routine_center"),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/entry-surfaces",
                title = "入口面板",
                description = "读取通知、悬浮、快捷方式、组件等手机入口状态。",
                usage = "/entry-surfaces",
                category = SessionPlatformCommandCategory.PRODUCT,
                recommended = true,
            ) {
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_PRODUCT_SHELL,
                    payload = mapOf("section" to "entry"),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/digest",
                title = "摘要面板",
                description = "读取 digest 和高优先级亮点。",
                usage = "/digest",
                category = SessionPlatformCommandCategory.PRODUCT,
            ) {
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_PRODUCT_SHELL,
                    payload = mapOf("section" to "digest"),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/interrupt-budget",
                title = "打断预算",
                description = "读取当前入口打断预算和阻断状态。",
                usage = "/interrupt-budget",
                category = SessionPlatformCommandCategory.PRODUCT,
            ) {
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_PRODUCT_SHELL,
                    payload = mapOf("section" to "interrupt"),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/swarm",
                title = "Swarm 摘要",
                description = "读取 swarm、worker dispatch 和协同摘要。",
                usage = "/swarm",
                category = SessionPlatformCommandCategory.WORKER,
            ) {
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_PRODUCT_SHELL,
                    payload = mapOf("section" to "swarm"),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/refresh-product-shell",
                title = "刷新产品壳",
                description = "手动刷新本地 product shell。",
                usage = "/refresh-product-shell --reason manual",
                category = SessionPlatformCommandCategory.PRODUCT,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.REFRESH_PRODUCT_SHELL,
                    payload = mapOf("reason" to args.optionOrRest("reason", 0)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/tip",
                title = "处理 Tip",
                description = "完成、关闭或重置一条 product shell tip。",
                usage = "/tip --tip-id tip_123 --action complete",
                category = SessionPlatformCommandCategory.PRODUCT,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.ACK_PRODUCT_SHELL_TIP,
                    payload =
                        mapOf(
                            "tip_id" to args.optionOrPositional("tip-id", 0),
                            "action" to args.optionOrPositional("action", 1),
                            "note" to args.optionOrRest("note", 2),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/onboarding-step",
                title = "更新引导步骤",
                description = "完成、跳过或重置 onboarding step。",
                usage = "/onboarding-step --step-id onboarding_1 --action complete",
                category = SessionPlatformCommandCategory.PRODUCT,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.UPDATE_PRODUCT_ONBOARDING,
                    payload =
                        mapOf(
                            "step_id" to args.optionOrPositional("step-id", 0),
                            "action" to args.optionOrPositional("action", 1),
                            "note" to args.optionOrRest("note", 2),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/providers",
                title = "Signal Providers",
                description = "读取 signal provider 的健康状态。",
                usage = "/providers",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
            ) { SessionPlatformCommand(SessionCapabilityKey.READ_SIGNAL_PROVIDERS) },
            SessionPlatformCommandDefinition(
                name = "/provider-policy",
                title = "Provider Policy",
                description = "读取 provider/router 策略。",
                usage = "/provider-policy",
                category = SessionPlatformCommandCategory.GOVERNANCE,
            ) { SessionPlatformCommand(SessionCapabilityKey.READ_PROVIDER_POLICY) },
            SessionPlatformCommandDefinition(
                name = "/provider-registry",
                title = "Provider Registry",
                description = "读取 planner provider registry、backend 与优先级配置。",
                usage = "/provider-registry",
                category = SessionPlatformCommandCategory.GOVERNANCE,
            ) { SessionPlatformCommand(SessionCapabilityKey.READ_PROVIDER_REGISTRY) },
            SessionPlatformCommandDefinition(
                name = "/routine-policy",
                title = "Routine 策略",
                description = "读取 routine policy 的 focus、window、surface 配置。",
                usage = "/routine-policy",
                category = SessionPlatformCommandCategory.PRODUCT,
            ) {
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_PRODUCT_POLICY,
                    payload = mapOf("policy_type" to "routine"),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/set-routine-policy",
                title = "更新 Routine 策略",
                description = "按语义字段更新 routine center 的 focus/window/checklist/surface。",
                usage = "/set-routine-policy --focus-theme \"消息收口\" --review-window 09:00-11:30",
                category = SessionPlatformCommandCategory.PRODUCT,
                recommended = true,
                argumentHints =
                    listOf(
                        "[--focus-theme <主题>]",
                        "[--review-window HH:mm-HH:mm]",
                        "[--follow-up-window HH:mm-HH:mm]",
                        "[--checklist-templates a|b|c]",
                        "[--preferred-surfaces notification,widget]",
                    ),
                validate = { args ->
                    requireAny(
                        args.option("focus-theme", "focus_theme"),
                        args.option("review-window", "review_window"),
                        args.option("follow-up-window", "follow_up_window"),
                        args.option("checklist-templates", "checklist_templates"),
                        args.option("preferred-surfaces", "preferred_surfaces"),
                    ).ifBlankOrNull("更新 routine policy 至少需要提供一个字段。")
                },
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.UPSERT_PRODUCT_POLICY,
                    payload =
                        mapOf(
                            "policy_type" to "routine",
                            "focus_theme" to args.option("focus-theme", "focus_theme"),
                            "review_window" to args.option("review-window", "review_window"),
                            "follow_up_window" to args.option("follow-up-window", "follow_up_window"),
                            "checklist_templates" to args.option("checklist-templates", "checklist_templates"),
                            "preferred_surfaces" to args.option("preferred-surfaces", "preferred_surfaces"),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/digest-policy",
                title = "Digest 策略",
                description = "读取 digest cadence、highlight 与 delivery surface 策略。",
                usage = "/digest-policy",
                category = SessionPlatformCommandCategory.PRODUCT,
            ) {
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_PRODUCT_POLICY,
                    payload = mapOf("policy_type" to "digest"),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/set-digest-policy",
                title = "更新 Digest 策略",
                description = "更新 digest 开关、节奏、highlight 数与投递面。",
                usage = "/set-digest-policy --enabled true --cadence adaptive --max-highlights 4",
                category = SessionPlatformCommandCategory.PRODUCT,
                recommended = true,
                argumentHints =
                    listOf(
                        "[--enabled true|false]",
                        "[--cadence adaptive|quiet|aggressive]",
                        "[--max-highlights <n>]",
                        "[--delivery-surfaces notification,widget]",
                    ),
                validate = { args ->
                    requireAny(
                        args.option("enabled"),
                        args.option("cadence"),
                        args.option("max-highlights", "max_highlights"),
                        args.option("delivery-surfaces", "delivery_surfaces"),
                    ).ifBlankOrNull("更新 digest policy 至少需要提供一个字段。")
                },
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.UPSERT_PRODUCT_POLICY,
                    payload =
                        mapOf(
                            "policy_type" to "digest",
                            "enabled" to args.option("enabled"),
                            "cadence" to args.option("cadence"),
                            "max_highlights" to args.option("max-highlights", "max_highlights"),
                            "delivery_surfaces" to args.option("delivery-surfaces", "delivery_surfaces"),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/quiet-hours",
                title = "Quiet Hours",
                description = "读取 quiet hours 的启用状态与当前窗口。",
                usage = "/quiet-hours",
                category = SessionPlatformCommandCategory.PRODUCT,
            ) {
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_PRODUCT_POLICY,
                    payload = mapOf("policy_type" to "quiet_hours"),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/set-quiet-hours",
                title = "更新 Quiet Hours",
                description = "更新静默时间窗口与启用状态。",
                usage = "/set-quiet-hours --enabled true --start-local-time 22:30 --end-local-time 08:00",
                category = SessionPlatformCommandCategory.PRODUCT,
                recommended = true,
                argumentHints =
                    listOf(
                        "[--enabled true|false]",
                        "[--start-local-time HH:mm]",
                        "[--end-local-time HH:mm]",
                    ),
                validate = { args ->
                    requireAny(
                        args.option("enabled"),
                        args.option("start-local-time", "start_local_time"),
                        args.option("end-local-time", "end_local_time"),
                    ).ifBlankOrNull("更新 quiet hours 至少需要提供一个字段。")
                },
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.UPSERT_PRODUCT_POLICY,
                    payload =
                        mapOf(
                            "policy_type" to "quiet_hours",
                            "enabled" to args.option("enabled"),
                            "start_local_time" to args.option("start-local-time", "start_local_time"),
                            "end_local_time" to args.option("end-local-time", "end_local_time"),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/interrupt-policy",
                title = "Interrupt 策略",
                description = "读取 interrupt budget、preferred sources 与 quiet-hours block 策略。",
                usage = "/interrupt-policy",
                category = SessionPlatformCommandCategory.PRODUCT,
            ) {
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_PRODUCT_POLICY,
                    payload = mapOf("policy_type" to "interrupt"),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/set-interrupt-policy",
                title = "更新 Interrupt 策略",
                description = "更新 base/focus budget、preferred sources 与 quiet-hours hard block。",
                usage = "/set-interrupt-policy --base-budget 4 --focus-budget 2 --preferred-sources notification,widget",
                category = SessionPlatformCommandCategory.PRODUCT,
                recommended = true,
                argumentHints =
                    listOf(
                        "[--base-budget <n>]",
                        "[--focus-budget <n>]",
                        "[--hard-block-in-quiet-hours true|false]",
                        "[--preferred-sources notification,widget]",
                        "[--blocked-sources voice]",
                    ),
                validate = { args ->
                    requireAny(
                        args.option("base-budget", "base_budget"),
                        args.option("focus-budget", "focus_budget"),
                        args.option("hard-block-in-quiet-hours", "hard_block_in_quiet_hours"),
                        args.option("preferred-sources", "preferred_surfaces", "preferred_sources"),
                        args.option("blocked-sources", "blocked_sources"),
                    ).ifBlankOrNull("更新 interrupt policy 至少需要提供一个字段。")
                },
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.UPSERT_PRODUCT_POLICY,
                    payload =
                        mapOf(
                            "policy_type" to "interrupt",
                            "base_budget" to args.option("base-budget", "base_budget"),
                            "focus_budget" to args.option("focus-budget", "focus_budget"),
                            "hard_block_in_quiet_hours" to args.option("hard-block-in-quiet-hours", "hard_block_in_quiet_hours"),
                            "preferred_sources" to args.option("preferred-sources", "preferred_sources"),
                            "blocked_sources" to args.option("blocked-sources", "blocked_sources"),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/set-provider-policy",
                title = "更新 Provider Policy",
                description = "以语义化 flags 更新 provider policy。",
                usage = "/set-provider-policy --enabled true --prefer-text-on-resume true",
                category = SessionPlatformCommandCategory.GOVERNANCE,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.UPSERT_PROVIDER_POLICY,
                    payload =
                        mapOf(
                            "enabled" to args.optionOrPositional("enabled", 0),
                            "prefer_text_on_artifact_heavy_stage" to args.optionOrPositional("prefer-text-on-artifact-heavy-stage", 1),
                            "prefer_text_on_resume" to args.optionOrPositional("prefer-text-on-resume", 2),
                            "sparse_node_gap" to args.optionOrPositional("sparse-node-gap", 3),
                            "visual_page_gap" to args.optionOrPositional("visual-page-gap", 4),
                            "text_failure_gap" to args.optionOrPositional("text-failure-gap", 5),
                            "stage_overrides" to args.optionOrPositional("stage-overrides", 6),
                            "package_overrides" to args.optionOrPositional("package-overrides", 7),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/set-provider-registry",
                title = "更新 Provider Registry",
                description = "启用、禁用或调整某个 planner provider 的 backend / priority / 能力标记。",
                usage = "/set-provider-registry --provider-id local_heuristic_text --enabled true --priority 60",
                category = SessionPlatformCommandCategory.GOVERNANCE,
                argumentHints =
                    listOf(
                        "--provider-id <provider_id>",
                        "[--enabled true|false]",
                        "[--priority <n>]",
                        "[--backend openai|local_heuristic|semantic_local|recovery_heuristic]",
                        "[--modality text|vision]",
                        "[--capabilities structured,resume]",
                    ),
                validate = { args ->
                    requireNonBlank(args.optionOrPositional("provider-id", 0), "更新 provider registry 需要提供 `--provider-id`。")
                },
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.UPSERT_PROVIDER_REGISTRY,
                    payload =
                        mapOf(
                            "provider_id" to args.optionOrPositional("provider-id", 0),
                            "enabled" to args.option("enabled"),
                            "priority" to args.option("priority"),
                            "backend" to args.option("backend"),
                            "modality" to args.option("modality"),
                            "route_label" to args.option("route-label", "route_label"),
                            "latency_tier" to args.option("latency-tier", "latency_tier"),
                            "cost_tier" to args.option("cost-tier", "cost_tier"),
                            "supports_screenshot" to args.option("supports-screenshot", "supports_screenshot"),
                            "supports_structured_output" to args.option("supports-structured-output", "supports_structured_output"),
                            "tags" to args.option("tags"),
                            "capabilities" to args.option("capabilities"),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/approvals",
                title = "Capability 审批",
                description = "读取待审批 capability 请求。",
                usage = "/approvals",
                category = SessionPlatformCommandCategory.GOVERNANCE,
            ) { SessionPlatformCommand(SessionCapabilityKey.READ_CAPABILITY_APPROVALS) },
            SessionPlatformCommandDefinition(
                name = "/approve-cap",
                title = "批准 Capability 请求",
                description = "批准一条待审批 capability 请求。",
                usage = "/approve-cap approval_id",
                category = SessionPlatformCommandCategory.GOVERNANCE,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.APPROVE_CAPABILITY_REQUEST,
                    payload =
                        mapOf(
                            "approval_id" to args.optionOrPositional("approval-id", 0),
                            "grant_scope" to args.option("grant-scope", "grant_scope"),
                            "ttl_minutes" to args.option("ttl-minutes", "ttl_minutes"),
                            "note" to args.optionOrRest("note", 1),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/reject-cap",
                title = "拒绝 Capability 请求",
                description = "拒绝一条待审批 capability 请求。",
                usage = "/reject-cap approval_id",
                category = SessionPlatformCommandCategory.GOVERNANCE,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.REJECT_CAPABILITY_REQUEST,
                    payload = mapOf("approval_id" to args.optionOrPositional("approval-id", 0)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/cap-grants",
                title = "Capability 授权",
                description = "读取当前 capability grant / authorization 状态。",
                usage = "/cap-grants --family session_control --include-inactive true",
                category = SessionPlatformCommandCategory.GOVERNANCE,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_CAPABILITY_GRANTS,
                    payload =
                        mapOf(
                            "limit" to args.option("limit"),
                            "family" to args.option("family"),
                            "session_id" to args.option("session-id", "session_id"),
                            "include_inactive" to args.option("include-inactive", "include_inactive"),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/revoke-cap-grant",
                title = "撤销 Capability 授权",
                description = "撤销一条已经签发的 capability grant。",
                usage = "/revoke-cap-grant grant_id --note revoked_by_user",
                category = SessionPlatformCommandCategory.GOVERNANCE,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.REVOKE_CAPABILITY_GRANT,
                    payload =
                        mapOf(
                            "grant_id" to args.optionOrPositional("grant-id", 0),
                            "note" to args.optionOrRest("note", 1),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/cap-policies",
                title = "Capability 策略",
                description = "读取 capability policy。",
                usage = "/cap-policies",
                category = SessionPlatformCommandCategory.GOVERNANCE,
            ) { SessionPlatformCommand(SessionCapabilityKey.READ_CAPABILITY_POLICIES) },
            SessionPlatformCommandDefinition(
                name = "/upsert-cap-policy",
                title = "写入 Capability 策略",
                description = "新增或替换 capability policy 规则。",
                usage = "/upsert-cap-policy rule_id entry_source_prefix capability decision --reason 说明",
                category = SessionPlatformCommandCategory.GOVERNANCE,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.UPSERT_CAPABILITY_POLICY,
                    payload =
                        mapOf(
                            "rule_id" to args.optionOrPositional("rule-id", 0),
                            "entry_source_prefix" to args.optionOrPositional("entry-source-prefix", 1),
                            "capability" to args.optionOrPositional("capability", 2),
                            "decision" to args.optionOrPositional("decision", 3),
                            "reason" to args.optionOrRest("reason", 4),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/delete-cap-policy",
                title = "删除 Capability 策略",
                description = "删除一条 capability policy 规则。",
                usage = "/delete-cap-policy rule_id",
                category = SessionPlatformCommandCategory.GOVERNANCE,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.DELETE_CAPABILITY_POLICY,
                    payload = mapOf("rule_id" to args.optionOrPositional("rule-id", 0)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/sidechains",
                title = "Sidechains",
                description = "读取 worker/subagent sidechain。",
                usage = "/sidechains --session-id session_123",
                category = SessionPlatformCommandCategory.WORKER,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_AGENT_SIDECHAINS,
                    sessionId = args.optionOrPositional("session-id", 0),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/fork",
                title = "Fork Worker",
                description = "把子任务排成 worker 队列。",
                usage = "/fork --session-id session_123 --task \"检查订单页\"",
                category = SessionPlatformCommandCategory.WORKER,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.FORK_WORKER,
                    sessionId = args.optionOrPositional("session-id", 0),
                    task = args.optionOrRest("task", 1),
                    payload = mapOf("summary" to args.optionOrRest("summary", 1)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/mailbox",
                title = "Worker Mailbox",
                description = "读取 worker/session graph mailbox。",
                usage = "/mailbox target_worker_id",
                category = SessionPlatformCommandCategory.WORKER,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_WORKER_MAILBOX,
                    sessionId = args.optionOrPositional("target", 0),
                    payload =
                        mapOf(
                            "target" to args.optionOrPositional("target", 0),
                            "include_consumed" to args.optionOrPositional("include-consumed", 1),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/post-worker-message",
                title = "投递 Worker 消息",
                description = "向 worker mailbox 投递结构化消息。",
                usage = "/post-worker-message control worker_42 --title \"补齐 provider policy\" --summary \"先处理失败冷却\"",
                category = SessionPlatformCommandCategory.WORKER,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.POST_WORKER_MESSAGE,
                    payload =
                        mapOf(
                            "type" to args.optionOrPositional("type", 0),
                            "recipient_worker_id" to args.optionOrPositional("recipient-worker-id", 1),
                            "recipient_session_id" to args.option("recipient-session-id", "recipient_session_id"),
                            "title" to args.option("title"),
                            "summary" to args.optionOrRest("summary", 2),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/worker-reply",
                title = "回复 Worker 消息",
                description = "回复某条 worker 消息。",
                usage = "/worker-reply reply_message_id approve \"可以继续\"",
                category = SessionPlatformCommandCategory.WORKER,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.POST_WORKER_MESSAGE,
                    payload =
                        mapOf(
                            "reply_to_message_id" to args.optionOrPositional("reply-to-message-id", 0),
                            "decision" to args.optionOrPositional("decision", 1),
                            "note" to args.optionOrRest("note", 2),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/ack-worker-message",
                title = "确认 Worker 消息",
                description = "确认消费一条 worker mailbox 消息。",
                usage = "/ack-worker-message message_id --note 已处理",
                category = SessionPlatformCommandCategory.WORKER,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.ACK_WORKER_MESSAGE,
                    payload =
                        mapOf(
                            "message_id" to args.optionOrPositional("message-id", 0),
                            "note" to args.optionOrRest("note", 1),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/replay",
                title = "Batch Replay",
                description = "批量跑 deterministic replay。",
                usage = "/replay session_a session_b session_c",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.BATCH_REPLAY,
                    sessionId = args.positional(0),
                    payload = mapOf("session_ids" to args.optionOrRest("session-ids", 0).replace(" ", ",")),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/regression-plan",
                title = "Regression Plan",
                description = "读取 benchmark 场景与最近 replay 的匹配计划。",
                usage = "/regression-plan --limit 4",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_REGRESSION_PLAN,
                    payload = mapOf("limit" to args.optionOrPositional("limit", 0)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/run-regression",
                title = "Run Regression",
                description = "按最近匹配 replay 执行一轮 deterministic regression。",
                usage = "/run-regression --limit 4",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.RUN_REGRESSION_PLAN,
                    payload = mapOf("limit" to args.optionOrPositional("limit", 0)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/inspect-replay",
                title = "Inspect Replay",
                description = "查看 replay 某一步的状态差异。",
                usage = "/inspect-replay --session-id session_123 --command-index 4",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.INSPECT_REPLAY,
                    sessionId = args.optionOrPositional("session-id", 0),
                    payload = mapOf("command_index" to args.optionOrPositional("command-index", 1)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/inspect-replay-state",
                title = "Inspect Replay State",
                description = "查看 replay 单步结构化状态。",
                usage = "/inspect-replay-state --session-id session_123 --command-index 4",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.INSPECT_REPLAY_STATE,
                    sessionId = args.optionOrPositional("session-id", 0),
                    payload = mapOf("command_index" to args.optionOrPositional("command-index", 1)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/inspect-replay-artifacts",
                title = "Inspect Replay Artifacts",
                description = "查看 replay 某一步的 artifact 线索。",
                usage = "/inspect-replay-artifacts --session-id session_123 --command-index 4",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.INSPECT_REPLAY_ARTIFACTS,
                    sessionId = args.optionOrPositional("session-id", 0),
                    payload = mapOf("command_index" to args.optionOrPositional("command-index", 1)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/compare-replay-steps",
                title = "Compare Replay Steps",
                description = "对比 replay 两个步骤。",
                usage = "/compare-replay-steps --session-id session_123 4 9",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.COMPARE_REPLAY_STEPS,
                    sessionId = args.optionOrPositional("session-id", 0),
                    payload =
                        mapOf(
                            "left_command_index" to args.optionOrPositional("left-command-index", 1),
                            "right_command_index" to args.optionOrPositional("right-command-index", 2),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/inspect-replay-breakpoint",
                title = "Inspect Replay Breakpoint",
                description = "联合 payload、artifact、trace、graph 做断点排障。",
                usage = "/inspect-replay-breakpoint --session-id session_123 --command-index 4",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.INSPECT_REPLAY_BREAKPOINT,
                    sessionId = args.optionOrPositional("session-id", 0),
                    payload = mapOf("command_index" to args.optionOrPositional("command-index", 1)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/artifact-retention",
                title = "Artifact 生命周期策略",
                description = "读取 artifact retention policy。",
                usage = "/artifact-retention",
                category = SessionPlatformCommandCategory.GOVERNANCE,
            ) { SessionPlatformCommand(SessionCapabilityKey.READ_ARTIFACT_RETENTION) },
            SessionPlatformCommandDefinition(
                name = "/run-artifact-retention",
                title = "执行 Artifact 清理",
                description = "执行 retention sweep。",
                usage = "/run-artifact-retention",
                category = SessionPlatformCommandCategory.GOVERNANCE,
            ) { SessionPlatformCommand(SessionCapabilityKey.RUN_ARTIFACT_RETENTION) },
            SessionPlatformCommandDefinition(
                name = "/set-artifact-retention",
                title = "更新 Artifact 策略",
                description = "更新 artifact retention policy。",
                usage = "/set-artifact-retention --max-age-days 7 --enabled true",
                category = SessionPlatformCommandCategory.GOVERNANCE,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.UPSERT_ARTIFACT_RETENTION,
                    payload =
                        mapOf(
                            "max_age_days" to args.optionOrPositional("max-age-days", 0),
                            "orphan_session_days" to args.optionOrPositional("orphan-session-days", 1),
                            "max_artifacts_per_session" to args.optionOrPositional("max-artifacts-per-session", 2),
                            "max_artifacts_per_type" to args.optionOrPositional("max-artifacts-per-type", 3),
                            "enabled" to args.optionOrPositional("enabled", 4),
                            "pinned_types" to args.optionOrPositional("pinned-types", 5),
                            "preview_line_limit" to args.optionOrPositional("preview-line-limit", 6),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/product-diagnostics",
                title = "产品诊断",
                description = "读取 product diagnostics。",
                usage = "/product-diagnostics",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
            ) { SessionPlatformCommand(SessionCapabilityKey.READ_PRODUCT_DIAGNOSTICS) },
            SessionPlatformCommandDefinition(
                name = "/command-center",
                title = "命令中心",
                description = "读取最近命令回执、运行状态和失败摘要。",
                usage = "/command-center [--session-id session_123] [--limit 8]",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_COMMAND_CENTER,
                    sessionId = args.optionOrPositional("session-id", 0),
                    payload = mapOf("limit" to args.optionOrPositional("limit", 1)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/why",
                title = "解释日志",
                description = "读取某个 session 最近几轮为什么这样做。",
                usage = "/why --session-id session_123 [--limit 8]",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_SESSION_EXPLANATION_LOG,
                    sessionId = args.optionOrPositional("session-id", 0),
                    payload = mapOf("limit" to args.optionOrPositional("limit", 1)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/notebook",
                title = "会话 Notebook",
                description = "读取某个 session 的长期运行 notebook。",
                usage = "/notebook --session-id session_123 [--limit 18]",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_SESSION_MEMORY_NOTEBOOK,
                    sessionId = args.optionOrPositional("session-id", 0),
                    payload = mapOf("limit" to args.optionOrPositional("limit", 1)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/action-lifecycle",
                title = "动作生命周期",
                description = "读取某个 session 最近动作从规划到执行的完整轨迹。",
                usage = "/action-lifecycle --session-id session_123 [--limit 8]",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_SESSION_ACTION_LIFECYCLE,
                    sessionId = args.optionOrPositional("session-id", 0),
                    payload = mapOf("limit" to args.optionOrPositional("limit", 1)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/main-loop",
                title = "主循环",
                description = "读取某个 session 当前主循环调度和系统状态。",
                usage = "/main-loop --session-id session_123",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_MAIN_LOOP,
                    sessionId = args.optionOrPositional("session-id", 0),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/loop-runtime",
                title = "Loop Runtime",
                description = "读取递归主循环同轮 drain、attachment、prefetch 与 tool catalog 状态。",
                usage = "/loop-runtime [--session-id session_123]",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_LOOP_RUNTIME,
                    sessionId = args.optionOrPositional("session-id", 0),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/loop-inbox",
                title = "Loop Inbox",
                description = "读取主循环递归调度前会消费的命令、mailbox、跟进和记忆队列。",
                usage = "/loop-inbox [--session-id session_123]",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_LOOP_INBOX,
                    sessionId = args.optionOrPositional("session-id", 0),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/memory-policy",
                title = "记忆策略",
                description = "读取 session notebook 自动维护节奏与触发原因。",
                usage = "/memory-policy --session-id session_123",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_MEMORY_POLICY,
                    sessionId = args.optionOrPositional("session-id", 0),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/memory-maintenance",
                title = "记忆维护状态",
                description = "读取后台 memory/notebook 维护器的运行状态和积压。",
                usage = "/memory-maintenance",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
                recommended = true,
            ) { SessionPlatformCommand(SessionCapabilityKey.READ_MEMORY_MAINTENANCE) },
            SessionPlatformCommandDefinition(
                name = "/memory-curator",
                title = "记忆整理器",
                description = "读取专门整理 session notebook 的 curator 状态。",
                usage = "/memory-curator [--session-id session_123]",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_MEMORY_CURATOR,
                    sessionId = args.optionOrPositional("session-id", 0),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/memory-fork",
                title = "记忆 Fork Runtime",
                description = "读取 notebook curator worker、policy 与 notebook 路径的 fork runtime 状态。",
                usage = "/memory-fork [--session-id session_123]",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_MEMORY_FORK,
                    sessionId = args.optionOrPositional("session-id", 0),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/turn-loop",
                title = "Turn Loop",
                description = "读取某个 session 当前循环的 agenda、blocker 和推荐命令。",
                usage = "/turn-loop --session-id session_123",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_SESSION_TURN_LOOP,
                    sessionId = args.optionOrPositional("session-id", 0),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/tool-ledger",
                title = "工具账本",
                description = "读取某个 session 最近的工具调用结果、耗时和权限命中。",
                usage = "/tool-ledger --session-id session_123 [--limit 8]",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_TOOL_LEDGER,
                    sessionId = args.optionOrPositional("session-id", 0),
                    payload = mapOf("limit" to args.optionOrPositional("limit", 1)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/tool-runtime",
                title = "工具运行时",
                description = "读取某个 session 的工具成功率、阻断率和权限命中。",
                usage = "/tool-runtime --session-id session_123 [--limit 12]",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_TOOL_RUNTIME,
                    sessionId = args.optionOrPositional("session-id", 0),
                    payload = mapOf("limit" to args.optionOrPositional("limit", 1)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/tool-catalog",
                title = "工具目录",
                description = "读取当前 planner / executor 可用工具目录。",
                usage = "/tool-catalog",
                category = SessionPlatformCommandCategory.GOVERNANCE,
                recommended = true,
            ) { SessionPlatformCommand(SessionCapabilityKey.READ_TOOL_CATALOG) },
            SessionPlatformCommandDefinition(
                name = "/tool-contracts",
                title = "工具契约",
                description = "读取完整 tool runtime contract，包括交互、defer、always-load 与并发属性。",
                usage = "/tool-contracts [--limit 12]",
                category = SessionPlatformCommandCategory.GOVERNANCE,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_TOOL_CONTRACTS,
                    payload = mapOf("limit" to args.optionOrPositional("limit", 0)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/follow-ups",
                title = "跟进队列",
                description = "读取主动任务、审批跟进和待处理队列。",
                usage = "/follow-ups [--limit 12]",
                category = SessionPlatformCommandCategory.PRODUCT,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_FOLLOW_UP_QUEUE,
                    payload = mapOf("limit" to args.optionOrPositional("limit", 0)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/follow-up-health",
                title = "跟进健康度",
                description = "读取主动任务收件箱的逾期、延期和审批健康度。",
                usage = "/follow-up-health [--limit 24]",
                category = SessionPlatformCommandCategory.PRODUCT,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_FOLLOW_UP_HEALTH,
                    payload = mapOf("limit" to args.optionOrPositional("limit", 0)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/task-os",
                title = "任务 OS",
                description = "读取当前活跃会话和跟进收件箱的统一状态。",
                usage = "/task-os [--limit 24]",
                category = SessionPlatformCommandCategory.PRODUCT,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_TASK_OS,
                    payload = mapOf("limit" to args.optionOrPositional("limit", 0)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/complete-follow-up",
                title = "完成跟进",
                description = "将一条主动跟进项标记为完成。",
                usage = "/complete-follow-up proactive_xxx",
                category = SessionPlatformCommandCategory.PRODUCT,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.COMPLETE_FOLLOW_UP,
                    payload = mapOf("task_id" to args.optionOrPositional("task-id", 0)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/defer-follow-up",
                title = "延后跟进",
                description = "将一条跟进项延后到稍后处理。",
                usage = "/defer-follow-up proactive_xxx --minutes 30",
                category = SessionPlatformCommandCategory.PRODUCT,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.DEFER_FOLLOW_UP,
                    payload =
                        mapOf(
                            "task_id" to args.optionOrPositional("task-id", 0),
                            "minutes" to args.optionOrPositional("minutes", 1),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/safety-center",
                title = "安全中心",
                description = "读取待确认动作、运行时授权和安全解释。",
                usage = "/safety-center [--session-id session_123]",
                category = SessionPlatformCommandCategory.GOVERNANCE,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_SAFETY_CENTER,
                    sessionId = args.optionOrPositional("session-id", 0),
                    payload =
                        mapOf(
                            "limit" to args.option("limit"),
                            "include_inactive" to args.option("include-inactive", "include_inactive"),
                            "target_package_name" to args.option("target-package-name", "target_package_name"),
                            "action_family" to args.option("action-family", "action_family"),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/permission-center",
                title = "权限中心",
                description = "读取审批、授权、规则来源和历史解释的统一视图。",
                usage = "/permission-center [--session-id session_123] [--limit 12]",
                category = SessionPlatformCommandCategory.GOVERNANCE,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_PERMISSION_CENTER,
                    sessionId = args.optionOrPositional("session-id", 0),
                    payload = mapOf("limit" to args.optionOrPositional("limit", 1)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/permission-product",
                title = "授权产品面",
                description = "读取 allow / ask / deny 分栏、规则来源和 Claude 风格授权摘要。",
                usage = "/permission-product [--session-id session_123] [--tab allow|ask|deny] [--query 微信] [--limit 12]",
                category = SessionPlatformCommandCategory.GOVERNANCE,
                recommended = true,
                argumentHints = listOf("[--session-id <session_id>]", "[--tab allow|ask|deny]", "[--query <关键词>]", "[--limit <n>]"),
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_PERMISSION_PRODUCT,
                    sessionId = args.optionOrPositional("session-id", 0),
                    payload =
                        mapOf(
                            "limit" to args.option("limit"),
                            "tab" to args.option("tab", "behavior"),
                            "query" to args.optionOrRest("query", 1),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/connected-apps",
                title = "Connected Apps",
                description = "读取 connected apps 的连接状态、确认模式与最近回执。",
                usage = "/connected-apps",
                category = SessionPlatformCommandCategory.GOVERNANCE,
                recommended = true,
            ) {
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_PRODUCT_POLICY,
                    payload = mapOf("policy_type" to "connected_apps"),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/set-connected-app",
                title = "更新 Connected App",
                description = "按 app 更新 connected/disconnected 状态和确认模式。",
                usage = "/set-connected-app --app-id wechat_assistant --connected true [--confirmation-mode final_handoff]",
                category = SessionPlatformCommandCategory.GOVERNANCE,
                argumentHints = listOf("--app-id <app_id>", "--connected true|false", "[--confirmation-mode final_handoff|ask_each_time]"),
                validate = { args ->
                    requireAll(
                        args.option("app-id", "app_id") to "更新 connected app 需要提供 `--app-id`。",
                        args.option("connected") to "更新 connected app 需要提供 `--connected true|false`。",
                    )
                },
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.UPSERT_PRODUCT_POLICY,
                    payload =
                        mapOf(
                            "policy_type" to "connected_apps",
                            "app_id" to args.option("app-id", "app_id"),
                            "connected" to args.option("connected"),
                            "confirmation_mode" to args.option("confirmation-mode", "confirmation_mode"),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/utilities",
                title = "Utilities",
                description = "读取 utilities 的启用状态、动作方式与最近回执。",
                usage = "/utilities",
                category = SessionPlatformCommandCategory.GOVERNANCE,
                recommended = true,
            ) {
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_PRODUCT_POLICY,
                    payload = mapOf("policy_type" to "utilities"),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/set-utility",
                title = "更新 Utility",
                description = "启用或禁用某个 utility 工具。",
                usage = "/set-utility --tool-name system.set_brightness --enabled true",
                category = SessionPlatformCommandCategory.GOVERNANCE,
                argumentHints = listOf("--tool-name <tool_name>", "--enabled true|false"),
                validate = { args ->
                    requireAll(
                        args.option("tool-name", "tool_name") to "更新 utility 需要提供 `--tool-name`。",
                        args.option("enabled") to "更新 utility 需要提供 `--enabled true|false`。",
                    )
                },
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.UPSERT_PRODUCT_POLICY,
                    payload =
                        mapOf(
                            "policy_type" to "utilities",
                            "tool_name" to args.option("tool-name", "tool_name"),
                            "enabled" to args.option("enabled"),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/safety-policies",
                title = "安全规则",
                description = "读取显式 allow/ask/deny 安全规则。",
                usage = "/safety-policies [--behavior ask] [--action-family confirm_send] [--target-package-name com.tencent.mm]",
                category = SessionPlatformCommandCategory.GOVERNANCE,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_SAFETY_POLICIES,
                    payload =
                        mapOf(
                            "limit" to args.option("limit"),
                            "behavior" to args.option("behavior"),
                            "action_family" to args.option("action-family", "action_family"),
                            "target_package_name" to args.option("target-package-name", "target_package_name"),
                            "tool_name" to args.option("tool-name", "tool_name"),
                            "page_state" to args.option("page-state", "page_state"),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/safety-decisions",
                title = "安全决策历史",
                description = "读取最近的安全决策、审批和授权历史。",
                usage = "/safety-decisions [--session-id session_123] [--limit 12]",
                category = SessionPlatformCommandCategory.GOVERNANCE,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_SAFETY_DECISIONS,
                    sessionId = args.optionOrPositional("session-id", 0),
                    payload = mapOf("limit" to args.optionOrPositional("limit", 1)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/set-safety-policy",
                title = "设置安全规则",
                description = "新增或覆盖一条显式安全规则。",
                usage = "/set-safety-policy --behavior ask --action-family confirm_send --target-package-name com.tencent.mm [--tool-name semantic.submit_primary_action] [--page-state CHAT] [--target-text-contains 发送] [--source-tag operator] [--surface-hint chat_send] [--explanation 每次发送前都确认]",
                category = SessionPlatformCommandCategory.GOVERNANCE,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.UPSERT_SAFETY_POLICY,
                    payload =
                        mapOf(
                            "behavior" to args.optionOrPositional("behavior", 0),
                            "action_family" to args.option("action-family", "action_family").ifBlank { args.positional(1) },
                            "target_package_name" to args.option("target-package-name", "target_package_name"),
                            "tool_name" to args.option("tool-name", "tool_name"),
                            "page_state" to args.option("page-state", "page_state"),
                            "target_text_contains" to args.option("target-text-contains", "target_text_contains"),
                            "source_tag" to args.option("source-tag", "source_tag"),
                            "surface_hint" to args.option("surface-hint", "surface_hint"),
                            "explanation" to args.option("explanation"),
                            "note" to args.optionOrRest("note", 2),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/delete-safety-policy",
                title = "删除安全规则",
                description = "删除一条显式安全规则。",
                usage = "/delete-safety-policy policy_xxx",
                category = SessionPlatformCommandCategory.GOVERNANCE,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.DELETE_SAFETY_POLICY,
                    payload = mapOf("rule_id" to args.optionOrPositional("rule-id", 0)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/revoke-safety-grant",
                title = "撤销安全授权",
                description = "撤销一条运行时安全授权。",
                usage = "/revoke-safety-grant safety_grant_xxx --note revoked_by_user",
                category = SessionPlatformCommandCategory.GOVERNANCE,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.REVOKE_RUNTIME_SAFETY_GRANT,
                    payload =
                        mapOf(
                            "grant_id" to args.optionOrPositional("grant-id", 0),
                            "note" to args.optionOrRest("note", 1),
                        ),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/grounding-health",
                title = "Grounding 健康度",
                description = "读取 GUI grounding 的失败、恢复和重试健康度。",
                usage = "/grounding-health [--session-id session_123] [--limit 12]",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_GROUNDING_HEALTH,
                    sessionId = args.optionOrPositional("session-id", 0),
                    payload = mapOf("limit" to args.optionOrPositional("limit", 1)),
                )
            },
            SessionPlatformCommandDefinition(
                name = "/operator",
                title = "Operator Console",
                description = "读取 operator console 摘要。",
                usage = "/operator --session-id session_123",
                category = SessionPlatformCommandCategory.DIAGNOSTICS,
                recommended = true,
            ) { args ->
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_OPERATOR_CONSOLE,
                    sessionId = args.optionOrPositional("session-id", 0),
                )
            },
        )

    private val byName =
        buildMap {
            commands.forEach { definition ->
                put(normalizeCommandName(definition.name), definition)
                definition.aliases.forEach { alias ->
                    put(normalizeCommandName(alias), definition)
                }
            }
        }

    fun definitions(): List<SessionPlatformCommandDefinition> = commands

    fun find(
        name: String,
    ): SessionPlatformCommandDefinition? = byName[normalizeCommandName(name)]

    fun parse(
        parsed: SessionPlatformParsedCommand,
    ): SessionPlatformCommand? = resolve(parsed).command

    fun resolve(
        parsed: SessionPlatformParsedCommand,
    ): SessionPlatformCommandResolution {
        val definition = find(parsed.name)
            ?: return SessionPlatformCommandResolution(
                summary = "未识别命令：${parsed.name}",
                lines =
                    buildList {
                        add("可以先试试这些命令：")
                        addAll(suggest(parsed.name, limit = 6).ifEmpty { catalogLines(limit = 6) })
                    },
            )
        val error = definition.validate?.invoke(parsed.args)
        if (error != null) {
            return SessionPlatformCommandResolution(
                summary = error,
                lines = helpLines(definition.name),
            )
        }
        return SessionPlatformCommandResolution(
            command = definition.parse(parsed.args),
            summary = "命令已解析：${definition.name}",
            lines = helpLines(definition.name),
        )
    }

    fun catalogLines(
        category: SessionPlatformCommandCategory? = null,
        limit: Int = Int.MAX_VALUE,
    ): List<String> =
        commands
            .asSequence()
            .filter { category == null || it.category == category }
            .take(limit)
            .map { definition ->
                val marker = if (definition.recommended) "recommended" else definition.category.name.lowercase()
                val aliases = definition.aliases.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = " | aliases=") ?: ""
                "$marker | ${definition.name} | ${definition.title} | ${definition.description}$aliases"
            }.toList()

    fun recommendedUsages(
        limit: Int = 8,
    ): List<String> =
        commands
            .asSequence()
            .filter { it.recommended }
            .map { it.usage }
            .take(limit)
            .toList()

    fun helpLines(
        commandName: String,
    ): List<String> {
        val definition = find(commandName)
            ?: return buildList {
                add("未找到命令：$commandName")
                addAll(suggest(commandName, limit = 6))
            }
        return buildList {
            add("${definition.name} | ${definition.title}")
            add("category=${definition.category.name.lowercase()} | recommended=${definition.recommended}")
            if (definition.aliases.isNotEmpty()) {
                add("aliases=${definition.aliases.joinToString(", ")}")
            }
            add("description=${definition.description}")
            add("usage=${definition.usage}")
            if (definition.argumentHints.isNotEmpty()) {
                add("args=${definition.argumentHints.joinToString(" | ")}")
            }
            definition.examples.distinct().take(4).forEach { example ->
                add("example | $example")
            }
        }
    }

    fun suggest(
        prefix: String,
        limit: Int = 8,
    ): List<String> {
        val needle = normalizeCommandName(prefix)
        if (needle.isBlank()) {
            return recommendedUsages(limit)
        }
        return commands
            .asSequence()
            .sortedWith(
                compareByDescending<SessionPlatformCommandDefinition> { matchesPrefix(it, needle) }
                    .thenByDescending { matchesAliasPrefix(it, needle) }
                    .thenByDescending { matchesLoose(it, needle) }
                    .thenBy { it.name.length },
            )
            .filter { matchesLoose(it, needle) }
            .map { definition ->
                "suggest | ${definition.name} | ${definition.title} | ${definition.usage}"
            }
            .take(limit)
            .toList()
    }

    private fun matchesPrefix(
        definition: SessionPlatformCommandDefinition,
        needle: String,
    ): Boolean = normalizeCommandName(definition.name).startsWith(needle)

    private fun matchesAliasPrefix(
        definition: SessionPlatformCommandDefinition,
        needle: String,
    ): Boolean = definition.aliases.any { normalizeCommandName(it).startsWith(needle) }

    private fun matchesLoose(
        definition: SessionPlatformCommandDefinition,
        needle: String,
    ): Boolean =
        matchesPrefix(definition, needle) ||
            matchesAliasPrefix(definition, needle) ||
            definition.title.lowercase().contains(needle) ||
            definition.description.lowercase().contains(needle)

    private fun normalizeCommandName(name: String): String = name.trim().lowercase().removePrefix("/")

    private fun requireNonBlank(
        value: String,
        message: String,
    ): String? = if (value.isBlank()) message else null

    private fun requireAll(vararg pairs: Pair<String, String>): String? =
        pairs.firstOrNull { it.first.isBlank() }?.second

    private fun requireAny(vararg values: String): Boolean =
        values.any { it.isNotBlank() }

    private fun Boolean.ifBlankOrNull(message: String): String? =
        if (this) {
            null
        } else {
            message
        }
}
