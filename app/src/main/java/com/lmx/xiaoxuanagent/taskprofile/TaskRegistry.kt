package com.lmx.xiaoxuanagent.taskprofile

import com.lmx.xiaoxuanagent.AppLaunchResolver
import com.lmx.xiaoxuanagent.agent.TaskIntentParser
import com.lmx.xiaoxuanagent.agent.TaskIntentType
import com.lmx.xiaoxuanagent.memory.PersonalMemoryStore
import com.lmx.xiaoxuanagent.memory.RouteMemoryHint
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext

enum class TaskRouteStatus {
    RESOLVED,
    APP_UNAVAILABLE,
    ROUTING_FAILED,
}

data class TaskRouteResult(
    val profile: TaskProfile,
    val score: Int,
    val installed: Boolean,
    val reason: String,
    val status: TaskRouteStatus,
)

object TaskRegistry {
    private const val DYNAMIC_PROFILE_PREFIX = "dynamic_app_"
    private val directSystemSettingsPatterns =
        listOf(
            Regex("""\bopen settings\b""", RegexOption.IGNORE_CASE),
            Regex("""\bsettings\b""", RegexOption.IGNORE_CASE),
            Regex("""(?:打开|进入|前往|去|启动)(?:系统)?设置"""),
            Regex("""(?:系统设置|应用设置|权限设置|通知设置|电池设置|wifi设置|蓝牙设置|网络设置|隐私设置)"""),
        )
    private val directSystemSettingsKeywords =
        listOf(
            "系统设置",
            "打开设置",
            "打开系统设置",
            "设置页面",
            "应用设置",
            "权限设置",
            "通知设置",
            "电池设置",
            "无线设置",
            "网络设置",
            "蓝牙设置",
            "wifi设置",
            "wi-fi设置",
            "隐私设置",
        )

    private val settingsProfile =
        KeywordTaskProfile(
            id = "system_settings_assistant",
            displayName = "系统设置助手",
            packageName = "com.android.settings",
            routeKeywords =
                listOf(
                    "设置",
                    "系统设置",
                    "权限",
                    "通知",
                    "电池",
                    "蓝牙",
                    "wifi",
                    "网络",
                    "隐私",
                ),
            capabilitySummary = "适合打开系统设置、权限、通知、电池、网络和应用设置。",
        )

    private val xianyuProfile =
        KeywordTaskProfile(
            id = "xianyu_assistant",
            displayName = "闲鱼助手",
            packageName = "com.taobao.idlefish",
            routeKeywords =
                listOf(
                    "闲鱼",
                    "二手",
                    "闲置",
                    "转卖",
                    "转让",
                    "求购",
                    "二手交易",
                ),
            capabilitySummary = "适合闲置、二手商品、转卖和求购价格浏览。",
        )

    private val jdProfile =
        KeywordTaskProfile(
            id = "jd_assistant",
            displayName = "京东助手",
            packageName = "com.jingdong.app.mall",
            routeKeywords =
                listOf(
                    "京东",
                    "自营",
                    "家电",
                    "电脑",
                    "手机",
                    "数码",
                    "电器",
                    "显卡",
                    "cpu",
                    "笔记本",
                ),
            capabilitySummary = "适合数码、电器、自营商品搜索与比价。",
        )

    private val pddProfile =
        KeywordTaskProfile(
            id = "pdd_assistant",
            displayName = "拼多多助手",
            packageName = "com.xunmeng.pinduoduo",
            routeKeywords =
                listOf(
                    "拼多多",
                    "拼单",
                    "百亿补贴",
                    "团购",
                    "便宜",
                    "低价",
                ),
            capabilitySummary = "适合低价商品搜索、拼单、补贴商品浏览。",
        )

    private val meituanProfile =
        KeywordTaskProfile(
            id = "meituan_assistant",
            displayName = "美团助手",
            packageName = "com.sankuai.meituan",
            routeKeywords =
                listOf(
                    "美团",
                    "外卖",
                    "奶茶",
                    "咖啡",
                    "餐厅",
                    "美食",
                    "到店",
                    "酒店",
                    "团购",
                ),
            capabilitySummary = "适合外卖、到店、美食、酒店和团购任务。",
        )

