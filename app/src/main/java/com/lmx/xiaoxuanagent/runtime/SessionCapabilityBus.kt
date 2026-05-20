package com.lmx.xiaoxuanagent.runtime

enum class SessionCapabilityKey {
    REFRESH_ASSISTANT_OS,
    START_SESSION,
    RESUME_SESSION,
    STOP_SESSION,
    APPROVE_SAFETY,
    REJECT_SAFETY,
    FORK_WORKER,
    SEARCH_ARTIFACTS,
    RECALL_MEMORY,
    COMPARE_SESSIONS,
    BATCH_REPLAY,
    INSPECT_REPLAY,
    INSPECT_REPLAY_STATE,
    INSPECT_REPLAY_ARTIFACTS,
    COMPARE_REPLAY_STEPS,
    INSPECT_REPLAY_BREAKPOINT,
    READ_CAPABILITY_APPROVALS,
    APPROVE_CAPABILITY_REQUEST,
    REJECT_CAPABILITY_REQUEST,
    READ_CAPABILITY_GRANTS,
    REVOKE_CAPABILITY_GRANT,
    READ_CAPABILITY_POLICIES,
    UPSERT_CAPABILITY_POLICY,
    DELETE_CAPABILITY_POLICY,
    READ_AGENT_SIDECHAINS,
    READ_SIGNAL_PROVIDERS,
    READ_WORKER_MAILBOX,
    POST_WORKER_MESSAGE,
    ACK_WORKER_MESSAGE,
    READ_MEMORY_QUEUE,
    RETRY_MEMORY_TASK,
    READ_MEMORY_WORKSPACE,
    READ_MEMORY_GOVERNANCE,
    UPSERT_MEMORY_ENTRY,
    DELETE_MEMORY_ENTRY,
    READ_SESSION_HISTORY,
    SEARCH_SESSION_HISTORY,
    READ_SESSION_COMPENSATIONS,
    RUN_SESSION_COMPENSATION,
    READ_COMMAND_CATALOG,
    READ_PRODUCT_SHELL,
    READ_PRODUCT_DIAGNOSTICS,
    READ_CURRENT_SCREEN,
    READ_REGRESSION_PLAN,
    RUN_REGRESSION_PLAN,
    READ_PRODUCT_POLICY,
    UPSERT_PRODUCT_POLICY,
    REFRESH_PRODUCT_SHELL,
    ACK_PRODUCT_SHELL_TIP,
    UPDATE_PRODUCT_ONBOARDING,
    READ_COMMAND_CENTER,
    READ_SESSION_EXPLANATION_LOG,
    READ_SESSION_MEMORY_NOTEBOOK,
    READ_SESSION_ACTION_LIFECYCLE,
    READ_MAIN_LOOP,
    READ_LOOP_RUNTIME,
    READ_LOOP_INBOX,
    READ_MEMORY_POLICY,
    READ_MEMORY_MAINTENANCE,
    READ_MEMORY_CURATOR,
    READ_MEMORY_FORK,
    READ_SESSION_TURN_LOOP,
    READ_TOOL_LEDGER,
    READ_TOOL_RUNTIME,
    READ_TOOL_CATALOG,
    READ_TOOL_CONTRACTS,
    READ_FOLLOW_UP_QUEUE,
    READ_FOLLOW_UP_HEALTH,
    READ_TASK_OS,
    COMPLETE_FOLLOW_UP,
    DEFER_FOLLOW_UP,
    READ_SAFETY_CENTER,
    READ_PERMISSION_CENTER,
    READ_PERMISSION_PRODUCT,
    READ_SAFETY_POLICIES,
    READ_SAFETY_DECISIONS,
    UPSERT_SAFETY_POLICY,
    DELETE_SAFETY_POLICY,
    REVOKE_RUNTIME_SAFETY_GRANT,
    READ_GROUNDING_HEALTH,
    READ_ARTIFACT_RETENTION,
    RUN_ARTIFACT_RETENTION,
    UPSERT_ARTIFACT_RETENTION,
    READ_PROVIDER_POLICY,
    UPSERT_PROVIDER_POLICY,
    READ_PROVIDER_REGISTRY,
    UPSERT_PROVIDER_REGISTRY,
    READ_OPERATOR_CONSOLE,
}

