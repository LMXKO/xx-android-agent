package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.BuildConfig
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object TaskResultExtractor {
    fun extract(
        task: String,
        observation: ScreenObservation,
        taskPlanState: TaskPlanState?,
    ): TaskResultPayload? {
        val constraints = TaskIntentParser.parse(task)
        val semantics = observationSemantics(observation)
        return when (resolveResultFamily(constraints, taskPlanState)) {
            ResultFamily.SHOPPING -> extractShoppingResult(constraints, semantics, taskPlanState)
            ResultFamily.NAVIGATION -> extractNavigationResult(constraints, semantics, taskPlanState)
            ResultFamily.MESSAGING -> extractMessagingResult(constraints, semantics, taskPlanState)
            ResultFamily.CONTENT -> extractContentResult(constraints, semantics, taskPlanState)
            ResultFamily.GENERIC -> extractGenericResult(constraints, semantics, taskPlanState)
        }
    }

    /**
     * LLM 化结果抽取：把屏幕上的文字证据交给模型，让它从结构化字段（title / price / fields / highlights）
     * 角度抽取业务结果。失败安全回落到启发式 [extract]。供 leg 完成钩子优先调用。
     *
     * 把 LLM 用在这里收益最高：屏幕上"价格"附近常有"优惠后/到手价/划线价/运费"多种价位，
     * 关键词正则刮取容易把折扣价当主价；模型能挑对那个真正属于商品本身的主价格。
     */
    suspend fun extractSmart(
        task: String,
        observation: ScreenObservation,
        taskPlanState: TaskPlanState?,
    ): TaskResultPayload? {
        val heuristic = extract(task, observation, taskPlanState)
        if (BuildConfig.AGENT_API_BASE_URL.isBlank() || BuildConfig.AGENT_MODEL.isBlank()) return heuristic
        val intentType = heuristic?.intentType.orEmpty().ifBlank { "general" }
        val llmContent =
            runCatching { callLlmExtract(task = task, intentType = intentType, observation = observation) }
                .getOrNull()
                ?: return heuristic
        return parseLlmExtraction(llmContent, intentType = intentType, fallback = heuristic) ?: heuristic
    }

    /** 纯解析:LLM 返回内容 → TaskResultPayload?(可纯 JVM 单测)。 */
    internal fun parseLlmExtraction(
        content: String,
        intentType: String,
        fallback: TaskResultPayload? = null,
    ): TaskResultPayload? {
        val json = runCatching { JSONObject(extractJsonObject(content)) }.getOrNull() ?: return null
        val title = json.optString("title").ifBlank { fallback?.title.orEmpty() }
        val summary = json.optString("summary").ifBlank { fallback?.summary.orEmpty() }
        if (title.isBlank() && summary.isBlank()) return null
        val highlights =
            json.optJSONArray("highlights")
                ?.let { arr ->
                    buildList {
                        for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
                ?.ifEmpty { fallback?.highlights }
                ?: fallback?.highlights
                ?: emptyList()
        val fieldsJson = json.optJSONArray("fields") ?: JSONArray()
        val fields =
            buildList {
                for (i in 0 until fieldsJson.length()) {
                    val obj = fieldsJson.optJSONObject(i) ?: continue
                    val key = obj.optString("key")
                    val value = obj.optString("value")
                    if (key.isBlank() || value.isBlank()) continue
                    val label = obj.optString("label").ifBlank { key }
                    add(TaskResultField(key = key, label = label, value = value))
                }
            }.ifEmpty { fallback?.fields ?: emptyList() }
        // 显式价格字段优先放进 highlights（让下一腿注入能看到价格证据）。
        val priceField = fields.firstOrNull { it.key.equals("price", ignoreCase = true) }
        val mergedHighlights =
            if (priceField != null && highlights.none { it.contains(priceField.value) }) {
                listOf(priceField.value) + highlights
            } else {
                highlights
            }.distinct().take(6)
        return TaskResultPayload(
            intentType = intentType,
            title = title.ifBlank { fallback?.title.orEmpty() },
            summary = summary.ifBlank { fallback?.summary.orEmpty() },
            highlights = mergedHighlights,
            fields = fields,
        )
    }

    private suspend fun callLlmExtract(
        task: String,
        intentType: String,
        observation: ScreenObservation,
    ): String? =
        withContext(Dispatchers.IO) {
            val payload =
                JSONObject().apply {
                    put("model", BuildConfig.AGENT_MODEL)
                    put("temperature", 0.0)
                    put("max_tokens", 400)
                    put(
                        "messages",
                        JSONArray()
                            .put(JSONObject().apply { put("role", "system"); put("content", llmExtractSystemPrompt()) })
                            .put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put("content", llmExtractUserPrompt(task, intentType, observation))
                                },
                            ),
                    )
                }
            val url = URL("${BuildConfig.AGENT_API_BASE_URL.trimEnd('/')}/chat/completions")
            val connection =
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = BuildConfig.AGENT_PLANNER_CONNECT_TIMEOUT_MS
                    readTimeout = BuildConfig.AGENT_PLANNER_READ_TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/json")
                    if (BuildConfig.AGENT_API_KEY.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer ${BuildConfig.AGENT_API_KEY}")
                    }
                }
            connection.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = connection.responseCode
            val body =
                (if (code in 200..299) connection.inputStream else connection.errorStream)
                    .bufferedReader()
                    .use(BufferedReader::readText)
            if (code !in 200..299) return@withContext null
            JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content")
        }

    private fun llmExtractSystemPrompt(): String =
        """
        你是手机 Agent 的"屏幕结果抽取器"。给定用户任务、当前页面文字片段，输出结构化业务结果。
        只输出一个 JSON 对象，不要 Markdown、不要解释。格式：
        {"title":"<最能代表当前页面对象的标题，例如商品名/路线/收件人/正文标题>",
         "summary":"<一两句话总结当前页面对任务的关键意义>",
         "highlights":["<最相关证据 1>","<最相关证据 2>"],
         "fields":[{"key":"price","label":"价格","value":"¥5999"},
                   {"key":"shipping","label":"运费","value":"包邮"}]}
        规则：
        - 商品类任务：title 必须是商品本身名称（不要附加店铺名/广告语），price 必须是真正"商品主价"，
          忽略划线原价、首单优惠后的"到手价"、运费、保险费、领券价；如无法明确判断主价，可省略。
        - 路线类任务：title 是目的地，fields 给 distance/duration。
        - 消息类任务：title 是收件人，fields 给 recipient。
        - 内容类任务：title 是内容标题，fields 给 author/likes 等。
        - 不能编造屏幕上没有的内容；信息缺失时该字段省略。
        - JSON 必须可被严格 parser 解析。
        """.trimIndent()

    private fun llmExtractUserPrompt(
        task: String,
        intentType: String,
        observation: ScreenObservation,
    ): String =
        JSONObject().apply {
            put("task", task)
            put("intent_type", intentType)
            put("page_state", observation.pageState)
            put("screen_summary", observation.screenSummary)
            put("top_texts", JSONArray(observation.topTexts.take(12)))
            put(
                "element_texts",
                JSONArray(
                    observation.elements
                        .asSequence()
                        .mapNotNull { it.text.takeIf(String::isNotBlank) }
                        .distinct()
                        .take(40)
                        .toList(),
                ),
            )
        }.toString()

    private fun extractJsonObject(raw: String): String {
        val unfenced =
            raw.trim()
                .removePrefix("```json").removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        if (unfenced.startsWith("{") && unfenced.endsWith("}")) return unfenced
        val start = unfenced.indexOf('{')
        val end = unfenced.lastIndexOf('}')
        return if (start >= 0 && end > start) unfenced.substring(start, end + 1) else "{}"
    }

    fun renderBriefSummary(payload: TaskResultPayload): String =
        buildString {
            append(payload.summary)
            if (payload.highlights.isNotEmpty()) {
                append(" | 证据: ").append(payload.highlights.take(3).joinToString(" / "))
            }
        }

    private fun extractShoppingResult(
        constraints: TaskConstraints,
        semantics: List<String>,
        taskPlanState: TaskPlanState?,
    ): TaskResultPayload =
        TaskResultPayload(
            intentType = "shopping",
            title = constraints.entryQuery.ifBlank { "商品调研结果" },
            summary =
                buildString {
                    append("已围绕 ").append(constraints.entryQuery.ifBlank { constraints.normalizedGoal })
                    append(" 完成商品信息收集")
                    constraints.objectiveHint?.takeIf { it.isNotBlank() }?.let {
                        append("，当前重点证据是 ").append(it)
                    }
                    if (taskPlanState?.currentStage == "summarize") {
                        append("，可进入结果整理阶段")
                    }
                },
            highlights =
                extractEvidenceSnippets(
                    semantics = semantics,
                    keywords = listOf("评价", "评论", "参数", "规格", "详情", "¥", "元"),
                ),
            fields =
                listOfNotNull(
                    constraints.entryQuery.takeIf { it.isNotBlank() }?.let { TaskResultField("query", "目标商品", it) },
                    constraints.objectiveHint?.takeIf { it.isNotBlank() }?.let { TaskResultField("objective", "关注点", it) },
                ),
        )

    private fun extractNavigationResult(
        constraints: TaskConstraints,
        semantics: List<String>,
        taskPlanState: TaskPlanState?,
    ): TaskResultPayload =
        TaskResultPayload(
            intentType = "navigation",
            title = constraints.destination?.ifBlank { constraints.entryQuery.ifBlank { "路线结果" } } ?: constraints.entryQuery.ifBlank { "路线结果" },
            summary =
                buildString {
                    append("已定位到 ")
                    append(constraints.destination?.ifBlank { constraints.entryQuery.ifBlank { "目标地点" } } ?: constraints.entryQuery.ifBlank { "目标地点" })
                    append(" 的路线信息")
                    constraints.objectiveHint?.takeIf { it.isNotBlank() }?.let {
                        append("，当前关注 ").append(it)
                    }
                    if (taskPlanState?.currentStage == "confirm_route") {
                        append("，已接近路线确认阶段")
                    }
                },
            highlights =
                extractEvidenceSnippets(
                    semantics = semantics,
                    keywords = listOf("路线", "导航", "到这去", "预计", "分钟", "公里"),
                ),
            fields =
                listOfNotNull(
                    constraints.destination?.takeIf { it.isNotBlank() }?.let { TaskResultField("destination", "目的地", it) },
                    constraints.objectiveHint?.takeIf { it.isNotBlank() }?.let { TaskResultField("objective", "输出目标", it) },
                ),
        )

    private fun extractMessagingResult(
        constraints: TaskConstraints,
        semantics: List<String>,
        taskPlanState: TaskPlanState?,
    ): TaskResultPayload =
        TaskResultPayload(
            intentType = "messaging",
            title = constraints.recipientName?.ifBlank { "通讯结果" } ?: "通讯结果",
            summary =
                buildString {
                    append("已定位通讯任务")
                    constraints.recipientName?.takeIf { it.isNotBlank() }?.let {
                        append("，目标联系人为 ").append(it)
                    }
                    constraints.messageBody?.takeIf { it.isNotBlank() }?.let {
                        append("，正文为 ").append(it)
                    }
                    if (taskPlanState?.currentStage == "confirm_send") {
                        append("，当前已到发送前确认阶段")
                    }
                },
            highlights =
                extractEvidenceSnippets(
                    semantics = semantics,
                    keywords = listOf("发送", "输入消息", "聊天", "联系人"),
                ),
            fields =
                listOfNotNull(
                    constraints.recipientName?.takeIf { it.isNotBlank() }?.let { TaskResultField("recipient", "联系人", it) },
                    constraints.messageBody?.takeIf { it.isNotBlank() }?.let { TaskResultField("message", "消息内容", it) },
                ),
        )

    private fun extractContentResult(
        constraints: TaskConstraints,
        semantics: List<String>,
        taskPlanState: TaskPlanState?,
    ): TaskResultPayload =
        TaskResultPayload(
            intentType = "content",
            title = constraints.entryQuery.ifBlank { constraints.normalizedGoal.ifBlank { "内容检索结果" } },
            summary =
                buildString {
                    append("已围绕 ")
                    append(constraints.entryQuery.ifBlank { constraints.normalizedGoal.ifBlank { "目标内容" } })
                    append(" 收集内容证据")
                    constraints.objectiveHint?.takeIf { it.isNotBlank() }?.let {
                        append("，重点为 ").append(it)
                    }
                    if (taskPlanState?.currentStage == "summarize") {
                        append("，已进入可收口阶段")
                    }
                },
            highlights =
                extractEvidenceSnippets(
                    semantics = semantics,
                    keywords = listOf("评论", "评论区", "热评", "高赞", "弹幕", "最新一期", "最近一期"),
                ),
            fields =
                listOfNotNull(
                    constraints.entryQuery.takeIf { it.isNotBlank() }?.let { TaskResultField("query", "目标内容", it) },
                    constraints.objectiveHint?.takeIf { it.isNotBlank() }?.let { TaskResultField("objective", "关注点", it) },
                ),
        )

    private fun extractGenericResult(
        constraints: TaskConstraints,
        semantics: List<String>,
        taskPlanState: TaskPlanState?,
    ): TaskResultPayload =
        TaskResultPayload(
            intentType = "generic",
            title = constraints.entryQuery.ifBlank { constraints.normalizedGoal.ifBlank { "任务结果" } },
            summary =
                buildString {
                    append("已完成任务相关页面收集")
                    if (taskPlanState?.currentStage == "summarize") {
                        append("，当前证据已足够收口")
                    }
                },
            highlights = extractEvidenceSnippets(semantics, constraints.keywordHints),
            fields =
                listOfNotNull(
                    constraints.entryQuery.takeIf { it.isNotBlank() }?.let { TaskResultField("goal", "目标", it) },
                    taskPlanState?.currentStage?.takeIf { it.isNotBlank() }?.let { TaskResultField("stage", "当前阶段", it) },
                ),
        )

    private fun observationSemantics(
        observation: ScreenObservation,
    ): List<String> =
        buildList {
            add(observation.pageState)
            add(observation.screenSummary)
            addAll(observation.topTexts)
            addAll(observation.elements.mapNotNull { it.text.takeIf(String::isNotBlank) })
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun extractEvidenceSnippets(
        semantics: List<String>,
        keywords: List<String>,
    ): List<String> {
        if (keywords.isEmpty()) return semantics.take(3)
        return semantics
            .filter { line ->
                keywords.any { keyword -> keyword.isNotBlank() && line.contains(keyword, ignoreCase = true) }
            }
            .take(4)
            .ifEmpty { semantics.take(3) }
    }

    private fun resolveResultFamily(
        constraints: TaskConstraints,
        taskPlanState: TaskPlanState?,
    ): ResultFamily =
        when (constraints.intentType) {
            TaskIntentType.SHOPPING -> ResultFamily.SHOPPING
            TaskIntentType.NAVIGATION -> ResultFamily.NAVIGATION
            TaskIntentType.MESSAGING -> ResultFamily.MESSAGING
            TaskIntentType.CONTENT -> ResultFamily.CONTENT
            TaskIntentType.GENERIC ->
                when (taskPlanState?.planType) {
                    "shopping_research" -> ResultFamily.SHOPPING
                    "navigation" -> ResultFamily.NAVIGATION
                    "messaging" -> ResultFamily.MESSAGING
                    "content_research" -> ResultFamily.CONTENT
                    else -> ResultFamily.GENERIC
                }
        }
}

private enum class ResultFamily {
    SHOPPING,
    NAVIGATION,
    MESSAGING,
    CONTENT,
    GENERIC,
}
