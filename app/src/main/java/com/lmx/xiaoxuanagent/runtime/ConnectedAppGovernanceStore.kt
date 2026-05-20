package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.ConnectedAppCatalog
import com.lmx.xiaoxuanagent.agent.ConnectedAppExecutionReceiptStore
import com.lmx.xiaoxuanagent.agent.ConnectedAppGoldenPathRegistry
import com.lmx.xiaoxuanagent.taskprofile.AutomationSupportPolicyStore
import com.lmx.xiaoxuanagent.taskprofile.ScreenAutomationProductSupportStore

object ConnectedAppGovernanceStore {
    fun lines(
        limit: Int = 4,
    ): List<String> =
        ConnectedAppCatalog.descriptors()
            .take(limit.coerceAtLeast(1))
            .map { descriptor ->
                val policy =
                    AutomationSupportPolicyStore.staticPolicy(
                        profileId = descriptor.appId,
                        packageName = descriptor.packageName,
                    )
                val support =
                    ScreenAutomationProductSupportStore.snapshot(
                        profileId = descriptor.appId,
                        packageName = descriptor.packageName,
                    )
                val goldenPaths = ConnectedAppGoldenPathRegistry.paths(descriptor.appId)
                val lifecycle = ConnectedAppLifecycleStore.snapshot(descriptor.appId)
                val receipt = ConnectedAppExecutionReceiptStore.latest(descriptor.appId)
                buildString {
                    append("connected_app | ").append(descriptor.title)
                    append(" | status=").append(if (lifecycle.connected) "connected" else "disconnected")
                    append(" | path=").append(policy.preferredExecutionPath)
                    append(" | ops=").append(descriptor.operations.size)
                    append(" | golden=").append(goldenPaths.size)
                    append(" | tier=").append(support.tier)
                    append(" | confirm=").append(lifecycle.confirmationMode)
                    receipt?.state?.takeIf { it.isNotBlank() }?.let { append(" | state=").append(it) }
                    append(" | ").append(receipt?.summary ?: descriptor.supportCeiling.ifBlank { descriptor.summary })
                }
            }
}
