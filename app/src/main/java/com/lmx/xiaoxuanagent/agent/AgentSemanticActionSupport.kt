package com.lmx.xiaoxuanagent.agent

internal object AgentSemanticActionResolver {
    fun resolve(
        indexedObservation: IndexedScreenObservation,
        action: AgentAction,
    ): AgentAction? {
        val observation = indexedObservation.observation
        return when (action) {
            AgentAction.FocusPrimaryInput -> resolveFocusPrimaryInput(observation)
            is AgentAction.PopulatePrimaryInput -> resolvePopulatePrimaryInput(indexedObservation, action)
            is AgentAction.SubmitPrimaryAction -> resolveSubmitPrimaryAction(indexedObservation, action)
            is AgentAction.DismissInterrupt -> resolveDismissInterrupt(indexedObservation, action)
            is AgentAction.OpenBestCandidate -> resolveOpenBestCandidate(indexedObservation, action)
            is AgentAction.ScrollForMore ->
                AgentAction.Scroll(
                    elementId = observation.defaultScrollableId,
                    direction = action.direction.trim().ifBlank { "down" },
                )
            is AgentAction.ReturnToTargetApp ->
                action.packageName.takeIf { it.isNotBlank() && !it.equals(observation.packageName, ignoreCase = true) }
                    ?.let(AgentAction::LaunchApp)
            is AgentAction.NavigateBack -> resolveNavigateBack(observation, action)
            else -> action
        }
    }

    internal fun resolveFocusPrimaryInput(
        observation: ScreenObservation,
    ): AgentAction? {
        val targetId =
            observation.focusedElementId
                ?.takeIf { it == observation.primaryEditableId }
                ?: observation.primaryEditableId
                ?: observation.elements.firstOrNull { it.editable && it.enabled }?.id
        return targetId?.let(AgentAction::Click)
    }

    internal fun resolvePopulatePrimaryInput(
        indexedObservation: IndexedScreenObservation,
        action: AgentAction.PopulatePrimaryInput,
    ): AgentAction {
        val observation = indexedObservation.observation
        val targetId =
            observation.primaryEditableId
                ?: observation.elements.firstOrNull { it.editable && it.enabled }?.id
        return if (targetId != null) {
            AgentAction.SetText(targetId, action.text)
        } else {
            bestCandidate(indexedObservation = indexedObservation, targetText = action.text, roleHint = "entry")
                ?.let { AgentAction.Click(it.id) }
                ?: AgentAction.OpenBestCandidate(targetText = action.text, roleHint = "entry")
        }
    }

    internal fun resolveSubmitPrimaryAction(
        indexedObservation: IndexedScreenObservation,
        action: AgentAction.SubmitPrimaryAction,
    ): AgentAction {
        val observation = indexedObservation.observation
        val preferredKeywords =
            buildList {
                action.hint.trim().takeIf { it.isNotBlank() }?.let(::add)
                addAll(listOf("发送", "确认", "完成", "搜索", "查找", "前往", "导航", "开始导航", "路线", "进入", "打开"))
            }
        val candidate =
            bestCandidate(
                indexedObservation = indexedObservation,
                targetText = action.hint,
                roleHint = "submit",
                additionalKeywords = preferredKeywords,
            )
        return when {
            candidate != null -> AgentAction.Click(candidate.id)
            !observation.primaryEditableId.isNullOrBlank() -> AgentAction.PressEnter
            else -> AgentAction.Wait
        }
    }

    internal fun resolveDismissInterrupt(
        indexedObservation: IndexedScreenObservation,
        action: AgentAction.DismissInterrupt,
    ): AgentAction {
        val observation = indexedObservation.observation
        observation.primaryInterruptActionId?.let { return AgentAction.Click(it) }
        val hintCandidate =
            observation.interruptiveHints
                .firstOrNull { hint ->
                    hint.elementId.isNotBlank() &&
                        listOf(hint.text, hint.reason, action.hint).any { semantic ->
                            matchesInterruptKeyword(semantic)
                        }
                }?.elementId
        if (!hintCandidate.isNullOrBlank()) {
            return AgentAction.Click(hintCandidate)
        }
        val candidate =
            bestCandidate(
                indexedObservation = indexedObservation,
                targetText = action.hint,
                roleHint = "dismiss",
                additionalKeywords = listOf("关闭", "跳过", "取消", "知道了", "稍后", "暂不", "我知道了", "以后再说"),
            )
        return candidate?.let { AgentAction.Click(it.id) } ?: AgentAction.Wait
    }

