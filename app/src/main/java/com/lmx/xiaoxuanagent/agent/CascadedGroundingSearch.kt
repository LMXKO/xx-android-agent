package com.lmx.xiaoxuanagent.agent

import android.graphics.Rect

internal object CascadedGroundingSearch {
    fun search(
        indexedObservation: IndexedScreenObservation,
        targetText: String = "",
        roleHint: String = "",
        additionalKeywords: List<String> = emptyList(),
        preferredScopeIds: List<String> = emptyList(),
    ): UiElementObservation? {
        val policy = AgentSemanticCandidatePolicies.forRole(roleHint)
        val keywords =
            (
                listOf(targetText) +
                    policy.roleKeywords +
                    additionalKeywords
                ).map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        if (keywords.isEmpty() && roleHint.isBlank()) return null

        val scopes = rankScopes(indexedObservation, policy, keywords, preferredScopeIds)
        val queryCropCandidates =
            QueryConditionedGroundingSearchEngine.search(
                indexedObservation = indexedObservation,
                targetText = targetText,
                roleHint = roleHint,
                additionalKeywords = additionalKeywords,
                preferredScopeIds = preferredScopeIds,
            ).map { candidate ->
                SearchCandidate(
                    element = candidate,
                    score = semanticScore(candidate, keywords, policy) + 36,
                    scopeId = candidate.searchScopeId.ifBlank { candidate.containerId },
                    stage = "query_crop",
                )
            }
        val scopedCandidates =
            scopes.take(6).flatMap { scope ->
                buildCandidates(indexedObservation, scope = scope)
                    .filter { candidate -> policy.accepts(candidate, keywords) }
                    .map { candidate ->
                        SearchCandidate(
                            element = candidate,
                            score = semanticScore(candidate, keywords, policy) + scope.score,
                            scopeId = scope.id,
                            stage = "cascaded",
                        )
                    }
            }
        val globalCandidates =
            buildCandidates(indexedObservation, scope = null)
                .filter { candidate -> policy.accepts(candidate, keywords) }
                .map { candidate ->
                    SearchCandidate(
                        element = candidate,
                        score = semanticScore(candidate, keywords, policy),
                        scopeId = "",
                        stage = "global",
                        )
                    }

        val initialCandidates =
            (queryCropCandidates + scopedCandidates + globalCandidates)
                .filter { it.score > 0 }
                .sortedWith(
                    compareByDescending<SearchCandidate> { it.score }
                        .thenBy { if (it.stage == "query_crop") 0 else 1 }
                        .thenBy { if (it.stage == "global") 1 else 0 }
                        .thenBy { if (it.element.source == "overflow_hidden") 1 else 0 }
                        .thenBy { it.element.id },
                )
        val refinedCandidates =
            refineCandidates(
                indexedObservation = indexedObservation,
                seeds = initialCandidates.take(4),
                keywords = keywords,
                policy = policy,
                stage = "local_refine",
                expansionScale = 1f,
            )
        val deepRefinedCandidates =
            refineCandidates(
                indexedObservation = indexedObservation,
                seeds = (refinedCandidates + initialCandidates).take(3),
                keywords = keywords,
                policy = policy,
                stage = "crop_reparse_refine",
                expansionScale = 1.55f,
            )
        val focusedCropCandidates =
            refineCandidates(
                indexedObservation = indexedObservation,
                seeds =
                    (deepRefinedCandidates + refinedCandidates + initialCandidates)
                        .filter {
                            it.element.source.contains("crop", ignoreCase = true) ||
                                it.element.source.contains("screen_parser", ignoreCase = true) ||
                                it.element.searchScopeId.startsWith("parser_")
                        }.take(3),
                keywords = keywords,
                policy = policy,
                stage = "focused_crop_refine",
                expansionScale = 0.78f,
            )
        return (focusedCropCandidates + deepRefinedCandidates + refinedCandidates + initialCandidates)
            .filter { it.score > 0 }
            .sortedWith(
                compareByDescending<SearchCandidate> { it.score }
                    .thenBy { if (it.stage == "query_crop") 0 else 1 }
                    .thenBy { if (it.stage == "global") 1 else 0 }
                    .thenBy { if (it.element.source == "overflow_hidden") 1 else 0 }
                    .thenBy { it.element.id },
            )
            .map { candidate ->
                if (candidate.scopeId.isBlank() || candidate.element.searchScopeId.isNotBlank()) {
                    candidate.element
                } else {
                    candidate.element.copy(searchScopeId = candidate.scopeId)
                }
            }
            .firstOrNull()
    }

