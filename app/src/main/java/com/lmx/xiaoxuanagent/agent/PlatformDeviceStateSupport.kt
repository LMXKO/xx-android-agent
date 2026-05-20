package com.lmx.xiaoxuanagent.agent

import android.accessibilityservice.AccessibilityService
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.provider.Settings

internal object PlatformDeviceStateSupport {
    fun stateLines(
        service: AccessibilityService,
        panel: String,
    ): List<String> =
        when (panel.trim().lowercase()) {
            "internet", "network", "wifi" -> networkLines(service)
            "bluetooth" -> bluetoothLines()
            "nfc" -> nfcLines(service)
            "airplane" -> airplaneLines(service)
            "volume" -> volumeLines(service)
            "torch", "flashlight" -> flashlightLines(service)
            else -> listOf("state=unknown")
        }

    private fun networkLines(
        service: AccessibilityService,
    ): List<String> {
        val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val wifiManager = service.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        val transport =
            when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
                else -> "offline_or_unknown"
            }
        return listOf(
            "wifi_enabled=${wifiManager?.isWifiEnabled ?: false}",
            "active_network=$transport",
        )
    }

    private fun bluetoothLines(): List<String> {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        return listOf(
            "bluetooth_supported=${adapter != null}",
            "bluetooth_enabled=${adapter?.isEnabled ?: false}",
        )
    }

    private fun nfcLines(
        service: AccessibilityService,
    ): List<String> {
        val adapter = NfcAdapter.getDefaultAdapter(service)
        return listOf(
            "nfc_supported=${adapter != null}",
            "nfc_enabled=${adapter?.isEnabled ?: false}",
        )
    }

    private fun volumeLines(
        service: AccessibilityService,
    ): List<String> {
        val audio = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val current = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: -1
        return listOf("music_volume=$current/$max")
    }

    private fun flashlightLines(
        service: AccessibilityService,
    ): List<String> {
        val camera = service.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val supported =
            runCatching {
                camera?.cameraIdList.orEmpty().any { id ->
                    camera?.getCameraCharacteristics(id)?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
            }.getOrDefault(false)
        return listOf(
            "flashlight_supported=$supported",
            "flashlight_enabled=${PlatformTorchControlSupport.readTorchState(service)}",
        )
    }

    private fun airplaneLines(
        service: AccessibilityService,
    ): List<String> {
        val enabled =
            runCatching {
                Settings.Global.getInt(service.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            }.getOrDefault(false)
        return listOf("airplane_mode=$enabled")
    }
}