    private val amapProfile =
        KeywordTaskProfile(
            id = "amap_assistant",
            displayName = "高德地图助手",
            packageName = "com.autonavi.minimap",
            routeKeywords =
                listOf(
                    "地图",
                    "导航",
                    "路线",
                    "打车",
                    "步行",
                    "骑行",
                    "开车",
                    "附近",
                    "到这去",
                ),
            capabilitySummary = "适合导航、路线规划、附近地点搜索。",
        )

    private val wechatProfile =
        KeywordTaskProfile(
            id = "wechat_assistant",
            displayName = "微信助手",
            packageName = "com.tencent.mm",
            routeKeywords =
                listOf(
                    "微信",
                    "发消息",
                    "聊天",
                    "联系人",
                    "群聊",
                    "公众号",
                    "小程序",
                ),
            capabilitySummary = "适合聊天、联系人、公众号和小程序入口任务。",
        )

    private val alipayProfile =
        KeywordTaskProfile(
            id = "alipay_assistant",
            displayName = "支付宝助手",
            packageName = "com.eg.android.AlipayGphone",
            routeKeywords =
                listOf(
                    "支付宝",
                    "账单",
                    "收款",
                    "转账",
                    "付款码",
                    "生活缴费",
                    "出行",
                ),
            capabilitySummary = "适合账单、收款、生活服务和出行入口任务。",
        )

    private val profiles: List<TaskProfile> =
        listOf(
            settingsProfile,
            ShoppingTaskProfile,
            xianyuProfile,
            jdProfile,
            pddProfile,
            meituanProfile,
            amapProfile,
            wechatProfile,
            alipayProfile,
        )

    private val profilesById: Map<String, TaskProfile> = profiles.associateBy { it.id }
    private val dynamicProfilesById = linkedMapOf<String, TaskProfile>()
    private val tokenSplitRegex = Regex("""[^A-Za-z0-9\p{IsHan}]+""")

    val defaultProfile: TaskProfile = ShoppingTaskProfile

    fun get(profileId: String): TaskProfile? = dynamicProfilesById[profileId] ?: profilesById[profileId]

    fun allProfiles(): List<TaskProfile> = profiles

    data class RouteDebugInfo(
        val candidateCount: Int,
        val candidatePreview: List<String>,
        val modelRawResponse: String,
        val memoryHintPreview: List<String> = emptyList(),
        val fallbackReason: String = "",
        val modelChoiceProfileId: String = "",
        val selectedProfileId: String = "",
        val policyTag: String = "",
    )

    internal fun renderRouteDebugLines(
        debug: RouteDebugInfo,
        limit: Int = 3,
    ): List<String> =
        buildList {
            add("policy=${debug.policyTag.ifBlank { "-" }}")
            add("modelChoice=${debug.modelChoiceProfileId.ifBlank { "-" }}")
            add("selected=${debug.selectedProfileId.ifBlank { "-" }}")
            if (debug.fallbackReason.isNotBlank()) {
                add("fallback=${debug.fallbackReason}")
            }
            if (debug.memoryHintPreview.isNotEmpty()) {
                add("memory=${debug.memoryHintPreview.take(limit).joinToString(" || ")}")
            }
            if (debug.modelRawResponse.isNotBlank()) {
                add("modelRaw=${debug.modelRawResponse.take(180)}")
            }
        }

