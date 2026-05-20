package com.lmx.xiaoxuanagent.agent

import android.view.accessibility.AccessibilityNodeInfo
import com.lmx.xiaoxuanagent.accessibility.allNodes
import com.lmx.xiaoxuanagent.accessibility.accessibilityContainerTitle
import com.lmx.xiaoxuanagent.accessibility.accessibilityLabel
import com.lmx.xiaoxuanagent.accessibility.accessibilityPaneTitle
import com.lmx.xiaoxuanagent.accessibility.accessibilityStateDescription
import com.lmx.xiaoxuanagent.accessibility.accessibilityUniqueId
import com.lmx.xiaoxuanagent.accessibility.boundsRect
import com.lmx.xiaoxuanagent.accessibility.collectionPositionSignature
import com.lmx.xiaoxuanagent.accessibility.readableText
import com.lmx.xiaoxuanagent.accessibility.supportsAction
import com.lmx.xiaoxuanagent.runtime.AppPageState
import kotlin.math.abs
import java.security.MessageDigest

object ObservationBuilder {
    private const val MAX_ELEMENTS = 64
    private const val MAX_SECONDARY_TARGETS = 12
    private const val MAX_RECALL_TARGETS = 16
    private const val MAX_HIDDEN_SEARCH_TARGETS = 96
    private const val MAX_INTERRUPTIVE_HINTS = 6
    private const val ROW_GROUP_THRESHOLD_PX = 56
    private const val MIN_CONTAINER_BUDGET = 2
    private const val MAX_CONTAINER_BUDGET = 6

    private val interruptiveTextKeywords =
        listOf(
            "跳过",
            "关闭",
            "暂不",
            "稍后",
            "以后再说",
            "下次再说",
            "知道了",
            "我知道了",
            "取消",
            "不了",
            "不用了",
            "残忍拒绝",
            "允许",
            "同意",
            "继续使用",
            "继续访问",
            "继续浏览",
            "去开启",
            "开启权限",
            "开启通知",
            "开启定位",
            "立即开启",
        )

    private val interruptiveViewIdKeywords =
        listOf(
            "close",
            "skip",
            "cancel",
            "dialog",
            "popup",
            "guide",
            "mask",
            "overlay",
            "dismiss",
        )