    private fun rankScopes(
        indexedObservation: IndexedScreenObservation,
        policy: AgentSemanticCandidatePolicy,
        keywords: List<String>,
        preferredScopeIds: List<String>,
    ): List<SearchScope> {
        val observation = indexedObservation.observation
        val containerScopes =
            (observation.elements + indexedObservation.searchElementsById.values)
                .groupBy { element -> element.containerId.ifBlank { element.searchScopeId } }
                .filterKeys { it.isNotBlank() }
                .map { (scopeId, members) ->
                    val rect = unionRect(members.mapNotNull { parseBounds(it.bounds) })
                    val semantics = members.joinToString(" ") { AgentSemanticCandidatePolicies.buildSemanticText(it) }
                    SearchScope(
                        id = scopeId,
                        source = if (scopeId.startsWith("parser_")) "parser_scope" else "container_scope",
                        bounds = rect,
                        semantics = semantics,
                        memberIds = members.map { it.id }.toSet(),
                        score = scopeScore(members, semantics, policy, keywords) + if (preferredScopeIds.contains(scopeId)) 20 else 0,
                    )
                }
        val parserScopes =
            indexedObservation.virtualTargetsById.values
                .filter { target ->
                    target.searchScopeId.isNotBlank() ||
                        target.source.contains("screen_parser", ignoreCase = true) ||
                        target.candidateTier in setOf("scope", "band", "local")
                }.groupBy { target ->
                    target.searchScopeId.ifBlank { target.parserRegionId.ifBlank { target.id } }
                }.map { (scopeId, targets) ->
                    val rect = unionRect(targets.mapNotNull { parseBounds(it.bounds) })
                    val semantics =
                        targets.joinToString(" ") { target ->
                            listOf(
                                target.text,
                                target.accessibilityLabel,
                                target.roleHint,
                                target.objectType,
                                target.descriptorTokens.joinToString(" "),
                                target.contextHints.joinToString(" "),
                                target.visualSignature,
                                target.spatialSignature,
                            ).joinToString(" ")
                        }
                    SearchScope(
                        id = scopeId,
                        source = "parser_scope",
                        bounds = rect,
                        semantics = semantics,
                        memberIds = targets.map { it.id }.toSet(),
                        score =
                            scopeScore(
                                syntheticElements(targets),
                                semantics,
                                policy,
                                keywords,
                            ) + 12 + if (preferredScopeIds.contains(scopeId)) 24 else 0,
                    )
                }
        return (containerScopes + parserScopes)
            .sortedByDescending { it.score }
            .distinctBy { it.id }
    }

    private fun buildCandidates(
        indexedObservation: IndexedScreenObservation,
        scope: SearchScope?,
    ): List<UiElementObservation> {
        val baseCandidates =
            indexedObservation.observation.elements +
                indexedObservation.searchElementsById.values +
                syntheticElements(indexedObservation.virtualTargetsById.values.toList())
        if (scope == null) return baseCandidates.distinctBy { it.id }
        return baseCandidates.filter { candidate ->
            when {
                scope.memberIds.contains(candidate.id) -> true
                candidate.containerId.isNotBlank() && candidate.containerId == scope.id -> true
                candidate.searchScopeId.isNotBlank() && candidate.searchScopeId == scope.id -> true
                scope.bounds != null && parseBounds(candidate.bounds)?.let { overlapScore(scope.bounds, it) >= 0.16f } == true -> true
                else -> false
            }
        }.distinctBy { it.id }
    }

