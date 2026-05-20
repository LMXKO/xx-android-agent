package com.lmx.xiaoxuanagent.memory

import com.lmx.xiaoxuanagent.agent.HumanCorrectionRecoveryIntent
import com.lmx.xiaoxuanagent.agent.interpretHumanCorrection
import com.lmx.xiaoxuanagent.agent.TaskResultPayload
import com.lmx.xiaoxuanagent.agent.TaskIntentParser
import com.lmx.xiaoxuanagent.runtime.AgentUiStatus

object MemoryExtractor {
    private val contactPatterns =
        listOf(
            Regex("""(?:给|发给|联系|回复|告诉)\s*([A-Za-z0-9\p{IsHan}·._-]{2,12}?)(?=(?:发|回复|说|留言|消息|微信|短信|电话|通话|$))"""),
            Regex("""(?:联系人|收件人)[：:]\s*([A-Za-z0-9\p{IsHan}·._-]{2,20})"""),
        )

    private val locationPatterns =
        listOf(
            Regex("""(?:地址|地点|目的地)[：:]\s*([A-Za-z0-9\p{IsHan}·._\-/()（）\s]{2,30})"""),
            Regex("""(?:去|到|前往|导航到)\s*([A-Za-z0-9\p{IsHan}·._\-/()（）\s]{2,30}?)(?=(?:怎么走|怎么去|路线|路程|多久|多长时间|多少时间|有多远|远不远|$))"""),
        )

    private val explicitSafetyRulePatterns =
        listOf(
            "不要支付" to "block",
            "不支付" to "block",
            "不下单" to "block",
            "不要下单" to "block",
            "不转账" to "block",
            "不要转账" to "block",
            "不要自动发送" to "confirm",
            "发送前确认" to "confirm",
        )

    fun extract(
        task: String,
        profileId: String,
        status: String,
        finalMessage: String,
        taskResult: TaskResultPayload? = null,
    ): MemoryExtractResult {
        val now = System.currentTimeMillis()
        val taskConstraints = TaskIntentParser.parse(task)
        val resultArtifacts =
            buildList {
                if (AgentUiStatus.isCompleted(status) && taskResult != null) {
                    add(
                        ResultArtifactMemory(
                            intentType = taskResult.intentType,
                            title = taskResult.title.take(60),
                            summary = taskResult.summary.take(120),
                            sourceProfileId = profileId,
                            updatedAt = now,
                        ),
                    )
                }
            }
        val contacts =
            buildList {
                taskConstraints.recipientName
                    ?.trim()
                    ?.takeIf { it.length >= 2 }
                    ?.let {
                        add(
                            ContactMemory(
                                name = it,
                                note = "最近一次通讯任务中提到的联系人",
                                sourceProfileId = profileId,
                                updatedAt = now,
                            ),
                        )
                    }
                contactPatterns.forEach { regex ->
                    regex.findAll("$task\n$finalMessage")
                        .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.takeIf { value -> value.length >= 2 } }
                        .forEach { candidate ->
                            add(
                                ContactMemory(
                                    name = candidate,
                                    note = "从任务文本中提取",
                                    sourceProfileId = profileId,
                                    updatedAt = now,
                                ),
                            )
                        }
                }
            }.distinctBy { it.name.lowercase() }