    fun buildIndexed(
        root: AccessibilityNodeInfo,
        pageState: AppPageState,
    ): IndexedScreenObservation {
        val viewportBounds = root.boundsRect()
        val snapshots = collectNodeSnapshots(root)
        val allNodes = snapshots.map { it.node }
        val visibleTexts = allNodes.map { it.readableText() }.filter { it.isNotBlank() }.distinct().take(8)
        val visibleNodeCount = allNodes.count { it.isVisibleToUser }
        val textNodeCount = allNodes.count { it.isVisibleToUser && it.readableText().isNotBlank() }
        val candidates = snapshots
            .mapNotNull(::toCandidate)
            .sortedWith(
                compareByDescending<NodeCandidate> { it.score }
                    .thenBy { it.bounds.top }
                    .thenBy { it.bounds.left },
            )

        val deduped = LinkedHashMap<String, NodeCandidate>()
        candidates.forEach { candidate ->
            deduped.putIfAbsent(candidate.stableKey, candidate)
        }

        val (topCandidates, overflowCandidates) = selectTopCandidates(deduped.values.toList())
        val secondaryCandidates = selectSecondaryCandidates(overflowCandidates)
        val remainingOverflowCandidates =
            overflowCandidates.filterNot { overflow ->
                secondaryCandidates.any { secondary -> secondary.stableKey == overflow.stableKey }
            }
        val recallCandidates = selectRecallCandidates(remainingOverflowCandidates)
        val tailOverflowCandidates =
            remainingOverflowCandidates.filterNot { overflow ->
                recallCandidates.any { recall -> recall.stableKey == overflow.stableKey }
            }
        val hiddenSearchCandidates = tailOverflowCandidates.take(MAX_HIDDEN_SEARCH_TARGETS)
        val selectedIdsByStableKey =
            topCandidates.mapIndexed { index, candidate ->
                candidate.stableKey to "e${(index + 1).toString().padStart(2, '0')}"
            }.toMap()
        val secondaryIdsByStableKey =
            secondaryCandidates.mapIndexed { index, candidate ->
                candidate.stableKey to "s${(index + 1).toString().padStart(2, '0')}"
            }.toMap()
        val recallIdsByStableKey =
            recallCandidates.mapIndexed { index, candidate ->
                candidate.stableKey to "r${(index + 1).toString().padStart(2, '0')}"
            }.toMap()
        val hiddenIdsByStableKey =
            hiddenSearchCandidates.mapIndexed { index, candidate ->
                candidate.stableKey to "h${(index + 1).toString().padStart(2, '0')}"
            }.toMap()
        val allSelectedIdsByStableKey =
            selectedIdsByStableKey + secondaryIdsByStableKey + recallIdsByStableKey + hiddenIdsByStableKey
        val containerAliases =
            buildContainerAliases(topCandidates + secondaryCandidates + recallCandidates + hiddenSearchCandidates)
        val indexedCandidates =
            topCandidates.map { candidate ->
                val elementId = selectedIdsByStableKey.getValue(candidate.stableKey)
                IndexedCandidate(
                    candidate = candidate,
                    element =
                        UiElementObservation(
                            id = elementId,
                            text = candidate.text,
                            viewId = candidate.viewId,
                            className = candidate.className,
                            bounds = candidate.boundsText,
                            clickable = candidate.node.isClickable,
                            editable = candidate.node.isEditable,
                            scrollable = candidate.node.isScrollable,
                            enabled = candidate.node.isEnabled,
                            focused = candidate.node.isFocused,
                            selected = candidate.node.isSelected,
                            parentId = allSelectedIdsByStableKey[candidate.parentStableKey].orEmpty(),
                            containerId = containerAliases[candidate.containerKey].orEmpty(),
                            depth = candidate.depth,
                            collectionPosition = candidate.collectionPosition,
                            accessibilityLabel = candidate.accessibilityLabel,
                            accessibilityUniqueId = candidate.uniqueId,
                            paneTitle = candidate.paneTitle,
                            containerTitle = candidate.containerTitle,
                            stateDescription = candidate.stateDescription,
                            descriptorTokens = candidate.descriptorTokens,
                            visualDescriptorTokens = emptyList(),
                            interactionRegions = emptyList(),
                            visualSignature = buildBaseVisualSignature(candidate, "tree"),
                            spatialSignature = "",
                            roleHint = candidate.roleHint,
                            neighborTexts = candidate.siblingTexts,
                            source = "tree",
                        ),
                        node = candidate.node,
                )
            }
        val secondaryIndexedCandidates =
            secondaryCandidates.map { candidate ->
                val elementId = secondaryIdsByStableKey.getValue(candidate.stableKey)
                IndexedCandidate(
                    candidate = candidate,
                    element =
                        UiElementObservation(
                            id = elementId,
                            text = candidate.text,
                            viewId = candidate.viewId,
                            className = candidate.className,
                            bounds = candidate.boundsText,
                            clickable = candidate.node.isClickable,
                            editable = candidate.node.isEditable,
                            scrollable = candidate.node.isScrollable,
                            enabled = candidate.node.isEnabled,
                            focused = candidate.node.isFocused,
                            selected = candidate.node.isSelected,
                            parentId = allSelectedIdsByStableKey[candidate.parentStableKey].orEmpty(),
                            containerId = containerAliases[candidate.containerKey].orEmpty(),
                            depth = candidate.depth,
                            collectionPosition = candidate.collectionPosition,
                            accessibilityLabel = candidate.accessibilityLabel,
                            accessibilityUniqueId = candidate.uniqueId,
                            paneTitle = candidate.paneTitle,
                            containerTitle = candidate.containerTitle,
                            stateDescription = candidate.stateDescription,
                            descriptorTokens = candidate.descriptorTokens,
                            visualDescriptorTokens = emptyList(),
                            interactionRegions = emptyList(),
                            visualSignature = buildBaseVisualSignature(candidate, "overflow_tree"),
                            spatialSignature = "",
                            roleHint = candidate.roleHint,
                            neighborTexts = candidate.siblingTexts,
                            source = "overflow_tree",
                        ),
                    node = candidate.node,
                )
            }
        val recallIndexedCandidates =
            recallCandidates.map { candidate ->
                val elementId = recallIdsByStableKey.getValue(candidate.stableKey)
                IndexedCandidate(
                    candidate = candidate,
                    element =
                        UiElementObservation(
                            id = elementId,
                            text = candidate.text,
                            viewId = candidate.viewId,
                            className = candidate.className,
                            bounds = candidate.boundsText,
                            clickable = candidate.node.isClickable,
                            editable = candidate.node.isEditable,
                            scrollable = candidate.node.isScrollable,
                            enabled = candidate.node.isEnabled,
                            focused = candidate.node.isFocused,
                            selected = candidate.node.isSelected,
                            parentId = allSelectedIdsByStableKey[candidate.parentStableKey].orEmpty(),
                            containerId = containerAliases[candidate.containerKey].orEmpty(),
                            depth = candidate.depth,
                            collectionPosition = candidate.collectionPosition,
                            accessibilityLabel = candidate.accessibilityLabel,
                            accessibilityUniqueId = candidate.uniqueId,
                            paneTitle = candidate.paneTitle,
                            containerTitle = candidate.containerTitle,
                            stateDescription = candidate.stateDescription,
                            descriptorTokens = candidate.descriptorTokens,
                            visualDescriptorTokens = emptyList(),
                            interactionRegions = emptyList(),
                            visualSignature = buildBaseVisualSignature(candidate, "overflow_recall"),
                            spatialSignature = "",
                            roleHint = candidate.roleHint,
                            neighborTexts = candidate.siblingTexts,
                            source = "overflow_recall",
                        ),
                    node = candidate.node,
                )
            }
        val hiddenIndexedCandidates =
            hiddenSearchCandidates.map { candidate ->
                val elementId = hiddenIdsByStableKey.getValue(candidate.stableKey)
                IndexedCandidate(
                    candidate = candidate,
                    element =
                        UiElementObservation(
                            id = elementId,
                            text = candidate.text,
                            viewId = candidate.viewId,
                            className = candidate.className,
                            bounds = candidate.boundsText,
                            clickable = candidate.node.isClickable,
                            editable = candidate.node.isEditable,
                            scrollable = candidate.node.isScrollable,
                            enabled = candidate.node.isEnabled,
                            focused = candidate.node.isFocused,
                            selected = candidate.node.isSelected,
                            parentId = allSelectedIdsByStableKey[candidate.parentStableKey].orEmpty(),
                            containerId = containerAliases[candidate.containerKey].orEmpty(),
                            depth = candidate.depth,
                            collectionPosition = candidate.collectionPosition,
                            accessibilityLabel = candidate.accessibilityLabel,
                            accessibilityUniqueId = candidate.uniqueId,
                            paneTitle = candidate.paneTitle,
                            containerTitle = candidate.containerTitle,
                            stateDescription = candidate.stateDescription,
                            descriptorTokens = candidate.descriptorTokens,
                            visualDescriptorTokens = emptyList(),
                            interactionRegions = emptyList(),
                            visualSignature = buildBaseVisualSignature(candidate, "overflow_hidden"),
                            spatialSignature = "",
                            roleHint = candidate.roleHint,
                            neighborTexts = candidate.siblingTexts,
                            source = "overflow_hidden",
                        ),
                    node = candidate.node,
                )
            }
        val allVisibleIndexedCandidates = indexedCandidates + secondaryIndexedCandidates + recallIndexedCandidates
        val elements =
            enrichSpatialMetadata(
                assignGridMetadata(allVisibleIndexedCandidates.map { it.element }),
                viewportBounds = viewportBounds,
            )
        val searchElements =
            enrichSpatialMetadata(
                hiddenIndexedCandidates.map { it.element },
                viewportBounds = viewportBounds,
            )
        val allIndexedCandidates = allVisibleIndexedCandidates + hiddenIndexedCandidates
        val primaryEditable = allVisibleIndexedCandidates.firstOrNull { it.node.isEditable }?.element?.id
        val focusedElement = allVisibleIndexedCandidates.firstOrNull { it.node.isFocused }?.element?.id
        val defaultScrollable = allVisibleIndexedCandidates.firstOrNull { it.node.isScrollable }?.element?.id
        val interruptiveHints =
            allVisibleIndexedCandidates.mapNotNull { indexedCandidate ->
                buildInterruptiveHint(
                    elements.firstOrNull { it.id == indexedCandidate.element.id } ?: indexedCandidate.element,
                )
            }.take(MAX_INTERRUPTIVE_HINTS)
        val primaryInterruptActionId = interruptiveHints.firstOrNull()?.elementId
        val structureHints =
            buildStructureHints(
                elements = elements,
                secondaryCandidates = secondaryCandidates,
                recallCandidates = recallCandidates,
                overflowCandidates = tailOverflowCandidates,
                hiddenSearchCandidates = hiddenSearchCandidates,
            )
        val signature = buildSignature(
            pageState = pageState.label,
            packageName = root.packageName?.toString().orEmpty(),
            elements = elements,
        )

        return IndexedScreenObservation(
            observation = ScreenObservation(
                packageName = root.packageName?.toString().orEmpty(),
                pageState = pageState.label,
                signature = signature,
                screenSummary = buildScreenSummary(
                    pageState = pageState,
                    topTexts = visibleTexts,
                    primaryEditableId = primaryEditable,
                    focusedElementId = focusedElement,
                    defaultScrollableId = defaultScrollable,
                    interruptiveHints = interruptiveHints,
                    totalNodeCount = allNodes.size,
                    visibleNodeCount = visibleNodeCount,
                    textNodeCount = textNodeCount,
                    elements = elements,
                    structureHints = structureHints,
                ),
                topTexts = visibleTexts,
                primaryEditableId = primaryEditable,
                focusedElementId = focusedElement,
                defaultScrollableId = defaultScrollable,
                primaryInterruptActionId = primaryInterruptActionId,
                interruptiveHints = interruptiveHints,
                elements = elements,
                structureHints = structureHints,
            ),
            nodesById = allIndexedCandidates.associate { it.element.id to it.node },
            searchElementsById = searchElements.associateBy { it.id },
        )
    }

