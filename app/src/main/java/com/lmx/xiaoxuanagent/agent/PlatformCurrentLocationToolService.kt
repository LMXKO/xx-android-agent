package com.lmx.xiaoxuanagent.agent

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.roundToInt

data class PlatformLocationSnapshot(
    val provider: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timeMs: Long,
)

object PlatformCurrentLocationToolService {
    const val DEFAULT_MAX_AGE_MINUTES = 30
    private const val MAX_MAX_AGE_MINUTES = 180

    fun readCurrentLocation(
        service: AccessibilityService,
        maxAgeMinutes: Int,
    ): AgentExecutionResult {
        val normalizedMaxAge = normalizeMaxAgeMinutes(maxAgeMinutes)
        if (!hasLocationPermission(service)) {
            return locationPermissionResult(normalizedMaxAge)
        }

        val snapshot =
            bestLastKnownLocation(service)?.let { location ->
                PlatformLocationSnapshot(
                    provider = location.provider.orEmpty().ifBlank { "unknown" },
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy,
                    timeMs = location.time,
                )
            } ?: return locationUnavailableResult(normalizedMaxAge)

        val nowMs = System.currentTimeMillis()
        val detailLines = formatLocationDetailLines(snapshot, nowMs, normalizedMaxAge)
        val ageMinutes = ageMinutes(snapshot, nowMs)
        if (!isFresh(snapshot, nowMs, normalizedMaxAge)) {
            val message = "最近定位已过期：约 $ageMinutes 分钟前，未作为当前位置使用。"
            return AgentExecutionResult(
                message = message,
                keepRunning = false,
                requiresObservationCheck = false,
                groundingSource = "system_current_location",
                toolRuntimeDetailLines = detailLines + "receipt=current_location_stale",
            ).also {
                UtilityExecutionReceiptStore.record(
                    toolName = "system.read_current_location",
                    summary = message,
                    detailLines = detailLines.take(6),
                    handoffRequired = false,
                )
            }
        }

        val coordinate = coordinateLabel(snapshot)
        val message = "已读取当前定位：$coordinate（约 ${snapshot.accuracyMeters.roundToInt()} 米精度，$ageMinutes 分钟前）。"
        return AgentExecutionResult(
            message = message,
            keepRunning = true,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "system_current_location",
            resolvedTargetText = coordinate,
            toolRuntimeDetailLines = detailLines,
        ).also {
            UtilityExecutionReceiptStore.record(
                toolName = "system.read_current_location",
                summary = message,
                detailLines = detailLines.take(6),
                handoffRequired = false,
            )
        }
    }

    fun formatLocationDetailLines(
        snapshot: PlatformLocationSnapshot,
        nowMs: Long,
        maxAgeMinutes: Int,
    ): List<String> =
        listOf(
            "receipt=current_location_read",
            "provider=${snapshot.provider.ifBlank { "unknown" }}",
            "latitude=${roundCoordinate(snapshot.latitude)}",
            "longitude=${roundCoordinate(snapshot.longitude)}",
            "accuracy_m=${snapshot.accuracyMeters.roundToInt().coerceAtLeast(0)}",
            "age_min=${ageMinutes(snapshot, nowMs)}",
            "max_age_min=${normalizeMaxAgeMinutes(maxAgeMinutes)}",
            "fresh=${isFresh(snapshot, nowMs, maxAgeMinutes)}",
        )

    fun normalizeMaxAgeMinutes(maxAgeMinutes: Int): Int =
        when {
            maxAgeMinutes <= 0 -> DEFAULT_MAX_AGE_MINUTES
            else -> maxAgeMinutes.coerceAtMost(MAX_MAX_AGE_MINUTES)
        }

    fun isFresh(
        snapshot: PlatformLocationSnapshot,
        nowMs: Long,
        maxAgeMinutes: Int,
    ): Boolean = ageMinutes(snapshot, nowMs) <= normalizeMaxAgeMinutes(maxAgeMinutes)

    fun ageMinutes(
        snapshot: PlatformLocationSnapshot,
        nowMs: Long,
    ): Int = ((nowMs - snapshot.timeMs).coerceAtLeast(0L) / 60_000L).toInt()

    fun coordinateLabel(snapshot: PlatformLocationSnapshot): String =
        "${roundCoordinate(snapshot.latitude)},${roundCoordinate(snapshot.longitude)}"

    fun hasLocationPermission(service: AccessibilityService): Boolean =
        ContextCompat.checkSelfPermission(service, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(service, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun bestLastKnownLocation(service: AccessibilityService): Location? {
        val manager = service.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers =
            runCatching { manager.getProviders(true) }
                .getOrDefault(emptyList())
        return (
            providers +
                listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER,
                )
        ).distinct()
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }.maxWithOrNull(compareBy<Location> { it.time }.thenBy { -it.accuracy })
    }

    private fun roundCoordinate(value: Double): String =
        String.format(Locale.US, "%.5f", value)

    private fun locationPermissionResult(maxAgeMinutes: Int): AgentExecutionResult =
        AgentExecutionResult(
            message = "需要定位权限后才能读取当前位置。",
            keepRunning = false,
            requiresObservationCheck = false,
            groundingSource = "system_current_location",
            toolRuntimeDetailLines =
                listOf(
                    "receipt=current_location_permission_required",
                    "permission=ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION",
                    "max_age_min=${normalizeMaxAgeMinutes(maxAgeMinutes)}",
                    "fallback=system.open_settings",
                ),
        )

    private fun locationUnavailableResult(maxAgeMinutes: Int): AgentExecutionResult =
        AgentExecutionResult(
            message = "当前没有可用的最近定位。",
            keepRunning = false,
            requiresObservationCheck = false,
            groundingSource = "system_current_location",
            toolRuntimeDetailLines =
                listOf(
                    "receipt=current_location_unavailable",
                    "max_age_min=${normalizeMaxAgeMinutes(maxAgeMinutes)}",
                    "fallback=system.open_device_panel",
                ),
        )
}
