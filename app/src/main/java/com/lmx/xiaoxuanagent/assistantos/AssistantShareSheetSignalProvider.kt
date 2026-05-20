package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey

object AssistantShareSheetSignalProvider {
    private const val PROVIDER_ID = "share_sheet_message"

    fun recordSharedText(
        sharedText: String,
    ): AssistantExternalSignal {
        val normalized = sharedText.trim()
        AssistantSignalProviderStore.markRegistered(
            providerId = PROVIDER_ID,
            source = PROVIDER_ID,
            trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            providerType = "share_sheet",
            supportedCapabilities = listOf(SessionCapabilityKey.START_SESSION),
            supportedSignalTypes = listOf(AssistantExternalSignalType.MESSAGE),
            routingTags = listOf("share", "message", "manual_prefill"),
            preferredEntrySource = "share_text",
            deliveryMode = "intent",
            routingPriority = 95,
        )
        val signal =
            AssistantExternalSignalStore.recordProviderSignal(
                type = AssistantExternalSignalType.MESSAGE,
                capability = SessionCapabilityKey.START_SESSION,
                title = "分享文本",
                summary = normalized.replace('\n', ' ').take(96),
                task = normalized.takeIf { it.isNotBlank() }?.let { "处理分享内容：${it.take(80)}" }.orEmpty(),
                query = normalized.take(240),
                source = PROVIDER_ID,
                signalKey = normalized.take(240),
                payload =
                    mapOf(
                        "ingress" to "share_sheet",
                        "handling_mode" to "manual_prefill",
                    ),
            )
        AssistantExternalSignalStore.markConsumed(signal.id)
        AssistantSignalProviderStore.markSignalObserved(
            providerId = PROVIDER_ID,
            source = PROVIDER_ID,
            trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            reason = normalized.take(48),
        )
        return signal
    }
}
