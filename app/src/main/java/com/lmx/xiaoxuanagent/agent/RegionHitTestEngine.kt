package com.lmx.xiaoxuanagent.agent

import android.graphics.Rect

internal data class RegionHitTestPoint(
    val x: Float,
    val y: Float,
    val score: Float,
    val source: String,
)

internal data class RegionHitTestPlan(
    val points: List<RegionHitTestPoint>,
    val mode: String,
)

internal object RegionHitTestEngine {
    fun buildPlan(
        rect: Rect,
        hotspotX: Float,
        hotspotY: Float,
        roleHint: String,
        objectType: String,
        tapPattern: String,
        descriptorTokens: List<String>,
        interactionRegions: List<InteractionRegion>,
        packageName: String = "",
        pageState: String = "",
        learnedRegions: List<InteractionRegion> = emptyList(),
        textFree: Boolean = false,
    ): RegionHitTestPlan {
        val rankedRegions =
            rankRegions(
                rect = rect,
                roleHint = roleHint,
                objectType = objectType,
                tapPattern = tapPattern,
                descriptorTokens = descriptorTokens,
                interactionRegions = interactionRegions + learnedRegions,
                packageName = packageName,
                pageState = pageState,
                textFree = textFree,
            )
        val points =
            rankedRegions.take(6).flatMap { candidate ->
                buildList {
                    val centerX = candidate.rect.exactCenterX()
                    val centerY = candidate.rect.exactCenterY()
                    add(RegionHitTestPoint(centerX, centerY, candidate.score, candidate.resolvedSource))
                    add(
                        RegionHitTestPoint(
                            candidate.rect.left + candidate.rect.width() * 0.33f,
                            candidate.rect.top + candidate.rect.height() * 0.33f,
                            candidate.score * 0.96f,
                            candidate.resolvedSource,
                        ),
                    )
                    add(
                        RegionHitTestPoint(
                            candidate.rect.left + candidate.rect.width() * 0.67f,
                            candidate.rect.top + candidate.rect.height() * 0.67f,
                            candidate.score * 0.95f,
                            candidate.resolvedSource,
                        ),
                    )
                    add(
                        RegionHitTestPoint(
                            candidate.rect.left + candidate.rect.width() * 0.42f,
                            candidate.rect.top + candidate.rect.height() * 0.42f,
                            candidate.score * 0.92f,
                            candidate.resolvedSource,
                        ),
                    )
                    if (candidate.rect.width() >= 24 && candidate.rect.height() >= 24) {
                        add(
                            RegionHitTestPoint(
                                candidate.rect.left + candidate.rect.width() * hotspotX.coerceIn(0.18f, 0.82f),
                                candidate.rect.top + candidate.rect.height() * hotspotY.coerceIn(0.18f, 0.82f),
                                candidate.score * 0.88f,
                                candidate.resolvedSource,
                            ),
                        )
                    }
                }
            }.sortedByDescending { it.score }
                .distinctBy { "${it.x.toInt()}:${it.y.toInt()}" }
        return RegionHitTestPlan(
            points = points,
            mode =
                when {
                    rankedRegions.firstOrNull()?.resolvedSource?.startsWith("action_head_") == true -> "action_head"
                    rankedRegions.firstOrNull()?.resolvedSource?.startsWith("learned_") == true -> "learned_region"
                    rankedRegions.firstOrNull()?.resolvedSource?.startsWith("grid_") == true -> "grid_region"
                    else -> rankedRegions.firstOrNull()?.resolvedSource ?: "fallback_region"
                },
        )
    }

