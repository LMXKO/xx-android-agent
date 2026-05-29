package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.BuildConfig
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 跨 App mission 的纯逻辑层（无 Android 依赖，可纯 JVM 单测）：
 *  - [decompose]：把一个用户目标启发式拆成跨 App 的 leg 序列；不符合则返回 null（走今天的单 App 路径）。
 *  - [composeFinalResult]：把各腿在黑板上累积的结果合并成最终对比结论。
 *
 * 本切片只支持"电商比价"模式（京东↔淘宝等，只读、不下单）。
 * leg 的 profileId→packageName 复用 [ConnectedAppCatalog]，不硬编码包名。
 */
object CrossAppMissionEngine {
    // 比价默认使用的两个电商 profile（京东 + 淘宝）
    private val defaultCompareApps = listOf("jd_assistant", "shopping_search")

    // profileId -> 用户口语里可能提到的 App 名
    private val appAliases =
        mapOf(
            "jd_assistant" to listOf("京东", "jd"),
            "shopping_search" to listOf("淘宝", "天猫", "taobao", "tmall"),
            "pdd_assistant" to listOf("拼多多", "pdd", "拼夕夕"),
            "wechat_assistant" to listOf("微信", "wechat"),
            "meituan_assistant" to listOf("美团", "外卖", "meituan", "饿了么"),
            "amap_assistant" to listOf("地图", "高德", "amap", "导航"),
            "xianyu_assistant" to listOf("闲鱼", "二手", "idlefish"),
            "alipay_assistant" to listOf("支付宝", "alipay"),
            "cainiao_assistant" to listOf("菜鸟", "快递", "包裹", "物流", "运单"),
            "didi_assistant" to listOf("滴滴", "打车", "网约车", "didi"),
            "bilibili_assistant" to listOf("B站", "b站", "bilibili", "哔哩哔哩"),
            "xhs_assistant" to listOf("小红书", "种草", "xhs"),
            "browser_assistant" to listOf("浏览器", "chrome", "网页", "browser"),
            "gmail_assistant" to listOf("gmail", "邮件", "邮箱"),
        )

    private val compareCues =
        listOf("比价", "对比价格", "比较价格", "比一下价格", "价格对比", "哪个便宜", "哪个更便宜", "谁更便宜")

    private val priceHints = listOf("¥", "￥", "元", "价")

    fun decompose(
        goal: String,
        installChecker: InstalledPackageChecker = InstalledPackageChecker.FROM_RUNTIME,
    ): CrossAppMission? {
        val trimmed = goal.trim()
        if (trimmed.isBlank()) return null
        val normalized = trimmed.lowercase()

        val mentioned =
            appAliases
                .filterValues { aliases -> aliases.any { trimmed.contains(it, ignoreCase = true) } }
                .keys
                .toList()
        val wantsCompare =
            compareCues.any { trimmed.contains(it) } ||
                (normalized.contains("价格") && (normalized.contains("比") || normalized.contains("对比")))

        val appPair: List<String> =
            when {
                mentioned.size >= 2 -> mentioned.take(2)
                wantsCompare && mentioned.size == 1 ->
                    (mentioned + defaultCompareApps.first { it !in mentioned }).take(2)
                wantsCompare -> defaultCompareApps
                else -> return null
            }
        if (appPair.size < 2) return null

        val constraints = TaskIntentParser.parse(trimmed)
        val product = constraints.entryQuery.ifBlank { trimmed }

        val legs =
            appPair.mapIndexedNotNull { index, profileId ->
                val descriptor = ConnectedAppCatalog.findByAppId(profileId) ?: return@mapIndexedNotNull null
                val installedPackage = descriptor.firstInstalledPackageName(installChecker) ?: return@mapIndexedNotNull null
                MissionLeg(
                    legId = "leg_${index}_$profileId",
                    profileId = profileId,
                    targetPackageName = installedPackage,
                    subTask = "在${appName(descriptor)}搜索$product 并查看价格信息",
                    intentType = "shopping",
                )
            }
        if (legs.size < 2) return null

        return CrossAppMission(
            missionId = "mission_${System.currentTimeMillis()}",
            goal = trimmed,
            legs = legs,
            declaredHandoffFields = setOf("query", "price"),
            kind = "compare",
        )
    }

    /**
     * 合并各腿结果。[lastPayload] 是最后一腿刚抽取、尚未进黑板的产出。
     * 按 mission.kind 选择收口形态：compare → 比价结论；general → 通用多腿汇总（不再把所有任务都套成"比价"）。
     */
    fun composeFinalResult(
        mission: CrossAppMission,
        lastPayload: TaskResultPayload?,
    ): TaskResultPayload {
        val payloads = if (lastPayload != null) mission.blackboard + lastPayload else mission.blackboard
        return if (mission.kind == "compare") {
            composeCompareResult(mission, payloads)
        } else {
            composeGeneralResult(mission, payloads)
        }
    }

