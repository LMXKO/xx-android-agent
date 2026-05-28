package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext

/**
 * App 安装预检：让 mission 分解器在选 App 时跳过未安装的候选，避免后续 LaunchApp 才发现失败。
 * 接口形式以便单测可以注入假实现而不依赖 Android PackageManager。
 */
fun interface InstalledPackageChecker {
    fun isInstalled(packageName: String): Boolean

    companion object {
        /** 任意都视为已安装（用于单测默认值，或没有 PackageManager 时的安全回落）。 */
        val ASSUME_ALL_INSTALLED = InstalledPackageChecker { true }

        /** 真实环境：用 AppRuntimeContext 提供的 PackageManager 检查；context 不可用时降级为"假定已装"。 */
        val FROM_RUNTIME =
            InstalledPackageChecker { packageName ->
                if (packageName.isBlank()) return@InstalledPackageChecker false
                val context = AppRuntimeContext.get() ?: return@InstalledPackageChecker true
                runCatching {
                    context.packageManager.getPackageInfo(packageName, 0)
                    true
                }.getOrDefault(false)
            }
    }
}

/** 给 ConnectedAppDescriptor:返回首个已安装的候选包名,全都未装则 null。 */
fun ConnectedAppDescriptor.firstInstalledPackageName(
    checker: InstalledPackageChecker = InstalledPackageChecker.FROM_RUNTIME,
): String? = candidatePackageNames().firstOrNull(checker::isInstalled)