    suspend fun resolve(task: String): TaskRouteResolution {
        val normalizedTask = task.trim()
        if (normalizedTask.isBlank()) {
            return TaskRouteResolution(
                result = TaskRouteResult(
                    profile = defaultProfile,
                    score = 0,
                    installed = false,
                    reason = "任务为空，无法进行大模型路由。",
                    status = TaskRouteStatus.ROUTING_FAILED,
                ),
                debug = RouteDebugInfo(
                    candidateCount = 0,
                    candidatePreview = emptyList(),
                    modelRawResponse = "",
                    memoryHintPreview = emptyList(),
                    policyTag = "empty_task",
                ),
            )
        }

        val context = AppRuntimeContext.get()
        val packageManager = context?.packageManager
        resolveDirectSystemRoute(normalizedTask, packageManager)?.let { return it }
        val staticCandidates =
            profiles.map { profile ->
                val installed =
                    packageManager?.let { pm ->
                        AppLaunchResolver.resolve(pm, profile.packageName) != null
                    } ?: true
                LlmRouteCandidate(
                    profileId = profile.id,
                    displayName = profile.displayName,
                    packageName = profile.packageName,
                    capabilitySummary = profile.capabilitySummary,
                    installed = installed,
                    aliases = profile.routeKeywords,
                    semanticTags = profile.capabilitySummary.split("、", "，", " ").filter { it.isNotBlank() }.take(6),
                    source = "curated",
                    category = curatedCategory(profile.id),
                )
            }

        val dynamicCandidates =
            packageManager?.let { pm ->
                val excludePackages = profiles.map { it.packageName }.toSet() + "com.lmx.xiaoxuanagent"
                AppLaunchResolver.listLaunchableApps(pm, excludePackages)
                    .map { app ->
                        val profileId = "$DYNAMIC_PROFILE_PREFIX${app.packageName}"
                        val semanticHints = AppSemanticHintsBuilder.build(app.label, app.packageName)
                        val profile =
                            GenericInstalledAppProfile(
                                id = profileId,
                                displayName = app.label,
                                packageName = app.packageName,
                                routeKeywords = semanticHints.aliases,
                                capabilitySummary = semanticHints.summary,
                            )
                        synchronized(dynamicProfilesById) {
                            dynamicProfilesById[profileId] = profile
                        }
                        LlmRouteCandidate(
                            profileId = profile.id,
                            displayName = profile.displayName,
                            packageName = profile.packageName,
                            capabilitySummary = profile.capabilitySummary,
                            installed = true,
                            aliases = semanticHints.aliases,
                            semanticTags = semanticHints.semanticTags,
                            source = "dynamic",
                            category = semanticHints.category,
                        )
                    }
            }.orEmpty()

        val candidates = staticCandidates + dynamicCandidates
        val routeMemoryHints =
            PersonalMemoryStore.recallRouteHints(
                task = normalizedTask,
                candidateProfileIds = candidates.map { it.profileId }.toSet(),
            )
        val candidatePreview =
            candidates
                .asSequence()
                .filter { it.installed }
                .take(24)
                .map { candidate ->
                    buildString {
                        append(candidate.displayName)
                        append("(").append(candidate.packageName).append(")")
                        if (candidate.aliases.isNotEmpty()) {
                            append(" aliases=").append(candidate.aliases.take(4).joinToString("/"))
                        }
                        if (candidate.semanticTags.isNotEmpty()) {
                            append(" tags=").append(candidate.semanticTags.take(4).joinToString("/"))
                        }
                    }
                }
                .toList()
        val memoryHintPreview =
            routeMemoryHints.map { hint ->
                "${hint.profileId}:${hint.score}:${hint.summary.take(48)}"
            }

        val choice =
            runCatching {
                LlmTaskRouter.route(
                    task = normalizedTask,
                    candidates = candidates,
                    routeMemoryHints = routeMemoryHints,
                )
            }.getOrElse { error ->
                val localFallback =
                    chooseLocalSemanticFallback(
                        task = normalizedTask,
                        candidates = candidates,
                        routeMemoryHints = routeMemoryHints,
                    )
                if (localFallback != null) {
                    val fallbackProfile = get(localFallback.profileId) ?: defaultProfile
                    return TaskRouteResolution(
                        result = TaskRouteResult(
                            profile = fallbackProfile,
                            score = localFallback.score,
                            installed = true,
                            reason =
                                buildString {
                                    append("LLM 路由失败，已切换到本地语义兜底。")
                                    append(" ").append(localFallback.reason)
                                    append(" 原始错误: ${error.message ?: error.javaClass.simpleName}")
                                },
                            status = TaskRouteStatus.RESOLVED,
                        ),
                        debug = RouteDebugInfo(
                            candidateCount = candidates.size,
                            candidatePreview = candidatePreview,
                            modelRawResponse = "",
                            memoryHintPreview = memoryHintPreview,
                            fallbackReason = "local_semantic:${localFallback.profileId}",
                            selectedProfileId = localFallback.profileId,
                            policyTag = "llm_error_local_semantic_fallback",
                        ),
                    )
                }
                return TaskRouteResolution(
                    result = TaskRouteResult(
                        profile = defaultProfile,
                        score = 0,
                        installed = false,
                        reason = "LLM 路由失败: ${error.message ?: error.javaClass.simpleName}",
                        status = TaskRouteStatus.ROUTING_FAILED,
                    ),
                debug = RouteDebugInfo(
                    candidateCount = candidates.size,
                    candidatePreview = candidatePreview,
                    modelRawResponse = "",
                    memoryHintPreview = memoryHintPreview,
                    fallbackReason = "",
                    policyTag = "llm_error",
                ),
            )
        }

        val preferredMemoryCandidate =
            RouteResolutionPolicy.selectMemoryPreferredCandidate(
                candidates = candidates,
                routeMemoryHints = routeMemoryHints,
            )
        val selectedProfile =
            choice.profileId?.let { profileId ->
                get(profileId)
            }

        if (selectedProfile == null) {
            val memoryFallbackProfile = preferredMemoryCandidate?.let { get(it.candidate.profileId) }
            if (memoryFallbackProfile != null) {
                return TaskRouteResolution(
                    result = TaskRouteResult(
                        profile = memoryFallbackProfile,
                        score = preferredMemoryCandidate.hint.score,
                        installed = preferredMemoryCandidate.candidate.installed,
                        reason = "模型未给出明确目标 App，按个人历史偏好回退到 ${memoryFallbackProfile.displayName}。 ${preferredMemoryCandidate.hint.summary}",
                        status = if (preferredMemoryCandidate.candidate.installed) TaskRouteStatus.RESOLVED else TaskRouteStatus.APP_UNAVAILABLE,
                    ),
                    debug = RouteDebugInfo(
                        candidateCount = candidates.size,
                        candidatePreview = candidatePreview,
                        modelRawResponse = choice.rawResponse,
                        memoryHintPreview = memoryHintPreview,
                        fallbackReason = "memory_preferred:${memoryFallbackProfile.id}",
                        modelChoiceProfileId = choice.profileId.orEmpty(),
                        selectedProfileId = memoryFallbackProfile.id,
                        policyTag = "memory_preferred_fallback",
                    ),
                )
            }
            return TaskRouteResolution(
                result = TaskRouteResult(
                    profile = defaultProfile,
                    score = 0,
                    installed = false,
                    reason = choice.reason.ifBlank { "模型没有给出明确目标 App。" },
                    status = TaskRouteStatus.ROUTING_FAILED,
                ),
                debug = RouteDebugInfo(
                    candidateCount = candidates.size,
                    candidatePreview = candidatePreview,
                    modelRawResponse = choice.rawResponse,
                    memoryHintPreview = memoryHintPreview,
                    fallbackReason = "",
                    modelChoiceProfileId = choice.profileId.orEmpty(),
                    policyTag = "llm_none",
                ),
            )
        }

        val selectedCandidate = candidates.firstOrNull { it.profileId == selectedProfile.id }
        val memoryBiasedCandidate =
            RouteResolutionPolicy.maybeApplyMemoryBias(
                choice = choice,
                selectedCandidate = selectedCandidate,
                preferredMemoryCandidate = preferredMemoryCandidate,
            )
        if (memoryBiasedCandidate != null) {
            val memoryProfile = get(memoryBiasedCandidate.candidate.profileId) ?: selectedProfile
            return TaskRouteResolution(
                result = TaskRouteResult(
                    profile = memoryProfile,
                    score = maxOf((choice.confidence * 100).toInt(), memoryBiasedCandidate.hint.score),
                    installed = true,
                    reason =
                        buildString {
                            append(choice.reason.ifBlank { "模型给出了低置信度候选 ${selectedProfile.displayName}。" })
                            append(" 由于该任务与用户历史偏好高度一致，保守改选为 ${memoryProfile.displayName}。")
                            append(" ").append(memoryBiasedCandidate.hint.summary)
                        },
                    status = TaskRouteStatus.RESOLVED,
                ),
                debug = RouteDebugInfo(
                    candidateCount = candidates.size,
                    candidatePreview = candidatePreview,
                    modelRawResponse = choice.rawResponse,
                    memoryHintPreview = memoryHintPreview,
                    fallbackReason = "memory_bias:${memoryProfile.id}",
                    modelChoiceProfileId = choice.profileId.orEmpty(),
                    selectedProfileId = memoryProfile.id,
                    policyTag = "memory_bias_override",
                ),
            )
        }
        val installed = selectedCandidate?.installed == true
        val fallbackCandidate =
            if (!installed) {
                RouteResolutionPolicy.findInstalledFallbackCandidate(
                    selectedCandidate = selectedCandidate,
                    candidates = candidates,
                    preferredMemoryCandidate = preferredMemoryCandidate,
                )
            } else {
                null
            }
        val finalProfile = fallbackCandidate?.let { get(it.profileId) } ?: selectedProfile
        val finalInstalled = fallbackCandidate?.installed ?: installed
        val fallbackReason =
            if (fallbackCandidate != null) {
                "原目标 ${selectedProfile.displayName} 不可用，自动回退到已安装同类应用 ${finalProfile.displayName}。"
            } else {
                ""
            }
        return TaskRouteResolution(
            result = TaskRouteResult(
                profile = finalProfile,
                score = (choice.confidence * 100).toInt(),
                installed = finalInstalled,
                reason =
                    buildString {
                        append(choice.reason.ifBlank { "模型选择 ${selectedProfile.displayName}。" })
                        if (fallbackReason.isNotBlank()) {
                            append(" ").append(fallbackReason)
                        }
                    },
                status = if (finalInstalled) TaskRouteStatus.RESOLVED else TaskRouteStatus.APP_UNAVAILABLE,
            ),
            debug = RouteDebugInfo(
                candidateCount = candidates.size,
                candidatePreview = candidatePreview,
                modelRawResponse = choice.rawResponse,
                memoryHintPreview = memoryHintPreview,
                fallbackReason = fallbackReason,
                modelChoiceProfileId = choice.profileId.orEmpty(),
                selectedProfileId = finalProfile.id,
                policyTag = if (fallbackCandidate != null) "installed_category_fallback" else "llm_direct",
            ),
        )
    }