    internal fun resolveOpenBestCandidate(
        indexedObservation: IndexedScreenObservation,
        action: AgentAction.OpenBestCandidate,
    ): AgentAction {
        val observation = indexedObservation.observation
        val candidate =
            bestCandidate(
                indexedObservation = indexedObservation,
                targetText = action.targetText,
                roleHint = action.roleHint,
            )
        return candidate?.let { AgentAction.Click(it.id) }
            ?: observation.defaultScrollableId?.let { AgentAction.Scroll(it, "down") }
            ?: AgentAction.Wait
    }

    internal fun resolveNavigateBack(
        observation: ScreenObservation,
        action: AgentAction.NavigateBack,
    ): AgentAction =
        if (action.preferScroll && !observation.defaultScrollableId.isNullOrBlank()) {
            AgentAction.Scroll(observation.defaultScrollableId, "up")
        } else {
            AgentAction.Back
        }

    internal fun bestCandidate(
        indexedObservation: IndexedScreenObservation,
        targetText: String = "",
        roleHint: String = "",
        additionalKeywords: List<String> = emptyList(),
    ): UiElementObservation? {
        val observation = indexedObservation.observation
        val policy = AgentSemanticCandidatePolicies.forRole(roleHint)
        val keywords =
            (
                listOf(targetText) +
                    policy.roleKeywords +
                    additionalKeywords
                )
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        CascadedGroundingSearch.search(
            indexedObservation = indexedObservation,
            targetText = targetText,
            roleHint = roleHint,
            additionalKeywords = additionalKeywords,
        )?.let { return it }
        return (observation.elements.asSequence() + indexedObservation.searchElementsById.values.asSequence())
            .filter { it.enabled && it.clickable }
            .filter { element -> policy.accepts(element, keywords) }
            .map { element -> element to scoreCandidate(element, keywords, policy) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<UiElementObservation, Int>> { it.second }
                    .thenBy { if (it.first.source == "overflow_hidden") 1 else 0 }
                    .thenByDescending { it.first.text.length }
                    .thenBy { it.first.id },
            ).map { it.first }
            .firstOrNull()
    }

    private fun scoreCandidate(
        element: UiElementObservation,
        keywords: List<String>,
        policy: AgentSemanticCandidatePolicy,
    ): Int {
        val semantic = AgentSemanticCandidatePolicies.buildSemanticText(element)
        val keywordScore =
            keywords.fold(0) { total, keyword ->
                total +
                    when {
                        element.text.equals(keyword, ignoreCase = true) -> 100 + keyword.length
                        element.text.contains(keyword, ignoreCase = true) -> 72 + keyword.length
                        semantic.contains(keyword, ignoreCase = true) -> 28 + keyword.length
                        else -> 0
                    }
            }
        if (keywordScore <= 0 && keywords.isNotEmpty()) return 0
        val roleBoost = policy.roleBoost(element)
        val metaPenalty = if (looksMetaControl(element)) 48 else 0
        val neighborBoost =
            keywords.fold(0) { total, keyword ->
                total + if (element.neighborTexts.any { it.contains(keyword, ignoreCase = true) }) 16 else 0
            }
        val structureBoost =
            buildList {
                if (element.roleHint.isNotBlank() && element.roleHint.equals(policy.roleHint, ignoreCase = true)) add(22)
                if (element.containerId.isNotBlank()) add(8)
                if (element.rowIndex >= 0) add(6)
                if (element.source == "visual" && keywords.isNotEmpty()) add(12)
                if (element.source.contains("crop", ignoreCase = true)) add(14)
                if (element.source == "overflow_tree") add(10)
                if (element.source == "overflow_recall") add(8)
                if (element.source == "overflow_hidden") add(6)
                if (element.source == "tree") add(6)
                if (element.accessibilityUniqueId.isNotBlank()) add(6)
                if (element.accessibilityLabel.isNotBlank() && element.text.isBlank()) add(8)
                if (element.visualDescriptorTokens.isNotEmpty()) add(8)
                if (element.visualSignature.isNotBlank()) add(6)
                if (element.interactionRegions.isNotEmpty()) add(4)
            }.sum()
        return (keywordScore + roleBoost + neighborBoost + structureBoost - metaPenalty).coerceAtLeast(0)
    }

    private fun looksMetaControl(
        element: UiElementObservation,
    ): Boolean {
        val semantic = listOf(element.text, element.viewId).joinToString(" ")
        return listOf("综合", "筛选", "更多", "店铺", "频道", "首页", "返回", "菜单").any {
            semantic.contains(it, ignoreCase = true)
        }
    }

    private fun matchesInterruptKeyword(
        semantic: String,
    ): Boolean {
        if (semantic.isBlank()) return false
        return listOf("关闭", "跳过", "取消", "稍后", "知道了", "以后再说", "dismiss", "close").any {
            semantic.contains(it, ignoreCase = true)
        }
    }
}