    private fun composeCompareResult(
        mission: CrossAppMission,
        payloads: List<TaskResultPayload>,
    ): TaskResultPayload {
        val product = TaskIntentParser.parse(mission.goal).entryQuery.ifBlank { mission.goal }

        val perApp =
            mission.legs.mapIndexedNotNull { index, leg ->
                val payload = payloads.getOrNull(index) ?: return@mapIndexedNotNull null
                val name = appLabel(leg.profileId)
                val priceText = pickPriceEvidence(payload)
                Triple(name, priceText, parsePrice(priceText))
            }

        val cheaper = perApp.filter { it.third != null }.minByOrNull { it.third!! }

        val summary =
            buildString {
                append("已在 ").append(perApp.joinToString("、") { it.first }).append(" 完成 ")
                append(product).append(" 比价。")
                perApp.forEach { (name, priceText, _) ->
                    append(" ").append(name).append("：").append(priceText.ifBlank { "未读到明确价格" }).append("。")
                }
                if (cheaper != null && perApp.count { it.third != null } >= 2) {
                    append(" 更便宜：").append(cheaper.first).append("。")
                }
            }

        return TaskResultPayload(
            intentType = "cross_app_compare",
            title = "$product 跨 App 比价",
            summary = summary,
            highlights = payloads.flatMap { it.highlights }.distinct().take(6),
            fields =
                perApp.map { (name, priceText, _) ->
                    TaskResultField(key = "price_${name}", label = "$name 价格", value = priceText)
                },
        )
    }

    /** 通用多腿任务收口：按黑板顺序汇总每步产出，并标注未完成的腿数（降级时仍给出可用的部分结果）。 */
    private fun composeGeneralResult(
        mission: CrossAppMission,
        payloads: List<TaskResultPayload>,
    ): TaskResultPayload {
        val steps =
            payloads.mapIndexed { index, payload ->
                val title = payload.title.ifBlank { "步骤${index + 1}" }
                val detail = payload.summary.ifBlank { payload.title }.ifBlank { "已完成" }
                title to detail
            }
        val failed = mission.failedLegCount()
        val summary =
            buildString {
                append("已完成跨 App 任务：").append(mission.goal.take(50)).append("。")
                steps.forEach { (title, detail) ->
                    append(" ").append(title.take(20)).append("：").append(detail.take(60)).append("。")
                }
                if (failed > 0) {
                    append(" 另有 ").append(failed).append(" 步未完成。")
                }
            }
        return TaskResultPayload(
            intentType = "cross_app_task",
            title = mission.goal.take(40),
            summary = summary,
            highlights = payloads.flatMap { it.highlights }.distinct().take(6),
            fields =
                steps.mapIndexed { index, (title, detail) ->
                    TaskResultField(key = "step_${index + 1}", label = title.take(20), value = detail.take(80))
                },
        )
    }

    /**
     * 把上一腿产出抽成**结构化交接槽位**（与 [rewriteSubTaskWithHandoff] 的自然语言注释并存），
     * 写进下一腿的 [MissionLeg.handoff]，便于下游使用与"下一腿是否真用了该实体"的校验/观测。
     */
    fun resolveHandoffFields(
        mission: CrossAppMission,
        previousPayload: TaskResultPayload?,
    ): Map<String, String> {
        if (previousPayload == null) return emptyMap()
        val allow = mission.declaredHandoffFields
        val out = linkedMapOf<String, String>()
        previousPayload.title.takeIf { it.isNotBlank() }?.let { out["entity"] = it.take(60) }
        previousPayload.fields.forEach { field ->
            if (allow.contains(field.key) && field.value.isNotBlank()) {
                out[field.key] = field.value.take(60)
            }
        }
        return out
    }

    /**
     * 统一入口：先用启发式快速通道，命中不了再用 LLM 通用分解。供 suspend 上下文（会话启动）调用。
     */
    suspend fun resolveMission(
        goal: String,
        installChecker: InstalledPackageChecker = InstalledPackageChecker.FROM_RUNTIME,
    ): CrossAppMission? =
        decompose(goal, installChecker) ?: runCatching { decomposeWithLlm(goal, installChecker) }.getOrNull()