        val locations =
            buildList {
                taskConstraints.destination
                    ?.trim()
                    ?.takeIf { it.length >= 2 }
                    ?.let {
                        add(
                            LocationMemory(
                                name = it,
                                category = "destination",
                                note = "最近一次导航任务目标地",
                                sourceProfileId = profileId,
                                updatedAt = now,
                            ),
                        )
                    }
                locationPatterns.forEach { regex ->
                    regex.findAll("$task\n$finalMessage")
                        .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.takeIf { value -> value.length >= 2 } }
                        .forEach { candidate ->
                            add(
                                LocationMemory(
                                    name = candidate,
                                    category = "place",
                                    note = "从任务文本中提取",
                                    sourceProfileId = profileId,
                                    updatedAt = now,
                                ),
                            )
                        }
                }
            }.distinctBy { it.name.lowercase() }

        val appPreferences =
            buildList {
                if (profileId.isNotBlank() && AgentUiStatus.isCompleted(status)) {
                    add(
                        AppPreferenceMemory(
                            profileId = profileId,
                            preference = "近期成功使用该应用完成过类似任务",
                            note = task.take(40),
                            updatedAt = now,
                        ),
                    )
                }
            }

        val safetyRules =
            buildList {
                explicitSafetyRulePatterns.forEach { (pattern, level) ->
                    if (task.contains(pattern) || finalMessage.contains(pattern)) {
                        add(
                            SafetyRuleMemory(
                                rule = pattern,
                                level = level,
                                note = "从任务偏好或结果中提取",
                                updatedAt = now,
                            ),
                        )
                    }
                }
                if (task.contains("不加支付部分") || task.contains("不含支付")) {
                    add(
                        SafetyRuleMemory(
                            rule = "禁止自动支付",
                            level = "block",
                            note = "用户明确要求不包含支付步骤",
                            updatedAt = now,
                        ),
                    )
                }
            }.distinctBy { it.rule.lowercase() }

        val correctionTemplates = emptyList<CorrectionTemplateMemory>()

        val facts = extractFacts(task, finalMessage)

        return MemoryExtractResult(
            resultArtifacts = resultArtifacts.take(2),
            contacts = contacts.take(3),
            locations = locations.take(3),
            appPreferences = appPreferences.take(2),
            safetyRules = safetyRules.take(4),
            correctionTemplates = correctionTemplates,
            facts = facts,
        )
    }

    fun extractCorrectionTemplates(
        task: String,
        profileId: String,
        correction: String,
    ): List<CorrectionTemplateMemory> {
        val normalized = correction.trim()
        if (normalized.isBlank()) return emptyList()
        val correctionInterpretation = interpretHumanCorrection(normalized)
        val now = System.currentTimeMillis()
        val templates = mutableListOf<CorrectionTemplateMemory>()
        if (correctionInterpretation.mentionsBacktrack) {
            templates +=
                CorrectionTemplateMemory(
                    templateType = "backtrack",
                    instruction = "如果进入错误页面或误点目标，先返回上一层再重新定位当前子目标。",
                    sourceProfileId = profileId,
                    note = task.take(48),
                    updatedAt = now,
                )
        }
        if (correctionInterpretation.mentionsOverscrollUp) {
            templates +=
                CorrectionTemplateMemory(
                    templateType = "overscroll_up",
                    instruction = "如果滚动过头，优先小步回拉，不要继续沿原方向滚动。",
                    sourceProfileId = profileId,
                    note = task.take(48),
                    updatedAt = now,
                )
        }
        if (correctionInterpretation.mentionsRefocus) {
            templates +=
                CorrectionTemplateMemory(
                    templateType = "refocus_entry",
                    instruction = "如果点错候选或上下文偏航，重新定位入口目标后再继续。",
                    sourceProfileId = profileId,
                    note = task.take(48),
                    updatedAt = now,
                )
        }
        correctionInterpretation.blockedTargetKeywords
            .forEach { blocked ->
                templates +=
                    CorrectionTemplateMemory(
                        templateType = "avoid_target",
                        argument = blocked,
                        instruction = "遇到相似任务时避开 $blocked，优先重新定位更贴近目标的候选。",
                        sourceProfileId = profileId,
                        note = task.take(48),
                        updatedAt = now,
                    )
            }
        return templates.distinctBy { "${it.templateType}|${it.argument.lowercase()}" }
    }

    private fun extractFacts(
        task: String,
        finalMessage: String,
    ): List<String> {
        val result = mutableListOf<String>()
        if (task.contains("喜欢")) {
            result += task.take(80)
        }
        Regex("""(?:地址|地点|联系人|店铺|商品)[：:]\s*([^\n，。]{2,24})""")
            .findAll("$task\n$finalMessage")
            .forEach { match ->
                result += match.value.take(80)
            }
        return result.distinct().take(3)
    }

}
