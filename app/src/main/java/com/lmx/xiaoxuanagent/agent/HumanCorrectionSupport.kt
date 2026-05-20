package com.lmx.xiaoxuanagent.agent

private val correctionBlockedTargets =
    listOf("公众号", "服务号", "群聊", "群", "频道", "小程序", "店铺", "品牌", "综合", "筛选", "更多", "首页")

private val backtrackCorrectionKeywords =
    listOf("点错", "进错", "错页", "回上一", "返回上一", "退回", "返回上", "回到上一")

private val overscrollUpCorrectionKeywords =
    listOf("滚过头", "滑过头", "往上", "上滑", "回拉", "拉回", "往回")

private val scrollDownCorrectionKeywords =
    listOf("往下", "下滑", "继续往下", "继续下滑")

private val refocusCorrectionKeywords =
    listOf("重新找", "重新搜索", "重搜", "重找", "重新定位", "找真正", "优先找", "不要点")

private val executableResumeCorrectionKeywords =
    listOf("继续", "重试", "重新", "返回", "回去", "重找", "重搜", "重新搜索", "重新定位", "找真正", "优先找")

private val manualVerificationCompletionKeywords =
    listOf("已登录", "登录好了", "已经登录", "验证码好了", "验证好了", "我处理好了", "处理完了", "已经完成", "已完成")

private val permissionGrantCompletionKeywords =
    listOf("已授权", "授权好了", "已经授权", "允许了", "已经允许", "已开启", "开好了", "设置好了", "我处理好了", "处理完了", "已经完成", "已完成")

internal data class HumanCorrectionInterpretation(
    val normalizedText: String,
    val blockedTargetKeywords: List<String>,
    val mentionsBacktrack: Boolean,
    val mentionsOverscrollUp: Boolean,
    val mentionsScrollDown: Boolean,
    val mentionsRefocus: Boolean,
    val mentionsExecutableResumeDirective: Boolean,
    val primaryRecoveryIntent: HumanCorrectionRecoveryIntent,
)

internal data class ResumeCorrectionDirective(
    val intent: HumanCorrectionRecoveryIntent,
    val policy: String,
    val guidance: String,
)

internal enum class HumanCorrectionRecoveryIntent {
    NONE,
    BACKTRACK,
    OVERSCROLL_UP,
    SCROLL_DOWN,
    REFOCUS_ENTRY,
}

internal fun normalizeCorrectionText(raw: String): String = raw.trim().replace('\n', ' ').take(120)

internal fun interpretHumanCorrection(
    raw: String,
): HumanCorrectionInterpretation {
    val normalizedText = normalizeCorrectionText(raw)
    val blockedTargetKeywords = extractBlockedTargetKeywords(normalizedText)
    val mentionsBacktrack = backtrackCorrectionKeywords.any { normalizedText.contains(it, ignoreCase = true) }
    val mentionsOverscrollUp = overscrollUpCorrectionKeywords.any { normalizedText.contains(it, ignoreCase = true) }
    val mentionsScrollDown = scrollDownCorrectionKeywords.any { normalizedText.contains(it, ignoreCase = true) }
    val mentionsRefocus = refocusCorrectionKeywords.any { normalizedText.contains(it, ignoreCase = true) }
    val mentionsExecutableResumeDirective =
        mentionsBacktrack ||
            mentionsOverscrollUp ||
            mentionsScrollDown ||
            executableResumeCorrectionKeywords.any { normalizedText.contains(it, ignoreCase = true) }
    val primaryRecoveryIntent =
        when {
            mentionsBacktrack -> HumanCorrectionRecoveryIntent.BACKTRACK
            mentionsOverscrollUp -> HumanCorrectionRecoveryIntent.OVERSCROLL_UP
            mentionsScrollDown -> HumanCorrectionRecoveryIntent.SCROLL_DOWN
            mentionsRefocus -> HumanCorrectionRecoveryIntent.REFOCUS_ENTRY
            else -> HumanCorrectionRecoveryIntent.NONE
        }
    return HumanCorrectionInterpretation(
        normalizedText = normalizedText,
        blockedTargetKeywords = blockedTargetKeywords,
        mentionsBacktrack = mentionsBacktrack,
        mentionsOverscrollUp = mentionsOverscrollUp,
        mentionsScrollDown = mentionsScrollDown,
        mentionsRefocus = mentionsRefocus,
        mentionsExecutableResumeDirective = mentionsExecutableResumeDirective,
        primaryRecoveryIntent = primaryRecoveryIntent,
    )
}

