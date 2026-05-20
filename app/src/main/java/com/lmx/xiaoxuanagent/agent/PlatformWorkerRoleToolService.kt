package com.lmx.xiaoxuanagent.agent

internal object PlatformWorkerRoleToolService {
    fun readRoles(): AgentExecutionResult =
        AgentExecutionResult(
            message = "已读取本地 worker 角色目录。",
            keepRunning = true,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "platform_worker_roles",
            toolRuntimeDetailLines = PlatformWorkerRoleCatalog.lines(),
        )
}
