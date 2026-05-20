package com.lmx.xiaoxuanagent.agent

internal data class WorkerRoleDescriptor(
    val id: String,
    val title: String,
    val summary: String,
    val joinPolicy: String,
    val priority: Int,
    val guidance: String,
)

internal object PlatformWorkerRoleCatalog {
    private val roles =
        listOf(
            WorkerRoleDescriptor("general", "通用执行", "处理不阻塞主链的通用旁路任务。", "detached", 0, "适合泛化子任务、资料整理、异步旁路执行。"),
            WorkerRoleDescriptor("explore", "探索排障", "优先找证据、读历史、查异常上下文。", "wait_any_child", 1, "适合查历史、找失败原因、旁路探索。"),
            WorkerRoleDescriptor("plan", "规划分解", "把任务拆解成步骤、检查阻塞和依赖。", "wait_all_children", 2, "适合长任务规划、阶段梳理、阻塞分析。"),
            WorkerRoleDescriptor("verification", "验证复核", "验证主线程结论、检查风险和遗漏。", "wait_all_children", 2, "适合复核结果、回归检查、收口验证。"),
        )

    fun resolve(role: String): WorkerRoleDescriptor = roles.firstOrNull { it.id == role.trim().lowercase() } ?: roles.first()

    fun exists(role: String): Boolean = roles.any { it.id == role.trim().lowercase() }

    fun lines(): List<String> =
        roles.map { role ->
            "${role.id} | ${role.title} | join=${role.joinPolicy} | ${role.summary}"
        }
}
