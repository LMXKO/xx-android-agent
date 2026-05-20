package com.lmx.xiaoxuanagent.runtime

internal object SessionPlatformNaturalIntentInterpreter {
    private val sessionIdRegex = Regex("""session_[A-Za-z0-9_-]+""")
    private val limitRegex = Regex("""(?:最近|查看|显示|列出|limit)\s*(\d{1,2})""")
    private val searchLeadRegex = Regex("""^(?:帮我|请|麻烦你|想要|我要|我想|查下|查一下|搜索|查找|看看|看下)\s*""")

    fun resolve(
        raw: String,
    ): SessionPlatformCommandResolution? {
        val input = raw.trim()
        if (input.isBlank() || input.startsWith("/")) return null
        val normalized = normalize(input)
        val sessionId = sessionIdRegex.find(input)?.value.orEmpty()

        resolveHelp(normalized)?.let { return it }
        resolveSessionControl(input, normalized, sessionId)?.let { return it }
        resolveAssistantShell(input, normalized, sessionId)?.let { return it }
        resolveSearchIntent(input, normalized, sessionId)?.let { return it }

        return SessionPlatformCommandResolution(
            command = SessionPlatformCommand(capability = SessionCapabilityKey.START_SESSION, task = input),
            summary = "已按自然语言意图解析为启动任务。",
            lines =
                buildList {
                    add("intent=start_session")
                    add("task=$input")
                    addAll(SessionPlatformCommandRegistry.helpLines("/start").take(3))
                },
        )
    }

