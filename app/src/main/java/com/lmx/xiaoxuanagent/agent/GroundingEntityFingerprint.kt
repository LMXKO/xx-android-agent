package com.lmx.xiaoxuanagent.agent

data class GroundingEntityDescriptor(
    val type: String = "",
    val primary: String = "",
    val anchors: List<String> = emptyList(),
    val contextTokens: List<String> = emptyList(),
    val stableIds: List<String> = emptyList(),
    val accessibilityLabels: List<String> = emptyList(),
    val collectionPositions: List<String> = emptyList(),
    val visualSignatures: List<String> = emptyList(),
    val spatialSignatures: List<String> = emptyList(),
    val visualTokens: List<String> = emptyList(),
)

data class GroundingEntityMatch(
    val verified: Boolean = false,
    val anchorMatches: Int = 0,
    val contextMatches: Int = 0,
    val stableIdMatches: Int = 0,
    val accessibilityMatches: Int = 0,
    val visualMatches: Int = 0,
    val spatialMatches: Int = 0,
    val score: Int = 0,
)

object GroundingEntityFingerprint {
    private val contactCues = listOf("联系人", "会话", "聊天", "发消息", "消息", "通讯录", "conversation", "contact", "chat")
    private val routeCues = listOf("路线", "导航", "到这去", "开始导航", "分钟", "公里", "route", "navigation", "destination")
    private val productCues = listOf("商品", "店铺", "规格", "参数", "详情", "价格", "销量", "月售", "券", "product", "card", "detail")
    private val genericNoise = listOf("更多", "进入", "打开", "查看", "点击", "下一步", "继续", "确认")
    private val pricePattern = Regex("""(?:¥|￥)?\d+(?:\.\d+)?(?:元)?""")

    fun fromExecutionResult(
        executionResult: AgentExecutionResult?,
    ): GroundingEntityDescriptor {
        if (executionResult == null) return GroundingEntityDescriptor()
        val primary =
            executionResult.expectedEntityHint
                .ifBlank { executionResult.resolvedTargetText }
        val type =
            executionResult.entityFingerprintType.ifBlank {
                inferType(
                    primary = primary,
                    roleHint = executionResult.targetObjectType,
                    contextHints = executionResult.targetContextHints,
                )
            }
        val anchors =
            (
                executionResult.entityFingerprintAnchors +
                    buildAnchors(primary = primary, type = type, contextHints = executionResult.targetContextHints)
            ).distinct()
        val contextTokens = buildContextTokens(type = type, contextHints = executionResult.targetContextHints)
        return GroundingEntityDescriptor(
            type = type,
            primary = primary,
            anchors = anchors,
            contextTokens = contextTokens,
            stableIds = listOf(executionResult.resolvedTargetStableId).filter { it.isNotBlank() },
            accessibilityLabels =
                listOf(executionResult.resolvedTargetAccessibilityLabel)
                    .plus(executionResult.targetDescriptorTokens)
                    .map(::normalizeToken)
                    .filter { it.length >= 2 }
                    .distinct()
                    .take(6),
            collectionPositions = listOf(executionResult.resolvedTargetCollectionPosition).filter { it.isNotBlank() },
            visualSignatures = listOf(executionResult.resolvedTargetVisualSignature).filter { it.isNotBlank() },
            spatialSignatures = listOf(executionResult.resolvedTargetSpatialSignature).filter { it.isNotBlank() },
            visualTokens = executionResult.targetVisualDescriptorTokens.map(::normalizeToken).filter { it.length >= 2 }.distinct().take(6),
        )
    }

