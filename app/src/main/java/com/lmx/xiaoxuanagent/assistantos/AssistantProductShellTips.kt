package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.memory.PersonalMemoryInsightSnapshot
import com.lmx.xiaoxuanagent.runtime.ArtifactRetentionReport
import com.lmx.xiaoxuanagent.runtime.SessionConversationCompactSnapshot

internal fun mergeProductTipLedger(
    previous: List<AssistantTipCard>,
    candidates: List<AssistantTipCandidate>,
    now: Long,
): List<AssistantTipCard> = AssistantTipScheduler.mergeLedger(previous, candidates, now)

internal fun buildProductTipCandidates(
    assistantSnapshot: AssistantOsSnapshot,
    mailboxPendingApprovals: Int,
    providerStates: List<AssistantSignalProviderState>,
    blockedSessionCount: Int,
    retentionPreview: ArtifactRetentionReport,
    memoryQueue: List<com.lmx.xiaoxuanagent.runtime.BackgroundMemoryQueueTask>,
    diagnostics: AssistantProductDiagnosticsSnapshot,
    memoryInsight: PersonalMemoryInsightSnapshot,
    conversationCompact: SessionConversationCompactSnapshot? = null,
    voiceInteraction: AssistantVoiceInteractionSnapshot = AssistantVoiceInteractionSnapshot(),
    adaptivePolicy: AssistantAdaptivePolicySnapshot,
    reason: String,
): List<AssistantTipCandidate> =
    AssistantTipRegistry.buildCandidates(
        assistantSnapshot = assistantSnapshot,
        mailboxPendingApprovals = mailboxPendingApprovals,
        providerStates = providerStates,
        blockedSessionCount = blockedSessionCount,
        retentionPreview = retentionPreview,
        memoryQueue = memoryQueue,
        diagnostics = diagnostics,
        memoryInsight = memoryInsight,
        conversationCompact = conversationCompact,
        voiceInteraction = voiceInteraction,
        adaptivePolicy = adaptivePolicy,
        reason = reason,
    )
