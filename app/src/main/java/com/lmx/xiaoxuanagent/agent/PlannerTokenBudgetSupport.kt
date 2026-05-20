package com.lmx.xiaoxuanagent.agent

internal data class PlannerTokenBudgetState(
    val estimatedTokens: Int,
    val budgetTokens: Int,
    val percentLeft: Int,
    val aboveWarning: Boolean,
    val aboveError: Boolean,
    val aboveAutoCompact: Boolean,
    val atBlockingLimit: Boolean,
    val recommendedProfile: String,
    val detailLines: List<String>,
)

internal object PlannerTokenBudgetSupport {
    private const val DEFAULT_BUDGET_TOKENS = 6_400
    private const val WARNING_BUFFER_TOKENS = 1_200
    private const val ERROR_BUFFER_TOKENS = 640
    private const val AUTO_COMPACT_THRESHOLD = 5_200

    fun evaluate(
        estimatedTokens: Int,
        currentProfile: String,
        budgetTokens: Int = DEFAULT_BUDGET_TOKENS,
    ): PlannerTokenBudgetState {
        val safeBudget = budgetTokens.coerceAtLeast(1)
        val percentLeft = ((safeBudget - estimatedTokens).coerceAtLeast(0) * 100 / safeBudget).coerceIn(0, 100)
        val warningThreshold = safeBudget - WARNING_BUFFER_TOKENS
        val errorThreshold = safeBudget - ERROR_BUFFER_TOKENS
        val aboveWarning = estimatedTokens >= warningThreshold
        val aboveError = estimatedTokens >= errorThreshold
        val aboveAutoCompact = estimatedTokens >= AUTO_COMPACT_THRESHOLD
        val atBlockingLimit = estimatedTokens >= safeBudget
        val recommendedProfile =
            when {
                atBlockingLimit || aboveError -> "auto_compact_retry"
                aboveAutoCompact || aboveWarning -> "reactive_retry"
                else -> currentProfile
            }
        return PlannerTokenBudgetState(
            estimatedTokens = estimatedTokens,
            budgetTokens = safeBudget,
            percentLeft = percentLeft,
            aboveWarning = aboveWarning,
            aboveError = aboveError,
            aboveAutoCompact = aboveAutoCompact,
            atBlockingLimit = atBlockingLimit,
            recommendedProfile = recommendedProfile,
            detailLines =
                buildList {
                    add("token_budget=$safeBudget")
                    add("token_estimate=$estimatedTokens")
                    add("token_percent_left=$percentLeft")
                    if (aboveWarning) add("token_warning=true")
                    if (aboveError) add("token_error=true")
                    if (aboveAutoCompact) add("token_auto_compact=true")
                    if (atBlockingLimit) add("token_blocking=true")
                    add("recommended_profile=$recommendedProfile")
                },
        )
    }
}