internal fun resolveResumeCorrectionDirective(
    raw: String,
): ResumeCorrectionDirective {
    val interpretation = interpretHumanCorrection(raw)
    return buildResumeCorrectionDirective(
        intent = interpretation.primaryRecoveryIntent,
        blockedTargets = interpretation.blockedTargetKeywords,
    )
}

internal fun buildResumeCorrectionDirective(
    intent: HumanCorrectionRecoveryIntent,
    blockedTargets: List<String> = emptyList(),
): ResumeCorrectionDirective =
    when (intent) {
        HumanCorrectionRecoveryIntent.BACKTRACK ->
            ResumeCorrectionDirective(
                intent = intent,
                policy = "backtrack",
                guidance = "建议先返回上一层，再重新定位当前子目标。",
            )

        HumanCorrectionRecoveryIntent.OVERSCROLL_UP ->
            ResumeCorrectionDirective(
                intent = intent,
                policy = "overscroll_up",
                guidance = "建议先小步回拉，避免继续沿当前方向滚动。",
            )

        HumanCorrectionRecoveryIntent.SCROLL_DOWN ->
            ResumeCorrectionDirective(
                intent = intent,
                policy = "scroll_down",
                guidance = "建议先继续向下探索当前列表，再决定是否重新定位。",
            )

        HumanCorrectionRecoveryIntent.REFOCUS_ENTRY ->
            ResumeCorrectionDirective(
                intent = intent,
                policy = "refocus_entry",
                guidance =
                    if (blockedTargets.isNotEmpty()) {
                        "建议先避开 ${blockedTargets.joinToString("/")}，重新定位入口目标后再继续。"
                    } else {
                        "建议先重新定位入口目标后再继续。"
                    },
            )

        HumanCorrectionRecoveryIntent.NONE ->
            ResumeCorrectionDirective(
                intent = intent,
                policy =
                    if (blockedTargets.isNotEmpty()) {
                        "avoid_blocked_target"
                    } else {
                        "freeform_correction"
                    },
                guidance =
                    if (blockedTargets.isNotEmpty()) {
                        "建议先避开 ${blockedTargets.joinToString("/")}，优先选择更贴近目标的候选。"
                    } else {
                        ""
                    },
            )
    }

internal fun extractBlockedTargetKeywords(correction: String): List<String> =
    correctionBlockedTargets.filter { correction.contains(it, ignoreCase = true) }

internal fun correctionMentionsBacktrack(
    correction: String,
): Boolean = interpretHumanCorrection(correction).mentionsBacktrack

internal fun correctionMentionsOverscrollUp(
    correction: String,
): Boolean = interpretHumanCorrection(correction).mentionsOverscrollUp

internal fun correctionMentionsScrollDown(
    correction: String,
): Boolean = interpretHumanCorrection(correction).mentionsScrollDown

internal fun correctionMentionsRefocus(
    correction: String,
): Boolean = interpretHumanCorrection(correction).mentionsRefocus

internal fun correctionMentionsExecutableResumeDirective(
    correction: String,
): Boolean = interpretHumanCorrection(correction).mentionsExecutableResumeDirective

internal fun correctionConfirmsWaitingEventCompleted(
    waitingEvent: String,
    correction: String,
): Boolean {
    val normalizedText = normalizeCorrectionText(correction)
    val completionKeywords =
        when (waitingEvent) {
            "manual_verification" -> manualVerificationCompletionKeywords
            "permission_grant" -> permissionGrantCompletionKeywords
            else -> emptyList()
        }
    return completionKeywords.any { normalizedText.contains(it, ignoreCase = true) }
}