    private fun refineCandidates(
        indexedObservation: IndexedScreenObservation,
        seeds: List<SearchCandidate>,
        keywords: List<String>,
        policy: AgentSemanticCandidatePolicy,
        stage: String,
        expansionScale: Float,
    ): List<SearchCandidate> {
        if (seeds.isEmpty()) return emptyList()
        val baseCandidates = buildCandidates(indexedObservation, scope = null)
        return seeds.flatMap { seed ->
            val focusRect =
                parseBounds(seed.element.bounds)?.let { rect ->
                    expandRect(rect, expansionScale)
                } ?: return@flatMap emptyList()
            baseCandidates
                .filter { candidate ->
                    parseBounds(candidate.bounds)?.let { candidateRect ->
                        overlapScore(focusRect, candidateRect) >= 0.12f || rectContains(focusRect, candidateRect)
                    } == true
                }
                .filter { candidate -> policy.accepts(candidate, keywords) }
                .map { candidate ->
                    val localityBoost =
                        parseBounds(candidate.bounds)?.let { candidateRect ->
                            when {
                                overlapScore(focusRect, candidateRect) >= 0.58f -> 26
                                overlapScore(focusRect, candidateRect) >= 0.32f -> 18
                                else -> 10
                            }
                        } ?: 0
                    SearchCandidate(
                        element =
                            if (seed.scopeId.isNotBlank() && candidate.searchScopeId.isBlank()) {
                                candidate.copy(searchScopeId = seed.scopeId)
                            } else {
                                candidate
                            },
                        score = semanticScore(candidate, keywords, policy) + localityBoost + (seed.score / 3),
                        scopeId = seed.scopeId,
                        stage = stage,
                    )
                }
        }.distinctBy { "${it.element.id}|${it.scopeId}" }
    }

    private fun syntheticElements(
        targets: List<VirtualUiTarget>,
    ): List<UiElementObservation> =
        targets.map { target ->
            UiElementObservation(
                id = target.id,
                text = target.text,
                viewId = "virtual:${target.source}",
                className = "VirtualTarget:${target.objectType.ifBlank { target.source }}",
                bounds = target.bounds,
                clickable = true,
                editable = false,
                scrollable = false,
                enabled = true,
                focused = false,
                selected = false,
                parentId = target.matchedElementId.orEmpty(),
                containerId = target.matchedContainerId,
                collectionPosition = target.collectionPosition,
                accessibilityLabel = target.accessibilityLabel,
                accessibilityUniqueId = target.accessibilityUniqueId,
                descriptorTokens = target.descriptorTokens,
                visualDescriptorTokens = target.descriptorTokens,
                interactionRegions = target.interactionRegions,
                visualSignature = target.visualSignature,
                spatialSignature = target.spatialSignature,
                roleHint = target.roleHint,
                neighborTexts = target.contextHints,
                source = target.source.ifBlank { "virtual" },
                searchScopeId = target.searchScopeId,
            )
        }

    private fun scopeScore(
        members: List<UiElementObservation>,
        semantics: String,
        policy: AgentSemanticCandidatePolicy,
        keywords: List<String>,
    ): Int {
        val keywordScore =
            keywords.fold(0) { total, keyword ->
                total +
                    when {
                        semantics.contains(keyword, ignoreCase = true) -> 24 + keyword.length
                        else -> 0
                    }
            }
        val roleBoost = members.sumOf { member -> policy.roleBoost(member) }.coerceAtMost(24)
        val descriptorBoost = members.count { it.visualDescriptorTokens.isNotEmpty() || it.descriptorTokens.isNotEmpty() } * 2
        val interactionBoost = members.count { it.interactionRegions.isNotEmpty() } * 2
        return keywordScore + roleBoost + descriptorBoost + interactionBoost + members.size.coerceAtMost(6)
    }