    private fun resolveHelp(
        normalized: String,
    ): SessionPlatformCommandResolution? {
        if (!normalized.containsAny("帮助", "help", "能做什么", "有哪些命令", "命令列表")) return null
        val category =
            when {
                normalized.containsAny("记忆", "memory") -> "memory"
                normalized.containsAny("治理", "governance", "审批", "安全") -> "governance"
                normalized.containsAny("诊断", "viewer", "回放", "artifact") -> "diagnostics"
                normalized.containsAny("产品", "today", "inbox", "控制台") -> "product"
                normalized.containsAny("worker", "协同", "swarm") -> "worker"
                else -> ""
            }
        return SessionPlatformCommandResolution(
            command =
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_COMMAND_CATALOG,
                    payload = mapOf("category" to category),
                ),
            summary = "已按自然语言意图解析为命令帮助。",
            lines =
                buildList {
                    add("intent=help")
                    if (category.isNotBlank()) add("category=$category")
                    addAll(SessionPlatformCommandRegistry.recommendedUsages(limit = 6))
                },
        )
    }

    private fun resolveAssistantShell(
        input: String,
        normalized: String,
        sessionId: String,
    ): SessionPlatformCommandResolution? {
        if (normalized.containsAny("刷新助手", "刷新小轩", "同步助手", "assistant sync", "刷新控制台", "同步控制台")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.REFRESH_ASSISTANT_OS),
                summary = "已按自然语言意图解析为刷新 Assistant OS。",
                lines = listOf("intent=refresh_assistant_os") + SessionPlatformCommandRegistry.helpLines("/assistant-sync").take(2),
            )
        }
        if (normalized.containsAny("命令中心", "命令回执", "命令状态", "控制平面")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_COMMAND_CENTER),
                summary = "已按自然语言意图解析为读取命令中心。",
                lines = listOf("intent=read_command_center"),
            )
        }
        if (normalized.containsAny("当前屏幕", "当前页面", "看屏幕", "读屏", "屏幕摘要", "页面摘要", "结构化屏幕")) {
            val preferredPackage = extractPackageName(input)
            val limit = limitRegex.find(normalized)?.groupValues?.getOrNull(1).orEmpty()
            return SessionPlatformCommandResolution(
                command =
                    SessionPlatformCommand(
                        capability = SessionCapabilityKey.READ_CURRENT_SCREEN,
                        payload =
                            buildMap {
                                if (preferredPackage.isNotBlank()) {
                                    put("preferred_package", preferredPackage)
                                }
                                if (limit.isNotBlank()) {
                                    put("limit", limit)
                                }
                            },
                    ),
                summary = "已按自然语言意图解析为读取当前屏幕。",
                lines =
                    buildList {
                        add("intent=read_current_screen")
                        if (preferredPackage.isNotBlank()) add("preferred_package=$preferredPackage")
                        if (limit.isNotBlank()) add("limit=$limit")
                    },
            )
        }
        if (normalized.containsAny("为什么这么做", "为什么这样做", "解释日志", "why log", "为什么卡住")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_SESSION_EXPLANATION_LOG, sessionId = sessionId),
                summary = "已按自然语言意图解析为读取 explanation 日志。",
                lines = listOf("intent=read_session_explanation_log"),
            )
        }
        if (normalized.containsAny("notebook", "会话笔记", "运行笔记", "session memory")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_SESSION_MEMORY_NOTEBOOK, sessionId = sessionId),
                summary = "已按自然语言意图解析为读取 session notebook。",
                lines = listOf("intent=read_session_memory_notebook"),
            )
        }
        if (normalized.containsAny("主循环", "loop 状态", "main loop", "调度状态")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_MAIN_LOOP, sessionId = sessionId),
                summary = "已按自然语言意图解析为读取主循环状态。",
                lines = listOf("intent=read_main_loop"),
            )
        }
        if (normalized.containsAny("loop runtime", "递归调度器", "同轮 drain", "主循环 runtime", "prefetch 队列")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_LOOP_RUNTIME, sessionId = sessionId),
                summary = "已按自然语言意图解析为读取 loop runtime。",
                lines = listOf("intent=read_loop_runtime"),
            )
        }
        if (normalized.containsAny("loop inbox", "循环队列", "中途命令", "当前 inbox", "递归队列")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_LOOP_INBOX, sessionId = sessionId),
                summary = "已按自然语言意图解析为读取 loop inbox。",
                lines = listOf("intent=read_loop_inbox"),
            )
        }
        if (normalized.containsAny("动作生命周期", "action lifecycle", "最后一步发生了什么", "这一步怎么被拦的")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_SESSION_ACTION_LIFECYCLE, sessionId = sessionId),
                summary = "已按自然语言意图解析为读取动作生命周期。",
                lines = listOf("intent=read_session_action_lifecycle"),
            )
        }
        if (normalized.containsAny("记忆策略", "memory policy", "notebook 为什么更新", "session memory 节奏")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_MEMORY_POLICY, sessionId = sessionId),
                summary = "已按自然语言意图解析为读取 session memory 策略。",
                lines = listOf("intent=read_memory_policy"),
            )
        }
        if (normalized.containsAny("记忆维护", "后台记忆", "memory maintenance", "notebook 维护", "记忆积压")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_MEMORY_MAINTENANCE),
                summary = "已按自然语言意图解析为读取后台记忆维护状态。",
                lines = listOf("intent=read_memory_maintenance"),
            )
        }
        if (normalized.containsAny("记忆整理器", "memory curator", "notebook curator", "后台整理 notebook")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_MEMORY_CURATOR, sessionId = sessionId),
                summary = "已按自然语言意图解析为读取 memory curator。",
                lines = listOf("intent=read_memory_curator"),
            )
        }
        if (normalized.containsAny("memory fork", "fork 记忆", "后台 fork", "curator worker", "谁在整理 notebook")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_MEMORY_FORK, sessionId = sessionId),
                summary = "已按自然语言意图解析为读取 memory fork runtime。",
                lines = listOf("intent=read_memory_fork"),
            )
        }
        if (normalized.containsAny("turn loop", "循环状态", "当前循环", "agenda", "为什么还没继续")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_SESSION_TURN_LOOP, sessionId = sessionId),
                summary = "已按自然语言意图解析为读取 turn loop。",
                lines = listOf("intent=read_session_turn_loop"),
            )
        }
        if (normalized.containsAny("工具账本", "工具调用", "tool ledger", "最近用了什么工具")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_TOOL_LEDGER, sessionId = sessionId),
                summary = "已按自然语言意图解析为读取工具账本。",
                lines = listOf("intent=read_tool_ledger"),
            )
        }
        if (normalized.containsAny("工具运行时", "tool runtime", "工具成功率", "哪些工具总失败")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_TOOL_RUNTIME, sessionId = sessionId),
                summary = "已按自然语言意图解析为读取工具运行时。",
                lines = listOf("intent=read_tool_runtime"),
            )
        }
        if (normalized.containsAny("工具目录", "可用工具", "tool catalog", "有哪些工具")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_TOOL_CATALOG),
                summary = "已按自然语言意图解析为读取工具目录。",
                lines = listOf("intent=read_tool_catalog"),
            )
        }
        if (normalized.containsAny("工具契约", "工具协议", "tool contracts", "哪些工具需要交互", "tool runtime contract")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_TOOL_CONTRACTS),
                summary = "已按自然语言意图解析为读取 tool runtime contract。",
                lines = listOf("intent=read_tool_contracts"),
            )
        }
        if (normalized.containsAny("跟进队列", "待跟进", "跟进项", "今日跟进", "follow up")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_FOLLOW_UP_QUEUE),
                summary = "已按自然语言意图解析为读取跟进队列。",
                lines = listOf("intent=read_follow_up_queue"),
            )
        }
        if (normalized.containsAny("跟进健康", "收件箱健康", "逾期跟进", "follow up health", "哪些跟进快过期")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_FOLLOW_UP_HEALTH),
                summary = "已按自然语言意图解析为读取跟进健康度。",
                lines = listOf("intent=read_follow_up_health"),
            )
        }
        if (normalized.containsAny("任务 os", "task os", "任务操作系统", "当前有哪些任务在抢")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_TASK_OS),
                summary = "已按自然语言意图解析为读取 task OS 状态。",
                lines = listOf("intent=read_task_os"),
            )
        }
        if (normalized.containsAny("安全中心", "授权中心", "风险确认", "安全授权")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_SAFETY_CENTER),
                summary = "已按自然语言意图解析为读取安全中心。",
                lines = listOf("intent=read_safety_center"),
            )
        }
        if (normalized.containsAny("权限中心", "permission center", "授权规则", "规则和授权", "规则来源", "为什么允许", "为什么拒绝")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_PERMISSION_CENTER, sessionId = sessionId),
                summary = "已按自然语言意图解析为读取权限中心。",
                lines = listOf("intent=read_permission_center"),
            )
        }
        if (normalized.containsAny("permission product", "授权产品", "allow ask deny 面板", "规则分栏", "allow ask deny")) {
            val tab = detectPermissionTab(normalized)
            return SessionPlatformCommandResolution(
                command =
                    SessionPlatformCommand(
                        capability = SessionCapabilityKey.READ_PERMISSION_PRODUCT,
                        sessionId = sessionId,
                        payload = mapOf("tab" to tab),
                    ),
                summary = "已按自然语言意图解析为读取 permission product。",
                lines = listOfNotNull("intent=read_permission_product", tab.takeIf { it.isNotBlank() }?.let { "tab=$it" }),
            )
        }
        if (normalized.containsAny("安全规则", "总是允许", "总是询问", "总是拒绝", "allow ask deny", "allow 规则", "deny 规则")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_SAFETY_POLICIES),
                summary = "已按自然语言意图解析为读取安全规则。",
                lines = listOf("intent=read_safety_policies"),
            )
        }
        if (normalized.containsAny("安全历史", "审批历史", "安全决策", "授权历史")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_SAFETY_DECISIONS, sessionId = sessionId),
                summary = "已按自然语言意图解析为读取安全决策历史。",
                lines = listOf("intent=read_safety_decisions"),
            )
        }
        if (normalized.containsAny("grounding", "识别健康", "为什么总是点错", "页面识别失败")) {
            return SessionPlatformCommandResolution(
                command = SessionPlatformCommand(capability = SessionCapabilityKey.READ_GROUNDING_HEALTH, sessionId = sessionId),
                summary = "已按自然语言意图解析为读取 grounding 健康度。",
                lines = listOf("intent=read_grounding_health"),
            )
        }
        val section =
            when {
                normalized.containsAny("today", "今日", "现在该做什么", "今天该做什么", "今日摘要") -> "today"
                normalized.containsAny("inbox", "收件箱", "待办箱", "提醒箱") -> "inbox"
                normalized.containsAny("viewer", "查看器", "回放", "诊断详情") -> "viewer"
                normalized.containsAny("approval", "审批列表", "待确认列表", "审批面板") -> "approvals"
                normalized.containsAny("session", "会话", "任务列表") -> "sessions"
                normalized.containsAny("governance", "治理", "策略") -> "governance"
                normalized.containsAny("memory", "记忆面板") -> "memory"
                else -> ""
            }
        if (section.isBlank()) return null
        return SessionPlatformCommandResolution(
            command =
                SessionPlatformCommand(
                    capability = SessionCapabilityKey.READ_PRODUCT_SHELL,
                    payload = mapOf("section" to section),
                ),
            summary = "已按自然语言意图解析为读取产品壳视图。",
            lines = listOf("intent=read_product_shell", "section=$section"),
        )
    }

    private fun resolveSessionControl(
        input: String,
        normalized: String,
        sessionId: String,
    ): SessionPlatformCommandResolution? {
        if (normalized.containsAny("继续", "恢复", "接着", "回到任务")) {
            return SessionPlatformCommandResolution(
                command =
                    SessionPlatformCommand(
                        capability = SessionCapabilityKey.RESUME_SESSION,
                        sessionId = sessionId,
                        userCorrection = extractTailNote(input, listOf("继续", "恢复", "接着", "回到任务")),
                    ),
                summary = "已按自然语言意图解析为恢复任务。",
                lines = buildSessionControlLines("resume_session", sessionId, "/resume"),
            )
        }
        if (normalized.containsAny("批准", "确认", "同意", "放行")) {
            return SessionPlatformCommandResolution(
                command =
                    SessionPlatformCommand(
                        capability = SessionCapabilityKey.APPROVE_SAFETY,
                        sessionId = sessionId,
                        userCorrection = extractTailNote(input, listOf("批准", "确认", "同意", "放行")),
                    ),
                summary = "已按自然语言意图解析为批准高风险动作。",
                lines = buildSessionControlLines("approve_safety", sessionId, "/approve"),
            )
        }
        if (normalized.containsAny("拒绝", "驳回", "不要执行", "取消这步")) {
            return SessionPlatformCommandResolution(
                command =
                    SessionPlatformCommand(
                        capability = SessionCapabilityKey.REJECT_SAFETY,
                        sessionId = sessionId,
                    ),
                summary = "已按自然语言意图解析为拒绝高风险动作。",
                lines = buildSessionControlLines("reject_safety", sessionId, "/reject"),
            )
        }
        if (normalized.containsAny("停止", "结束任务", "取消任务", "先停下")) {
            val reason = extractTailNote(input, listOf("停止", "结束任务", "取消任务", "先停下")).ifBlank { input }
            return SessionPlatformCommandResolution(
                command =
                    SessionPlatformCommand(
                        capability = SessionCapabilityKey.STOP_SESSION,
                        sessionId = sessionId,
                        payload = mapOf("reason" to reason),
                    ),
                summary = "已按自然语言意图解析为停止任务。",
                lines = buildSessionControlLines("stop_session", sessionId, "/stop"),
            )
        }
        return null
    }

    private fun resolveSearchIntent(
        input: String,
        normalized: String,
        sessionId: String,
    ): SessionPlatformCommandResolution? {
        if (normalized.containsAny("历史任务", "最近任务", "会话历史", "任务历史", "有哪些任务")) {
            val limit = limitRegex.find(normalized)?.groupValues?.getOrNull(1).orEmpty()
            return SessionPlatformCommandResolution(
                command =
                    SessionPlatformCommand(
                        capability = SessionCapabilityKey.READ_SESSION_HISTORY,
                        payload = mapOf("limit" to limit),
                    ),
                summary = "已按自然语言意图解析为读取任务历史。",
                lines = listOfNotNull("intent=read_session_history", limit.takeIf { it.isNotBlank() }?.let { "limit=$it" }),
            )
        }
        if (normalized.containsAny("搜索历史", "查历史", "找历史任务", "搜索会话", "查会话")) {
            val query = stripLeadPhrase(input).removeKnownPrefixes("搜索历史", "查历史", "找历史任务", "搜索会话", "查会话").trim()
            return SessionPlatformCommandResolution(
                command =
                    SessionPlatformCommand(
                        capability = SessionCapabilityKey.SEARCH_SESSION_HISTORY,
                        query = query.ifBlank { input },
                    ),
                summary = "已按自然语言意图解析为检索任务历史。",
                lines = listOf("intent=search_session_history", "query=${query.ifBlank { input }}"),
            )
        }
        if (normalized.containsAny("长期记忆", "记忆里", "记得", "回忆", "查记忆")) {
            val query = stripLeadPhrase(input).removeKnownPrefixes("长期记忆", "记忆里", "记得", "回忆", "查记忆").trim().trim('?', '？')
            return SessionPlatformCommandResolution(
                command =
                    SessionPlatformCommand(
                        capability = SessionCapabilityKey.RECALL_MEMORY,
                        query = query.ifBlank { input },
                    ),
                summary = "已按自然语言意图解析为检索长期记忆。",
                lines = listOf("intent=recall_memory", "query=${query.ifBlank { input }}"),
            )
        }
        if (normalized.containsAny("artifact", "产物", "结果文件", "结果截图", "查结果")) {
            val query = stripLeadPhrase(input).removeKnownPrefixes("artifact", "产物", "结果文件", "结果截图", "查结果").trim()
            return SessionPlatformCommandResolution(
                command =
                    SessionPlatformCommand(
                        capability = SessionCapabilityKey.SEARCH_ARTIFACTS,
                        sessionId = sessionId,
                        query = query.ifBlank { input },
                    ),
                summary = "已按自然语言意图解析为检索 artifact。",
                lines =
                    buildList {
                        add("intent=search_artifacts")
                        sessionId.takeIf { it.isNotBlank() }?.let { add("session_id=$it") }
                        add("query=${query.ifBlank { input }}")
                    },
            )
        }
        return null
    }

    private fun buildSessionControlLines(
        intent: String,
        sessionId: String,
        helpCommand: String,
    ): List<String> =
        buildList {
            add("intent=$intent")
            sessionId.takeIf { it.isNotBlank() }?.let { add("session_id=$it") }
            addAll(SessionPlatformCommandRegistry.helpLines(helpCommand).take(2))
        }

    private fun extractTailNote(
        input: String,
        keywords: List<String>,
    ): String {
        val match = keywords.firstOrNull { keyword -> input.contains(keyword) } ?: return ""
        return input.substringAfter(match, "").trim().trim('，', ',', '。', ':', '：')
    }

    private fun normalize(
        input: String,
    ): String = input.trim().lowercase()

    private fun String.containsAny(
        vararg needles: String,
    ): Boolean = needles.any { contains(it) }

    private fun stripLeadPhrase(
        input: String,
    ): String = input.replaceFirst(searchLeadRegex, "")

    private fun String.removeKnownPrefixes(
        vararg prefixes: String,
    ): String {
        var current = this
        prefixes.forEach { prefix ->
            if (current.startsWith(prefix)) {
                current = current.removePrefix(prefix).trim()
            }
        }
        return current
    }

    private fun detectPermissionTab(
        normalized: String,
    ): String =
        when {
            normalized.containsAny("allow", "允许", "总是允许", "放行") -> "allow"
            normalized.containsAny("deny", "拒绝", "总是拒绝", "禁止") -> "deny"
            normalized.containsAny("ask", "询问", "确认", "总是询问") -> "ask"
            else -> ""
        }

    private fun extractPackageName(
        input: String,
    ): String {
        val match = Regex("""(?:package|pkg|包名|应用)\s*[:=]?\s*([a-zA-Z0-9_.]+)""").find(input)
        return match?.groupValues?.getOrNull(1).orEmpty()
    }
}