    private fun curatedCategory(profileId: String): String? =
        when (profileId) {
            settingsProfile.id -> "system"
            ShoppingTaskProfile.id,
            xianyuProfile.id,
            jdProfile.id,
            pddProfile.id,
            -> "shopping"

            meituanProfile.id -> "local_service"
            amapProfile.id -> "navigation"
            wechatProfile.id -> "messaging"
            alipayProfile.id -> "payment"
            else -> null
        }

    internal data class LocalSemanticRouteFallback(
        val profileId: String,
        val score: Int,
        val reason: String,
    )

    internal fun chooseLocalSemanticFallback(
        task: String,
        candidates: List<LlmRouteCandidate>,
        routeMemoryHints: List<RouteMemoryHint> = emptyList(),
    ): LocalSemanticRouteFallback? {
        val constraints = TaskIntentParser.parse(task)
        val normalizedTask = task.lowercase()
        val memoryByProfile = routeMemoryHints.associateBy { it.profileId }
        val installedCandidates = candidates.filter { it.installed }
        if (installedCandidates.isEmpty()) return null

        val scored: List<Pair<LlmRouteCandidate, Int>> =
            installedCandidates.map { candidate ->
                val candidateText =
                    buildString {
                        append(candidate.displayName).append(' ')
                        append(candidate.packageName).append(' ')
                        append(candidate.capabilitySummary).append(' ')
                        append(candidate.aliases.joinToString(" ")).append(' ')
                        append(candidate.semanticTags.joinToString(" "))
                    }.lowercase()
                val explicitAliasScore =
                    candidate.aliases.maxOfOrNull { alias ->
                        val normalizedAlias = alias.trim().lowercase()
                        when {
                            normalizedAlias.isBlank() -> 0
                            normalizedTask.contains(normalizedAlias) -> 180 + normalizedAlias.length * 3
                            else -> 0
                        }
                    } ?: 0
                val displayNameScore =
                    candidate.displayName
                        .takeIf { it.isNotBlank() && normalizedTask.contains(it.lowercase()) }
                        ?.let { 120 + it.length * 2 }
                        ?: 0
                val packageTokenScore =
                    extractPackageTokens(candidate.packageName).fold(0) { acc, token ->
                        acc + if (token.length >= 3 && normalizedTask.contains(token)) 16 else 0
                    }
                val intentCategoryScore =
                    if (candidate.category == categoryForIntent(constraints.intentType)) 110 else 0
                val goalTokenScore =
                    constraints.keywordHints.distinct().fold(0) { acc, hint ->
                        val normalizedHint = hint.lowercase()
                        acc + when {
                            normalizedHint.length < 2 -> 0
                            candidateText.contains(normalizedHint) -> 12 + normalizedHint.length
                            else -> 0
                        }
                    }
                val capabilityScore =
                    listOfNotNull(
                        constraints.objectiveHint,
                        constraints.destination,
                        constraints.recipientName,
                        constraints.searchQuery,
                    ).fold(0) { acc, signal ->
                        val normalizedSignal = signal.lowercase()
                        acc + if (normalizedSignal.isNotBlank() && candidateText.contains(normalizedSignal)) 18 else 0
                    }
                val memoryScore = (memoryByProfile[candidate.profileId]?.score ?: 0) / 4
                val totalScore =
                    explicitAliasScore +
                        displayNameScore +
                        packageTokenScore +
                        intentCategoryScore +
                        goalTokenScore +
                        capabilityScore +
                        memoryScore
                Pair(candidate, totalScore)
            }.sortedByDescending { it.second }

        val best = scored.firstOrNull() ?: return null
        val runnerUp = scored.getOrNull(1)
        val thresholdMet = best.second >= 110
        val marginEnough = runnerUp == null || best.second - runnerUp.second >= 24
        if (!thresholdMet || !marginEnough) return null

        val bestCandidate = best.first
        return LocalSemanticRouteFallback(
            profileId = bestCandidate.profileId,
            score = best.second,
            reason =
                buildString {
                    append("本地语义兜底选择 ${bestCandidate.displayName}")
                    bestCandidate.category?.let { append("，category=").append(it) }
                    if (bestCandidate.aliases.isNotEmpty()) {
                        append("，aliases=").append(bestCandidate.aliases.take(3).joinToString("/"))
                    }
                    memoryByProfile[bestCandidate.profileId]?.summary?.takeIf { it.isNotBlank() }?.let {
                        append("，memory=").append(it.take(48))
                    }
                },
        )
    }