    private fun semanticScore(
        element: UiElementObservation,
        keywords: List<String>,
        policy: AgentSemanticCandidatePolicy,
    ): Int {
        val semantic = AgentSemanticCandidatePolicies.buildSemanticText(element)
        val keywordScore =
            keywords.fold(0) { total, keyword ->
                total +
                    when {
                        element.text.equals(keyword, ignoreCase = true) -> 110 + keyword.length
                        element.text.contains(keyword, ignoreCase = true) -> 78 + keyword.length
                        semantic.contains(keyword, ignoreCase = true) -> 32 + keyword.length
                        else -> 0
                    }
            }
        if (keywordScore <= 0 && keywords.isNotEmpty()) return 0
        val structureBoost =
            buildList {
                if (element.roleHint.isNotBlank() && element.roleHint.equals(policy.roleHint, ignoreCase = true)) add(22)
                if (element.source.contains("screen_parser", ignoreCase = true)) add(18)
                if (element.source.contains("crop", ignoreCase = true)) add(16)
                if (element.searchScopeId.isNotBlank()) add(14)
                if (element.source == "overflow_hidden") add(14)
                if (element.source == "overflow_recall") add(12)
                if (element.source == "overflow_tree") add(10)
                if (element.interactionRegions.isNotEmpty()) add(8)
                if (element.visualSignature.isNotBlank()) add(8)
                if (element.visualDescriptorTokens.isNotEmpty()) add(10)
                if (element.accessibilityUniqueId.isNotBlank()) add(8)
            }.sum()
        val neighborBoost =
            keywords.fold(0) { total, keyword ->
                total + if (element.neighborTexts.any { it.contains(keyword, ignoreCase = true) }) 14 else 0
            }
        return (keywordScore + structureBoost + neighborBoost + policy.roleBoost(element)).coerceAtLeast(0)
    }

    private fun parseBounds(bounds: String): Rect? {
        val values = Regex("""-?\d+""").findAll(bounds).map { it.value.toInt() }.toList()
        if (values.size < 4) return null
        return Rect(values[0], values[1], values[2], values[3])
    }

    private fun unionRect(rects: List<Rect>): Rect? {
        if (rects.isEmpty()) return null
        return Rect(
            rects.minOf { it.left },
            rects.minOf { it.top },
            rects.maxOf { it.right },
            rects.maxOf { it.bottom },
        )
    }

    private fun overlapScore(
        a: Rect,
        b: Rect,
    ): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val width = (right - left).coerceAtLeast(0)
        val height = (bottom - top).coerceAtLeast(0)
        val area = width * height
        if (area <= 0) return 0f
        val base =
            minOf(
                rectArea(a),
                rectArea(b),
            ).coerceAtLeast(1)
        return area.toFloat() / base.toFloat()
    }

    private fun expandRect(
        rect: Rect,
        scale: Float,
    ): Rect {
        val normalizedScale = scale.coerceIn(0.6f, 2.2f)
        val horizontal = (rectWidth(rect) * 0.3f * normalizedScale).toInt().coerceAtLeast(24)
        val vertical = (rectHeight(rect) * 0.3f * normalizedScale).toInt().coerceAtLeast(24)
        return Rect(
            rect.left - horizontal,
            rect.top - vertical,
            rect.right + horizontal,
            rect.bottom + vertical,
        )
    }

    private fun rectWidth(
        rect: Rect,
    ): Int = (rect.right - rect.left).coerceAtLeast(0)

    private fun rectHeight(
        rect: Rect,
    ): Int = (rect.bottom - rect.top).coerceAtLeast(0)

    private fun rectArea(
        rect: Rect,
    ): Int = rectWidth(rect) * rectHeight(rect)

    private fun rectContains(
        outer: Rect,
        inner: Rect,
    ): Boolean =
        inner.left >= outer.left &&
            inner.top >= outer.top &&
            inner.right <= outer.right &&
            inner.bottom <= outer.bottom

    private data class SearchScope(
        val id: String,
        val source: String,
        val bounds: Rect?,
        val semantics: String,
        val memberIds: Set<String>,
        val score: Int,
    )

    private data class SearchCandidate(
        val element: UiElementObservation,
        val score: Int,
        val scopeId: String,
        val stage: String,
    )
}
