package com.lmx.xiaoxuanagent.safety

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.lmx.xiaoxuanagent.assistantos.AssistantEntrySurface
import com.lmx.xiaoxuanagent.assistantos.AssistantExperimentKey
import com.lmx.xiaoxuanagent.assistantos.AssistantFeatureFlagKey
import com.lmx.xiaoxuanagent.assistantos.AssistantOsSnapshot
import com.lmx.xiaoxuanagent.assistantos.AssistantPermissionMode
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.TargetAppReturnTrigger

data class AssistantSurfaceAvailability(
    val supported: Boolean,
    val enabled: Boolean,
    val available: Boolean,
    val summary: String,
)

object PermissionModeOrchestrator {
    fun effectivePermissionMode(
        snapshot: AssistantOsSnapshot,
    ): AssistantPermissionMode =
        if (snapshot.isExperimentEnabled(AssistantExperimentKey.PERSISTENT_ASSISTANT_OS_V1)) {
            snapshot.permissionMode
        } else {
            AssistantPermissionMode.ASSISTED
        }

    fun canDrawOverlays(): Boolean {
        val context = AppRuntimeContext.get() ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    fun hasNotificationPermission(): Boolean {
        val context = AppRuntimeContext.get() ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun shouldAutoReturnToTargetApp(
        snapshot: AssistantOsSnapshot,
        trigger: TargetAppReturnTrigger,
        explicitReturnRequest: Boolean = false,
    ): Boolean {
        if (explicitReturnRequest) {
            return true
        }
        if (!snapshot.isExperimentEnabled(AssistantExperimentKey.PERSISTENT_ASSISTANT_OS_V1)) {
            return false
        }
        return when (effectivePermissionMode(snapshot)) {
            AssistantPermissionMode.PROMPT_EACH_TIME -> false
            AssistantPermissionMode.ASSISTED ->
                trigger == TargetAppReturnTrigger.USER_ACTION &&
                    snapshot.isFeatureEnabled(AssistantFeatureFlagKey.USER_ACTION_RETURN)

            AssistantPermissionMode.HANDS_FREE ->
                when (trigger) {
                    TargetAppReturnTrigger.USER_ACTION ->
                        snapshot.isFeatureEnabled(AssistantFeatureFlagKey.USER_ACTION_RETURN)

                    TargetAppReturnTrigger.BOOTSTRAP_AUTO ->
                        snapshot.isFeatureEnabled(AssistantFeatureFlagKey.BOOTSTRAP_AUTO_RETURN)
                }
        }
    }

    fun resolveSurfaceAvailability(
        snapshot: AssistantOsSnapshot,
        surface: AssistantEntrySurface,
    ): AssistantSurfaceAvailability =
        when (surface) {
            AssistantEntrySurface.MAIN_APP ->
                AssistantSurfaceAvailability(
                    supported = true,
                    enabled = true,
                    available = true,
                    summary = "always_on",
                )

            AssistantEntrySurface.NOTIFICATION -> {
                val enabled =
                    snapshot.isExperimentEnabled(AssistantExperimentKey.ENTRY_CONTROL_CENTER_V1) &&
                        snapshot.isFeatureEnabled(AssistantFeatureFlagKey.NOTIFICATION_CONTROL_CENTER)
                val hasPermission = hasNotificationPermission()
                AssistantSurfaceAvailability(
                    supported = true,
                    enabled = enabled,
                    available = hasPermission,
                    summary = if (hasPermission) "notification_ready" else "post_notification_permission_required",
                )
            }

            AssistantEntrySurface.SHARE_SHEET ->
                AssistantSurfaceAvailability(
                    supported = true,
                    enabled =
                        snapshot.isExperimentEnabled(AssistantExperimentKey.ENTRY_CONTROL_CENTER_V1) &&
                            snapshot.isFeatureEnabled(AssistantFeatureFlagKey.SHARE_SHEET_ENTRY),
                    available = true,
                    summary = "share_intent_ready",
                )

            AssistantEntrySurface.OVERLAY -> {
                val enabled = snapshot.isFeatureEnabled(AssistantFeatureFlagKey.OVERLAY_SURFACE_STUB)
                val available = canDrawOverlays()
                AssistantSurfaceAvailability(
                    supported = enabled,
                    enabled = enabled,
                    available = available,
                    summary = if (available) "overlay_ready" else "overlay_permission_required",
                )
            }

            AssistantEntrySurface.SHORTCUT ->
                AssistantSurfaceAvailability(
                    supported = true,
                    enabled = true,
                    available = true,
                    summary = "dynamic_shortcuts_ready",
                )

            AssistantEntrySurface.WIDGET ->
                AssistantSurfaceAvailability(
                    supported = true,
                    enabled = snapshot.isFeatureEnabled(AssistantFeatureFlagKey.ASSISTANT_INBOX),
                    available = true,
                    summary = "widget_ready",
                )

            AssistantEntrySurface.TILE ->
                AssistantSurfaceAvailability(
                    supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N,
                    enabled = true,
                    available = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N,
                    summary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) "tile_ready" else "tile_unsupported",
                )

            AssistantEntrySurface.VOICE ->
                AssistantSurfaceAvailability(
                    supported = true,
                    enabled = true,
                    available = !snapshot.activeSession.awaitingConfirmation,
                    summary = if (snapshot.activeSession.awaitingConfirmation) "voice_gated_by_approval" else "voice_ready",
                )

            AssistantEntrySurface.SYSTEM ->
                AssistantSurfaceAvailability(
                    supported = true,
                    enabled = true,
                    available = true,
                    summary = "bridge_runtime_ready",
                )
        }
}