    private fun resolveDirectSystemRoute(
        task: String,
        packageManager: android.content.pm.PackageManager?,
    ): TaskRouteResolution? {
        if (!looksLikeSystemSettingsTask(task)) return null
        val installed =
            packageManager?.let { pm ->
                AppLaunchResolver.resolve(pm, settingsProfile.packageName) != null
            } ?: true
        return TaskRouteResolution(
            result =
                TaskRouteResult(
                    profile = settingsProfile,
                    score = 100,
                    installed = installed,
                    reason =
                        if (installed) {
                            "任务显式要求打开系统设置，跳过通用 App 路由，直接进入系统设置。"
                        } else {
                            "任务显式要求打开系统设置，但当前设备无法解析系统设置入口。"
                        },
                    status = if (installed) TaskRouteStatus.RESOLVED else TaskRouteStatus.APP_UNAVAILABLE,
                ),
            debug =
                RouteDebugInfo(
                    candidateCount = 1,
                    candidatePreview = listOf("${settingsProfile.displayName}(${settingsProfile.packageName})"),
                    modelRawResponse = "",
                    memoryHintPreview = emptyList(),
                    fallbackReason = "direct_system_settings",
                    modelChoiceProfileId = settingsProfile.id,
                    selectedProfileId = settingsProfile.id,
                    policyTag = "direct_system_settings",
                ),
        )
    }

    private fun looksLikeSystemSettingsTask(
        task: String,
    ): Boolean {
        val normalizedTask = task.trim()
        if (normalizedTask.isBlank()) return false
        if (directSystemSettingsPatterns.any { it.containsMatchIn(normalizedTask) }) {
            return true
        }
        return directSystemSettingsKeywords.any { keyword ->
            normalizedTask.contains(keyword, ignoreCase = true)
        }
    }

    private fun categoryForIntent(intentType: TaskIntentType): String? =
        when (intentType) {
            TaskIntentType.NAVIGATION -> "navigation"
            TaskIntentType.SHOPPING -> "shopping"
            TaskIntentType.MESSAGING -> "messaging"
            TaskIntentType.CONTENT -> null
            TaskIntentType.GENERIC -> null
        }

    private fun extractPackageTokens(packageName: String): List<String> =
        packageName
            .split(tokenSplitRegex)
            .map { it.trim().lowercase() }
            .filter { it.length >= 2 && it !in setOf("com", "android", "app") }
}

data class TaskRouteResolution(
    val result: TaskRouteResult,
    val debug: TaskRegistry.RouteDebugInfo,
)