    fun fromTaskConstraints(
        constraints: TaskConstraints,
        executionResult: AgentExecutionResult?,
    ): GroundingEntityDescriptor {
        val fromExecution = fromExecutionResult(executionResult)
        val primary =
            when (constraints.intentType) {
                TaskIntentType.MESSAGING -> constraints.recipientName.orEmpty().ifBlank { fromExecution.primary }
                TaskIntentType.NAVIGATION -> constraints.destination.orEmpty().ifBlank { constraints.entryQuery }.ifBlank { fromExecution.primary }
                TaskIntentType.SHOPPING -> constraints.searchQuery.orEmpty().ifBlank { constraints.entryQuery }.ifBlank { fromExecution.primary }
                else -> fromExecution.primary.ifBlank { constraints.entryQuery }
            }
        val type =
            fromExecution.type.ifBlank {
                when (constraints.intentType) {
                    TaskIntentType.MESSAGING -> "contact"
                    TaskIntentType.NAVIGATION -> "route"
                    TaskIntentType.SHOPPING -> "product"
                    else -> ""
                }
            }
        val mergedContext =
            (
                fromExecution.contextTokens +
                    fromExecution.visualTokens +
                    constraints.keywordHints +
                    listOfNotNull(constraints.destination, constraints.recipientName, constraints.searchQuery, constraints.objectiveHint)
            ).distinct()
        return GroundingEntityDescriptor(
            type = type,
            primary = primary,
            anchors = (fromExecution.anchors + buildAnchors(primary, type, mergedContext)).distinct(),
            contextTokens = (buildContextTokens(type, mergedContext) + fromExecution.visualTokens).distinct().take(8),
            stableIds = fromExecution.stableIds,
            accessibilityLabels = fromExecution.accessibilityLabels,
            collectionPositions = fromExecution.collectionPositions,
            visualSignatures = fromExecution.visualSignatures,
            spatialSignatures = fromExecution.spatialSignatures,
            visualTokens = fromExecution.visualTokens,
        )
    }

    fun inferType(
        primary: String,
        roleHint: String,
        contextHints: List<String>,
    ): String {
        val semantic = buildList {
            add(primary)
            add(roleHint)
            addAll(contextHints)
        }.joinToString(" ").lowercase()
        return when {
            contactCues.any { semantic.contains(it.lowercase()) } -> "contact"
            routeCues.any { semantic.contains(it.lowercase()) } -> "route"
            productCues.any { semantic.contains(it.lowercase()) } -> "product"
            else -> ""
        }
    }

    fun displayLabel(
        type: String,
    ): String =
        when (type) {
            "contact" -> "联系人"
            "route" -> "路线卡"
            "product" -> "商品卡"
            else -> "目标对象"
        }

    fun buildAnchors(
        primary: String,
        type: String,
        contextHints: List<String>,
    ): List<String> {
        val normalizedPrimary = normalizeToken(primary)
        val primaryTokens = splitFragments(normalizedPrimary)
        val contextualAnchors =
            contextHints
                .asSequence()
                .map(::normalizeToken)
                .filter { it.length >= 2 }
                .flatMap { hint ->
                    sequenceOf(hint) + splitFragments(hint).asSequence()
                }
                .filterNot(::looksGeneric)
                .distinct()
                .take(
                    when (type) {
                        "contact" -> 2
                        "route" -> 3
                        "product" -> 4
                        else -> 2
                    },
                )
                .toList()
        return buildList {
            normalizedPrimary.takeIf { it.length >= 2 }?.let(::add)
            primaryTokens.filterNot(::looksGeneric).take(2).forEach(::add)
            contextualAnchors.forEach(::add)
        }.distinct()
    }

    fun buildContextTokens(
        type: String,
        contextHints: List<String>,
    ): List<String> {
        val normalizedHints =
            contextHints
                .map(::normalizeToken)
                .filter { it.length >= 2 }
                .filterNot(::looksGeneric)
        val typeCues =
            when (type) {
                "contact" -> listOf("聊天", "会话", "发消息", "联系人")
                "route" -> listOf("路线", "导航", "到这去", "分钟", "公里")
                "product" -> listOf("商品", "详情", "价格", "店铺", "规格", "月售")
                else -> emptyList()
            }
        return (normalizedHints + typeCues).distinct().take(6)
    }

