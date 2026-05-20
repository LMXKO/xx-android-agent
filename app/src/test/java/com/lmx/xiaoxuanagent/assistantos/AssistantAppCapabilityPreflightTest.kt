package com.lmx.xiaoxuanagent.assistantos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantAppCapabilityPreflightTest {
    @Test
    fun `evaluate returns green when ordinary app capabilities are ready`() {
        val snapshot =
            AssistantAppCapabilityPreflight.evaluate(
                AssistantAppCapabilityPreflightInput(
                    accessibilityConnected = true,
                    postNotificationPermissionGranted = true,
                    notificationProviderReady = true,
                    notificationListenerEnabled = true,
                    overlayFeatureEnabled = true,
                    overlayPermissionGranted = true,
                    shareSheetEnabled = true,
                    shortcutSupported = true,
                    widgetEnabled = true,
                    tileSupported = true,
                    speechRecognizerAvailable = true,
                    recordAudioPermissionGranted = true,
                    voiceForegroundServiceDeclared = true,
                    foregroundServiceDeclared = true,
                    runtimeResidencyLikely = true,
                    bootReceiverDeclared = true,
                    batteryOptimizationIgnored = true,
                    screenshotStatus = "captured: screen.png",
                    targetPackageName = "com.tencent.mm",
                    targetAppLaunchable = true,
                    providerStates =
                        listOf(
                            AssistantSignalProviderState(
                                providerId = "notification_listener",
                                source = "notification_listener",
                                trustLevel = AssistantSignalProviderTrustLevel.HIGH,
                            ),
                        ),
                ),
            )

        assertEquals("green", snapshot.status)
        assertEquals(0, snapshot.blockedCount)
        assertTrue(snapshot.lines.first().contains("status=green"))
    }

    @Test
    fun `evaluate blocks missing accessibility and unavailable target app`() {
        val snapshot =
            AssistantAppCapabilityPreflight.evaluate(
                AssistantAppCapabilityPreflightInput(
                    accessibilityConnected = false,
                    notificationControlEnabled = false,
                    notificationListenerEnabled = true,
                    notificationProviderReady = true,
                    overlayFeatureEnabled = false,
                    recordAudioPermissionGranted = true,
                    batteryOptimizationIgnored = true,
                    screenshotStatus = "captured",
                    targetPackageName = "com.missing.app",
                    targetAppLaunchable = false,
                ),
            )

        assertEquals("red", snapshot.status)
        assertTrue(snapshot.blockedCount >= 2)
        assertTrue(snapshot.lines.any { it.contains("accessibility_runtime | blocked") })
        assertTrue(snapshot.lines.any { it.contains("target_app_launch | blocked") })
        assertTrue(snapshot.recommendedCommands.contains("/entry-surfaces"))
        assertTrue(snapshot.recommendedCommands.contains("/operator"))
    }

    @Test
    fun `evaluate degrades fragile permissions with fallback commands`() {
        val snapshot =
            AssistantAppCapabilityPreflight.evaluate(
                AssistantAppCapabilityPreflightInput(
                    accessibilityConnected = true,
                    postNotificationPermissionGranted = false,
                    notificationControlEnabled = true,
                    notificationProviderReady = false,
                    notificationListenerEnabled = false,
                    overlayFeatureEnabled = true,
                    overlayPermissionGranted = false,
                    shareSheetEnabled = true,
                    shortcutSupported = true,
                    widgetEnabled = true,
                    tileSupported = true,
                    speechRecognizerAvailable = true,
                    recordAudioPermissionGranted = false,
                    foregroundServiceDeclared = true,
                    voiceForegroundServiceDeclared = true,
                    runtimeResidencyLikely = true,
                    bootReceiverDeclared = true,
                    batteryOptimizationIgnored = false,
                    screenshotStatus = "截图采集失败",
                    targetPackageName = "",
                ),
            )

        assertEquals("yellow", snapshot.status)
        assertTrue(snapshot.degradedCount >= 5)
        assertTrue(snapshot.lines.any { it.contains("notification_control | degraded") })
        assertTrue(snapshot.lines.any { it.contains("overlay_control | degraded") })
        assertTrue(snapshot.lines.any { it.contains("voice_runtime | degraded") })
        assertTrue(snapshot.recommendedCommands.contains("/entry-surfaces"))
        assertTrue(snapshot.recommendedCommands.contains("/provider-policy"))
        assertTrue(snapshot.recommendedCommands.contains("/product-shell --section entry"))
    }
}
