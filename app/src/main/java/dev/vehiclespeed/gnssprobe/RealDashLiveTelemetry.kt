package dev.vehiclespeed.gnssprobe

import dev.vehiclespeed.gnssprobe.estimator.EstimatorMode
import dev.vehiclespeed.gnssprobe.estimator.SpeedObservation
import kotlin.math.abs

data class RealDashLiveInput(
    val captureActive: Boolean,
    val nowElapsedNs: Long,
    val rawGpsSpeedMps: Double?,
    val lastRawGpsElapsedNs: Long,
    val lastAcceptedGpsElapsedNs: Long,
    val estimatedSpeedMps: Double?,
    val estimatorTimestampNs: Long,
    val estimatorMode: EstimatorMode,
    val mountCalibrationReady: Boolean,
    val fusionReady: Boolean = false,
    val rawGpsSigmaMps: Double? = null,
)

data class RealDashLiveTelemetry(
    val estimatedSpeedKph: Double,
    val rawGpsSpeedKph: Double,
    val gpsAgeMs: Int,
    val estimatorModeCode: Int,
    val flags: Int,
    val valid: Boolean,
    val source: String,
)

object RealDashLiveTelemetrySelector {
    const val FLAG_VALID = 1
    const val FLAG_RAW_GPS_FRESH = 1 shl 1
    const val FLAG_CAPTURE_ACTIVE = 1 shl 2
    const val FLAG_CALIBRATION_PROVISIONAL = 1 shl 3
    const val FLAG_ESTIMATOR_FRESH = 1 shl 4
    const val FLAG_MOUNT_READY = 1 shl 5
    const val FLAG_GPS_FALLBACK = 1 shl 6

    fun select(input: RealDashLiveInput): RealDashLiveTelemetry {
        val rawGpsAgeMs = ageMs(input.nowElapsedNs, input.lastRawGpsElapsedNs)
        val acceptedGpsAgeMs = ageMs(input.nowElapsedNs, input.lastAcceptedGpsElapsedNs)
        val estimatorAgeMs = ageMs(input.nowElapsedNs, input.estimatorTimestampNs)
        val rawGpsFresh = rawGpsAgeMs <= RAW_GPS_FRESHNESS_MS &&
            SpeedObservation.usable(input.rawGpsSpeedMps, input.rawGpsSigmaMps)
        val acceptedGpsFresh = acceptedGpsAgeMs <= ACCEPTED_GPS_FRESHNESS_MS
        val estimatorFresh = estimatorAgeMs <= ESTIMATOR_FRESHNESS_MS
        val hasEstimate = SpeedObservation.usable(input.estimatedSpeedMps, null)
        val usableMode = input.estimatorMode != EstimatorMode.UNINITIALIZED &&
            input.estimatorMode != EstimatorMode.GPS_DEGRADED &&
            input.estimatorMode != EstimatorMode.GPS_ONLY
        val envelope = 6.0 + 12.0 * (rawGpsAgeMs / 1000.0).coerceAtMost(2.0)
        val fused = input.captureActive && input.fusionReady && hasEstimate && estimatorFresh &&
            acceptedGpsFresh && usableMode && input.mountCalibrationReady && rawGpsFresh &&
            abs(input.estimatedSpeedMps!! - input.rawGpsSpeedMps!!) <= envelope
        val fallback = !fused && input.captureActive && rawGpsFresh
        val valid = fused || fallback
        val outputMps = when {
            fused -> input.estimatedSpeedMps!!
            fallback -> input.rawGpsSpeedMps!!
            else -> 0.0
        }

        var flags = FLAG_CALIBRATION_PROVISIONAL
        if (input.captureActive) flags = flags or FLAG_CAPTURE_ACTIVE
        if (rawGpsFresh && input.rawGpsSpeedMps?.isFinite() == true) {
            flags = flags or FLAG_RAW_GPS_FRESH
        }
        if (estimatorFresh && hasEstimate) flags = flags or FLAG_ESTIMATOR_FRESH
        if (input.mountCalibrationReady && input.fusionReady) flags = flags or FLAG_MOUNT_READY
        if (valid) flags = flags or FLAG_VALID
        if (fallback) flags = flags or FLAG_GPS_FALLBACK

        return RealDashLiveTelemetry(
            estimatedSpeedKph = outputMps * MPS_TO_KPH,
            rawGpsSpeedKph = input.rawGpsSpeedMps
                ?.takeIf { it.isFinite() }
                ?.coerceAtLeast(0.0)
                ?.times(MPS_TO_KPH)
                ?: 0.0,
            gpsAgeMs = rawGpsAgeMs.coerceIn(0, 65_535),
            estimatorModeCode = if (fallback) 5 else modeCode(input.estimatorMode),
            flags = flags,
            valid = valid,
            source = when { fused -> "FUSED"; fallback -> "GPS_ONLY"; else -> "UNAVAILABLE" },
        )
    }

    fun modeCode(mode: EstimatorMode): Int = when (mode) {
        EstimatorMode.UNINITIALIZED -> 0
        EstimatorMode.GPS_CORRECTION -> 1
        EstimatorMode.PREDICTING -> 2
        EstimatorMode.GPS_DEGRADED -> 3
        EstimatorMode.STATIONARY -> 4
        EstimatorMode.GPS_ONLY -> 5
    }

    private fun ageMs(nowElapsedNs: Long, eventElapsedNs: Long): Int {
        if (eventElapsedNs <= 0L || eventElapsedNs > nowElapsedNs) return 65_535
        return ((nowElapsedNs - eventElapsedNs).coerceAtLeast(0L) / NS_PER_MS)
            .coerceAtMost(65_535L)
            .toInt()
    }

    private const val MPS_TO_KPH = 3.6
    private const val NS_PER_MS = 1_000_000L
    private const val RAW_GPS_FRESHNESS_MS = 3_000
    private const val ACCEPTED_GPS_FRESHNESS_MS = 3_000
    private const val ESTIMATOR_FRESHNESS_MS = 500
}