    fun matchObservation(
        observation: ScreenObservation,
        descriptor: GroundingEntityDescriptor,
    ): GroundingEntityMatch {
        if (
            descriptor.primary.isBlank() &&
            descriptor.anchors.isEmpty() &&
            descriptor.stableIds.isEmpty() &&
            descriptor.accessibilityLabels.isEmpty() &&
            descriptor.collectionPositions.isEmpty() &&
            descriptor.visualSignatures.isEmpty() &&
            descriptor.spatialSignatures.isEmpty() &&
            descriptor.visualTokens.isEmpty()
        ) {
            return GroundingEntityMatch()
        }
        val semantics = buildObservationSemantics(observation)
        val stableIdMatches =
            observation.elements.count { element ->
                element.accessibilityUniqueId.isNotBlank() &&
                    descriptor.stableIds.any { it.equals(element.accessibilityUniqueId, ignoreCase = true) }
            }
        val accessibilityMatches =
            descriptor.accessibilityLabels
                .distinct()
                .count { label ->
                    label.length >= 2 && semantics.contains(label, ignoreCase = true)
                }
        val anchorMatches =
            descriptor.anchors
                .distinct()
                .count { anchor -> anchor.length >= 2 && semantics.contains(anchor, ignoreCase = true) }
        val contextMatches =
            descriptor.contextTokens
                .distinct()
                .count { token -> token.length >= 2 && semantics.contains(token, ignoreCase = true) }
        val collectionMatches =
            descriptor.collectionPositions
                .distinct()
                .count { token -> token.isNotBlank() && semantics.contains(token, ignoreCase = true) }
        val visualMatches =
            descriptor.visualSignatures
                .distinct()
                .count { token -> token.isNotBlank() && semantics.contains(token, ignoreCase = true) } +
                descriptor.visualTokens
                    .distinct()
                    .count { token -> token.length >= 2 && semantics.contains(token, ignoreCase = true) }
        val spatialMatches =
            descriptor.spatialSignatures
                .distinct()
                .count { token -> token.isNotBlank() && semantics.contains(token, ignoreCase = true) }
        val score =
            stableIdMatches * 6 +
                accessibilityMatches * 2 +
                anchorMatches * 3 +
                contextMatches +
                collectionMatches +
                visualMatches * 2 +
                spatialMatches * 2
        val pageSemantic = "${observation.pageState} ${observation.screenSummary}".lowercase()
        val verified =
            when (descriptor.type) {
                "contact" ->
                    stableIdMatches >= 1 || (anchorMatches >= 1 && (
                        contextMatches >= 1 ||
                            accessibilityMatches >= 1 ||
                            visualMatches >= 1 ||
                            contactCues.any { cue -> pageSemantic.contains(cue.lowercase()) }
                    ))

                "route" ->
                    stableIdMatches >= 1 || (anchorMatches >= 1 && (
                        contextMatches >= 1 ||
                            accessibilityMatches >= 1 ||
                            (visualMatches >= 1 && spatialMatches >= 1) ||
                            routeCues.any { cue -> pageSemantic.contains(cue.lowercase()) }
                    ))

                "product" ->
                    stableIdMatches >= 1 || (anchorMatches >= 1 && (
                        contextMatches >= 1 ||
                            accessibilityMatches >= 1 ||
                            (visualMatches >= 1 && spatialMatches >= 1) ||
                            productCues.any { cue -> pageSemantic.contains(cue.lowercase()) } ||
                            pricePattern.containsMatchIn(semantics)
                    ))

                else ->
                    stableIdMatches >= 1 || anchorMatches >= 1 || (visualMatches >= 1 && spatialMatches >= 1) || score >= 4
            }
        return GroundingEntityMatch(
            verified = verified,
            anchorMatches = anchorMatches,
            contextMatches = contextMatches,
            stableIdMatches = stableIdMatches,
            accessibilityMatches = accessibilityMatches,
            visualMatches = visualMatches,
            spatialMatches = spatialMatches,
            score = score,
        )
    }

    private fun buildObservationSemantics(
        observation: ScreenObservation,
    ): String =
        buildList {
            add(observation.pageState)
            add(observation.screenSummary)
            addAll(observation.topTexts)
            addAll(observation.structureHints)
            observation.elements.forEach { element ->
                element.text.takeIf { it.isNotBlank() }?.let(::add)
                element.accessibilityLabel.takeIf { it.isNotBlank() }?.let(::add)
                element.accessibilityUniqueId.takeIf { it.isNotBlank() }?.let(::add)
                element.containerTitle.takeIf { it.isNotBlank() }?.let(::add)
                element.paneTitle.takeIf { it.isNotBlank() }?.let(::add)
                element.stateDescription.takeIf { it.isNotBlank() }?.let(::add)
                element.collectionPosition.takeIf { it.isNotBlank() }?.let(::add)
                element.visualSignature.takeIf { it.isNotBlank() }?.let(::add)
                element.spatialSignature.takeIf { it.isNotBlank() }?.let(::add)
                addAll(element.visualDescriptorTokens)
                addAll(element.descriptorTokens)
                addAll(element.neighborTexts)
            }
        }.joinToString(" ")

    private fun splitFragments(
        raw: String,
    ): List<String> =
        raw.split(Regex("""[\s,/，。；;、|:_\-]+"""))
            .map(::normalizeToken)
            .filter { it.length >= 2 }
            .filterNot(::looksGeneric)
            .distinct()

    private fun normalizeToken(
        raw: String,
    ): String = raw.replace("\\s+".toRegex(), " ").trim()

    private fun looksGeneric(
        token: String,
    ): Boolean =
        token.isBlank() ||
            genericNoise.any { token.equals(it, ignoreCase = true) } ||
            token.length < 2
}
