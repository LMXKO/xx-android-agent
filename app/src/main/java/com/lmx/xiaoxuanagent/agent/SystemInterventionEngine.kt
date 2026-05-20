package com.lmx.xiaoxuanagent.agent

object SystemInterventionEngine {
    private val trustedSystemPackages =
        setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.coloros.codebook",
        )

    private val softDismissKeywords =
        listOf("跳过", "关闭", "取消", "暂不", "稍后", "以后再说", "知道了", "我知道了", "残忍拒绝")

    private val permissionAllowKeywords =
        listOf("允许", "仅在使用中允许", "始终允许", "去开启", "开启权限", "允许访问", "同意并继续")

    private val chooserKeywords =
        listOf("仅此一次", "始终", "打开方式", "选择应用", "分享至", "分享给")

    fun decide(
        task: String,
        observation: ScreenObservation,
        visualContext: VisualPerceptionContext,
    ): AgentDecision? {
        if (shouldSuppressVisualOnlyIntervention(observation, visualContext)) {
            return null
        }
        resolvePermissionDialog(task, observation, visualContext)?.let { return it }
        resolveChooserDialog(observation, visualContext)?.let { return it }
        resolveSoftPopup(observation, visualContext)?.let { return it }
        return null
    }

    private fun shouldSuppressVisualOnlyIntervention(
        observation: ScreenObservation,
        visualContext: VisualPerceptionContext,
    ): Boolean {
        if (observation.packageName in trustedSystemPackages) return false
        if (observation.elements.isEmpty()) return true
        if (observation.interruptiveHints.isNotEmpty()) return false
        if (visualContext.groundedTexts.any { !it.matchedElementId.isNullOrBlank() }) return false
        return visualContext.visualHints.isNotEmpty() || visualContext.ocrTexts.isNotEmpty()
    }

    private fun resolvePermissionDialog(
        task: String,
        observation: ScreenObservation,
        visualContext: VisualPerceptionContext,
    ): AgentDecision? {
        val semantics =
            (observation.topTexts + observation.interruptiveHints.map { it.text } + visualContext.visualHints)
                .joinToString(" ")
        val looksLikePermission =
            listOf("权限", "访问", "位置", "通知", "相机", "麦克风", "照片", "存储", "通讯录").any {
                semantics.contains(it)
            }
        if (!looksLikePermission) return null

        val shouldAllow =
            when {
                task.contains("导航") || task.contains("路线") || task.contains("附近") -> semantics.contains("位置")
                task.contains("扫码") || task.contains("拍照") -> semantics.contains("相机")
                task.contains("图片") || task.contains("相册") -> semantics.contains("照片") || semantics.contains("存储")
                else -> semantics.contains("通知") && task.contains("通知")
            }

        val keywords = if (shouldAllow) permissionAllowKeywords else softDismissKeywords + listOf("不允许", "拒绝")
        val decision = resolveByKeywords(keywords, observation, visualContext) ?: return null
        return decision.copy(
            reason =
                if (shouldAllow) {
                    "系统工具层：当前权限与任务直接相关，允许继续。"
                } else {
                    "系统工具层：当前权限或授权提示与主任务无直接关系，优先拒绝或稍后。"
                },
        )
    }

    private fun resolveChooserDialog(
        observation: ScreenObservation,
        visualContext: VisualPerceptionContext,
    ): AgentDecision? {
        val semantics = observation.topTexts + visualContext.visualHints
        val looksLikeChooser = chooserKeywords.any { keyword -> semantics.any { it.contains(keyword) } }
        if (!looksLikeChooser) return null

        return resolveByKeywords(listOf("仅此一次", "继续", "打开", "进入"), observation, visualContext)?.copy(
            reason = "系统工具层：当前像系统选择器或分享面板，优先走最小侵入的一次性继续路径。",
        )
    }

    private fun resolveSoftPopup(
        observation: ScreenObservation,
        visualContext: VisualPerceptionContext,
    ): AgentDecision? {
        val hasInterrupt = observation.interruptiveHints.isNotEmpty() || visualContext.visualHints.isNotEmpty()
        if (!hasInterrupt) return null
        return resolveByKeywords(softDismissKeywords, observation, visualContext)?.copy(
            reason = "系统工具层：检测到遮挡主流程的软弹窗，优先关闭或跳过。",
        )
    }

    private fun resolveByKeywords(
        keywords: List<String>,
        observation: ScreenObservation,
        visualContext: VisualPerceptionContext,
    ): AgentDecision? {
        val element =
            observation.elements.firstOrNull { element ->
                element.clickable && element.enabled &&
                    keywords.any { keyword ->
                        element.text.contains(keyword) || element.viewId.contains(keyword, ignoreCase = true)
                    }
            }
        if (element != null) {
            val targetText =
                deriveSemanticTargetText(
                    primaryText = element.text,
                    fallbackText = element.viewId.ifBlank { element.id },
                    keywords = keywords,
                )
            if (targetText.isBlank()) return null
            return AgentDecision(
                action = AgentAction.OpenBestCandidate(targetText = targetText, roleHint = "system_action"),
                reason = "系统工具层命中节点 ${element.text.ifBlank { element.viewId }}，改用语义化目标打开。",
                rawResponse = """{"tool":"system_intervention","action":"open_best_candidate","target_text":"$targetText","role_hint":"system_action"}""",
            )
        }

        val grounded =
            visualContext.groundedTexts.firstOrNull { candidate ->
                keywords.any { keyword -> candidate.text.contains(keyword, ignoreCase = true) }
            }
        if (grounded != null) {
            if (observation.packageName !in trustedSystemPackages) {
                val matchedTarget =
                    deriveSemanticTargetText(
                        primaryText = grounded.matchedElementText.ifBlank { grounded.text },
                        fallbackText = grounded.text.ifBlank { grounded.matchedElementId.orEmpty() },
                        keywords = keywords,
                    )
                if (matchedTarget.isBlank()) return null
                return AgentDecision(
                    action = AgentAction.OpenBestCandidate(targetText = matchedTarget, roleHint = "system_action"),
                    reason = "系统工具层通过视觉文字 ${grounded.text} 对齐到语义候选，优先走语义打开。",
                    rawResponse = """{"tool":"system_intervention","action":"open_best_candidate","target_text":"$matchedTarget","role_hint":"system_action"}""",
                )
            }
            val point = boundsCenter(grounded.bounds) ?: return null
            return AgentDecision(
                action = AgentAction.TapPoint(point.first, point.second),
                reason = "系统工具层通过视觉文字 ${grounded.text} 触发坐标点击。",
                rawResponse = """{"tool":"system_intervention","action":"tap_point","x":${point.first},"y":${point.second}}""",
            )
        }

        if (observation.packageName !in trustedSystemPackages) {
            return null
        }

        val visual =
            visualContext.ocrTexts.firstOrNull { block ->
                keywords.any { keyword -> block.text.contains(keyword, ignoreCase = true) }
            }?.let { block ->
                GroundCandidate(
                    bounds = block.bounds,
                    text = block.text,
                )
            }
        if (visual != null) {
            val point = boundsCenter(visual.bounds) ?: return null
            return AgentDecision(
                action = AgentAction.TapPoint(point.first, point.second),
                reason = "系统工具层通过 OCR 文字 ${visual.text} 触发坐标点击。",
                rawResponse = """{"tool":"system_intervention","action":"tap_point","x":${point.first},"y":${point.second}}""",
            )
        }

        return null
    }

    private fun boundsCenter(
        bounds: String,
    ): Pair<Int, Int>? {
        val matches = Regex("""-?\d+""").findAll(bounds).map { it.value.toInt() }.toList()
        if (matches.size < 4) return null
        val left = matches[0]
        val top = matches[1]
        val right = matches[2]
        val bottom = matches[3]
        return ((left + right) / 2) to ((top + bottom) / 2)
    }

    private fun deriveSemanticTargetText(
        primaryText: String,
        fallbackText: String,
        keywords: List<String>,
    ): String {
        val candidates = listOf(primaryText.trim(), fallbackText.trim()).filter { it.isNotBlank() }
        val matchedKeyword =
            keywords
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .sortedByDescending { it.length }
                .firstOrNull { keyword ->
                    candidates.any { candidate -> candidate.contains(keyword, ignoreCase = true) }
                }
        if (!matchedKeyword.isNullOrBlank()) {
            return matchedKeyword
        }
        return candidates.firstOrNull { text ->
            text.length <= 8 && !looksInformationalStatusText(text)
        }.orEmpty()
    }

    private fun looksInformationalStatusText(
        text: String,
    ): Boolean {
        if (text.isBlank()) return false
        if ((text.contains('，') || text.contains(',') || text.contains('。') || text.contains(':') || text.contains('：')) && text.length >= 10) {
            return true
        }
        return listOf(
            Regex(""".+已(登录|关闭|开启|连接|同步|完成).*"""),
            Regex(""".*(Mac|Windows|电脑|平板).*(已登录|在线|同步).*"""),
        ).any { it.containsMatchIn(text) }
    }

    private data class GroundCandidate(
        val bounds: String,
        val text: String,
        val matchedElementText: String = "",
    )
}
