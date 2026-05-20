package com.lmx.xiaoxuanagent.agent

import android.util.Log
import com.lmx.xiaoxuanagent.BuildConfig
import com.lmx.xiaoxuanagent.runtime.CompactService
import com.lmx.xiaoxuanagent.runtime.PlatformTraceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

interface AgentPlanner {
    val modeLabel: String

    suspend fun plan(
        task: String,
        observation: ScreenObservation,
        history: List<AgentTurnRecord>,
        artifactHints: List<PlannerArtifactHint>,
        memoryContext: PlanningMemoryContext,
        activeSkills: List<SkillContext>,
        taskPlanState: TaskPlanState,
        visualContext: VisualPerceptionContext,
        screenshot: ScreenshotPayload? = null,
        targetPackageName: String,
    ): AgentDecision
}

class OpenAiPlanner(
    private val providerLabelOverride: String? = null,
) : AgentPlanner {
    private companion object {
        private const val TAG = "TbAgent"
        private const val FAST_PRIMARY_CONNECT_TIMEOUT_MS = 8_000
        private const val FAST_PRIMARY_READ_TIMEOUT_MS = 18_000
        private const val HEAVY_FALLBACK_CONNECT_TIMEOUT_MS = 12_000
        private const val HEAVY_FALLBACK_READ_TIMEOUT_MS = 45_000
        private const val MAX_PROMPT_ESTIMATED_TOKENS = 6_400
    }

    internal data class PlannerRequestStrategy(
        val primaryModel: String,
        val fallbackModel: String?,
        val attachScreenshotOnPrimary: Boolean,
        val attachScreenshotOnFallback: Boolean,
        val policyTag: String,
        val primaryConnectTimeoutMs: Int,
        val primaryReadTimeoutMs: Int,
        val fallbackConnectTimeoutMs: Int,
        val fallbackReadTimeoutMs: Int,
    )

    internal data class PlannerPromptProfile(
        val label: String,
        val runtimeMode: String = "",
        val targetBudget: Int = 4,
        val memoryLineLimit: Int = 8,
        val notebookLineLimit: Int = 6,
        val signalLineLimit: Int = 6,
        val toolContractLimit: Int = 4,
        val attachmentLimit: Int = 6,
        val toolCatalogLimit: Int = Int.MAX_VALUE,
        val stepLimit: Int = 10,
        val waitConditionLimit: Int = 6,
        val visualObjectLimit: Int = 12,
        val parserRegionLimit: Int = 12,
        val historyTurnLimit: Int = 6,
        val artifactHintLimit: Int = 6,
    )

    override val modeLabel: String =
        providerLabelOverride?.takeIf { it.isNotBlank() }
            ?: BuildConfig.AGENT_MODEL.ifBlank { "LLM_UNCONFIGURED" }

    override suspend fun plan(
        task: String,
        observation: ScreenObservation,
        history: List<AgentTurnRecord>,
        artifactHints: List<PlannerArtifactHint>,
        memoryContext: PlanningMemoryContext,
        activeSkills: List<SkillContext>,
        taskPlanState: TaskPlanState,
        visualContext: VisualPerceptionContext,
        screenshot: ScreenshotPayload?,
        targetPackageName: String,
    ): AgentDecision = withContext(Dispatchers.IO) {
        if (BuildConfig.AGENT_API_BASE_URL.isBlank() || BuildConfig.AGENT_MODEL.isBlank()) {
            error("未配置 AGENT_API_BASE_URL 或 AGENT_MODEL，无法调用 LLM。")
        }

        val requestStrategy = resolvePlannerRequestStrategy(observation, visualContext, screenshot)
        val systemPrompt = systemPrompt(targetPackageName)
        val promptProfiles =
            listOf(
                PlannerPromptProfile(label = "default", toolCatalogLimit = 24),
                PlannerPromptProfile(
                    label = "reactive_retry",
                    runtimeMode = "reactive",
                    targetBudget = 3,
                    memoryLineLimit = 6,
                    notebookLineLimit = 4,
                    signalLineLimit = 4,
                    toolContractLimit = 3,
                    attachmentLimit = 4,
                    toolCatalogLimit = 16,
                    stepLimit = 8,
                    waitConditionLimit = 4,
                    visualObjectLimit = 8,
                    parserRegionLimit = 8,
                    historyTurnLimit = 4,
                    artifactHintLimit = 4,
                ),
                PlannerPromptProfile(
                    label = "auto_compact_retry",
                    runtimeMode = "auto",
                    targetBudget = 2,
                    memoryLineLimit = 4,
                    notebookLineLimit = 3,
                    signalLineLimit = 3,
                    toolContractLimit = 2,
                    attachmentLimit = 3,
                    toolCatalogLimit = 10,
                    stepLimit = 6,
                    waitConditionLimit = 3,
                    visualObjectLimit = 5,
                    parserRegionLimit = 5,
                    historyTurnLimit = 3,
                    artifactHintLimit = 3,
                ),
            )
        var lastError: Throwable? = null
        promptProfiles.forEachIndexed { index, profile ->
            val prompt =
                buildUserPrompt(
                    task = task,
                    observation = observation,
                    history = history,
                    artifactHints = artifactHints,
                    memoryContext = memoryContext,
                    activeSkills = activeSkills,
                    taskPlanState = taskPlanState,
                    visualContext = visualContext,
                    targetPackageName = targetPackageName,
                    promptProfile = profile,
                )
            val estimatedTokens = estimatePromptTokens(systemPrompt.length + prompt.length)
            Log.d(
                TAG,
                "planner strategy policy=${requestStrategy.policyTag} profile=${profile.label} primary=${requestStrategy.primaryModel} " +
                    "fallback=${requestStrategy.fallbackModel ?: "-"} attachPrimary=${requestStrategy.attachScreenshotOnPrimary} " +
                    "attachFallback=${requestStrategy.attachScreenshotOnFallback} promptChars=${prompt.length} estTokens=$estimatedTokens " +
                    "primaryTimeout=${requestStrategy.primaryConnectTimeoutMs}/${requestStrategy.primaryReadTimeoutMs}ms " +
                    "fallbackTimeout=${requestStrategy.fallbackConnectTimeoutMs}/${requestStrategy.fallbackReadTimeoutMs}ms",
            )
            if (estimatedTokens > MAX_PROMPT_ESTIMATED_TOKENS && index < promptProfiles.lastIndex) {
                PlatformTraceStore.record(
                    category = "planner_prompt_compacted",
                    summary = "${modeLabel.lowercase()} | ${profile.label} | skip_oversized",
                    metadata =
                        mapOf(
                            "policy_tag" to requestStrategy.policyTag,
                            "prompt_profile" to profile.label,
                            "estimated_tokens" to estimatedTokens.toString(),
                        ),
                )
                return@forEachIndexed
            }
            runCatching {
                requestDecisionWithFallback(
                    requestStrategy = requestStrategy,
                    task = task,
                    observation = observation,
                    prompt = prompt,
                    screenshot = screenshot,
                    targetPackageName = targetPackageName,
                    promptProfile = profile,
                )
            }.onSuccess { return@withContext it }
                .onFailure { error ->
                    lastError = error
                    val shouldRetry = shouldRetryWithMoreCompaction(error) && index < promptProfiles.lastIndex
                    PlatformTraceStore.record(
                        category = "planner_prompt_compacted",
                        summary = "${modeLabel.lowercase()} | ${profile.label} | ${if (shouldRetry) "retry" else "fail"}",
                        metadata =
                            mapOf(
                                "policy_tag" to requestStrategy.policyTag,
                                "prompt_profile" to profile.label,
                                "error" to (error.message ?: error.javaClass.simpleName),
                            ),
                    )
                    if (!shouldRetry) {
                        throw error
                    }
                }
        }
        throw lastError ?: error("planner 未生成可用决策。")
    }

    private fun requestDecisionWithFallback(
        requestStrategy: PlannerRequestStrategy,
        task: String,
        observation: ScreenObservation,
        prompt: String,
        screenshot: ScreenshotPayload?,
        targetPackageName: String,
        promptProfile: PlannerPromptProfile,
    ): AgentDecision {
        var resolvedModel = requestStrategy.primaryModel
        return runCatching {
            requestDecision(
                model = requestStrategy.primaryModel,
                task = task,
                observation = observation,
                prompt = prompt,
                screenshot = screenshot.takeIf { requestStrategy.attachScreenshotOnPrimary },
                targetPackageName = targetPackageName,
                connectTimeoutMs = requestStrategy.primaryConnectTimeoutMs,
                readTimeoutMs = requestStrategy.primaryReadTimeoutMs,
            )
        }.recoverCatching { primaryError ->
            PlatformTraceStore.record(
                category = "planner_request_failure",
                summary = "${modeLabel.lowercase()} | ${requestStrategy.primaryModel} | ${primaryError.javaClass.simpleName}",
                metadata =
                    mapOf(
                        "planner_mode" to modeLabel.lowercase(),
                        "model" to requestStrategy.primaryModel,
                        "policy_tag" to requestStrategy.policyTag,
                        "prompt_profile" to promptProfile.label,
                    ),
            )
            val fallbackModel = requestStrategy.fallbackModel
            if (fallbackModel.isNullOrBlank() || fallbackModel == requestStrategy.primaryModel) {
                throw primaryError
            }
            Log.w(
                TAG,
                "planner primary failed, fallback model=$fallbackModel policy=${requestStrategy.policyTag} profile=${promptProfile.label}",
                primaryError,
            )
            resolvedModel = fallbackModel
            requestDecision(
                model = fallbackModel,
                task = task,
                observation = observation,
                prompt = prompt,
                screenshot = screenshot.takeIf { requestStrategy.attachScreenshotOnFallback },
                targetPackageName = targetPackageName,
                connectTimeoutMs = requestStrategy.fallbackConnectTimeoutMs,
                readTimeoutMs = requestStrategy.fallbackReadTimeoutMs,
            )
        }.onSuccess {
            PlatformTraceStore.record(
                category = "planner_request_success",
                summary = "${modeLabel.lowercase()} | $resolvedModel",
                metadata =
                    mapOf(
                        "planner_mode" to modeLabel.lowercase(),
                        "model" to resolvedModel,
                        "policy_tag" to requestStrategy.policyTag,
                        "prompt_profile" to promptProfile.label,
                    ),
            )
        }.getOrThrow()
    }

    private fun requestDecision(
        model: String,
        task: String,
        observation: ScreenObservation,
        prompt: String,
        screenshot: ScreenshotPayload?,
        targetPackageName: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): AgentDecision {
        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0.1)
            put("max_tokens", 240)
            put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt(targetPackageName))
                        },
                    )
                    .put(buildUserMessage(prompt, screenshot)),
            )
        }
        Log.d(
            TAG,
            "planner request model=$model task=${task.take(80)} sig=${observation.signature} elements=${observation.elements.size} " +
                "screenshot=${if (screenshot == null) "none" else "${screenshot.width}x${screenshot.height}"} " +
                "timeout=${connectTimeoutMs}/${readTimeoutMs}ms",
        )

        val url = URL("${BuildConfig.AGENT_API_BASE_URL.trimEnd('/')}/chat/completions")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Content-Type", "application/json")
            if (BuildConfig.AGENT_API_KEY.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${BuildConfig.AGENT_API_KEY}")
            }
        }

        connection.outputStream.use { output ->
            output.write(payload.toString().toByteArray())
        }

        val responseCode = connection.responseCode
        val responseText = readConnectionText(connection, responseCode in 200..299)
        Log.d(TAG, "planner response code=$responseCode body=${responseText.take(500)}")
        if (responseCode !in 200..299) {
            error("LLM 请求失败，code=$responseCode body=$responseText")
        }

        val responseJson = JSONObject(responseText)
        val content = responseJson
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .optString("content")

        return parseDecision(
            rawContent = content,
            observation = observation,
            targetPackageName = targetPackageName,
        )
    }

    private fun systemPrompt(targetPackageName: String): String = """
        你是一个 Android 手机 Agent。
        你的目标是基于当前 observation 和截图，为手机选择下一步最小动作。
        当前 MVP 先以单一目标 App 任务为主，但动作接口要通用。
        你必须严格返回一个 JSON 对象，不能输出 Markdown。
        
        可用动作:
        1. {"action":"launch_app","package_name":"$targetPackageName","reason":"..."}
        2. {"action":"click","element_id":"e01","target_text":"韩威","reason":"..."}
        3. {"action":"set_text","element_id":"e02","text":"零食","reason":"..."}
        4. {"action":"scroll","element_id":"e03","direction":"down","reason":"..."}
        5. {"action":"scroll","direction":"down","reason":"..."}
        6. {"action":"long_click","element_id":"e04","reason":"..."}
        7. {"action":"copy_text","element_id":"e05","reason":"..."}
        8. {"action":"paste_clipboard","element_id":"e06","reason":"..."}
        9. {"action":"press_enter","reason":"..."}
        10. {"action":"tap_point","x":540,"y":1680,"reason":"..."}
        11. {"action":"swipe","start_x":540,"start_y":1800,"end_x":540,"end_y":600,"duration_ms":350,"reason":"..."}
        12. {"action":"back","reason":"..."}
        13. {"action":"home","reason":"..."}
        14. {"action":"notifications","reason":"..."}
        15. {"action":"quick_settings","reason":"..."}
        16. {"action":"recents","reason":"..."}
        17. {"action":"open_settings","destination":"notifications","reason":"..."}
        18. {"action":"share_text","text":"稍后把这段发给我自己","reason":"..."}
        19. {"action":"create_alarm","time":"07:30","message":"明早提醒开会","reason":"..."}
        20. {"action":"create_timer","duration":"15分钟","message":"提醒取外卖","reason":"..."}
        21. {"action":"open_stopwatch","reason":"..."}
        22. {"action":"insert_calendar_event","title":"周会","details":"准备周报","when_label":"明天上午","reason":"..."}
        23. {"action":"dial_number","number":"13800138000","contact_name":"张三","reason":"..."}
        24. {"action":"draft_sms","recipient":"13800138000","body":"我晚点到","reason":"..."}
        25. {"action":"wait","reason":"..."}
        26. {"action":"finish","summary":"...","reason":"..."}
        27. {"action":"fail","reason":"..."}
        28. {"action":"focus_primary_input","reason":"..."}
        29. {"action":"populate_primary_input","text":"韩威","reason":"..."}
        30. {"action":"submit_primary_action","hint":"搜索","reason":"..."}
        31. {"action":"dismiss_interrupt","hint":"关闭弹窗","reason":"..."}
        32. {"action":"open_best_candidate","target_text":"韩威","role_hint":"conversation","reason":"..."}
        33. {"action":"scroll_for_more","direction":"down","reason":"..."}
        34. {"action":"return_to_target_app","package_name":"$targetPackageName","reason":"..."}
        35. {"action":"read_session_history","query":"上次搜索失败原因","reason":"..."}
        36. {"action":"search_artifacts","query":"评论区截图","artifact_type":"planning_screenshot","reason":"..."}
        37. {"action":"recall_memory","query":"用户常用导航 App 偏好","reason":"..."}
        38. {"action":"read_session_notebook","reason":"..."}
        39. {"action":"write_session_note","note":"当前已经定位到正确会话，但发送前需确认正文","tag":"checkpoint","reason":"..."}
        40. {"action":"search_tools","query":"需要找能做网页检索和待办收口的工具","reason":"..."}
        41. {"action":"web_search","query":"高德地图 官网 客服","reason":"..."}
        42. {"action":"web_fetch","url":"<web_url>","reason":"..."}
        43. {"action":"read_connected_app_capabilities","app_id":"amap_assistant","reason":"..."}
        44. {"action":"execute_connected_app_action","app_id":"amap_assistant","operation":"open_route","primary":"虹桥火车站","secondary":"","note":"优先走结构化地图路由","reason":"..."}
        45. {"action":"read_notifications","package_name":"com.tencent.mm","limit":5,"reason":"..."}
        46. {"action":"reply_notification","notification_key":"key_123","reply_text":"我稍后回复你","action_hint":"reply","reason":"..."}
        47. {"action":"media_control","command":"pause","reason":"..."}
        48. {"action":"adjust_volume","direction":"lower","stream":"music","step":2,"reason":"..."}
        49. {"action":"open_device_panel","panel":"bluetooth","reason":"..."}
        50. {"action":"capture_screenshot","note":"记录当前确认页","reason":"..."}
        51. {"action":"capture_photo","note":"打开相机拍照上传","reason":"..."}
        52. {"action":"read_todo_board","reason":"..."}
        53. {"action":"write_todo_board","content":"- 回到目标会话\n- 确认正文\n- 发送后截图","mode":"append","reason":"..."}
        54. {"action":"delegate_local_agent","task":"并行检查历史截图里最近一次失败原因","summary":"让本地 worker 去做旁路排障","role":"explore","reason":"..."}
        55. {"action":"read_worker_mailbox","target":"","include_consumed":false,"limit":8,"reason":"..."}
        56. {"action":"reply_worker_message","message_id":"mail_123","decision":"approve","note":"继续沿这个方向补证据","reason":"..."}
        57. {"action":"ack_worker_message","message_id":"mail_123","note":"主线程已吸收这条结果","reason":"..."}
        58. {"action":"read_session_memory_file","reason":"..."}
        59. {"action":"read_worker_roles","reason":"..."}
        60. {"action":"read_worker_status","target":"","include_children":true,"reason":"..."}
        61. {"action":"merge_worker_result","message_id":"mail_123","note":"把这条旁路排障结果收口到主线程","reason":"..."}
        
        规则:
        - 一次只选一个动作。
        - 先严格理解 task_constraints，再决定动作。
        - `task_plan.current_stage` 是当前阶段约束，优先做能推进当前阶段的最小动作，不要跨阶段跳跃。
        - 如果当前阶段是 `enter_query` / `enter_destination` / `find_conversation`，优先输入或定位目标，不要提前 finish、不要过早进入深层页面。
        - 如果当前阶段是 `submit_query`，优先触发搜索/提交，而不是改做无关点击。
        - 优先参考 `tool_policy.preferred_tools`，除非当前 observation 明确否定它，不要跳过更稳定的高层语义工具。
        - 如果 `tool_policy.avoid_tools` 已列出 `gui.tap_point` 或 `gui.swipe`，说明当前节点树已足够表达目标，应尽量避免点位类动作。
        - 如果当前阶段是 `browse_candidates`，优先打开更贴近任务目标的候选对象，不要点综合、筛选、更多、店铺、频道、首页等元控件。
        - 如果当前阶段是 `inspect_information`，优先进入评论区、评价、参数、详情、正文、高赞、热评等证据入口，不要过早总结。
        - 如果当前阶段是 `confirm_route` 或 `confirm_send`，优先做确认或纠偏，不要重新开始搜索整个任务。
        - 只有当前阶段是 `summarize`，或页面证据已经明显满足 `task_plan.completion_signal` 时，才允许 finish。
        - 结合 memory_context 和 active_skills 做决策，但不要虚构页面元素或执行条件。
        - 若需要在 App 间搬运文本，可使用 copy_text 和 paste_clipboard。
        - 若当前输入框已经有焦点，且需要提交搜索或确认，可使用 press_enter。
        - 如果需要先把焦点拉回主输入框，再继续输入或提交，可使用 focus_primary_input。
        - 如果当前页面已经有主输入框，且你只是要把目标词填进去，优先使用 populate_primary_input，而不是构造脆弱的 set_text。
        - 如果当前阶段需要提交/发送/确认，但具体按钮文案或 element_id 不稳定，可优先使用 submit_primary_action。
        - 如果当前被弹窗、引导或遮罩挡住，且要先把它关掉再继续主任务，可使用 dismiss_interrupt。
        - 如果需要从一组候选里打开最相关对象，但 element_id 不稳定，可使用 open_best_candidate，并补充 target_text 或 role_hint。
        - 如果只是继续探索更多候选，而不是绑定具体容器，可使用 scroll_for_more。
        - 如果当前已跑偏到别的 App，但要回到目标 App 继续执行，可使用 return_to_target_app。
        - 如果任务本质上是打开系统设置、发起分享、建闹钟、建计时器、开秒表、拨号、发短信草稿或建日历事件，优先使用 open_settings / share_text / create_alarm / create_timer / open_stopwatch / dial_number / draft_sms / insert_calendar_event，不要退化成 GUI 探索。
        - 如果任务是在回顾历史、查之前做过什么、找上次失败原因、找中间证据或回放线索，优先使用 read_session_history / search_artifacts / read_session_notebook / recall_memory，不要先盲目操作当前页面。
        - 如果已经得到稳定中间结论、纠偏策略或需要给后续轮次留锚点，可使用 write_session_note 把结论写进当前 session notebook。
        - 如果你不确定有哪些本地能力最适合当前任务，先用 search_tools 检索工具能力，不要盲目回退到低层 GUI 动作。
        - 如果当前页面证据不足，但任务需要外部公共信息，可使用 web_search / web_fetch 做公网补充，再把稳定结论写回 notebook 或 todo board。
        - 如果当前 profile 或任务已经存在 connected app 能力，优先先用 read_connected_app_capabilities / execute_connected_app_action 走结构化路径，再考虑 GUI fallback。
        - 地图、系统设置这类高确定性任务，优先 connected app 或 system utility，不要默认先走界面探索。
        - 如果任务是读通知、回通知、暂停媒体、调音量、开蓝牙/网络面板、拍照、留存截图，优先用 read_notifications / reply_notification / media_control / adjust_volume / open_device_panel / capture_screenshot / capture_photo。
        - 如果任务需要显式拆成清单、阶段计划或待办收口，可使用 read_todo_board / write_todo_board。
        - 如果当前任务里有明显可并行、可旁路排障或不阻塞主链的子任务，可使用 delegate_local_agent 把子任务委派给本地 worker；role 可选 general/explore/plan/verification。
        - 如果你不确定该派哪类 worker，先用 read_worker_roles 看内建角色目录。
        - 如果已经派发过 worker，或需要读取旁路线索、处理 worker 权限请求、吸收 partial result，可使用 read_worker_mailbox / reply_worker_message / ack_worker_message 完成 worker 回流闭环。
        - 如果要判断 worker 当前跑到哪一步、join 是否阻塞、是否已有 terminal result，可使用 read_worker_status。
        - 如果一条 worker mailbox 结果已经确认有价值，需要把它正式写进主线程记忆并确认消息，可使用 merge_worker_result。
        - 如果需要快速恢复“当前已经做到哪、还差什么、哪些稳定事实不能丢”，优先使用 read_session_memory_file。
        - 如果 visual_context.ocr_texts、visual_context.grounded_texts、visual_context.visual_objects 或 visual_context.visual_hints 提供了节点树里缺失的文本/对象，可用它辅助定位；当 visual_objects 明确给出 type/role/context 时，优先把它当成可操作候选，而不只是 OCR 提示。
        - 只有当节点树无法表达目标，且视觉上位置非常明确时，才使用 tap_point 或 swipe。
        - 如果 task_constraints.normalized_goal 已给出，输入或搜索时优先使用它，不要把整句自然语言要求原样塞进输入框。
        - 如果 task_constraints.recipient_name 已给出，通讯类任务应先定位该联系人或会话，再考虑输入正文。
        - 如果 task_constraints.message_body 已给出，只有在目标会话和可编辑输入框明确出现后，才输入该正文。
        - 如果 task_plan.resume_context.active=true，表示系统刚从挂起/外部等待中恢复；优先续跑 resume_context.resumed_subgoal_id，对齐 resume_context.resume_hint，先做最小纠偏动作，不要立刻再次进入被动等待。
        - 如果 task_plan.resume_context.user_correction 非空，说明用户刚明确指出上一步错在哪里或接下来该怎么修正；这条人工纠错优先级高于你的默认偏好，必须先用它避免重复错误。
        - 如果 memory_context.correction_templates 非空，说明历史上同类任务出现过可复用纠错经验；它优先级低于本轮 user_correction，但高于盲目重复旧动作。
        - memory_context.result_artifacts 是历史结果对象或结论的摘要，只能作为弱偏好或候选先验；如果它和当前 observation/截图证据冲突，必须以当前页面证据为准。
        - 通讯类任务点击联系人/会话前，要优先选择标题或语义与 recipient_name 高匹配的候选，避免误点公众号、服务号、群聊、频道、小程序等不匹配目标。
        - 如果当前页面已经出现发送框、输入框或聊天工具栏，但看不到 recipient_name，且还能看到公众号/群聊/频道等强不匹配信号，优先 back 纠正，而不是继续输入正文。
        - 通讯类任务里，只有在当前页面能确认 recipient_name 时，才允许 set_text 为 message_body 或继续发送。
        - 内容检索任务里，如果目标是“评论 / 高赞 / 热评 / 最新一期 / 弹幕”，先定位正确内容对象，再进入评论区或详情证据入口，不要把 tab、频道、首页入口当成目标内容。
        - 优先使用当前 observation 里的 element_id，不要编造。
        - click / long_click / copy_text / paste_clipboard 使用的 element_id 必须来自当前 observation.elements；如果你主要是根据截图或 OCR 判断目标，请额外带上 `target_text`，写成你实际要操作的可见文字，便于本地校正。
        - 如果 element 里带有 container_id / row_index / column_index / neighbor_texts，优先选择与目标文本同容器、同行或邻近语义更一致的元素，不要只按全局关键词命中。
        - 如果截图和节点文本有差异，优先参考两者都支持的结论。
        - observation.package_name 比截图语义更可信；如果 observation.package_name 已经是目标 App，不要仅凭截图像“设置页/助手页”就判断还停留在配置页面。
        - 如果有 primary_editable_id，输入优先使用它。
        - 如果有 default_scrollable_id，滚动优先使用它。
        - 如果有 primary_interrupt_action_id 或 interruptive_hints，说明当前可能存在弹窗、引导、授权提示或遮罩。
        - 如果这些中断元素与主任务无关，或明显遮挡主流程，优先点击关闭、跳过、暂不、取消、知道了这类元素。
        - 对允许、同意、继续这类按钮要更谨慎，只有继续任务明显依赖它时才点击。
        - 在搜索结果页，优先点击与关键词和价格约束匹配的商品卡片；不要轻易点击“综合/销量/筛选/更多/店铺/品牌”等元控件。
        - 只有当当前页面看不到任何明显满足约束的候选商品时，才考虑滚动、排序或筛选。
        - 如果任务强调“评价最高”，优先进入商品详情/评价页获取信息，不要默认改成“销量排序”。
        - 如果 recent_history 已显示上一轮动作被判定为 recovery_category，或已有 suggested_recovery_action，本轮不要重复同一个失败动作；优先做恢复、退出或替代候选。
        - 不要连续重复完全相同的失败动作。
        - 不要支付，不要下单，不要加购物车。
        - 只有当 observation.package_name 不是 $targetPackageName 时，才允许 launch_app；如果当前已经在目标 App 内，禁止再次 launch_app。
        - 只有在已经进入信息获取阶段时，才允许 finish 给出简要结论。
    """.trimIndent()

    private fun buildUserPrompt(
        task: String,
        observation: ScreenObservation,
        history: List<AgentTurnRecord>,
        artifactHints: List<PlannerArtifactHint>,
        memoryContext: PlanningMemoryContext,
        activeSkills: List<SkillContext>,
        taskPlanState: TaskPlanState,
        visualContext: VisualPerceptionContext,
        targetPackageName: String,
        promptProfile: PlannerPromptProfile = PlannerPromptProfile(label = "default"),
    ): String {
        val baseProfiledMemoryContext = compactPlanningMemoryContext(memoryContext, promptProfile)
        val pipelineCompaction =
            PlannerPromptCompactionPipeline.apply(
                promptProfile = promptProfile,
                history = history,
                artifactHints = artifactHints,
                memoryContext = baseProfiledMemoryContext,
            )
        val profiledMemoryContext =
            pipelineCompaction.memoryContext.copy(
                compactionSignals = (pipelineCompaction.memoryContext.compactionSignals + pipelineCompaction.signalLines).takeLast(12),
            )
        val tokenBudget =
            PlannerTokenBudgetSupport.evaluate(
                estimatedTokens = pipelineCompaction.tokenEstimateAfter,
                currentProfile = promptProfile.label,
            )
        val taskConstraints = TaskIntentParser.parse(task)
        val compactContext =
            CompactService.compactPlannerContext(
                task = task,
                topTexts = observation.topTexts,
                elements = observation.elements,
                history = pipelineCompaction.history,
                artifactHints = pipelineCompaction.artifactHints.take(promptProfile.artifactHintLimit),
                memoryBuckets = memoryContextPayload(profiledMemoryContext),
                activeSkills = activeSkills,
                taskPlanState = taskPlanState,
                visualContext = visualContext,
            )
        val toolPolicy =
            AgentToolSelectionPolicy.analyze(
                task = task,
                observation = observation,
                memoryContext = profiledMemoryContext,
                taskPlanState = taskPlanState,
                targetPackageName = targetPackageName,
            )
        val screenSafetyProjection =
            PlannerScreenSafetyProjection.project(
                topTexts = compactContext.topTexts,
                elements = compactContext.elements,
                ocrTexts = compactContext.ocrTexts,
                groundedTexts = compactContext.groundedTexts,
                visualHints = compactContext.visualHints,
                profileId = com.lmx.xiaoxuanagent.taskprofile.TaskRegistry.allProfiles().firstOrNull { it.packageName == targetPackageName }?.id.orEmpty(),
            )
        val observationJson = JSONObject().apply {
            put("task", task)
            put(
                "task_constraints",
                JSONObject().apply {
                    put("intent_type", taskConstraints.intentType.name.lowercase())
                    put("normalized_goal", taskConstraints.normalizedGoal)
                    put("entry_query", taskConstraints.entryQuery)
                    put("search_query", taskConstraints.searchQuery)
                    put("destination", taskConstraints.destination)
                    put("recipient_name", taskConstraints.recipientName)
                    put("message_body", taskConstraints.messageBody)
                    put("objective_hint", taskConstraints.objectiveHint)
                    put("max_price_yuan", taskConstraints.maxPriceYuan)
                    put("prefer_high_review", taskConstraints.preferHighReview)
                    put("prefer_high_sales", taskConstraints.preferHighSales)
                    put("keyword_hints", JSONArray(taskConstraints.keywordHints))
                },
            )
            put("package_name", observation.packageName)
            put("page_state", observation.pageState)
            put("signature", observation.signature)
            put("screen_summary", observation.screenSummary)
            put("structure_hints", JSONArray(observation.structureHints))
            put("primary_editable_id", observation.primaryEditableId)
            put("focused_element_id", observation.focusedElementId)
            put("default_scrollable_id", observation.defaultScrollableId)
            put("primary_interrupt_action_id", observation.primaryInterruptActionId)
            put(
                "context_compact",
                JSONObject().apply {
                    put("policy", "artifact_first_summary_bias")
                    put("compact_policy", compactContext.policyTag)
                    put("prompt_pipeline_tokens_before", pipelineCompaction.tokenEstimateBefore)
                    put("prompt_pipeline_tokens_after", pipelineCompaction.tokenEstimateAfter)
                    put("prompt_pipeline_signals", JSONArray(pipelineCompaction.signalLines))
                    put(
                        "token_budget",
                        JSONObject().apply {
                            put("estimated_tokens", tokenBudget.estimatedTokens)
                            put("budget_tokens", tokenBudget.budgetTokens)
                            put("percent_left", tokenBudget.percentLeft)
                            put("above_warning", tokenBudget.aboveWarning)
                            put("above_error", tokenBudget.aboveError)
                            put("above_auto_compact", tokenBudget.aboveAutoCompact)
                            put("at_blocking_limit", tokenBudget.atBlockingLimit)
                            put("recommended_profile", tokenBudget.recommendedProfile)
                        },
                    )
                    put("governance_hints", JSONArray(compactContext.governanceHints))
                    put("original_element_count", compactContext.meta.originalElementCount)
                    put("compact_element_count", compactContext.meta.compactElementCount)
                    put("original_history_count", compactContext.meta.originalHistoryCount)
                    put("compact_history_count", compactContext.meta.compactHistoryCount)
                    put("original_top_text_count", compactContext.meta.originalTopTextCount)
                    put("compact_top_text_count", compactContext.meta.compactTopTextCount)
                    put("original_visual_hint_count", compactContext.meta.originalVisualHintCount)
                    put("compact_visual_hint_count", compactContext.meta.compactVisualHintCount)
                    put("original_artifact_hint_count", compactContext.meta.originalArtifactHintCount)
                    put("compact_artifact_hint_count", compactContext.meta.compactArtifactHintCount)
                },
            )
            put(
                "interruptive_hints",
                JSONArray().apply {
                    observation.interruptiveHints.forEach { hint ->
                        put(
                            JSONObject().apply {
                                put("element_id", hint.elementId)
                                put("text", hint.text)
                                put("reason", hint.reason)
                            },
                        )
                    }
                },
            )
            put("top_texts", JSONArray(screenSafetyProjection.topTexts))
            put(
                "tool_policy",
                JSONObject().apply {
                    put("summary", toolPolicy.summary)
                    put("preferred_tools", JSONArray(toolPolicy.preferredTools))
                    put("allowed_tools", JSONArray(toolPolicy.allowedTools))
                    put("avoid_tools", JSONArray(toolPolicy.avoidTools))
                    put(
                        "tool_scores",
                        JSONObject().apply {
                            toolPolicy.toolScores.forEach { (toolName, score) ->
                                put(toolName, score)
                            }
                        },
                    )
                    put(
                        "fallback_chains",
                        JSONObject().apply {
                            toolPolicy.fallbackChains.forEach { (toolName, fallbacks) ->
                                put(toolName, JSONArray(fallbacks))
                            }
                        },
                    )
                    put("selection_hints", JSONArray(toolPolicy.selectionHints))
                },
            )
            put("runtime_compaction", JSONArray((profiledMemoryContext.compactionSignals + tokenBudget.detailLines).takeLast(16)))
            put("loop_inbox_attachments", JSONArray(profiledMemoryContext.loopInboxAttachments))
            put(
                "turn_attachments",
                JSONArray().apply {
                    profiledMemoryContext.turnAttachments.take(promptProfile.attachmentLimit).forEach { attachment ->
                        put(
                            JSONObject().apply {
                                put("attachment_id", attachment.attachmentId)
                                put("source", attachment.source)
                                put("type", attachment.type)
                                put("title", attachment.title)
                                put("summary", attachment.summary)
                                put("priority", attachment.priority)
                                put("detail_lines", JSONArray(attachment.detailLines))
                                put("recommended_commands", JSONArray(attachment.recommendedCommands))
                                put("consumed_at_ms", attachment.consumedAtMs)
                            },
                        )
                    }
                },
            )
            put("loop_runtime_signals", JSONArray(profiledMemoryContext.loopRuntimeSignals))
            put("loop_runtime_drain", JSONArray(profiledMemoryContext.loopRuntimeDrain))
            put("loop_runtime_prefetch", JSONArray(profiledMemoryContext.loopRuntimePrefetch))
            put("loop_runtime_task_summary", JSONArray(profiledMemoryContext.loopRuntimeTaskSummary))
            put("skill_prefetch_signals", JSONArray(profiledMemoryContext.skillPrefetchSignals))
            put("tool_refresh_signals", JSONArray(profiledMemoryContext.toolRefreshSignals))
            put("permission_product_signals", JSONArray(profiledMemoryContext.permissionProductSignals))
            put("memory_fork_signals", JSONArray(profiledMemoryContext.memoryForkSignals))
            put("tool_runtime_contracts", JSONArray(profiledMemoryContext.toolRuntimeContracts))
            put(
                        "tool_catalog",
                        JSONArray().apply {
                    AgentToolCatalog.descriptors().take(pipelineCompaction.toolCatalogLimit).forEach { descriptor ->
                        put(
                            JSONObject().apply {
                                put("name", descriptor.name)
                                put("category", descriptor.type.name.lowercase())
                                put("summary", descriptor.summary)
                                put("irreversible", descriptor.irreversible)
                                put("concurrency_safe", descriptor.concurrencySafe)
                                put("requires_user_interaction", descriptor.requiresUserInteraction)
                                put("should_defer", descriptor.shouldDefer)
                                put("always_load", descriptor.alwaysLoad)
                                put("permission_family", descriptor.permissionFamily)
                                put("interrupt_behavior", descriptor.interruptBehavior)
                                put("input_contract", descriptor.inputContract)
                                put("result_contract", descriptor.resultContract)
                                put("progress_label", descriptor.progressLabel)
                                put("fallback_tools", JSONArray(descriptor.fallbackTools))
                            },
                        )
                    }
                },
            )
            put(
                "task_plan",
                JSONObject().apply {
                    put("plan_type", taskPlanState.planType)
                    put("current_stage", taskPlanState.currentStage)
                    put("stage_summary", taskPlanState.stageSummary)
                    put("next_objective", taskPlanState.nextObjective)
                    put("completion_signal", taskPlanState.completionSignal)
                    put("current_subgoal_id", taskPlanState.currentSubgoalId)
                    put("waiting_for_external", taskPlanState.waitingForExternal)
                    put("waiting_for_event", taskPlanState.waitingForEvent)
                    put("suspendable", taskPlanState.suspendable)
                    put("suspend_reason", taskPlanState.suspendReason)
                    put("orchestration_summary", taskPlanState.orchestrationSummary)
                    put(
                        "resume_context",
                        JSONObject().apply {
                            resumeContextPayload(taskPlanState.resumeContext).forEach { (key, value) ->
                                put(key, value)
                            }
                        },
                    )
                    put(
                        "steps",
                        JSONArray().apply {
                            taskPlanState.steps.take(promptProfile.stepLimit).forEach { step ->
                                put(
                                    JSONObject().apply {
                                        put("id", step.id)
                                        put("title", step.title)
                                        put("status", step.status)
                                        put("evidence", step.evidence)
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "wait_conditions",
                        JSONArray().apply {
                            taskPlanState.waitConditions.take(promptProfile.waitConditionLimit).forEach { condition ->
                                put(
                                    JSONObject().apply {
                                        put("id", condition.id)
                                        put("title", condition.title)
                                        put("type", condition.type)
                                        put("status", condition.status)
                                        put("reason", condition.reason)
                                        put("resume_event", condition.resumeEvent)
                                        put("resume_hint", condition.resumeHint)
                                        put("blocking_node_id", condition.blockingNodeId)
                                        put("source_hints", JSONArray(condition.sourceHints))
                                    },
                                )
                            }
                        },
                    )
                    put("task_stack", buildTaskStackJson(taskPlanState.taskStack))
                    put("task_graph", buildTaskGraphJson(taskPlanState.taskGraph))
                    put("stage_contract", buildStageContract(taskPlanState))
                },
            )
            put(
                "screen_safety",
                JSONObject().apply {
                    put("projection_active", screenSafetyProjection.lines.isNotEmpty())
                    put("projection_lines", JSONArray(screenSafetyProjection.lines))
                },
            )
            put(
                "visual_context",
                JSONObject().apply {
                    put("capture_available", visualContext.captureAvailable)
                    put("summary", visualContext.summary.take(160))
                    put("visual_hints", JSONArray(screenSafetyProjection.visualHints))
                    put(
                        "ocr_texts",
                        JSONArray().apply {
                            screenSafetyProjection.ocrTexts.forEach { block ->
                                put(
                                    JSONObject().apply {
                                        put("text", block.text.take(32))
                                        put("bounds", block.bounds)
                                        put("confidence", block.confidence.toDouble())
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "grounded_texts",
                        JSONArray().apply {
                            screenSafetyProjection.groundedTexts.forEach { grounded ->
                                put(
                                    JSONObject().apply {
                                        put("text", grounded.text.take(32))
                                        put("bounds", grounded.bounds)
                                        put("matched_element_id", grounded.matchedElementId)
                                        put("matched_element_text", grounded.matchedElementText.take(32))
                                        put("overlap_score", grounded.overlapScore.toDouble())
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "visual_objects",
                        JSONArray().apply {
                            visualContext.visualObjects.take(pipelineCompaction.visualObjectLimit).forEach { visualObject ->
                                put(
                                    JSONObject().apply {
                                        put("id", visualObject.id)
                                        put("type", visualObject.type)
                                        put("label", visualObject.label.take(36))
                                        put("bounds", visualObject.bounds)
                                        put("confidence", visualObject.confidence.toDouble())
                                        put("source", visualObject.source)
                                        put("candidate_tier", visualObject.candidateTier)
                                        put("role_hint", visualObject.roleHint)
                                        put("context_hints", JSONArray(visualObject.contextHints.take(4)))
                                        put("descriptor_tokens", JSONArray(visualObject.descriptorTokens.take(6)))
                                        put("text_free", visualObject.textFree)
                                        put("tap_pattern", visualObject.tapPattern)
                                        put("tap_hotspot", JSONArray().put(visualObject.tapHotspotX.toDouble()).put(visualObject.tapHotspotY.toDouble()))
                                        put("interaction_regions", visualObject.interactionRegions.size)
                                        put("visual_signature", visualObject.visualSignature.take(48))
                                        put("spatial_signature", visualObject.spatialSignature.take(36))
                                        put("matched_element_id", visualObject.matchedElementId)
                                        put("matched_container_id", visualObject.matchedContainerId)
                                        put("a11y_unique_id", visualObject.accessibilityUniqueId)
                                        put("a11y_label", visualObject.accessibilityLabel.take(24))
                                        put("collection_position", visualObject.collectionPosition)
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "parser_regions",
                        JSONArray().apply {
                            visualContext.parserRegions.take(pipelineCompaction.parserRegionLimit).forEach { region ->
                                put(
                                    JSONObject().apply {
                                        put("id", region.id)
                                        put("type", region.type)
                                        put("label", region.label.take(36))
                                        put("bounds", region.bounds)
                                        put("confidence", region.confidence.toDouble())
                                        put("source", region.source)
                                        put("parser_layer", region.parserLayer)
                                        put("search_tier", region.searchTier)
                                        put("role_hint", region.roleHint)
                                        put("context_hints", JSONArray(region.contextHints.take(4)))
                                        put("descriptor_tokens", JSONArray(region.descriptorTokens.take(8)))
                                        put("text_free", region.textFree)
                                        put("tap_pattern", region.tapPattern)
                                        put("tap_hotspot", JSONArray().put(region.tapHotspotX.toDouble()).put(region.tapHotspotY.toDouble()))
                                        put("interaction_regions", region.interactionRegions.size)
                                        put("visual_signature", region.visualSignature.take(48))
                                        put("spatial_signature", region.spatialSignature.take(36))
                                        put("matched_element_ids", JSONArray(region.matchedElementIds.take(6)))
                                        put("matched_container_ids", JSONArray(region.matchedContainerIds.take(4)))
                                        put("a11y_unique_ids", JSONArray(region.accessibilityUniqueIds.take(4)))
                                        put("a11y_labels", JSONArray(region.accessibilityLabels.take(4)))
                                    },
                                )
                            }
                        },
                    )
                },
            )
            put(
                "artifact_hints",
                JSONArray().apply {
                    compactContext.compactArtifactHints.forEach { hint ->
                        put(
                            JSONObject().apply {
                                put("artifact_id", hint.artifactId)
                                put("type", hint.type)
                                put("summary", hint.summary.take(160))
                            },
                        )
                    }
                },
            )
            put(
                "memory_context",
                JSONObject().apply {
                    compactContext.memoryBuckets.forEach { (key, values) ->
                        put(key, JSONArray(values))
                    }
                },
            )
            put(
                "active_skills",
                JSONArray().apply {
                    compactContext.activeSkills.forEach { skill ->
                        put(
                            JSONObject().apply {
                                put("id", skill.id)
                                put("title", skill.title)
                                put("description", skill.description.take(72))
                                put("instructions", skill.instructions.take(120))
                                put("layer", skill.layer.name.lowercase())
                                put("risk_level", skill.riskLevel.name.lowercase())
                                put("required_tools", JSONArray(skill.requiredTools))
                                put("parameters", JSONArray(skill.parameters.take(4)))
                            },
                        )
                    }
                },
            )
            put(
                "elements",
                JSONArray().apply {
                    screenSafetyProjection.elements.forEach { element ->
                        put(
                            JSONObject().apply {
                                put("id", element.id)
                                put("text", element.text.take(36))
                                put("view_id", element.viewId)
                                put("class", element.className)
                                put("bounds", element.bounds)
                                put("clickable", element.clickable)
                                put("editable", element.editable)
                                put("scrollable", element.scrollable)
                                put("enabled", element.enabled)
                                put("focused", element.focused)
                                put("selected", element.selected)
                                put("parent_id", element.parentId)
                                put("container_id", element.containerId)
                                put("depth", element.depth)
                                put("row_index", element.rowIndex)
                                put("column_index", element.columnIndex)
                                put("collection_position", element.collectionPosition)
                                put("a11y_label", element.accessibilityLabel.take(36))
                                put("a11y_unique_id", element.accessibilityUniqueId)
                                put("visual_signature", element.visualSignature.take(48))
                                put("spatial_signature", element.spatialSignature.take(36))
                                put("container_title", element.containerTitle.take(24))
                                put("pane_title", element.paneTitle.take(24))
                                put("state_description", element.stateDescription.take(24))
                                put("descriptor_tokens", JSONArray(element.descriptorTokens.take(6)))
                                put("visual_descriptor_tokens", JSONArray(element.visualDescriptorTokens.take(6)))
                                put("interaction_regions", element.interactionRegions.size)
                                put("role_hint", element.roleHint)
                                put("neighbor_texts", JSONArray(element.neighborTexts))
                                put("source", element.source)
                            },
                        )
                    }
                },
            )
            put(
                "recent_history",
                JSONArray().apply {
                    compactContext.recentHistory.forEach { turn ->
                        put(
                            JSONObject().apply {
                                put("observation_signature", turn.observationSignature)
                                put("page_state", turn.pageState)
                                put("action", turn.action)
                                put("result", turn.result)
                                put("recovery_category", turn.recoveryCategory)
                                put("recovery_summary", turn.recoverySummary)
                                put("suggested_recovery_action", turn.suggestedRecoveryAction)
                            },
                        )
                    }
                },
            )
            put("history_digest", JSONArray(compactContext.historyDigest))
        }
        return observationJson.toString()
    }

    private fun buildUserMessage(
        prompt: String,
        screenshot: ScreenshotPayload?,
    ): JSONObject {
        return JSONObject().apply {
            put("role", "user")
            if (screenshot == null) {
                put("content", prompt)
            } else {
                put(
                    "content",
                    JSONArray()
                        .put(
                            JSONObject().apply {
                                put("type", "text")
                                put("text", prompt)
                            },
                        )
                        .put(
                            JSONObject().apply {
                                put("type", "image_url")
                                        put(
                                            "image_url",
                                            JSONObject().apply {
                                                put("url", "data:${screenshot.mimeType};base64,${screenshot.base64Data}")
                                            },
                                        )
                            },
                        ),
                )
            }
        }
    }

    internal fun debugBuildUserPrompt(
        task: String,
        observation: ScreenObservation,
        history: List<AgentTurnRecord>,
        artifactHints: List<PlannerArtifactHint>,
        memoryContext: PlanningMemoryContext,
        activeSkills: List<SkillContext>,
        taskPlanState: TaskPlanState,
        visualContext: VisualPerceptionContext,
        targetPackageName: String,
    ): String =
        buildUserPrompt(
            task,
            observation,
            history,
            artifactHints,
            memoryContext,
            activeSkills,
            taskPlanState,
            visualContext,
            targetPackageName,
        )

    private fun compactPlanningMemoryContext(
        memoryContext: PlanningMemoryContext,
        profile: PlannerPromptProfile,
    ): PlanningMemoryContext {
        val profileSignals =
            listOfNotNull(
                "profile=${profile.label}",
                profile.runtimeMode.takeIf { it.isNotBlank() }?.let { "mode=$it" },
                "target_budget=${profile.targetBudget}",
            )
        return memoryContext.copy(
            shortTermNotes = memoryContext.shortTermNotes.takeLast(profile.memoryLineLimit),
            longTermMemories = memoryContext.longTermMemories.takeLast(profile.memoryLineLimit),
            userPreferences = memoryContext.userPreferences.takeLast(profile.memoryLineLimit),
            retrievalMemories = memoryContext.retrievalMemories.takeLast(profile.memoryLineLimit),
            resultArtifacts = memoryContext.resultArtifacts.takeLast(profile.memoryLineLimit),
            correctionTemplates = memoryContext.correctionTemplates.takeLast(profile.memoryLineLimit),
            contacts = memoryContext.contacts.takeLast(profile.memoryLineLimit),
            locations = memoryContext.locations.takeLast(profile.memoryLineLimit),
            appPreferences = memoryContext.appPreferences.takeLast(profile.memoryLineLimit),
            safetyRules = memoryContext.safetyRules.takeLast(profile.memoryLineLimit),
            sessionMemories = memoryContext.sessionMemories.takeLast(profile.memoryLineLimit),
            turnLoopSideband = memoryContext.turnLoopSideband.takeLast(profile.signalLineLimit),
            sessionNotebook = memoryContext.sessionNotebook.takeLast(profile.notebookLineLimit),
            loopInboxAttachments = memoryContext.loopInboxAttachments.takeLast(profile.signalLineLimit),
            loopRuntimeSignals = memoryContext.loopRuntimeSignals.takeLast(profile.signalLineLimit),
            loopRuntimeDrain = memoryContext.loopRuntimeDrain.takeLast(profile.signalLineLimit),
            loopRuntimePrefetch = memoryContext.loopRuntimePrefetch.takeLast(profile.signalLineLimit),
            loopRuntimeTaskSummary = memoryContext.loopRuntimeTaskSummary.takeLast(profile.signalLineLimit),
            skillPrefetchSignals = memoryContext.skillPrefetchSignals.takeLast(profile.signalLineLimit),
            toolRefreshSignals = memoryContext.toolRefreshSignals.takeLast(profile.signalLineLimit),
            permissionProductSignals = memoryContext.permissionProductSignals.takeLast(profile.signalLineLimit),
            memoryForkSignals = memoryContext.memoryForkSignals.takeLast(profile.signalLineLimit),
            toolRuntimeContracts = memoryContext.toolRuntimeContracts.takeLast(profile.toolContractLimit),
            compactionSignals = (memoryContext.compactionSignals + profileSignals).takeLast(profile.signalLineLimit + profileSignals.size),
            turnAttachments = memoryContext.turnAttachments.takeLast(profile.attachmentLimit),
        )
    }

    private fun estimatePromptTokens(
        chars: Int,
    ): Int = (chars / 4.0).toInt() + 1

    private fun shouldRetryWithMoreCompaction(
        error: Throwable,
    ): Boolean {
        val message = error.message.orEmpty().lowercase()
        return listOf(
            "maximum context length",
            "context length",
            "too many tokens",
            "prompt is too long",
            "context window",
            "413",
        ).any { marker -> message.contains(marker) }
    }

    internal fun debugResolvePlannerRequestStrategy(
        observation: ScreenObservation,
        visualContext: VisualPerceptionContext,
        screenshot: ScreenshotPayload? = null,
    ): PlannerRequestStrategy = resolvePlannerRequestStrategy(observation, visualContext, screenshot)

    internal fun debugMemoryContextPayload(
        memoryContext: PlanningMemoryContext,
    ): Map<String, List<String>> = memoryContextPayload(memoryContext)

    internal fun debugResumeContextPayload(
        resumeContext: ResumeContext,
    ): Map<String, Any> = resumeContextPayload(resumeContext)

    private fun memoryContextPayload(
        memoryContext: PlanningMemoryContext,
    ): Map<String, List<String>> =
        linkedMapOf(
            "short_term_notes" to memoryContext.shortTermNotes,
            "long_term_memories" to memoryContext.longTermMemories,
            "user_preferences" to memoryContext.userPreferences,
            "retrieval_memories" to memoryContext.retrievalMemories,
            "result_artifacts" to memoryContext.resultArtifacts,
            "correction_templates" to memoryContext.correctionTemplates,
            "contacts" to memoryContext.contacts,
            "locations" to memoryContext.locations,
            "app_preferences" to memoryContext.appPreferences,
            "safety_rules" to memoryContext.safetyRules,
            "session_memories" to memoryContext.sessionMemories,
            "turn_loop_sideband" to memoryContext.turnLoopSideband,
            "session_notebook" to memoryContext.sessionNotebook,
            "turn_attachments" to memoryContext.turnAttachments.map { attachment ->
                buildString {
                    append(attachment.source)
                    append(" | ").append(attachment.type)
                    append(" | ").append(attachment.title.ifBlank { attachment.attachmentId }.take(48))
                    attachment.summary.takeIf { it.isNotBlank() }?.let { append(" | ").append(it.take(96)) }
                }
            },
            "loop_inbox_attachments" to memoryContext.loopInboxAttachments,
            "loop_runtime_signals" to memoryContext.loopRuntimeSignals,
            "loop_runtime_drain" to memoryContext.loopRuntimeDrain,
            "loop_runtime_prefetch" to memoryContext.loopRuntimePrefetch,
            "loop_runtime_task_summary" to memoryContext.loopRuntimeTaskSummary,
            "skill_prefetch_signals" to memoryContext.skillPrefetchSignals,
            "tool_refresh_signals" to memoryContext.toolRefreshSignals,
            "permission_product_signals" to memoryContext.permissionProductSignals,
            "memory_fork_signals" to memoryContext.memoryForkSignals,
            "tool_runtime_contracts" to memoryContext.toolRuntimeContracts,
            "runtime_compaction_signals" to memoryContext.compactionSignals,
        )

    private fun shouldAttachScreenshot(
        observation: ScreenObservation,
        visualContext: VisualPerceptionContext,
        screenshot: ScreenshotPayload?,
    ): Boolean {
        if (screenshot == null) return false
        if (observation.elements.size <= 8) return true
        if (visualContext.groundedTexts.isNotEmpty()) return true
        return visualContext.visualHints.isNotEmpty() && observation.elements.size <= 14
    }

    private fun resolvePlannerRequestStrategy(
        observation: ScreenObservation,
        visualContext: VisualPerceptionContext,
        screenshot: ScreenshotPayload?,
    ): PlannerRequestStrategy {
        val primaryModel = BuildConfig.AGENT_MODEL.ifBlank { BuildConfig.AGENT_ROUTE_MODEL }
        val fastModel = BuildConfig.AGENT_ROUTE_MODEL.ifBlank { primaryModel }
        val screenshotEligible = shouldAttachScreenshot(observation, visualContext, screenshot)
        val weakStructure =
            observation.elements.size <= 10 &&
                observation.primaryEditableId.isNullOrBlank() &&
                observation.defaultScrollableId.isNullOrBlank() &&
                observation.interruptiveHints.isEmpty()
        val visualHeavy =
            visualContext.captureAvailable &&
                (
                    visualContext.ocrTexts.isNotEmpty() ||
                        visualContext.groundedTexts.isNotEmpty() ||
                        visualContext.visualHints.isNotEmpty()
                )
        val shouldPreferFastPrimary =
            fastModel.isNotBlank() &&
                fastModel != primaryModel &&
                weakStructure &&
                visualHeavy

        return if (shouldPreferFastPrimary) {
            PlannerRequestStrategy(
                primaryModel = fastModel,
                fallbackModel = primaryModel.takeIf { it.isNotBlank() && it != fastModel },
                attachScreenshotOnPrimary = false,
                attachScreenshotOnFallback = screenshotEligible,
                policyTag = "weak_visual_fast_primary",
                primaryConnectTimeoutMs = min(BuildConfig.AGENT_ROUTE_CONNECT_TIMEOUT_MS, FAST_PRIMARY_CONNECT_TIMEOUT_MS),
                primaryReadTimeoutMs = min(BuildConfig.AGENT_ROUTE_READ_TIMEOUT_MS, FAST_PRIMARY_READ_TIMEOUT_MS),
                fallbackConnectTimeoutMs = min(BuildConfig.AGENT_PLANNER_CONNECT_TIMEOUT_MS, HEAVY_FALLBACK_CONNECT_TIMEOUT_MS),
                fallbackReadTimeoutMs = min(BuildConfig.AGENT_PLANNER_READ_TIMEOUT_MS, HEAVY_FALLBACK_READ_TIMEOUT_MS),
            )
        } else {
            PlannerRequestStrategy(
                primaryModel = primaryModel,
                fallbackModel = fastModel.takeIf { it.isNotBlank() && it != primaryModel },
                attachScreenshotOnPrimary = screenshotEligible,
                attachScreenshotOnFallback = false,
                policyTag = "default_primary",
                primaryConnectTimeoutMs = BuildConfig.AGENT_PLANNER_CONNECT_TIMEOUT_MS,
                primaryReadTimeoutMs = BuildConfig.AGENT_PLANNER_READ_TIMEOUT_MS,
                fallbackConnectTimeoutMs = BuildConfig.AGENT_ROUTE_CONNECT_TIMEOUT_MS,
                fallbackReadTimeoutMs = BuildConfig.AGENT_ROUTE_READ_TIMEOUT_MS,
            )
        }
    }

    private fun resumeContextPayload(
        resumeContext: ResumeContext,
    ): Map<String, Any> =
        linkedMapOf(
            "active" to resumeContext.active,
            "source" to resumeContext.source,
            "resume_event" to resumeContext.resumeEvent,
            "resume_hint" to resumeContext.resumeHint,
            "resumed_subgoal_id" to resumeContext.resumedSubgoalId,
            "resume_attempt" to resumeContext.resumeAttempt,
            "user_correction" to resumeContext.userCorrection,
        )

    private fun buildTaskStackJson(stack: List<TaskStackFrame>): JSONArray =
        JSONArray().apply {
            stack.forEach { frame ->
                put(
                    JSONObject().apply {
                        put("id", frame.id)
                        put("title", frame.title)
                        put("status", frame.status)
                        put("resume_hint", frame.resumeHint)
                        put("frame_type", frame.frameType)
                        put("depth", frame.depth)
                        put("active", frame.active)
                        put("blocking_reason", frame.blockingReason)
                        put("child_ids", JSONArray(frame.childIds))
                    },
                )
            }
        }

    private fun buildTaskGraphJson(nodes: List<TaskGraphNode>): JSONArray =
        JSONArray().apply {
            nodes.forEach { node ->
                put(taskGraphNodeJson(node))
            }
        }

    private fun buildStageContract(taskPlanState: TaskPlanState): JSONObject =
        JSONObject().apply {
            put("plan_type", taskPlanState.planType)
            put("current_stage", taskPlanState.currentStage)
            put(
                "must_do",
                JSONArray(
                    when (taskPlanState.currentStage) {
                        "enter_query", "enter_destination", "find_conversation" ->
                            listOf("优先完成目标输入或定位", "只做能推进入口阶段的最小动作")

                        "submit_query" ->
                            listOf("优先触发搜索或提交", "等待结果页或下一阶段出现")

                        "browse_candidates" ->
                            listOf("优先打开与任务最相关的候选对象", "避免点击元控件或无关入口")

                        "inspect_information" ->
                            listOf("优先进入评论/评价/参数/正文等证据入口", "补齐回答任务所需信息")

                        "confirm_route", "confirm_send" ->
                            listOf("优先确认关键动作", "先纠偏再继续")

                        "summarize" ->
                            listOf("基于已有证据收口", "只在满足完成信号时结束")

                        else ->
                            listOf("根据当前页面做最小推进动作")
                    },
                ),
            )
            put(
                "avoid",
                JSONArray(
                    when (taskPlanState.currentStage) {
                        "enter_query", "enter_destination", "find_conversation" ->
                            listOf("不要提前 finish", "不要跳过目标定位直接进入深层动作")

                        "browse_candidates" ->
                            listOf("不要点综合/筛选/更多/频道/首页等元控件", "不要反复点击刚失败的候选")

                        "inspect_information" ->
                            listOf("不要在缺证据时直接总结", "不要重复无关滚动")

                        "confirm_route", "confirm_send" ->
                            listOf("不要重新开始整个任务", "不要忽略当前确认入口")

                        "summarize" ->
                            listOf("不要重新搜索整个任务", "不要跳回无关入口")

                        else ->
                            listOf("不要重复同一失败动作")
                    },
                ),
            )
        }

    private fun taskGraphNodeJson(node: TaskGraphNode): JSONObject =
        JSONObject().apply {
            put("id", node.id)
            put("title", node.title)
            put("status", node.status)
            put("summary", node.summary)
            put("kind", node.kind)
            put("lane", node.lane)
            put("dependencies", JSONArray(node.dependencies))
            put("blockers", JSONArray(node.blockers))
            put("capability_tags", JSONArray(node.capabilityTags))
            put("children", buildTaskGraphJson(node.children))
        }

    private fun parseDecision(
        rawContent: String,
        observation: ScreenObservation,
        targetPackageName: String,
    ): AgentDecision {
        val cleanJson = extractJson(rawContent)
        val json = JSONObject(cleanJson)
        val reason = json.optString("reason").ifBlank { "模型未提供原因。" }
        val actionName = json.getString("action")
        val knownElementIds = observation.elements.map { it.id }.toSet()
        val action = when (actionName) {
            "launch_app" -> AgentAction.LaunchApp(json.getString("package_name"))
            "click" -> {
                val elementId = json.getString("element_id")
                val targetText = json.optString("target_text")
                val roleHint = json.optString("role_hint")
                if (elementId !in knownElementIds) {
                    if (targetText.isNotBlank() || roleHint.isNotBlank()) {
                        AgentAction.OpenBestCandidate(
                            targetText = targetText,
                            roleHint = roleHint,
                        )
                    } else {
                        resolveFallbackClick(
                            targetText = targetText,
                            reason = reason,
                            observation = observation,
                            knownElementIds = knownElementIds,
                        )
                    }
                } else {
                    if (targetText.isNotBlank() || roleHint.isNotBlank()) {
                        val fallbackTarget =
                            targetText.ifBlank {
                                observation.elements.firstOrNull { it.id == elementId }?.text.orEmpty()
                            }
                        AgentAction.OpenBestCandidate(
                            targetText = fallbackTarget,
                            roleHint = roleHint,
                        )
                    } else {
                        AgentAction.Click(elementId)
                    }
                }
            }

            "set_text" -> {
                val requestedId = json.optString("element_id").ifBlank {
                    observation.primaryEditableId.orEmpty()
                }
                val resolvedId =
                    when {
                        requestedId in knownElementIds -> requestedId
                        !observation.primaryEditableId.isNullOrBlank() -> observation.primaryEditableId
                        else -> ""
                    }
                if (resolvedId.isBlank()) {
                    AgentAction.Wait
                } else {
                    if (requestedId.isBlank() || resolvedId == observation.primaryEditableId) {
                        AgentAction.PopulatePrimaryInput(json.getString("text"))
                    } else {
                        AgentAction.SetText(
                            elementId = resolvedId,
                            text = json.getString("text"),
                        )
                    }
                }
            }

            "scroll" -> {
                val direction = json.optString("direction", "down")
                val resolvedElementId =
                    json.optString("element_id").ifBlank {
                        observation.defaultScrollableId.orEmpty()
                    }.takeIf { it.isNotBlank() && it in knownElementIds }
                if (resolvedElementId.isNullOrBlank() || resolvedElementId == observation.defaultScrollableId) {
                    AgentAction.ScrollForMore(direction)
                } else {
                    AgentAction.Scroll(
                        elementId = resolvedElementId,
                        direction = direction,
                    )
                }
            }

            "long_click" -> {
                val elementId = json.getString("element_id")
                if (elementId !in knownElementIds) AgentAction.Wait else AgentAction.LongClick(elementId)
            }

            "copy_text" -> {
                val elementId = json.getString("element_id")
                if (elementId !in knownElementIds) AgentAction.Wait else AgentAction.CopyText(elementId)
            }

            "paste_clipboard" -> {
                val elementId = json.optString("element_id").ifBlank {
                    observation.primaryEditableId.orEmpty()
                }
                if (elementId !in knownElementIds) AgentAction.Wait else AgentAction.PasteClipboard(elementId)
            }

            "read_session_history" -> AgentAction.ReadSessionHistory(json.optString("query"))
            "search_artifacts" ->
                AgentAction.SearchArtifacts(
                    query = json.optString("query"),
                    artifactType = json.optString("artifact_type"),
                )
            "recall_memory" -> AgentAction.RecallMemory(json.optString("query"))
            "read_session_notebook" -> AgentAction.ReadSessionNotebook
            "write_session_note" ->
                AgentAction.WriteSessionNote(
                    note = json.optString("note"),
                    tag = json.optString("tag"),
                )
            "search_tools" -> AgentAction.SearchTools(json.optString("query"))
            "web_search" -> AgentAction.WebSearch(json.optString("query"))
            "web_fetch" -> AgentAction.WebFetch(json.optString("url"))
            "read_connected_app_capabilities" -> AgentAction.ReadConnectedAppCapabilities(json.optString("app_id"))
            "execute_connected_app_action" ->
                AgentAction.ExecuteConnectedAppAction(
                    appId = json.optString("app_id"),
                    operation = json.optString("operation"),
                    primary = json.optString("primary"),
                    secondary = json.optString("secondary"),
                    note = json.optString("note"),
                )
            "read_todo_board" -> AgentAction.ReadTodoBoard
            "write_todo_board" ->
                AgentAction.WriteTodoBoard(
                    content = json.optString("content"),
                    mode = json.optString("mode", "append"),
                )
            "delegate_local_agent" ->
                AgentAction.DelegateLocalAgent(
                    task = json.optString("task"),
                    summary = json.optString("summary"),
                    role = json.optString("role").ifBlank { "general" },
                )
            "read_worker_roles" -> AgentAction.ReadWorkerRoles
            "read_worker_mailbox" ->
                AgentAction.ReadWorkerMailbox(
                    target = json.optString("target"),
                    includeConsumed = json.optBoolean("include_consumed", false),
                    limit = json.optInt("limit", 12),
                )
            "reply_worker_message" ->
                AgentAction.ReplyWorkerMessage(
                    messageId = json.optString("message_id"),
                    decision = json.optString("decision"),
                    note = json.optString("note"),
                )
            "ack_worker_message" ->
                AgentAction.AcknowledgeWorkerMessage(
                    messageId = json.optString("message_id"),
                    note = json.optString("note"),
                )
            "read_session_memory_file" -> AgentAction.ReadSessionMemoryFile
            "read_worker_status" ->
                AgentAction.ReadWorkerStatus(
                    target = json.optString("target"),
                    includeChildren = json.optBoolean("include_children", true),
                )
            "merge_worker_result" ->
                AgentAction.MergeWorkerResult(
                    messageId = json.optString("message_id"),
                    note = json.optString("note"),
                )
            "press_enter" -> AgentAction.PressEnter
            "focus_primary_input" -> AgentAction.FocusPrimaryInput
            "submit_primary_action" -> AgentAction.SubmitPrimaryAction(json.optString("hint"))
            "dismiss_interrupt" -> AgentAction.DismissInterrupt(json.optString("hint"))
            "open_best_candidate" ->
                AgentAction.OpenBestCandidate(
                    targetText = json.optString("target_text"),
                    roleHint = json.optString("role_hint"),
                )

            "scroll_for_more" -> AgentAction.ScrollForMore(json.optString("direction", "down"))
            "return_to_target_app" ->
                AgentAction.ReturnToTargetApp(
                    json.optString("package_name").ifBlank { targetPackageName },
                )

            "tap_point" -> AgentAction.TapPoint(
                x = json.getInt("x"),
                y = json.getInt("y"),
            )

            "swipe" -> AgentAction.Swipe(
                startX = json.getInt("start_x"),
                startY = json.getInt("start_y"),
                endX = json.getInt("end_x"),
                endY = json.getInt("end_y"),
                durationMs = json.optLong("duration_ms", 350L),
            )

            "back" -> AgentAction.NavigateBack(hint = json.optString("hint"))
            "home" -> AgentAction.Home
            "notifications" -> AgentAction.Notifications
            "quick_settings" -> AgentAction.QuickSettings
            "recents" -> AgentAction.Recents
            "open_settings" -> AgentAction.OpenSettings(json.optString("destination"))
            "share_text" -> AgentAction.ShareText(json.optString("text"))
            "create_alarm" ->
                AgentAction.CreateAlarm(
                    timeLabel = json.optString("time"),
                    message = json.optString("message"),
                )
            "create_timer" ->
                AgentAction.CreateTimer(
                    durationLabel = json.optString("duration"),
                    message = json.optString("message"),
                )
            "open_stopwatch" -> AgentAction.OpenStopwatch
            "insert_calendar_event" ->
                AgentAction.InsertCalendarEvent(
                    title = json.optString("title"),
                    details = json.optString("details"),
                    whenLabel = json.optString("when_label"),
                )
            "dial_number" ->
                AgentAction.DialNumber(
                    number = json.optString("number"),
                    contactName = json.optString("contact_name"),
                )
            "draft_sms" ->
                AgentAction.DraftSms(
                    recipient = json.optString("recipient"),
                    body = json.optString("body"),
                )
            "read_notifications" ->
                AgentAction.ReadNotifications(
                    packageName = json.optString("package_name"),
                    limit = json.optInt("limit", 6),
                )
            "reply_notification" ->
                AgentAction.ReplyNotification(
                    notificationKey = json.optString("notification_key"),
                    replyText = json.optString("reply_text"),
                    actionHint = json.optString("action_hint"),
                )
            "media_control" -> AgentAction.MediaControl(json.optString("command"))
            "adjust_volume" ->
                AgentAction.AdjustVolume(
                    direction = json.optString("direction", "raise"),
                    stream = json.optString("stream", "music"),
                    step = json.optInt("step", 1),
                )
            "open_device_panel" -> AgentAction.OpenDevicePanel(json.optString("panel"))
            "read_device_status" -> AgentAction.ReadDeviceStatus(json.optString("target"))
            "set_brightness" -> AgentAction.SetBrightness(json.optString("level"))
            "set_do_not_disturb" -> AgentAction.SetDoNotDisturb(json.optString("mode", "on"))
            "set_battery_saver" -> AgentAction.SetBatterySaver(json.optString("mode", "on"))
            "open_power_dialog" -> AgentAction.OpenPowerDialog
            "capture_screenshot" -> AgentAction.CaptureScreenshot(json.optString("note"))
            "capture_photo" -> AgentAction.CapturePhoto(json.optString("note"))
            "wait" -> AgentAction.Wait
            "finish" -> AgentAction.Finish(json.optString("summary", reason))
            "fail" -> AgentAction.Fail(reason)
            else -> error("无法解析 action=${json.optString("action")}")
        }
        return AgentDecision(
            action = action,
            reason = reason,
            rawResponse = cleanJson,
        )
    }

    private fun resolveFallbackClick(
        targetText: String,
        reason: String,
        observation: ScreenObservation,
        knownElementIds: Set<String>,
    ): AgentAction {
        val fallbackKeywords =
            (
                listOf(targetText) +
                    extractQuotedTargets(reason) +
                    extractSemanticTargets(reason) +
                    when {
                        reason.contains("搜索") || reason.contains("查找") || reason.contains("放大镜") ->
                            listOf("搜索", "查找", "放大镜", "联系人", "添加朋友")
                        reason.contains("返回") -> listOf("返回")
                        else -> emptyList()
                    }
                )
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        if (fallbackKeywords.isEmpty()) return AgentAction.Wait
        val matched =
            observation.elements
                .asSequence()
                .filter { it.id in knownElementIds && it.clickable && it.enabled }
                .mapNotNull { element ->
                    val semantic = listOf(element.text, element.viewId, element.className).joinToString(" ")
                    val score =
                        fallbackKeywords.fold(0) { acc, keyword ->
                            acc + when {
                                element.text.equals(keyword, ignoreCase = true) -> 120 + keyword.length
                                element.text.contains(keyword, ignoreCase = true) -> 80 + keyword.length
                                semantic.contains(keyword, ignoreCase = true) -> 36 + keyword.length
                                else -> 0
                            }
                        }
                    if (score <= 0) null else element.id to score
                }
                .sortedByDescending { it.second }
                .firstOrNull()
        return if (matched != null) AgentAction.Click(matched.first) else AgentAction.Wait
    }

    internal fun debugResolveFallbackClick(
        targetText: String = "",
        reason: String,
        observation: ScreenObservation,
    ): AgentAction =
        resolveFallbackClick(
            targetText = targetText,
            reason = reason,
            observation = observation,
            knownElementIds = observation.elements.map { it.id }.toSet(),
        )

    private fun extractQuotedTargets(
        text: String,
    ): List<String> {
        if (text.isBlank()) return emptyList()
        val patterns =
            listOf(
                Regex("[“\"]([^”\"]{1,24})[”\"]"),
                Regex("‘([^’]{1,24})’"),
                Regex("'([^']{1,24})'"),
            )
        return patterns
            .flatMap { pattern ->
                pattern.findAll(text).mapNotNull { match ->
                    match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                }.toList()
            }
            .distinct()
    }

    private fun extractSemanticTargets(
        text: String,
    ): List<String> {
        if (text.isBlank()) return emptyList()
        val patterns =
            listOf(
                Regex("(?:目标联系人|联系人|会话|收件人|用户|对象|目的地|地点|地址|搜索词|关键词|商品|视频|文章|评论区|评价区|参数页|详情页)[：: ]*([\\p{L}\\p{N}_·\\-]{1,24})"),
                Regex("对应([\\p{L}\\p{N}_·\\-]{1,24})"),
            )
        return patterns
            .flatMap { pattern ->
                pattern.findAll(text).mapNotNull { match ->
                    match.groupValues.getOrNull(1)?.trim()?.takeIf { candidate ->
                        candidate.isNotBlank() &&
                            candidate.none { it in listOf('，', '。', ',', '.', '、', '：', ':') }
                    }
                }.toList()
            }
            .distinct()
    }

    private fun extractJson(rawContent: String): String {
        val trimmed = rawContent.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }

        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1)
        }
        error("模型返回的内容不是 JSON: $rawContent")
    }

    private fun readConnectionText(
        connection: HttpURLConnection,
        success: Boolean,
    ): String {
        val stream = if (success) connection.inputStream else connection.errorStream
        return stream.bufferedReader().use(BufferedReader::readText)
    }
}
