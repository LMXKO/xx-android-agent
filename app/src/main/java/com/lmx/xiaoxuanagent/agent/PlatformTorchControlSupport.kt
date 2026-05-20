package com.lmx.xiaoxuanagent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

internal object PlatformTorchControlSupport {
    fun toggleTorch(
        service: AccessibilityService,
    ): Boolean {
        val camera = service.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
        val cameraId =
            runCatching {
                camera.cameraIdList.firstOrNull { id ->
                    camera.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
            }.getOrNull() ?: return false
        val current = readTorchState(service)
        return runCatching {
            camera.setTorchMode(cameraId, !current)
            true
        }.getOrDefault(false)
    }

    fun readTorchState(
        service: AccessibilityService,
    ): Boolean =
        service.applicationContext
            .getSharedPreferences("utility_runtime", Context.MODE_PRIVATE)
            .getBoolean("torch_enabled", false)

    fun persistTorchState(
        service: AccessibilityService,
        enabled: Boolean,
    ) {
        service.applicationContext
            .getSharedPreferences("utility_runtime", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("torch_enabled", enabled)
            .apply()
    }
}
