package com.lmx.xiaoxuanagent.agent

internal object QueryConditionedGroundingSearchEngine {
    private const val MAX_QUERY_SEEDS = 4
    private const val MAX_QUERY_RESULTS = 24
    private const val MAX_QUERY_PASSES = 2

    fun search(
        indexedObservation: IndexedScreenObservation,
        targetText: String = "",
        roleHint: String = "",
        additionalKeywords: List<String> = emptyList(),
        preferredScopeIds: List<String> = emptyList(),
    ): List<UiElementObservation> {
        val screenshot = indexedObservation.screenshot ?: return emptyList()
        val visualContext = indexedObservation.visualContext
        if (!visualContext.captureAvailable || (visualContext.visualObjects.isEmpty() && visualContext.parserRegions.isEmpty())) {
            return emptyList()
        }
        val queryTokens =
            (
                listOf(targetText, roleHint) +
                    additionalKeywords
                ).map(::normalizeToken)
                .filter { it.length >= 2 }
                .distinct()
        if (queryTokens.isEmpty() && preferredScopeIds.isEmpty()) return emptyList()

        var seeds = rankSeeds(indexedObservation, queryTokens, roleHint, preferredScopeIds)
        if (seeds.isEmpty()) return emptyList()
        val allCandidates = linkedMapOf<String, UiElementObservation>()
        repeat(MAX_QUERY_PASSES) { passIndex ->
            val passResults =
                seeds.take(MAX_QUERY_SEEDS).flatMap { seed ->
                    val cropResult =
                        VisualPerceptionEngine.reanalyzeCrop(
                            screenshot = screenshot,
                            observation = indexedObservation.observation,
                            baseVisualContext = visualContext,
                            focusBounds = seed.bounds,
                            focusLabel = seed.label.ifBlank { targetText.ifBlank { roleHint } },
                        )
                    toUiCandidates(
                        cropResult = cropResult,
                        queryTokens = queryTokens,
                        roleHint = roleHint,
                        preferredScopeIds = preferredScopeIds,
                        passIndex = passIndex + 1,
                    )
                }.sortedByDescending { candidate ->
                    candidateScore(candidate, queryTokens, roleHint, preferredScopeIds)
                }.distinctBy { it.id }
            passResults.forEach { candidate ->
                allCandidates.putIfAbsent(candidate.id, candidate)
            }
            seeds = rankCandidateSeeds(passResults, queryTokens, roleHint, preferredScopeIds)
            if (seeds.isEmpty()) return@repeat
        }
        return allCandidates.values
            .sortedByDescending { candidateScore(it, queryTokens, roleHint, preferredScopeIds) }
            .take(MAX_QUERY_RESULTS)
    }

    private fun rankSeeds(
        indexedObservation: IndexedScreenObservation,
        queryTokens: List<String>,
        roleHint: String,
        preferredScopeIds: List<String>,
    ): List<QuerySeed> {
        val parserSeeds =
            indexedObservation.visualContext.parserRegions.map { region ->
                QuerySeed(
                    seedId = region.id,
                    label = region.label.ifBlank { region.type },
                    bounds = region.bounds,
                    scopeId = region.id,
                    score =
                        scoreSemantics(
                            semantics =
                                buildString {
                                    append(region.label).append(' ')
                                    append(region.type).append(' ')
                                    append(region.roleHint).append(' ')
                                    append(region.descriptorTokens.joinToString(" ")).append(' ')
                                    append(region.contextHints.joinToString(" "))
                                },
                            queryTokens = queryTokens,
                            roleHint = roleHint,
                        ) +
                            if (preferredScopeIds.contains(region.id)) 22 else 0 +
                            if (region.searchTier in setOf("local", "local_refine", "local_model")) 10 else 0 +
                            if (region.source.contains("crop", ignoreCase = true)) 12 else 0,
                )
            }
        val visualSeeds =
            indexedObservation.visualContext.visualObjects.map { visualObject ->
                QuerySeed(
                    seedId = visualObject.id,
                    label = visualObject.label.ifBlank { visualObject.type },
                    bounds = visualObject.bounds,
                    scopeId = visualObject.matchedContainerId.ifBlank { visualObject.matchedElementId.orEmpty() },
                    score =
                        scoreSemantics(
                            semantics =
                                buildString {
                                    append(visualObject.label).append(' ')
                                    append(visualObject.type).append(' ')
                                    append(visualObject.roleHint).append(' ')
                                    append(visualObject.descriptorTokens.joinToString(" ")).append(' ')
                                    append(visualObject.contextHints.joinToString(" "))
                                },
                            queryTokens = queryTokens,
                            roleHint = roleHint,
                        ) +
                            if (preferredScopeIds.any { preferred ->
                                    preferred == visualObject.matchedContainerId || preferred == visualObject.matchedElementId
                                }
                            ) 18 else 0 +
                            if (visualObject.source.contains("crop", ignoreCase = true)) 10 else 0 +
                            if (visualObject.candidateTier in setOf("local", "crop")) 8 else 0,
                )
            }
        return (parserSeeds + visualSeeds)
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .distinctBy { "${normalizeBoundsKey(it.bounds)}|${it.scopeId}" }
    }

    private fun rankCandidateSeeds(
        candidates: List<UiElementObservation>,
        queryTokens: List<String>,
        roleHint: String,
        preferredScopeIds: List<String>,
    ): List<QuerySeed> =
        candidates.map { candidate ->
            QuerySeed(
                seedId = candidate.id,
                label = candidate.text,
                bounds = candidate.bounds,
                scopeId = candidate.searchScopeId.ifBlank { candidate.containerId },
                score = candidateScore(candidate, queryTokens, roleHint, preferredScopeIds) + 14,
            )
        }.sortedByDescending { it.score }
            .distinctBy { "${normalizeBoundsKey(it.bounds)}|${it.scopeId}" }

