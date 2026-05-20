package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentToolRuntimeObjectRegistry

data class SessionToolContractSnapshot(
    val totalCount: Int = 0,
    val interactiveCount: Int = 0,
    val deferredCount: Int = 0,
    val alwaysLoadCount: Int = 0,
    val concurrentCount: Int = 0,
    val summary: String = "",
    val lines: List<String> = emptyList(),
)

object SessionToolContractStore {
    fun readSnapshot(
        limit: Int = 12,
    ): SessionToolContractSnapshot {
        val contracts = AgentToolRuntimeObjectRegistry.catalog().map { it.previewContract }
        return SessionToolContractSnapshot(
            totalCount = contracts.size,
            interactiveCount = contracts.count { it.requiresUserInteraction },
            deferredCount = contracts.count { it.shouldDefer },
            alwaysLoadCount = contracts.count { it.alwaysLoad },
            concurrentCount = contracts.count { it.concurrencySafe },
            summary =
                buildString {
                    append("contracts=").append(contracts.size)
                    append(" interact=").append(contracts.count { it.requiresUserInteraction })
                    append(" defer=").append(contracts.count { it.shouldDefer })
                    append(" always=").append(contracts.count { it.alwaysLoad })
                    append(" concurrent=").append(contracts.count { it.concurrencySafe })
                },
            lines =
                contracts.take(limit.coerceAtLeast(1)).map { contract ->
                    val runtimeObject =
                        AgentToolRuntimeObjectRegistry.catalog().firstOrNull { it.name == contract.toolName }
                    buildString {
                        append(contract.toolName)
                        append(" | perm=").append(contract.permissionFamily)
                        append(" | interrupt=").append(contract.interruptBehavior.ifBlank { "-" })
                        append(" | interact=").append(contract.requiresUserInteraction)
                        append(" | defer=").append(contract.shouldDefer)
                        append(" | always=").append(contract.alwaysLoad)
                        runtimeObject?.groupKey?.takeIf { it.isNotBlank() }?.let { append(" | group=").append(it) }
                    }
                },
        )
    }
}