    private fun rankRegions(
        rect: Rect,
        roleHint: String,
        objectType: String,
        tapPattern: String,
        descriptorTokens: List<String>,
        interactionRegions: List<InteractionRegion>,
        packageName: String,
        pageState: String,
        textFree: Boolean,
    ): List<RegionCandidate> {
        val explicitRegions =
            interactionRegions.mapNotNull { region ->
                val candidateRect =
                    Rect(
                        (rect.left + rect.width() * region.leftFraction).toInt(),
                        (rect.top + rect.height() * region.topFraction).toInt(),
                        (rect.left + rect.width() * region.rightFraction).toInt(),
                        (rect.top + rect.height() * region.bottomFraction).toInt(),
                    )
                if (candidateRect.isEmpty) null else RegionCandidate(candidateRect, region.score.coerceAtLeast(0.1f), region.label.ifBlank { "explicit_region" })
            }
        val actionHeadRegions =
            ActionRegionHeadEngine.proposeRegions(
                roleHint = roleHint,
                objectType = objectType,
                tapPattern = tapPattern,
                descriptorTokens = descriptorTokens,
                packageName = packageName,
                pageState = pageState,
                textFree = textFree,
            ).mapNotNull { region ->
                val candidateRect =
                    Rect(
                        (rect.left + rect.width() * region.leftFraction).toInt(),
                        (rect.top + rect.height() * region.topFraction).toInt(),
                        (rect.left + rect.width() * region.rightFraction).toInt(),
                        (rect.top + rect.height() * region.bottomFraction).toInt(),
                    )
                if (candidateRect.isEmpty) null else RegionCandidate(candidateRect, region.score.coerceAtLeast(0.1f), region.label.ifBlank { "action_head_region" })
            }
        val denseGridRegions =
            buildDenseGridRegions(
                rect = rect,
                tapPattern = tapPattern,
                roleHint = roleHint,
                objectType = objectType,
                textFree = textFree,
            )
        val syntheticRegions =
            buildList {
                add(region(rect, 0.18f, 0.18f, 0.82f, 0.82f, 0.52f, "region_body"))
                if (roleHint == "dismiss") {
                    add(region(rect, 0.68f, 0.08f, 0.94f, 0.34f, 0.96f, "corner_action"))
                }
                if (tapPattern == "list_row" || descriptorTokens.any { it.contains("联系人") || it.contains("会话") || it.contains("路线") }) {
                    add(region(rect, 0.12f, 0.18f, 0.66f, 0.76f, 0.9f, "row_primary"))
                    add(region(rect, 0.18f, 0.22f, 0.84f, 0.76f, 0.72f, "row_secondary"))
                }
                if (tapPattern == "card_body" || tapPattern == "container_body" || objectType.contains("card", ignoreCase = true)) {
                    add(region(rect, 0.18f, 0.16f, 0.86f, 0.72f, 0.9f, "card_body"))
                    add(region(rect, 0.28f, 0.28f, 0.72f, 0.58f, 0.76f, "card_focus"))
                }
                if (
                    tapPattern == "icon_cluster" ||
                    objectType.contains("icon", ignoreCase = true) ||
                    objectType.contains("button", ignoreCase = true) ||
                    textFree
                ) {
                    add(region(rect, 0.24f, 0.2f, 0.52f, 0.54f, 0.84f, "icon_core"))
                    add(region(rect, 0.48f, 0.18f, 0.78f, 0.52f, 0.8f, "icon_alt"))
                    add(region(rect, 0.28f, 0.46f, 0.72f, 0.82f, 0.7f, "icon_lower"))
                }
            }
        return (actionHeadRegions + explicitRegions + syntheticRegions + denseGridRegions)
            .groupBy { candidate ->
                "${candidate.rect.left}:${candidate.rect.top}:${candidate.rect.right}:${candidate.rect.bottom}"
            }.values
            .map { candidates ->
                candidates.map { candidate ->
                    val learnedBonus =
                        if (candidate.source.startsWith("action_head")) {
                            0f
                        } else {
                            learnedActionHeadBonus(
                                candidate = candidate,
                                parentRect = rect,
                                packageName = packageName,
                                pageState = pageState,
                                roleHint = roleHint,
                                objectType = objectType,
                                descriptorTokens = descriptorTokens,
                                textFree = textFree,
                            )
                        }
                    val resolvedSource =
                        if (learnedBonus >= 0.22f) {
                            "action_head_${candidate.source}"
                        } else if (candidate.source.startsWith("action_head")) {
                            candidate.source
                        } else {
                            candidate.source
                        }
                    candidate.copy(
                        score =
                            scoreRegionCandidate(
                                candidate = candidate,
                                roleHint = roleHint,
                                objectType = objectType,
                                tapPattern = tapPattern,
                                descriptorTokens = descriptorTokens,
                                textFree = textFree,
                            ) + learnedBonus + if (resolvedSource.startsWith("action_head_")) 0.04f else 0f,
                        resolvedSource = resolvedSource,
                    )
                }.maxByOrNull { it.score } ?: candidates.first()
            }
            .sortedByDescending { it.score }
    }