    /**
     * 把上一腿抽到的实体（商品名/快递单号/联系人/价格...）注入下一腿的 subTask，
     * 让 leg1 真正搜"上一腿确认的那个对象"，而不是分解时写死的原始词。
     *
     * 规则：
     *  - 只把 declaredHandoffFields 声明允许的字段带过去（默认 query/price 等非敏感字段）。
     *  - 优先用 payload.title（最稳的实体名），其次 fields[query]/fields 里被声明的字段。
     *  - 找不到可注入实体 → 返回 nextSubTask 原样（向后兼容 / 不破坏现有路径）。
     */
    fun rewriteSubTaskWithHandoff(
        mission: CrossAppMission,
        nextSubTask: String,
        previousPayload: TaskResultPayload?,
    ): String {
        if (previousPayload == null) return nextSubTask
        val allow = mission.declaredHandoffFields
        val entity =
            previousPayload.title.takeIf { it.isNotBlank() }
                ?: previousPayload.fields.firstOrNull { allow.contains(it.key) && it.value.isNotBlank() }?.value
                ?: previousPayload.highlights.firstOrNull().orEmpty()
        if (entity.isBlank()) return nextSubTask

        val priceHint =
            if (allow.any { it.equals("price", ignoreCase = true) }) {
                previousPayload.fields.firstOrNull { it.key.equals("price", ignoreCase = true) }?.value.orEmpty()
            } else {
                ""
            }
        val annotation =
            buildString {
                append("（参考上一腿：")
                append(entity.take(40))
                if (priceHint.isNotBlank()) append("，价格 ").append(priceHint.take(20))
                append("）")
            }
        // 已经包含同一实体则不重复注入
        if (nextSubTask.contains(entity, ignoreCase = true)) return nextSubTask + annotation
        return "$nextSubTask $annotation"
    }