data class SessionCapabilityDescriptor(
    val key: SessionCapabilityKey,
    val title: String,
    val description: String,
)

data class SessionCapabilityRequest(
    val id: String = "cap_${System.currentTimeMillis()}",
    val key: SessionCapabilityKey,
    val sessionId: String = "",
    val task: String = "",
    val query: String = "",
    val entrySource: String = "capability_bus",
    val userCorrection: String = "",
    val payload: Map<String, String> = emptyMap(),
)

data class SessionCapabilityResult(
    val success: Boolean,
    val summary: String,
    val sessionId: String = "",
    val workerId: String = "",
    val payloadLines: List<String> = emptyList(),
)

object SessionCapabilityBus {
    private val descriptors =
        listOf(
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.REFRESH_ASSISTANT_OS,
                title = "Refresh Assistant OS",
                description = "刷新 assistant OS 的 trigger / transport / projection。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.START_SESSION,
                title = "Start Session",
                description = "启动一个新的 runtime session。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.RESUME_SESSION,
                title = "Resume Session",
                description = "恢复挂起或等待中的 session。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.STOP_SESSION,
                title = "Stop Session",
                description = "停止当前或指定 session。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.APPROVE_SAFETY,
                title = "Approve Safety",
                description = "批准待处理的高风险动作。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.REJECT_SAFETY,
                title = "Reject Safety",
                description = "拒绝待处理的高风险动作。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.FORK_WORKER,
                title = "Fork Worker",
                description = "把子任务排成 worker 队列，交给 assistant OS 后台执行。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.SEARCH_ARTIFACTS,
                title = "Search Artifacts",
                description = "按 query 检索 artifact 索引。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.RECALL_MEMORY,
                title = "Recall Memory",
                description = "通过 recall index 检索长期记忆。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.COMPARE_SESSIONS,
                title = "Compare Sessions",
                description = "对比两个 session 的 route/result/platform 差异。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.BATCH_REPLAY,
                title = "Batch Replay",
                description = "批量跑 deterministic replay 校验。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.INSPECT_REPLAY,
                title = "Inspect Replay",
                description = "按命令索引检查 replay 前后状态与差异。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.INSPECT_REPLAY_STATE,
                title = "Inspect Replay State",
                description = "读取 replay 单步前后结构化状态。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.INSPECT_REPLAY_ARTIFACTS,
                title = "Inspect Replay Artifacts",
                description = "查看某条 replay 命令对应的 artifact 线索。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.COMPARE_REPLAY_STEPS,
                title = "Compare Replay Steps",
                description = "对比同一 session 的两个 replay 步骤状态差异。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.INSPECT_REPLAY_BREAKPOINT,
                title = "Inspect Replay Breakpoint",
                description = "联合 command payload、artifact、trace、graph、sidechain 做断点排障。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_CAPABILITY_APPROVALS,
                title = "Read Capability Approvals",
                description = "读取平台待审批 capability 请求。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.APPROVE_CAPABILITY_REQUEST,
                title = "Approve Capability Request",
                description = "批准一条待审批 capability 请求并执行。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.REJECT_CAPABILITY_REQUEST,
                title = "Reject Capability Request",
                description = "拒绝一条待审批 capability 请求。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_CAPABILITY_GRANTS,
                title = "Read Capability Grants",
                description = "读取当前生效或历史 capability 授权范围。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.REVOKE_CAPABILITY_GRANT,
                title = "Revoke Capability Grant",
                description = "撤销一条 capability 授权。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_CAPABILITY_POLICIES,
                title = "Read Capability Policies",
                description = "读取本地 capability policy 规则与诊断结果。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.UPSERT_CAPABILITY_POLICY,
                title = "Upsert Capability Policy",
                description = "新增或替换一条 capability policy 规则。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.DELETE_CAPABILITY_POLICY,
                title = "Delete Capability Policy",
                description = "删除一条 capability policy 规则。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_AGENT_SIDECHAINS,
                title = "Read Agent Sidechains",
                description = "读取 worker/subagent 的 sidechain 事件与快照。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_SIGNAL_PROVIDERS,
                title = "Read Signal Providers",
                description = "读取外部 signal provider 的健康状态。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_WORKER_MAILBOX,
                title = "Read Worker Mailbox",
                description = "读取 worker/session graph 的 mailbox 协作消息。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.POST_WORKER_MESSAGE,
                title = "Post Worker Message",
                description = "向 worker mailbox 投递 result / permission / control 消息。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.ACK_WORKER_MESSAGE,
                title = "Ack Worker Message",
                description = "确认消费一条 worker mailbox 消息。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_MEMORY_QUEUE,
                title = "Read Memory Queue",
                description = "读取后台长期记忆抽取队列。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.RETRY_MEMORY_TASK,
                title = "Retry Memory Task",
                description = "重试一条失败或延迟的后台记忆任务。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_MEMORY_WORKSPACE,
                title = "Read Memory Workspace",
                description = "读取 memory workspace 的 manifest、digest 与老化分布。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_MEMORY_GOVERNANCE,
                title = "Read Memory Governance",
                description = "读取长期记忆的可编辑条目、治理摘要与审计轨迹。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.UPSERT_MEMORY_ENTRY,
                title = "Upsert Memory Entry",
                description = "新增或更新一条长期记忆治理条目。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.DELETE_MEMORY_ENTRY,
                title = "Delete Memory Entry",
                description = "删除一条长期记忆治理条目。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_SESSION_HISTORY,
                title = "Read Session History",
                description = "读取本地 session history 资产摘要。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.SEARCH_SESSION_HISTORY,
                title = "Search Session History",
                description = "按 query 检索本地 session history。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_SESSION_COMPENSATIONS,
                title = "Read Session Compensations",
                description = "读取 session 的回滚/补偿计划。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.RUN_SESSION_COMPENSATION,
                title = "Run Session Compensation",
                description = "执行某个 session turn 的补偿动作。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_COMMAND_CATALOG,
                title = "Read Command Catalog",
                description = "读取命令目录、帮助和推荐建议。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_PRODUCT_SHELL,
                title = "Read Product Shell",
                description = "读取 onboarding / tips / swarm 的本地产品控制层快照。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_PRODUCT_DIAGNOSTICS,
                title = "Read Product Diagnostics",
                description = "读取 product shell diagnostics 与 analytics 摘要。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_CURRENT_SCREEN,
                title = "Read Current Screen",
                description = "读取当前前台屏幕的无障碍树、视觉提示和可操作候选。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_REGRESSION_PLAN,
                title = "Read Regression Plan",
                description = "读取 benchmark 场景和最近 replay 匹配出来的回归计划。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.RUN_REGRESSION_PLAN,
                title = "Run Regression Plan",
                description = "按最近匹配 replay 执行一轮 deterministic regression。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_PRODUCT_POLICY,
                title = "Read Product Policy",
                description = "读取 routine / digest / quiet hours / interrupt 的产品策略。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.UPSERT_PRODUCT_POLICY,
                title = "Upsert Product Policy",
                description = "更新 routine / digest / quiet hours / interrupt 的产品策略。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.REFRESH_PRODUCT_SHELL,
                title = "Refresh Product Shell",
                description = "强制刷新 product shell 快照与建议。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.ACK_PRODUCT_SHELL_TIP,
                title = "Acknowledge Product Tip",
                description = "确认、关闭或重新激活一条 product shell tip。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.UPDATE_PRODUCT_ONBOARDING,
                title = "Update Product Onboarding",
                description = "手动完成、跳过或重置一条 onboarding step。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_COMMAND_CENTER,
                title = "Read Command Center",
                description = "读取最近命令回执、运行状态和失败摘要。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_SESSION_EXPLANATION_LOG,
                title = "Read Session Explanation Log",
                description = "读取 session 每轮为什么这样做的解释日志。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_SESSION_MEMORY_NOTEBOOK,
                title = "Read Session Memory Notebook",
                description = "读取 session 的长期运行 notebook 与摘要。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_SESSION_ACTION_LIFECYCLE,
                title = "Read Session Action Lifecycle",
                description = "读取动作从规划、安全到执行/恢复的生命周期。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_MAIN_LOOP,
                title = "Read Main Loop",
                description = "读取主循环调度、队列、记忆与工具运行概况。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_LOOP_RUNTIME,
                title = "Read Loop Runtime",
                description = "读取递归调度器的 queue drain、prefetch、task signal 与 tool refresh 状态。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_LOOP_INBOX,
                title = "Read Loop Inbox",
                description = "读取主循环中途会消费的命令、mailbox、follow-up 与 memory inbox。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_MEMORY_POLICY,
                title = "Read Memory Policy",
                description = "读取 session notebook 自动维护节奏与阈值状态。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_MEMORY_MAINTENANCE,
                title = "Read Memory Maintenance",
                description = "读取后台 session memory/notebook 维护状态与积压情况。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_MEMORY_CURATOR,
                title = "Read Memory Curator",
                description = "读取专门整理 session notebook 的 curator 状态。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_MEMORY_FORK,
                title = "Read Memory Fork",
                description = "读取 detached memory curator fork 的 worker、policy 与 notebook 状态。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_SESSION_TURN_LOOP,
                title = "Read Session Turn Loop",
                description = "读取当前 turn agenda、blocker 和推荐命令。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_TOOL_LEDGER,
                title = "Read Tool Ledger",
                description = "读取最近的工具调用协议记录、结果和耗时。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_TOOL_RUNTIME,
                title = "Read Tool Runtime",
                description = "读取工具执行成功率、阻断率和权限命中概况。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_TOOL_CATALOG,
                title = "Read Tool Catalog",
                description = "读取当前 planner / executor 可用工具目录与风险说明。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_TOOL_CONTRACTS,
                title = "Read Tool Contracts",
                description = "读取统一 tool runtime contract 的并发、权限、交互与 defer 语义。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_FOLLOW_UP_QUEUE,
                title = "Read Follow Up Queue",
                description = "读取主动任务、审批跟进和今日待处理队列。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_FOLLOW_UP_HEALTH,
                title = "Read Follow Up Health",
                description = "读取主动任务收件箱的优先级、逾期和延期健康度。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_TASK_OS,
                title = "Read Task OS",
                description = "读取当前任务 OS 的活跃会话、逾期任务与阻塞情况。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.COMPLETE_FOLLOW_UP,
                title = "Complete Follow Up",
                description = "将一条跟进项标记为完成。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.DEFER_FOLLOW_UP,
                title = "Defer Follow Up",
                description = "将一条跟进项延后到稍后处理。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_SAFETY_CENTER,
                title = "Read Safety Center",
                description = "读取当前安全确认、运行时授权和风险解释。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_PERMISSION_CENTER,
                title = "Read Permission Center",
                description = "读取审批、显式规则、授权与安全历史的统一视图。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_PERMISSION_PRODUCT,
                title = "Read Permission Product",
                description = "读取 allow/ask/deny 规则分栏、来源和产品化解释。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_SAFETY_POLICIES,
                title = "Read Safety Policies",
                description = "读取显式 allow/ask/deny 安全规则。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_SAFETY_DECISIONS,
                title = "Read Safety Decisions",
                description = "读取最近的安全决策、审批和授权历史。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.UPSERT_SAFETY_POLICY,
                title = "Upsert Safety Policy",
                description = "新增或覆盖一条显式安全规则。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.DELETE_SAFETY_POLICY,
                title = "Delete Safety Policy",
                description = "删除一条显式安全规则。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.REVOKE_RUNTIME_SAFETY_GRANT,
                title = "Revoke Runtime Safety Grant",
                description = "撤销一条运行时安全授权。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_GROUNDING_HEALTH,
                title = "Read Grounding Health",
                description = "读取手机 GUI grounding 的失败、恢复与重试健康度。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_ARTIFACT_RETENTION,
                title = "Read Artifact Retention",
                description = "读取 artifact retention policy 与 dry-run 结果。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.RUN_ARTIFACT_RETENTION,
                title = "Run Artifact Retention",
                description = "执行 artifact retention sweep。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.UPSERT_ARTIFACT_RETENTION,
                title = "Upsert Artifact Retention",
                description = "更新 artifact retention policy。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_PROVIDER_POLICY,
                title = "Read Provider Policy",
                description = "读取 planner provider 的路由策略与失败统计。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.UPSERT_PROVIDER_POLICY,
                title = "Upsert Provider Policy",
                description = "更新 planner provider 的路由策略、fallback gap 与 override。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_PROVIDER_REGISTRY,
                title = "Read Provider Registry",
                description = "读取 planner provider registry、backend 与优先级配置。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.UPSERT_PROVIDER_REGISTRY,
                title = "Upsert Provider Registry",
                description = "启用、禁用或调整一个 planner provider 的 registry 配置。",
            ),
            SessionCapabilityDescriptor(
                key = SessionCapabilityKey.READ_OPERATOR_CONSOLE,
                title = "Read Operator Console",
                description = "读取 mailbox / memory / retention / provider 的聚合控制面摘要。",
            ),
        )

    fun listCapabilities(): List<SessionCapabilityDescriptor> = descriptors

    suspend fun dispatch(
        request: SessionCapabilityRequest,
    ): SessionCapabilityResult {
        PlatformTraceStore.record(
            category = "capability_dispatch",
            sessionId = request.sessionId,
            summary = request.key.name.lowercase(),
            metadata =
                buildMap {
                    put("entry_source", request.entrySource)
                    request.task.takeIf { it.isNotBlank() }?.let { put("task", it.take(120)) }
                    request.query.takeIf { it.isNotBlank() }?.let { put("query", it.take(120)) }
                },
        )
        val policy = SessionCapabilityPolicyStore.resolve(request)
        if (policy.decision == SessionCapabilityPolicyDecision.DENY) {
            return SessionCapabilityResult(
                success = false,
                summary = "能力调用已被策略阻止：${policy.reason}",
                sessionId = request.sessionId,
            )
        }
        if (policy.decision == SessionCapabilityPolicyDecision.REVIEW) {
            val approval = PlatformCapabilityApprovalStore.enqueue(request, policy)
            return SessionCapabilityResult(
                success = false,
                summary = "能力调用已进入待审批队列：${approval.approvalId}",
                sessionId = request.sessionId,
                payloadLines = listOf(policy.reason, approval.summary, approval.explanation),
            )
        }
        return dispatchUnchecked(request)
    }

    private suspend fun dispatchUnchecked(
        request: SessionCapabilityRequest,
    ): SessionCapabilityResult =
        when (request.key) {
            SessionCapabilityKey.REFRESH_ASSISTANT_OS,
            SessionCapabilityKey.START_SESSION,
            SessionCapabilityKey.RESUME_SESSION,
            SessionCapabilityKey.STOP_SESSION,
            SessionCapabilityKey.APPROVE_SAFETY,
            SessionCapabilityKey.REJECT_SAFETY,
            SessionCapabilityKey.FORK_WORKER,
            -> dispatchRuntimeCapability(request)

            SessionCapabilityKey.SEARCH_ARTIFACTS,
            SessionCapabilityKey.RECALL_MEMORY,
            SessionCapabilityKey.COMPARE_SESSIONS,
            SessionCapabilityKey.BATCH_REPLAY,
            SessionCapabilityKey.INSPECT_REPLAY,
            SessionCapabilityKey.INSPECT_REPLAY_STATE,
            SessionCapabilityKey.INSPECT_REPLAY_ARTIFACTS,
            SessionCapabilityKey.COMPARE_REPLAY_STEPS,
            SessionCapabilityKey.INSPECT_REPLAY_BREAKPOINT,
            -> dispatchInspectorCapability(request)

            SessionCapabilityKey.READ_CAPABILITY_APPROVALS,
            SessionCapabilityKey.APPROVE_CAPABILITY_REQUEST,
            SessionCapabilityKey.REJECT_CAPABILITY_REQUEST,
            SessionCapabilityKey.READ_CAPABILITY_GRANTS,
            SessionCapabilityKey.REVOKE_CAPABILITY_GRANT,
            SessionCapabilityKey.READ_CAPABILITY_POLICIES,
            SessionCapabilityKey.UPSERT_CAPABILITY_POLICY,
            SessionCapabilityKey.DELETE_CAPABILITY_POLICY,
            SessionCapabilityKey.READ_PROVIDER_POLICY,
            SessionCapabilityKey.UPSERT_PROVIDER_POLICY,
            SessionCapabilityKey.READ_PROVIDER_REGISTRY,
            SessionCapabilityKey.UPSERT_PROVIDER_REGISTRY,
            ->
                dispatchGovernanceCapability(request) { approvedRequest ->
                    dispatchUnchecked(approvedRequest)
                }

            SessionCapabilityKey.READ_AGENT_SIDECHAINS,
            SessionCapabilityKey.READ_SIGNAL_PROVIDERS,
            SessionCapabilityKey.READ_WORKER_MAILBOX,
            SessionCapabilityKey.POST_WORKER_MESSAGE,
            SessionCapabilityKey.ACK_WORKER_MESSAGE,
            SessionCapabilityKey.READ_MEMORY_QUEUE,
            SessionCapabilityKey.RETRY_MEMORY_TASK,
            SessionCapabilityKey.READ_MEMORY_WORKSPACE,
            SessionCapabilityKey.READ_MEMORY_GOVERNANCE,
            SessionCapabilityKey.UPSERT_MEMORY_ENTRY,
            SessionCapabilityKey.DELETE_MEMORY_ENTRY,
            SessionCapabilityKey.READ_SESSION_HISTORY,
            SessionCapabilityKey.SEARCH_SESSION_HISTORY,
            SessionCapabilityKey.READ_SESSION_COMPENSATIONS,
            SessionCapabilityKey.RUN_SESSION_COMPENSATION,
            SessionCapabilityKey.READ_COMMAND_CATALOG,
            SessionCapabilityKey.READ_PRODUCT_SHELL,
            SessionCapabilityKey.READ_PRODUCT_DIAGNOSTICS,
            SessionCapabilityKey.READ_CURRENT_SCREEN,
            SessionCapabilityKey.READ_REGRESSION_PLAN,
            SessionCapabilityKey.RUN_REGRESSION_PLAN,
            SessionCapabilityKey.READ_PRODUCT_POLICY,
            SessionCapabilityKey.UPSERT_PRODUCT_POLICY,
            SessionCapabilityKey.REFRESH_PRODUCT_SHELL,
            SessionCapabilityKey.ACK_PRODUCT_SHELL_TIP,
            SessionCapabilityKey.UPDATE_PRODUCT_ONBOARDING,
            SessionCapabilityKey.READ_COMMAND_CENTER,
            SessionCapabilityKey.READ_SESSION_EXPLANATION_LOG,
            SessionCapabilityKey.READ_SESSION_MEMORY_NOTEBOOK,
            SessionCapabilityKey.READ_SESSION_ACTION_LIFECYCLE,
            SessionCapabilityKey.READ_MAIN_LOOP,
            SessionCapabilityKey.READ_LOOP_RUNTIME,
            SessionCapabilityKey.READ_LOOP_INBOX,
            SessionCapabilityKey.READ_MEMORY_POLICY,
            SessionCapabilityKey.READ_MEMORY_MAINTENANCE,
            SessionCapabilityKey.READ_MEMORY_CURATOR,
            SessionCapabilityKey.READ_MEMORY_FORK,
            SessionCapabilityKey.READ_SESSION_TURN_LOOP,
            SessionCapabilityKey.READ_TOOL_LEDGER,
            SessionCapabilityKey.READ_TOOL_RUNTIME,
            SessionCapabilityKey.READ_TOOL_CATALOG,
            SessionCapabilityKey.READ_TOOL_CONTRACTS,
            SessionCapabilityKey.READ_FOLLOW_UP_QUEUE,
            SessionCapabilityKey.READ_FOLLOW_UP_HEALTH,
            SessionCapabilityKey.READ_TASK_OS,
            SessionCapabilityKey.COMPLETE_FOLLOW_UP,
            SessionCapabilityKey.DEFER_FOLLOW_UP,
            SessionCapabilityKey.READ_SAFETY_CENTER,
            SessionCapabilityKey.READ_PERMISSION_CENTER,
            SessionCapabilityKey.READ_PERMISSION_PRODUCT,
            SessionCapabilityKey.READ_SAFETY_POLICIES,
            SessionCapabilityKey.READ_SAFETY_DECISIONS,
            SessionCapabilityKey.UPSERT_SAFETY_POLICY,
            SessionCapabilityKey.DELETE_SAFETY_POLICY,
            SessionCapabilityKey.REVOKE_RUNTIME_SAFETY_GRANT,
            SessionCapabilityKey.READ_GROUNDING_HEALTH,
            SessionCapabilityKey.READ_ARTIFACT_RETENTION,
            SessionCapabilityKey.RUN_ARTIFACT_RETENTION,
            SessionCapabilityKey.UPSERT_ARTIFACT_RETENTION,
            SessionCapabilityKey.READ_OPERATOR_CONSOLE,
            -> dispatchPlatformControlCapability(request)
        }
}