    private fun learnedActionHeadBonus(
        candidate: RegionCandidate,
        parentRect: Rect,
        packageName: String,
        pageState: String,
        roleHint: String,
        objectType: String,
        descriptorTokens: List<String>,
        textFree: Boolean,
    ): Float {
        val parentWidth = parentRect.width().toFloat().coerceAtLeast(1f)
        val parentHeight = parentRect.height().toFloat().coerceAtLeast(1f)
        return ActionRegionHeadStore.scoreCandidate(
            packageName = packageName,
            pageState = pageState,
            roleHint = roleHint,
            objectType = objectType,
            descriptorTokens = descriptorTokens,
            textFree = textFree,
            source = candidate.source,
            centerXFraction = ((candidate.rect.exactCenterX() - parentRect.left) / parentWidth).coerceIn(0f, 1f),
            centerYFraction = ((candidate.rect.exactCenterY() - parentRect.top) / parentHeight).coerceIn(0f, 1f),
            widthFraction = (candidate.rect.width().toFloat() / parentWidth).coerceIn(0f, 1f),
            heightFraction = (candidate.rect.height().toFloat() / parentHeight).coerceIn(0f, 1f),
        )
    }

    private fun buildDenseGridRegions(
        rect: Rect,
        tapPattern: String,
        roleHint: String,
        objectType: String,
        textFree: Boolean,
    ): List<RegionCandidate> {
        val rows =
            when {
                textFree || objectType.contains("icon", ignoreCase = true) -> 3
                tapPattern == "list_row" -> 2
                else -> 3
            }
        val columns =
            when {
                tapPattern == "list_row" -> 3
                objectType.contains("card", ignoreCase = true) -> 3
                else -> 3
            }
        val regions = mutableListOf<RegionCandidate>()
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val left = column.toFloat() / columns.toFloat()
                val top = row.toFloat() / rows.toFloat()
                val right = (column + 1).toFloat() / columns.toFloat()
                val bottom = (row + 1).toFloat() / rows.toFloat()
                val insetX = 0.06f
                val insetY = 0.06f
                val source =
                    when {
                        tapPattern == "list_row" -> "grid_row_${row + 1}_${column + 1}"
                        objectType.contains("icon", ignoreCase = true) || textFree -> "grid_icon_${row + 1}_${column + 1}"
                        roleHint == "dismiss" -> "grid_dismiss_${row + 1}_${column + 1}"
                        else -> "grid_region_${row + 1}_${column + 1}"
                    }
                regions +=
                    region(
                        rect = rect,
                        left = (left + insetX).coerceIn(0f, 0.94f),
                        top = (top + insetY).coerceIn(0f, 0.94f),
                        right = (right - insetX).coerceIn(0.06f, 1f),
                        bottom = (bottom - insetY).coerceIn(0.06f, 1f),
                        score = 0.38f,
                        source = source,
                    )
            }
        }
        return regions
    }

    private fun scoreRegionCandidate(
        candidate: RegionCandidate,
        roleHint: String,
        objectType: String,
        tapPattern: String,
        descriptorTokens: List<String>,
        textFree: Boolean,
    ): Float {
        val source = candidate.source
        val semanticBoost =
            when {
                source.startsWith("learned_") -> 0.44f
                source == "corner_action" && roleHint == "dismiss" -> 0.42f
                source.startsWith("row") && tapPattern == "list_row" -> 0.34f
                source.startsWith("grid_row") && tapPattern == "list_row" -> 0.26f
                source.startsWith("card") && tapPattern.contains("card") -> 0.32f
                source.startsWith("grid_region") && objectType.contains("card", ignoreCase = true) -> 0.22f
                source.startsWith("icon") && (textFree || objectType.contains("icon", ignoreCase = true)) -> 0.32f
                source.startsWith("grid_icon") && (textFree || objectType.contains("icon", ignoreCase = true)) -> 0.28f
                descriptorTokens.any { token -> token.contains("路线") || token.contains("联系人") } && source.startsWith("grid_") -> 0.14f
                else -> 0f
            }
        val sizePenalty =
            when {
                candidate.rect.width() < 18 || candidate.rect.height() < 18 -> 0.22f
                else -> 0f
            }
        return candidate.score + semanticBoost - sizePenalty
    }

    private fun region(
        rect: Rect,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        score: Float,
        source: String,
    ) = RegionCandidate(
        rect =
            Rect(
                (rect.left + rect.width() * left).toInt(),
                (rect.top + rect.height() * top).toInt(),
                (rect.left + rect.width() * right).toInt(),
                (rect.top + rect.height() * bottom).toInt(),
            ),
        score = score,
        source = source,
    )

    private data class RegionCandidate(
        val rect: Rect,
        val score: Float,
        val source: String,
        val resolvedSource: String = source,
    )
}