    private fun collectNodeSnapshots(
        root: AccessibilityNodeInfo,
    ): List<NodeSnapshot> {
        val result = mutableListOf<NodeSnapshot>()
        val queue = ArrayDeque<Triple<AccessibilityNodeInfo, Int, AccessibilityNodeInfo?>>()
        queue.add(Triple(root, 0, null))
        while (queue.isNotEmpty()) {
            val (node, depth, parent) = queue.removeFirst()
            val siblingTexts =
                parent
                    ?.let { collectReadableTexts(it).filterNot { text -> text == node.readableText().trim() } }
                    .orEmpty()
                    .distinct()
                    .take(6)
            result += NodeSnapshot(node = node, depth = depth, parent = parent, siblingTexts = siblingTexts)
            repeat(node.childCount) { index ->
                node.getChild(index)?.let { child ->
                    queue.add(Triple(child, depth + 1, node))
                }
            }
        }
        return result
    }

    private fun collectReadableTexts(
        node: AccessibilityNodeInfo,
    ): List<String> =
        buildList {
            repeat(node.childCount) { index ->
                node.getChild(index)?.readableText()?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }

    private fun toCandidate(snapshot: NodeSnapshot): NodeCandidate? {
        val node = snapshot.node
        val text = node.readableText().trim()
        val accessibilityLabel = node.accessibilityLabel().trim()
        val uniqueId = node.accessibilityUniqueId().trim()
        val paneTitle = node.accessibilityPaneTitle().trim()
        val containerTitle = node.accessibilityContainerTitle().trim()
        val stateDescription = node.accessibilityStateDescription().trim()
        val collectionPosition = node.collectionPositionSignature().trim()
        val viewId = node.viewIdResourceName.orEmpty()
        val rect = node.boundsRect()
        val area = rect.width() * rect.height()
        val supportsClick = node.supportsAction(AccessibilityNodeInfo.ACTION_CLICK)
        val supportsLongClick = node.supportsAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        val supportsSetText = node.supportsAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
        val supportsScroll =
            node.supportsAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ||
                node.supportsAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        val isLeafText = text.isNotBlank() && node.childCount == 0
        val isInteresting =
            node.isClickable ||
                node.isEditable ||
                node.isScrollable ||
                node.isFocusable ||
                node.isFocused ||
                supportsClick ||
                supportsLongClick ||
                supportsSetText ||
                supportsScroll ||
                isLeafText ||
                text.isNotBlank()
        if (!isInteresting) return null
        if (!node.isVisibleToUser) return null
        if (rect.width() <= 0 || rect.height() <= 0) return null
        if (area >= 3_000_000 && text.isBlank() && viewId.isBlank() && !node.isEditable && !supportsScroll) {
            return null
        }
        if (
            text.isBlank() &&
            viewId.isBlank() &&
            !node.isEditable &&
            !supportsScroll &&
            !supportsClick &&
            !supportsSetText &&
            !node.isFocusable &&
            rect.width() < 200
        ) {
            return null
        }
        val parentStableKey = snapshot.parent?.let(::buildStableNodeKey).orEmpty()
        val containerKey = resolveContainerKey(node, snapshot.parent)
        val roleHint = inferRoleHint(node = node, text = text, viewId = viewId, className = node.className?.toString().orEmpty())
        val descriptorTokens =
            buildDescriptorTokens(
                text = text,
                accessibilityLabel = accessibilityLabel,
                paneTitle = paneTitle,
                containerTitle = containerTitle,
                stateDescription = stateDescription,
                collectionPosition = collectionPosition,
                roleHint = roleHint,
                siblingTexts = snapshot.siblingTexts,
                viewId = viewId,
            )

        return NodeCandidate(
            node = node,
            text = text,
            viewId = viewId,
            className = node.className?.toString().orEmpty(),
            bounds = rect,
            boundsText = "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]",
            depth = snapshot.depth,
            parentStableKey = parentStableKey,
            containerKey = containerKey,
            roleHint = roleHint,
            siblingTexts = snapshot.siblingTexts,
            accessibilityLabel = accessibilityLabel,
            uniqueId = uniqueId,
            paneTitle = paneTitle,
            containerTitle = containerTitle,
            stateDescription = stateDescription,
            collectionPosition = collectionPosition,
            descriptorTokens = descriptorTokens,
            score =
                scoreNode(
                    node = node,
                    text = text,
                    accessibilityLabel = accessibilityLabel,
                    viewId = viewId,
                    area = area,
                    supportsClick = supportsClick,
                    supportsLongClick = supportsLongClick,
                    supportsSetText = supportsSetText,
                    supportsScroll = supportsScroll,
                    isLeafText = isLeafText,
                    depth = snapshot.depth,
                    roleHint = roleHint,
                    siblingTexts = snapshot.siblingTexts,
                ),
            stableKey = buildStableNodeKey(node),
        )
    }

    private fun scoreNode(
        node: AccessibilityNodeInfo,
        text: String,
        accessibilityLabel: String,
        viewId: String,
        area: Int,
        supportsClick: Boolean,
        supportsLongClick: Boolean,
        supportsSetText: Boolean,
        supportsScroll: Boolean,
        isLeafText: Boolean,
        depth: Int,
        roleHint: String,
        siblingTexts: List<String>,
    ): Int {
        var score = 0
        if (node.isEditable) score += 220
        if (node.isFocused) score += 150
        if (node.isScrollable) score += 120
        if (node.isClickable) score += 90
        if (node.isFocusable) score += 55
        if (supportsSetText) score += 90
        if (supportsScroll) score += 60
        if (supportsClick) score += 45
        if (supportsLongClick) score += 20
        if (node.isEnabled) score += 20
        if (viewId.isNotBlank()) score += 40
        if (text.isNotBlank()) score += minOf(text.length, 24) * 3
        if (accessibilityLabel.isNotBlank() && accessibilityLabel != text) score += 34
        if (isLeafText) score += 40
        if (node.className?.toString()?.contains("Edit", ignoreCase = true) == true) score += 80
        if (node.className?.toString()?.contains("Button", ignoreCase = true) == true) score += 50
        if (node.className?.toString()?.contains("TextView", ignoreCase = true) == true && text.isNotBlank()) score += 35
        if (isLikelyInterruptive(text = text, viewId = viewId, className = node.className?.toString().orEmpty())) score += 160
        if (roleHint == "submit" || roleHint == "dismiss" || roleHint == "conversation") score += 24
        if (roleHint == "title") score += 18
        if (roleHint == "meta") score -= 24
        if (depth in 2..8) score += 18 else if (depth > 12) score -= 16
        if (siblingTexts.isNotEmpty()) score += 8
        if (area > 600_000 && text.isBlank() && viewId.isBlank() && !node.isEditable) score -= 80
        return score
    }

    private fun buildSignature(
        pageState: String,
        packageName: String,
        elements: List<UiElementObservation>,
    ): String {
        val raw = buildString {
            append(pageState).append('\n')
            append(packageName).append('\n')
            elements.forEach { element ->
                append(element.id).append('|')
                append(element.text).append('|')
                append(element.viewId).append('|')
                append(element.className).append('|')
                append(element.bounds).append('|')
                append(element.clickable).append('|')
                append(element.editable).append('|')
                append(element.scrollable).append('|')
                append(element.enabled).append('|')
                append(element.focused).append('|')
                append(element.selected).append('|')
                append(element.parentId).append('|')
                append(element.containerId).append('|')
                append(element.depth).append('|')
                append(element.rowIndex).append('|')
                append(element.columnIndex).append('|')
                append(element.collectionPosition).append('|')
                append(element.accessibilityLabel).append('|')
                append(element.accessibilityUniqueId).append('|')
                append(element.containerTitle).append('|')
                append(element.paneTitle).append('|')
                append(element.stateDescription).append('|')
                append(element.roleHint).append('|')
                append(element.source).append('\n')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun buildScreenSummary(
        pageState: AppPageState,
        topTexts: List<String>,
        primaryEditableId: String?,
        focusedElementId: String?,
        defaultScrollableId: String?,
        interruptiveHints: List<InterruptiveHint>,
        totalNodeCount: Int,
        visibleNodeCount: Int,
        textNodeCount: Int,
        elements: List<UiElementObservation>,
        structureHints: List<String>,
    ): String {
        val clickableCount = elements.count { it.clickable }
        val editableCount = elements.count { it.editable }
        val scrollableCount = elements.count { it.scrollable }
        val topHints = topTexts.take(4).joinToString(" / ")
        val interruptHints = interruptiveHints.take(3).joinToString(" / ") { "${it.elementId}:${it.text}" }
        val structureHead = structureHints.take(3).joinToString(" | ")
        return buildString {
            append("page=").append(pageState.label)
            append(", editable=").append(editableCount)
            append(", clickable=").append(clickableCount)
            append(", scrollable=").append(scrollableCount)
            append(", rawNodes=").append(totalNodeCount)
            append(", visibleNodes=").append(visibleNodeCount)
            append(", textNodes=").append(textNodeCount)
            if (!primaryEditableId.isNullOrBlank()) append(", primaryEditable=").append(primaryEditableId)
            if (!focusedElementId.isNullOrBlank()) append(", focused=").append(focusedElementId)
            if (!defaultScrollableId.isNullOrBlank()) append(", defaultScrollable=").append(defaultScrollableId)
            if (interruptHints.isNotBlank()) append(", interrupts=").append(interruptHints)
            if (structureHead.isNotBlank()) append(", structure=").append(structureHead)
            if (topHints.isNotBlank()) append(", hints=").append(topHints)
        }
    }

    private fun buildInterruptiveHint(
        element: UiElementObservation,
    ): InterruptiveHint? {
        if (!isLikelyInterruptive(element.text, element.viewId, element.className)) {
            return null
        }
        return InterruptiveHint(
            elementId = element.id,
            text = element.text.ifBlank { element.viewId.ifBlank { element.className } },
            reason = interruptiveReason(element),
        )
    }

    private fun interruptiveReason(
        element: UiElementObservation,
    ): String {
        val semantic = listOf(element.text, element.viewId, element.className)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        return when {
            semantic.contains("跳过", ignoreCase = true) -> "疑似新手引导或开屏跳过"
            semantic.contains("关闭", ignoreCase = true) -> "疑似弹窗关闭按钮"
            semantic.contains("取消", ignoreCase = true) || semantic.contains("暂不", ignoreCase = true) -> "疑似弹窗取消或稍后处理"
            semantic.contains("允许", ignoreCase = true) || semantic.contains("同意", ignoreCase = true) -> "疑似授权或协议提示"
            else -> "疑似弹窗或引导干扰元素"
        }
    }

    private fun isLikelyInterruptive(
        text: String,
        viewId: String,
        className: String,
    ): Boolean {
        val semantics = listOf(text, viewId, className).filter { it.isNotBlank() }
        if (semantics.isEmpty()) return false
        if (interruptiveTextKeywords.any { keyword -> semantics.any { it.contains(keyword, ignoreCase = true) } }) {
            return true
        }
        return interruptiveViewIdKeywords.any { keyword ->
            semantics.any { it.contains(keyword, ignoreCase = true) }
        }
    }

    private fun buildStableNodeKey(
        node: AccessibilityNodeInfo,
    ): String {
        node.accessibilityUniqueId().takeIf { it.isNotBlank() }?.let { return "uid:$it" }
        val rect = node.boundsRect()
        return listOf(
            node.readableText().trim(),
            node.accessibilityLabel().trim(),
            node.viewIdResourceName.orEmpty(),
            node.className?.toString().orEmpty(),
            node.collectionPositionSignature(),
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            node.isClickable,
            node.isEditable,
            node.isScrollable,
        ).joinToString("|")
    }

    private fun resolveContainerKey(
        node: AccessibilityNodeInfo,
        directParent: AccessibilityNodeInfo?,
    ): String {
        var current = directParent ?: node.parent
        while (current != null) {
            val className = current.className?.toString().orEmpty()
            if (
                current.isScrollable ||
                className.contains("Recycler", ignoreCase = true) ||
                className.contains("List", ignoreCase = true) ||
                className.contains("Grid", ignoreCase = true) ||
                current.childCount >= 3
            ) {
                return "container:${buildStableNodeKey(current)}"
            }
            current = current.parent
        }
        return "container:root"
    }

    private fun inferRoleHint(
        node: AccessibilityNodeInfo,
        text: String,
        viewId: String,
        className: String,
    ): String {
        val semantic =
            listOf(
                text,
                viewId,
                className,
                node.accessibilityLabel(),
                node.accessibilityPaneTitle(),
                node.accessibilityContainerTitle(),
                node.accessibilityStateDescription(),
            ).joinToString(" ")
        return when {
            node.isEditable || className.contains("Edit", ignoreCase = true) -> "input"
            isLikelyInterruptive(text, viewId, className) -> "dismiss"
            listOf("发送", "确认", "完成", "搜索", "查找", "进入", "打开", "导航").any { semantic.contains(it, ignoreCase = true) } -> "submit"
            listOf("联系人", "聊天", "会话").any { semantic.contains(it, ignoreCase = true) } -> "conversation"
            listOf("详情", "参数", "评论", "评价", "正文").any { semantic.contains(it, ignoreCase = true) } -> "detail"
            listOf("综合", "筛选", "更多", "频道", "首页", "返回", "菜单").any { semantic.contains(it, ignoreCase = true) } -> "meta"
            !node.isClickable && text.isNotBlank() && text.length <= 24 -> "title"
            else -> ""
        }
    }

    private fun buildContainerAliases(
        candidates: List<NodeCandidate>,
    ): Map<String, String> =
        candidates
            .map { it.containerKey }
            .distinct()
            .mapIndexed { index, key -> key to "c${(index + 1).toString().padStart(2, '0')}" }
            .toMap()

    private fun assignGridMetadata(
        elements: List<UiElementObservation>,
    ): List<UiElementObservation> {
        val updates = mutableMapOf<String, Pair<Int, Int>>()
        elements
            .groupBy { it.containerId.ifBlank { "root" } }
            .values
            .forEach { bucket ->
                val sorted = bucket.sortedWith(compareBy<UiElementObservation>({ topOf(it.bounds) }, { leftOf(it.bounds) }))
                var currentRow = -1
                var lastTop = Int.MIN_VALUE
                val rowColumns = mutableMapOf<Int, Int>()
                sorted.forEach { element ->
                    val top = topOf(element.bounds)
                    if (currentRow == -1 || abs(top - lastTop) > ROW_GROUP_THRESHOLD_PX) {
                        currentRow += 1
                        lastTop = top
                        rowColumns[currentRow] = 0
                    }
                    val column = rowColumns.getValue(currentRow)
                    rowColumns[currentRow] = column + 1
                    updates[element.id] = currentRow to column
                }
            }
        return elements.map { element ->
            val (rowIndex, columnIndex) = updates[element.id] ?: (-1 to -1)
            element.copy(rowIndex = rowIndex, columnIndex = columnIndex)
        }
    }

    private fun buildStructureHints(
        elements: List<UiElementObservation>,
        secondaryCandidates: List<NodeCandidate> = emptyList(),
        recallCandidates: List<NodeCandidate> = emptyList(),
        overflowCandidates: List<NodeCandidate> = emptyList(),
        hiddenSearchCandidates: List<NodeCandidate> = emptyList(),
    ): List<String> {
        val containers = elements.map { it.containerId }.filter { it.isNotBlank() }.distinct().size
        val roleHead =
            elements
                .mapNotNull { it.roleHint.takeIf(String::isNotBlank) }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(4)
                .joinToString(",") { "${it.key}:${it.value}" }
        return buildList {
            add("containers=$containers")
            if (roleHead.isNotBlank()) add("roles=$roleHead")
            elements.firstOrNull { it.containerId.isNotBlank() && it.neighborTexts.isNotEmpty() }?.let { sample ->
                add("sample_neighbors=${sample.id}:${sample.neighborTexts.take(3).joinToString("/")}")
            }
            if (overflowCandidates.isNotEmpty()) {
                add("overflow_count=${overflowCandidates.size}")
                add(
                    "overflow_head=" +
                        overflowCandidates
                            .take(3)
                            .joinToString("/") { candidate ->
                                candidate.text.ifBlank { candidate.roleHint.ifBlank { candidate.className.substringAfterLast('.') } }.take(14)
                    },
                )
            }
            if (secondaryCandidates.isNotEmpty()) {
                add("secondary_candidates=${secondaryCandidates.size}")
                add(
                    "secondary_head=" +
                        secondaryCandidates
                            .take(4)
                            .joinToString("/") { candidate ->
                                candidate.text.ifBlank { candidate.roleHint.ifBlank { candidate.className.substringAfterLast('.') } }.take(14)
                            },
                )
            }
            if (recallCandidates.isNotEmpty()) {
                add("recall_candidates=${recallCandidates.size}")
                add(
                    "recall_head=" +
                        recallCandidates
                            .take(4)
                            .joinToString("/") { candidate ->
                                candidate.text.ifBlank {
                                    candidate.accessibilityLabel.ifBlank {
                                        candidate.roleHint.ifBlank { candidate.className.substringAfterLast('.') }
                                    }
                                }.take(14)
                            },
                )
            }
            if (hiddenSearchCandidates.isNotEmpty()) {
                add("hidden_search=${hiddenSearchCandidates.size}")
                add(
                    "hidden_head=" +
                        hiddenSearchCandidates
                            .take(4)
                            .joinToString("/") { candidate ->
                                candidate.text.ifBlank {
                                    candidate.accessibilityLabel.ifBlank {
                                        candidate.roleHint.ifBlank { candidate.className.substringAfterLast('.') }
                                    }
                                }.take(14)
                            },
                )
            }
        }
    }

    private fun enrichSpatialMetadata(
        elements: List<UiElementObservation>,
        viewportBounds: android.graphics.Rect,
    ): List<UiElementObservation> =
        elements.map { element ->
            element.copy(
                spatialSignature =
                    buildSpatialSignature(
                        bounds = element.bounds,
                        viewportBounds = viewportBounds,
                        rowIndex = element.rowIndex,
                        columnIndex = element.columnIndex,
                        roleHint = element.roleHint,
                        source = element.source,
                        collectionPosition = element.collectionPosition,
                    ),
            )
        }

    private fun buildSpatialSignature(
        bounds: String,
        viewportBounds: android.graphics.Rect,
        rowIndex: Int,
        columnIndex: Int,
        roleHint: String,
        source: String,
        collectionPosition: String,
    ): String {
        val values = Regex("""-?\d+""").findAll(bounds).map { it.value.toInt() }.toList()
        if (values.size < 4) return listOf(source, roleHint, collectionPosition).filter { it.isNotBlank() }.joinToString(":")
        val rect = android.graphics.Rect(values[0], values[1], values[2], values[3])
        val widthBucket =
            when {
                rect.width() >= viewportBounds.width() * 0.62f -> "wide"
                rect.width() >= viewportBounds.width() * 0.34f -> "medium"
                else -> "compact"
            }
        val heightBucket =
            when {
                rect.height() >= viewportBounds.height() * 0.2f -> "tall"
                rect.height() >= viewportBounds.height() * 0.08f -> "mid"
                else -> "short"
            }
        val horizontal =
            when {
                rect.exactCenterX() <= viewportBounds.width() * 0.33f -> "left"
                rect.exactCenterX() >= viewportBounds.width() * 0.66f -> "right"
                else -> "center"
            }
        val vertical =
            when {
                rect.exactCenterY() <= viewportBounds.height() * 0.2f -> "top"
                rect.exactCenterY() >= viewportBounds.height() * 0.8f -> "bottom"
                else -> "middle"
            }
        return listOf(
            "$vertical-$horizontal",
            widthBucket,
            heightBucket,
            source,
            roleHint.takeIf { it.isNotBlank() },
            collectionPosition.takeIf { it.isNotBlank() },
            rowIndex.takeIf { it >= 0 }?.let { "row$it" },
            columnIndex.takeIf { it >= 0 }?.let { "col$it" },
        ).filterNotNull().joinToString(":")
    }

    private fun buildBaseVisualSignature(
        candidate: NodeCandidate,
        source: String,
    ): String =
        listOf(
            source,
            candidate.className.substringAfterLast('.'),
            candidate.roleHint.takeIf { it.isNotBlank() },
            candidate.viewId.substringAfterLast('/').takeIf { it.isNotBlank() },
            candidate.uniqueId.takeIf { it.isNotBlank() },
        ).filterNotNull().joinToString(":")

    private fun selectTopCandidates(
        candidates: List<NodeCandidate>,
    ): Pair<List<NodeCandidate>, List<NodeCandidate>> {
        if (candidates.size <= MAX_ELEMENTS) return candidates to emptyList()
        val selected = LinkedHashMap<String, NodeCandidate>()
        val byContainer = candidates.groupBy { it.containerKey.ifBlank { "root" } }
        byContainer.values.forEach { bucket ->
            bucket
                .sortedByDescending { it.score }
                .take(containerBudget(bucket.size))
                .forEach { candidate -> selected.putIfAbsent(candidate.stableKey, candidate) }
        }
        candidates.forEach { candidate ->
            if (selected.size >= MAX_ELEMENTS) return@forEach
            selected.putIfAbsent(candidate.stableKey, candidate)
        }
        val topCandidates = selected.values.toList().take(MAX_ELEMENTS)
        val overflow = candidates.filterNot { selected.containsKey(it.stableKey) }
        return topCandidates to overflow
    }

    private fun selectSecondaryCandidates(
        overflowCandidates: List<NodeCandidate>,
    ): List<NodeCandidate> {
        if (overflowCandidates.isEmpty()) return emptyList()
        val selected = LinkedHashMap<String, NodeCandidate>()
        val byContainer = overflowCandidates.groupBy { it.containerKey.ifBlank { "root" } }
        byContainer.values.forEach { bucket ->
            bucket
                .sortedByDescending { it.score }
                .take(2)
                .forEach { candidate -> selected.putIfAbsent(candidate.stableKey, candidate) }
        }
        overflowCandidates.forEach { candidate ->
            if (selected.size >= MAX_SECONDARY_TARGETS) return@forEach
            selected.putIfAbsent(candidate.stableKey, candidate)
        }
        return selected.values
            .sortedWith(compareByDescending<NodeCandidate> { it.score }.thenBy { it.bounds.top }.thenBy { it.bounds.left })
            .take(MAX_SECONDARY_TARGETS)
    }

    private fun selectRecallCandidates(
        overflowCandidates: List<NodeCandidate>,
    ): List<NodeCandidate> {
        if (overflowCandidates.isEmpty()) return emptyList()
        val selected = LinkedHashMap<String, NodeCandidate>()
        val byContainer = overflowCandidates.groupBy { it.containerKey.ifBlank { "root" } }
        byContainer.values.forEach { bucket ->
            bucket
                .sortedWith(
                    compareByDescending<NodeCandidate> { it.score + if (it.text.isBlank()) 8 else 0 }
                        .thenBy { it.bounds.top }
                        .thenBy { it.bounds.left },
                )
                .take(1)
                .forEach { candidate -> selected.putIfAbsent(candidate.stableKey, candidate) }
        }
        overflowCandidates.forEach { candidate ->
            if (selected.size >= MAX_RECALL_TARGETS) return@forEach
            selected.putIfAbsent(candidate.stableKey, candidate)
        }
        return selected.values
            .sortedWith(compareByDescending<NodeCandidate> { it.score }.thenBy { it.bounds.top }.thenBy { it.bounds.left })
            .take(MAX_RECALL_TARGETS)
    }

    private fun buildDescriptorTokens(
        text: String,
        accessibilityLabel: String,
        paneTitle: String,
        containerTitle: String,
        stateDescription: String,
        collectionPosition: String,
        roleHint: String,
        siblingTexts: List<String>,
        viewId: String,
    ): List<String> =
        buildList {
            add(text)
            add(accessibilityLabel)
            add(paneTitle)
            add(containerTitle)
            add(stateDescription)
            add(collectionPosition)
            add(roleHint)
            add(viewId.substringAfterLast('/'))
            addAll(siblingTexts.take(4))
        }.map { it.trim() }
            .filter { it.isNotBlank() && it.length >= 2 }
            .distinct()
            .take(8)

    private fun containerBudget(
        size: Int,
    ): Int =
        when {
            size <= MIN_CONTAINER_BUDGET -> size
            size >= MAX_CONTAINER_BUDGET -> MAX_CONTAINER_BUDGET
            else -> size.coerceAtLeast(MIN_CONTAINER_BUDGET)
        }

    private fun topOf(bounds: String): Int =
        Regex("""-?\d+""").findAll(bounds).map { it.value.toInt() }.toList().getOrElse(1) { 0 }

    private fun leftOf(bounds: String): Int =
        Regex("""-?\d+""").findAll(bounds).map { it.value.toInt() }.toList().getOrElse(0) { 0 }

    private data class NodeCandidate(
        val node: AccessibilityNodeInfo,
        val text: String,
        val viewId: String,
        val className: String,
        val bounds: android.graphics.Rect,
        val boundsText: String,
        val depth: Int,
        val parentStableKey: String,
        val containerKey: String,
        val roleHint: String,
        val siblingTexts: List<String>,
        val accessibilityLabel: String,
        val uniqueId: String,
        val paneTitle: String,
        val containerTitle: String,
        val stateDescription: String,
        val collectionPosition: String,
        val descriptorTokens: List<String>,
        val score: Int,
        val stableKey: String,
    )

    private data class NodeSnapshot(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        val parent: AccessibilityNodeInfo?,
        val siblingTexts: List<String>,
    )

    private data class IndexedCandidate(
        val candidate: NodeCandidate,
        val element: UiElementObservation,
        val node: AccessibilityNodeInfo,
    )
}