    /** LLM 通用分解：让模型判断任务是否需要跨多个 App，并产出有序 leg 列表。失败安全返回 null（回落单 App）。 */
    suspend fun decomposeWithLlm(
        goal: String,
        installChecker: InstalledPackageChecker = InstalledPackageChecker.FROM_RUNTIME,
    ): CrossAppMission? =
        withContext(Dispatchers.IO) {
            val trimmed = goal.trim()
            if (trimmed.isBlank()) return@withContext null
            if (BuildConfig.AGENT_API_BASE_URL.isBlank() || BuildConfig.AGENT_MODEL.isBlank()) return@withContext null

            val payload =
                JSONObject().apply {
                    put("model", BuildConfig.AGENT_MODEL)
                    put("temperature", 0.0)
                    put("max_tokens", 400)
                    put(
                        "messages",
                        JSONArray()
                            .put(JSONObject().apply { put("role", "system"); put("content", llmSystemPrompt()) })
                            .put(JSONObject().apply { put("role", "user"); put("content", llmUserPrompt(trimmed, installChecker)) }),
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
            val content =
                JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content")
            parseLlmDecomposition(content, trimmed, installChecker)
        }

    /** 纯解析：LLM 返回内容 → CrossAppMission?（可纯 JVM 单测）。非多 App / 不足两腿 / 无法解析 → null。 */
    internal fun parseLlmDecomposition(
        content: String,
        goal: String,
        installChecker: InstalledPackageChecker = InstalledPackageChecker.ASSUME_ALL_INSTALLED,
    ): CrossAppMission? {
        val json = runCatching { JSONObject(extractJsonObject(content)) }.getOrNull() ?: return null
        val legsJson =
            json.optJSONArray("legs")
                ?: json.optJSONArray("steps")
                ?: json.optJSONArray("plan")
                ?: return null
        // multi_app 缺省时，只要给出 >=2 条腿就视为多 App；这能容忍模型遗漏这个开关。
        val explicitFlag = if (json.has("multi_app")) json.optBoolean("multi_app") else null
        if (explicitFlag == false) return null
        val legs =
            buildList {
                for (index in 0 until legsJson.length()) {
                    val obj = legsJson.optJSONObject(index) ?: continue
                    val appToken = pickField(obj, listOf("app", "app_id", "appId", "profile_id", "profileId", "package_name", "package"))
                    val descriptor = resolveDescriptor(appToken) ?: continue
                    val installedPackage = descriptor.firstInstalledPackageName(installChecker) ?: continue
                    val subTask =
                        pickField(obj, listOf("sub_task", "subtask", "task", "taskDescription", "description", "goal", "objective"))
                            .ifBlank { goal }
                    val intentType = pickField(obj, listOf("intent_type", "intentType", "intent", "type")).ifBlank { "general" }
                    add(
                        MissionLeg(
                            legId = "leg_${size}_${descriptor.appId}",
                            profileId = descriptor.appId,
                            targetPackageName = installedPackage,
                            subTask = subTask,
                            intentType = intentType,
                        ),
                    )
                }
            }
        if (legs.size < 2) return null
        val handoffArray =
            json.optJSONArray("handoff_fields")
                ?: json.optJSONArray("handoffFields")
                ?: json.optJSONArray("carry")
        val handoff =
            handoffArray
                ?.let { arr ->
                    buildSet {
                        for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
                ?.ifEmpty { null }
                ?: setOf("query")
        return CrossAppMission(
            missionId = "mission_${System.currentTimeMillis()}",
            goal = goal,
            legs = legs,
            declaredHandoffFields = handoff,
            kind = if (looksLikeCompare(goal)) "compare" else "general",
        )
    }

    private fun looksLikeCompare(goal: String): Boolean {
        val normalized = goal.lowercase()
        return compareCues.any { goal.contains(it) } ||
            (normalized.contains("价格") && (normalized.contains("比") || normalized.contains("对比")))
    }

    private fun pickField(
        obj: JSONObject,
        keys: List<String>,
    ): String {
        keys.forEach { key ->
            obj.optString(key).takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    private fun resolveDescriptor(token: String): ConnectedAppDescriptor? {
        val normalized = token.trim()
        if (normalized.isBlank()) return null
        ConnectedAppCatalog.findByAppId(normalized)?.let { return it }
        appAliases.entries
            .firstOrNull { (_, aliases) -> aliases.any { normalized.contains(it, ignoreCase = true) } }
            ?.key
            ?.let { ConnectedAppCatalog.findByAppId(it) }
            ?.let { return it }
        return ConnectedAppCatalog.descriptors().firstOrNull {
            it.title.contains(normalized, ignoreCase = true) || it.packageName.equals(normalized, ignoreCase = true)
        }
    }

    private fun extractJsonObject(raw: String): String {
        // 剥掉 ```json ... ``` / ``` ... ``` 之类的 markdown 围栏
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

    private fun llmSystemPrompt(): String =
        """
        你是手机 Agent 的"跨 App 任务分解器"。判断用户任务是否需要依次操作【多个】App 才能完成；
        若需要，输出有序的执行腿（leg）序列，每条腿指定一个候选 App 和该 App 内的子任务。
        只输出一个 JSON 对象，不要 Markdown、不要解释。格式：
        {"multi_app":true,"handoff_fields":["query","price"],"legs":[{"app":"jd_assistant","sub_task":"在京东搜索<商品>查看价格"},{"app":"shopping_search","sub_task":"在淘宝搜索<商品>查看价格"}]}
        或单 App / 不明确时：{"multi_app":false}
        规则：
        - `app` 必须取自候选列表的 `app_id`。
        - 至少 2 条腿才算多 App；只需 1 个 App 的任务输出 multi_app=false。
        - `handoff_fields` 列出需要在 App 之间传递的信息字段（如商品名 query、价格 price、单号 tracking、联系人 contact）。
        - 子任务要具体、可执行；保持各腿目标一致（如同一件商品）。
        - 涉及支付/转账/发送的最终一步只描述"准备好"，由系统在最后交用户确认。
        """.trimIndent()

    private fun llmUserPrompt(
        goal: String,
        installChecker: InstalledPackageChecker,
    ): String =
        JSONObject().apply {
            put("task", goal)
            put(
                "candidates",
                JSONArray().apply {
                    ConnectedAppCatalog.descriptors()
                        .filter { it.firstInstalledPackageName(installChecker) != null }
                        .forEach { descriptor ->
                            put(
                                JSONObject().apply {
                                    put("app_id", descriptor.appId)
                                    put("title", descriptor.title)
                                    put("summary", descriptor.summary.take(60))
                                },
                            )
                        }
                },
            )
        }.toString()

    private fun appName(descriptor: ConnectedAppDescriptor): String =
        descriptor.title.substringBefore(' ').ifBlank { descriptor.title }

    private fun appLabel(profileId: String): String =
        ConnectedAppCatalog.findByAppId(profileId)?.let { appName(it) } ?: profileId

    private fun pickPriceEvidence(payload: TaskResultPayload): String {
        payload.fields.firstOrNull { it.key.equals("price", ignoreCase = true) }?.value
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return payload.highlights.firstOrNull { hint -> priceHints.any { hint.contains(it) } }
            ?: payload.highlights.firstOrNull().orEmpty()
    }

    private fun parsePrice(text: String): Double? {
        if (text.isBlank()) return null
        val match = Regex("([0-9]+(?:\\.[0-9]+)?)").find(text.replace(",", "")) ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }
}
