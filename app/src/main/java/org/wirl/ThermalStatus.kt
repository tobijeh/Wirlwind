package org.wirl

import android.os.Build
import android.os.PowerManager

/** The phone's thermal state for Diagnostics, so a slow dictation can be told apart from a hot phone. */
object ThermalStatus {
    fun label(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }

    /** The current thermal state, or "unavailable" before Android 10, which has no thermal API. */
    fun read(power: PowerManager?): String {
        if (power == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unavailable"
        return label(power.currentThermalStatus)
    }
}
