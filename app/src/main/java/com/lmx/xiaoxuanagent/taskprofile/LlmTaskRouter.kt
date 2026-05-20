package com.lmx.xiaoxuanagent.taskprofile

import android.util.Log
import com.lmx.xiaoxuanagent.BuildConfig
import com.lmx.xiaoxuanagent.memory.RouteMemoryHint
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class LlmRouteCandidate(
    val profileId: String,
    val displayName: String,
    val packageName: String,
    val capabilitySummary: String,
    val installed: Boolean,
    val aliases: List<String> = emptyList(),
    val semanticTags: List<String> = emptyList(),
    val source: String = "static",
    val category: String? = null,
)

data class LlmRouteChoice(
    val profileId: String?,
    val reason: String,
    val confidence: Double,
    val rawResponse: String,
)

object LlmTaskRouter {
    private const val TAG = "TbAgent"

    suspend fun route(
        task: String,
        candidates: List<LlmRouteCandidate>,
        routeMemoryHints: List<RouteMemoryHint> = emptyList(),
    ): LlmRouteChoice = withContext(Dispatchers.IO) {
        if (BuildConfig.AGENT_API_BASE_URL.isBlank() || BuildConfig.AGENT_ROUTE_MODEL.isBlank()) {
            error("未配置 AGENT_API_BASE_URL 或 AGENT_ROUTE_MODEL，无法调用 LLM 路由。")
        }
        if (candidates.isEmpty()) {
            return@withContext LlmRouteChoice(
                profileId = null,
                reason = "没有可供选择的 App 候选。",
                confidence = 0.0,
                rawResponse = "",
            )
        }

        val payload = JSONObject().apply {
            put("model", BuildConfig.AGENT_ROUTE_MODEL)
            put("temperature", 0.0)
            put("max_tokens", 120)
            put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt())
                        },
                    )
                    .put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", buildUserPrompt(task, candidates, routeMemoryHints))
                        },
                    ),
            )
        }

        Log.d(
            TAG,
            "route request model=${BuildConfig.AGENT_ROUTE_MODEL} task=${task.take(80)} candidates=${candidates.size}",
        )

        val url = URL("${BuildConfig.AGENT_API_BASE_URL.trimEnd('/')}/chat/completions")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = BuildConfig.AGENT_ROUTE_CONNECT_TIMEOUT_MS
            readTimeout = BuildConfig.AGENT_ROUTE_READ_TIMEOUT_MS
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
        Log.d(TAG, "route response code=$responseCode body=${responseText.take(500)}")
        if (responseCode !in 200..299) {
            error("LLM 路由请求失败，code=$responseCode body=$responseText")
        }

        val content =
            JSONObject(responseText)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content")

        parseChoice(content, candidates)
    }

    private fun systemPrompt(): String = """
        你是一个 Android 手机 Agent 的任务路由器。
        你的职责不是规划动作，而是仅根据用户任务语义，从候选 App 中选出“最应该首先打开的那个 App”。
        不要做关键词规则匹配解释，不要输出 Markdown，不要规划步骤。
        你必须严格输出一个 JSON 对象，格式如下：
        {"profile_id":"amap_assistant","reason":"...","confidence":0.96}
        或
        {"profile_id":"none","reason":"...","confidence":0.20}

        规则：
        - `profile_id` 必须严格取自候选列表里的 `profile_id`，或者输出 `none`。
        - 选择标准是“最匹配当前任务的首个目标 App”，不是默认 App。
        - 如果用户在任务里明确点名某个 App，例如“打开闲鱼… / 去微信… / 用支付宝…”，应优先选择该 App，而不是同类的其他 App。
        - 如果任务是路线、到某地、怎么走、去机场、去车站、打车、步行、骑行、开车这类出行任务，应优先选择地图 App。
        - 如果任务是搜商品、看评价、比价、推荐商品，应选择最匹配场景的购物 App。
        - 如果任务包含“二手、闲置、转卖、求购、闲鱼”，应优先选择闲鱼，而不是淘宝。
        - 如果任务是发消息、联系某人、群聊，应选择通讯 App。
        - 如果任务是外卖、奶茶、餐厅、酒店、团购，应选择本地生活 App。
        - 如果任务不明确，或候选里没有明显合适的 App，输出 `none`。
        - 优先选择 `installed=true` 的候选；只有当任务明确点名某个未安装 App，且没有已安装的等价候选时，才可以选择未安装候选。
        - 如果某个未安装候选和某个已安装候选属于同类能力（例如都属于 navigation），优先已安装候选。
        - 如果 `route_memory_hints` 里显示用户历史上经常成功使用某个 App 完成类似任务，或历史结果对象与当前任务高度相似，可以把它作为偏好信号，但不能压过任务语义本身。

        参考：
        - “去浦东机场怎么走” -> 地图
        - “打开购物 App 搜零食看评价” -> 购物 App
        - “打开闲鱼看二手内存条价格” -> 闲鱼
        - “给张三发微信说我晚点到” -> 微信
        - “帮我点一杯奶茶” -> 美团
        - “看下这个月支付宝账单” -> 支付宝
    """.trimIndent()

    private fun buildUserPrompt(
        task: String,
        candidates: List<LlmRouteCandidate>,
        routeMemoryHints: List<RouteMemoryHint>,
    ): String =
        JSONObject().apply {
            put("task", task)
            put(
                "route_memory_hints",
                JSONArray().apply {
                    routeMemoryHints.take(3).forEach { hint ->
                        put(
                            JSONObject().apply {
                                put("profile_id", hint.profileId)
                                put("summary", hint.summary.take(96))
                                put("score", hint.score)
                            },
                        )
                    }
                },
            )
            put(
                "candidates",
                JSONArray().apply {
                    candidates.take(16).forEach { candidate ->
                        put(
                            JSONObject().apply {
                                put("profile_id", candidate.profileId)
                                put("display_name", candidate.displayName)
                                put("package_name", candidate.packageName)
                                put("capability_summary", candidate.capabilitySummary.take(72))
                                put("installed", candidate.installed)
                                put("aliases", JSONArray(candidate.aliases.take(4)))
                                put("semantic_tags", JSONArray(candidate.semanticTags.take(4)))
                                put("source", candidate.source)
                                put("category", candidate.category)
                            },
                        )
                    }
                },
            )
        }.toString()

    private fun parseChoice(
        rawContent: String,
        candidates: List<LlmRouteCandidate>,
    ): LlmRouteChoice {
        val cleanJson = extractJson(rawContent)
        val json = JSONObject(cleanJson)
        val requestedProfileId = json.optString("profile_id").trim()
        val normalizedProfileId =
            when {
                requestedProfileId.equals("none", ignoreCase = true) -> null
                candidates.any { it.profileId == requestedProfileId } -> requestedProfileId
                else -> null
            }
        return LlmRouteChoice(
            profileId = normalizedProfileId,
            reason = json.optString("reason").ifBlank { "模型没有提供路由原因。" },
            confidence = json.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
            rawResponse = cleanJson,
        )
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
        error("路由模型返回的内容不是 JSON: $rawContent")
    }

    private fun readConnectionText(
        connection: HttpURLConnection,
        success: Boolean,
    ): String {
        val stream = if (success) connection.inputStream else connection.errorStream
        return stream.bufferedReader().use(BufferedReader::readText)
    }
}
