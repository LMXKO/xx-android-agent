package com.lmx.xiaoxuanagent.agent

internal object ActionRegionHeadEngine {
    fun proposeRegions(
        roleHint: String,
        objectType: String,
        tapPattern: String,
        descriptorTokens: List<String>,
        packageName: String,
        pageState: String,
        textFree: Boolean,
    ): List<InteractionRegion> {
        val learnedRegions =
            ActionRegionHeadStore.suggestRegions(
                packageName = packageName,
                pageState = pageState,
                roleHint = roleHint,
                objectType = objectType,
                descriptorTokens = descriptorTokens,
                textFree = textFree,
            )
        val semanticRegions =
            buildList {
                if (tapPattern == "list_row" || descriptorTokens.any { it.contains("联系人") || it.contains("会话") || it.contains("路线") }) {
                    add(region(0.08f, 0.16f, 0.74f, 0.82f, 0.84f, "action_head_row"))
                    add(region(0.14f, 0.2f, 0.88f, 0.8f, 0.72f, "action_head_row_alt"))
                }
                if (tapPattern.contains("card") || objectType.contains("card", ignoreCase = true) || roleHint == "detail") {
                    add(region(0.16f, 0.14f, 0.88f, 0.76f, 0.82f, "action_head_card"))
                    add(region(0.24f, 0.22f, 0.74f, 0.6f, 0.7f, "action_head_card_focus"))
                }
                if (textFree || objectType.contains("icon", ignoreCase = true) || objectType.contains("button", ignoreCase = true)) {
                    add(region(0.18f, 0.16f, 0.54f, 0.54f, 0.76f, "action_head_icon"))
                    add(region(0.44f, 0.16f, 0.82f, 0.54f, 0.72f, "action_head_icon_alt"))
                    add(region(0.26f, 0.42f, 0.74f, 0.84f, 0.64f, "action_head_icon_lower"))
                }
                if (roleHint == "dismiss") {
                    add(region(0.66f, 0.06f, 0.96f, 0.36f, 0.92f, "action_head_corner"))
                }
            }
        val denseRegions =
            buildDenseHeadRegions(
                tapPattern = tapPattern,
                roleHint = roleHint,
                objectType = objectType,
                textFree = textFree,
            )
        return (learnedRegions + semanticRegions + denseRegions)
            .distinctBy { "${it.leftFraction}:${it.topFraction}:${it.rightFraction}:${it.bottomFraction}:${it.label}" }
            .sortedByDescending { it.score }
            .take(10)
    }

    private fun buildDenseHeadRegions(
        tapPattern: String,
        roleHint: String,
        objectType: String,
        textFree: Boolean,
    ): List<InteractionRegion> {
        val rows =
            when {
                tapPattern == "list_row" -> 2
                textFree || objectType.contains("icon", ignoreCase = true) -> 3
                else -> 3
            }
        val columns =
            when {
                tapPattern == "list_row" -> 3
                else -> 3
            }
        return buildList {
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    val left = column.toFloat() / columns
                    val top = row.toFloat() / rows
                    val right = (column + 1).toFloat() / columns
                    val bottom = (row + 1).toFloat() / rows
                    add(
                        region(
                            left = (left + 0.05f).coerceIn(0f, 0.94f),
                            top = (top + 0.05f).coerceIn(0f, 0.94f),
                            right = (right - 0.05f).coerceIn(0.06f, 1f),
                            bottom = (bottom - 0.05f).coerceIn(0.06f, 1f),
                            score =
                                when {
                                    roleHint == "dismiss" && row == 0 && column == columns - 1 -> 0.76f
                                    tapPattern == "list_row" && column == 0 -> 0.72f
                                    textFree || objectType.contains("icon", ignoreCase = true) -> 0.64f
                                    else -> 0.52f
                                },
                            label = "action_head_grid_${row + 1}_${column + 1}",
                        ),
                    )
                }
            }
        }
    }

    private fun region(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        score: Float,
        label: String,
    ) = InteractionRegion(
        leftFraction = left,
        topFraction = top,
        rightFraction = right,
        bottomFraction = bottom,
        score = score,
        label = label,
    )
}
