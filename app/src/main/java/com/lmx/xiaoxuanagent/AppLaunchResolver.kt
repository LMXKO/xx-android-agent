package com.lmx.xiaoxuanagent

import android.content.Intent
import android.content.pm.PackageManager

data class LaunchableAppInfo(
    val packageName: String,
    val label: String,
)

object AppLaunchResolver {
    fun resolve(packageManager: PackageManager, packageName: String): Intent? {
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val launcherQuery = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)

        val resolvedActivity = packageManager.queryIntentActivities(
            launcherQuery,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        ).firstOrNull()?.activityInfo ?: return null

        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(resolvedActivity.packageName, resolvedActivity.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    fun listLaunchableApps(
        packageManager: PackageManager,
        excludePackages: Set<String> = emptySet(),
    ): List<LaunchableAppInfo> {
        val launcherQuery = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(
            launcherQuery,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        )
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val packageName = activityInfo.packageName.orEmpty()
                if (packageName.isBlank() || packageName in excludePackages) {
                    return@mapNotNull null
                }
                val label =
                    resolveInfo.loadLabel(packageManager).toString().trim()
                        .ifBlank { packageName.substringAfterLast('.') }
                LaunchableAppInfo(
                    packageName = packageName,
                    label = label,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
