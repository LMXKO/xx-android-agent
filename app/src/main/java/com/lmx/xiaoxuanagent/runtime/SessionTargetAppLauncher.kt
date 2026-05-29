package com.lmx.xiaoxuanagent.runtime

/**
 * 目标 App 拉起注册器。
 *
 * 长程恢复发生在后台（心跳 / boot），此时只有无障碍服务这类常驻组件具备
 * `startActivity` 能力把目标 App 拉到前台、从而产生无障碍事件驱动主回路。
 * runtime 层不直接依赖 accessibility 层：由 [AgentAccessibilityService] 在
 * onServiceConnected 时注册一个启动闭包，恢复链通过本注册器调用，沿用
 * [AgentHookRegistry] / [AppForegroundTracker] 的注册器范式，避免反向 import 依赖。
 */
object SessionTargetAppLauncher {
    @Volatile
    private var launcher: ((packageName: String, reason: String) -> Boolean)? = null

    fun register(fn: (packageName: String, reason: String) -> Boolean) {
        launcher = fn
    }

    fun unregister() {
        launcher = null
    }

    fun isAvailable(): Boolean = launcher != null

    /** 请求把目标 App 拉到前台；无注册者或包名为空时返回 false。 */
    fun launch(
        packageName: String,
        reason: String,
    ): Boolean {
        if (packageName.isBlank()) return false
        return launcher?.invoke(packageName, reason) ?: false
    }
}