    private fun toUiCandidates(
        cropResult: CropReanalysisResult,
        queryTokens: List<String>,
        roleHint: String,
        preferredScopeIds: List<String>,
        passIndex: Int,
    ): List<UiElementObservation> {
        val parserCandidates =
            cropResult.parserRegions.map { region ->
                UiElementObservation(
                    id = "query_${passIndex}_${region.id}",
                    text = region.label,
                    viewId = "query_crop:${region.source}",
                    className = "QueryCropParser:${region.type}",
                    bounds = region.bounds,
                    clickable = true,
                    editable = false,
                    scrollable = false,
                    enabled = true,
                    focused = false,
                    selected = false,
                    containerId = region.matchedContainerIds.firstOrNull().orEmpty(),
                    accessibilityLabel = region.accessibilityLabels.firstOrNull().orEmpty(),
                    accessibilityUniqueId = region.accessibilityUniqueIds.firstOrNull().orEmpty(),
                    descriptorTokens = region.descriptorTokens,
                    visualDescriptorTokens = region.descriptorTokens,
                    interactionRegions = region.interactionRegions,
                    visualSignature = region.visualSignature,
                    spatialSignature = region.spatialSignature,
                    roleHint = region.roleHint,
                    neighborTexts = region.contextHints,
                    source = "query_crop_parser",
                    searchScopeId =
                        when {
                            region.id in preferredScopeIds -> region.id
                            region.searchTier in setOf("scope", "band", "local", "local_refine", "model", "local_model") -> region.id
                            else -> region.matchedContainerIds.firstOrNull().orEmpty()
                        },
                )
            }
        val visualCandidates =
            cropResult.visualObjects.map { visualObject ->
                UiElementObservation(
                    id = "query_${passIndex}_${visualObject.id}",
                    text = visualObject.label,
                    viewId = "query_crop:${visualObject.source}",
                    className = "QueryCropVisual:${visualObject.type}",
                    bounds = visualObject.bounds,
                    clickable = true,
                    editable = false,
                    scrollable = false,
                    enabled = true,
                    focused = false,
                    selected = false,
                    parentId = visualObject.matchedElementId.orEmpty(),
                    containerId = visualObject.matchedContainerId,
                    collectionPosition = visualObject.collectionPosition,
                    accessibilityLabel = visualObject.accessibilityLabel,
                    accessibilityUniqueId = visualObject.accessibilityUniqueId,
                    descriptorTokens = visualObject.descriptorTokens,
                    visualDescriptorTokens = visualObject.descriptorTokens,
                    interactionRegions = visualObject.interactionRegions,
                    visualSignature = visualObject.visualSignature,
                    spatialSignature = visualObject.spatialSignature,
                    roleHint = visualObject.roleHint,
                    neighborTexts = visualObject.contextHints,
                    source = "query_crop_visual",
                    searchScopeId = visualObject.matchedContainerId.ifBlank { visualObject.matchedElementId.orEmpty() },
                )
            }
        return (parserCandidates + visualCandidates)
            .filter { candidate -> candidateScore(candidate, queryTokens, roleHint, preferredScopeIds) > 0 }
            .distinctBy { "${normalizeBoundsKey(it.bounds)}|${it.text}|${it.source}" }
    }

    private fun candidateScore(
        candidate: UiElementObservation,
        queryTokens: List<String>,
        roleHint: String,
        preferredScopeIds: List<String>,
    ): Int {
        val semantics =
            buildString {
                append(candidate.text).append(' ')
                append(candidate.roleHint).append(' ')
                append(candidate.accessibilityLabel).append(' ')
                append(candidate.descriptorTokens.joinToString(" ")).append(' ')
                append(candidate.visualDescriptorTokens.joinToString(" ")).append(' ')
                append(candidate.neighborTexts.joinToString(" "))
            }
        return scoreSemantics(semantics, queryTokens, roleHint) +
            if (candidate.source.contains("query_crop", ignoreCase = true)) 18 else 0 +
            if (preferredScopeIds.any { it == candidate.searchScopeId || it == candidate.containerId }) 14 else 0 +
            if (candidate.interactionRegions.isNotEmpty()) 6 else 0 +
            if (candidate.visualSignature.isNotBlank()) 4 else 0
    }

    private fun scoreSemantics(
        semantics: String,
        queryTokens: List<String>,
        roleHint: String,
    ): Int {
        var total = 0
        queryTokens.forEach { token ->
            total +=
                when {
                    semantics.contains(token, ignoreCase = true) -> 18 + token.length
                    else -> 0
                }
        }
        if (roleHint.isNotBlank() && semantics.contains(roleHint, ignoreCase = true)) {
            total += 12
        }
        return total
    }

    private fun normalizeToken(value: String): String =
        value.trim().lowercase()
            .replace(Regex("""[\s，。,.!！?？:：;；'"“”‘’、】【\[\]\-_/\\|]+"""), "")

    private fun normalizeBoundsKey(bounds: String): String {
        val values = Regex("""-?\d+""").findAll(bounds).map { it.value }.toList()
        return if (values.size >= 4) values.take(4).joinToString(",") else bounds
    }

    private data class QuerySeed(
        val seedId: String,
        val label: String,
        val bounds: String,
        val scopeId: String,
        val score: Int,
    )
}
