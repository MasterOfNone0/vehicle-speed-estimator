package dev.vehiclespeed.gnssprobe.estimator

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/** Shared plausibility limits; these are fault boundaries, not accuracy guarantees. */
object SpeedObservation {
    const val MAX_SPEED_MPS = 150.0
    const val MAX_SIGMA_MPS = 5.0
    const val FRESH_NS = 3_000_000_000L

    fun usable(speed: Double?, sigma: Double?): Boolean =
        speed != null && speed.isFinite() && speed in 0.0..MAX_SPEED_MPS &&
            (sigma == null || (sigma.isFinite() && sigma in 0.0..MAX_SIGMA_MPS))
}

data class GuardedEstimatorState(
    val estimator: VelocityEstimatorState,
    val calibration: MountCalibrationState,
    val fusionReady: Boolean,
    val reason: String,
    val recoveryCount: Int,
)

/**
 * Owns fusion eligibility separately from the Kalman correction gate. With an accelerometer alone,
 * a quiet signal while driving does not establish gravity or mounting alignment. A fresh, quiet
 * stop is required before learning bias. Large attitude changes suspend fusion even while moving.
 * Android and the replay harness both use this same controller.
 */
class GuardedSpeedEstimator(
    transform: VehicleFrameTransform = VehicleFrameTransform.DEVELOPMENT_DRIVE,
    // Additional receiver lag is device-specific. Zero until characterized on this tablet.
    gpsObservationDelaySeconds: Double = 0.0,
) {
    private val mount = MountCalibrationManager(transform)
    private val filter = VelocityBiasKalmanFilter(gpsObservationDelaySeconds = gpsObservationDelaySeconds)
    private var calibration = mount.state()
    private var fused = false
    private var reason = "Waiting for GPS"
    private var recoveries = 0
    private var lastAccelNs = 0L
    private var lastGpsNs = 0L
    private var lastCredibleNs = 0L
    private var gpsSpeed: Double? = null
    private var gpsSigma: Double? = null
    private var credibleCount = 0
    private var credibleStartNs = 0L
    private var stationaryStartNs = 0L
    private var quietStartNs = 0L
    private var quietAnchor: Axis3? = null
    private var quietMean: Axis3? = null
    private var quietSamples = 0
    private var gravity: Axis3? = null
    private var fusionGravity: Axis3? = null
    private var handlingStartNs = 0L
    private var accepted = false
    private var longitudinal = 0.0

    fun reset() {
        mount.reset()
        filter.reset()
        calibration = mount.state()
        fused = false
        reason = "Waiting for GPS"
        recoveries = 0
        lastAccelNs = 0L
        lastGpsNs = 0L
        lastCredibleNs = 0L
        gpsSpeed = null
        gpsSigma = null
        credibleCount = 0
        credibleStartNs = 0L
        stationaryStartNs = 0L
        clearQuiet()
        gravity = null
        fusionGravity = null
        handlingStartNs = 0L
        accepted = false
        longitudinal = 0.0
    }

    fun onGpsSpeed(
        timestampNs: Long,
        speedMps: Double,
        sigmaMps: Double?,
        arrivalNs: Long = timestampNs,
    ): GuardedEstimatorState {
        accepted = false
        if (timestampNs <= lastGpsNs || timestampNs <= 0L || timestampNs > arrivalNs) return state()
        lastGpsNs = timestampNs
        if (!SpeedObservation.usable(speedMps, sigmaMps) ||
            arrivalNs - timestampNs !in 0..SpeedObservation.FRESH_NS
        ) {
            credibleCount = 0
            stationaryStartNs = 0L
            suspendFusion("GPS uncertain; waiting for credible fixes")
            return state()
        }

        val dt = (timestampNs - lastCredibleNs) / 1e9
        val consistent = lastCredibleNs > 0L && dt in 0.001..2.5 &&
            abs(speedMps - (gpsSpeed ?: speedMps)) <= 1.0 + 12.0 * dt
        if (!consistent) {
            credibleCount = 0
            credibleStartNs = timestampNs
            stationaryStartNs = 0L
        }
        credibleCount++
        gpsSpeed = speedMps
        gpsSigma = sigmaMps
        lastCredibleNs = timestampNs
        if (speedMps <= 0.3 && (sigmaMps ?: 1.5) <= 1.5) {
            if (stationaryStartNs == 0L) stationaryStartNs = timestampNs
        } else {
            stationaryStartNs = 0L
            clearQuiet()
        }
        calibration = mount.onGpsSpeed(timestampNs, speedMps, sigmaMps)

        if (fused) {
            val next = filter.onGpsSpeed(timestampNs, speedMps, sigmaMps)
            if (!next.gpsMeasurementAccepted || !healthy(next)) {
                suspendFusion("GPS and IMU disagree; GPS fallback")
            } else {
                accepted = true
            }
        }
        if (!fused) {
            accepted = true // Accepted as a raw observation, never as a fused correction.
            if (credibleCount >= 3 && timestampNs - credibleStartNs >= 1_000_000_000L) {
                filter.reanchor(timestampNs, speedMps, sigmaMps)
                if (stationaryStartNs == 0L && recoveries == 0) {
                    reason = "GPS only; park with tablet mounted to enable fusion"
                }
            }
        }
        return state()
    }

    fun onAcceleration(timestampNs: Long, x: Double, y: Double, z: Double): GuardedEstimatorState {
        accepted = false
        if (timestampNs <= lastAccelNs || timestampNs <= 0L) return state()
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
            suspendFusion("Invalid accelerometer sample")
            return state()
        }
        val dt = if (lastAccelNs == 0L) 0.01 else (timestampNs - lastAccelNs) / 1e9
        lastAccelNs = timestampNs
        if (dt > 0.25) suspendFusion("Accelerometer gap; GPS fallback")
        val sample = Axis3(x, y, z)
        gravity = gravity?.let { it + (sample - it) * (1.0 - exp(-dt.coerceAtMost(0.25) / 0.3)) } ?: sample
        calibration = mount.onAcceleration(timestampNs, x, y, z)
        longitudinal = calibration.activeTransform.longitudinalAccelerationMps2(x, y, z)
        val fresh = lastCredibleNs > 0L && timestampNs - lastCredibleNs in 0..2_000_000_000L

        if (fused) {
            val angle = fusionGravity?.let { angleDegrees(it, gravity!!) } ?: 0.0
            // A hard launch tilts apparent gravity and spikes the low-pass residual too.
            // Neither is evidence of handling at modest angles without a gyroscope.
            val handling = angle >= 55.0
            if (handling) {
                if (handlingStartNs == 0L) handlingStartNs = timestampNs
            } else handlingStartNs = 0L
            when {
                !fresh -> suspendFusion("GPS stale; fusion suspended")
                calibration.mode != MountCalibrationMode.READY -> suspendFusion("Mount moved; waiting for a quiet stop")
                handlingStartNs > 0L && timestampNs - handlingStartNs >= 200_000_000L ->
                    suspendFusion("Tablet orientation changed; GPS fallback")
            }
        }

        if (fused) {
            val next = filter.onAcceleration(timestampNs, longitudinal)
            if (!healthy(next)) suspendFusion("Prediction exceeded GPS envelope; GPS fallback")
        }

        // A later GPS fix must confirm this whole quiet interval; one stale zero cannot enable it.
        val quiet = fresh && stationaryStartNs > 0L && sample.magnitude() in 7.0..12.0 &&
            calibration.gravityNoiseRmsMps2 <= 0.30 &&
            abs(longitudinal) <= 4.0 && calibration.mode == MountCalibrationMode.READY
        if (!quiet) {
            clearQuiet()
        } else {
            if (quietAnchor == null || angleDegrees(quietAnchor!!, sample) > 3.0) {
                clearQuiet()
                quietAnchor = sample
                quietMean = sample
                quietStartNs = timestampNs
            }
            quietSamples++
            quietMean = quietMean!! + (sample - quietMean!!) * (1.0 / quietSamples)
            if (timestampNs - quietStartNs >= 3_000_000_000L &&
                lastCredibleNs - quietStartNs >= 2_500_000_000L &&
                lastCredibleNs - stationaryStartNs >= 2_000_000_000L && credibleCount >= 3
            ) {
                if (!fused) {
                    val mean = quietMean!!
                    val bias = calibration.activeTransform.longitudinalAccelerationMps2(mean.x, mean.y, mean.z)
                    filter.reanchor(timestampNs, 0.0, 0.1, bias)
                    fused = true
                    reason = "Fusion ready"
                }
                fusionGravity = quietMean
            }
        }
        return state()
    }

    fun state(): GuardedEstimatorState {
        val value = if (fused) filter.state().copy(gpsMeasurementAccepted = accepted) else {
            VelocityEstimatorState(
                initialized = gpsSpeed != null,
                timestampNs = max(lastAccelNs, lastCredibleNs),
                velocityMps = gpsSpeed ?: 0.0,
                longitudinalAccelerationMps2 = longitudinal,
                correctedAccelerationMps2 = 0.0,
                accelerationBiasMps2 = 0.0,
                speedVarianceMps2 = (gpsSigma ?: 1.5).let { it * it },
                mode = if (gpsSpeed == null) EstimatorMode.UNINITIALIZED else EstimatorMode.GPS_ONLY,
                gpsMeasurementAccepted = accepted,
            )
        }
        return GuardedEstimatorState(value, calibration, fused, reason, recoveries)
    }

    private fun healthy(value: VelocityEstimatorState): Boolean =
        value.velocityMps.isFinite() && value.velocityMps in 0.0..SpeedObservation.MAX_SPEED_MPS &&
            value.accelerationBiasMps2.isFinite() && abs(value.accelerationBiasMps2) <= 6.0 &&
            value.speedVarianceMps2.isFinite() && value.speedVarianceMps2 <= 25.0 &&
            abs(value.velocityMps - (gpsSpeed ?: value.velocityMps)) <= predictionEnvelopeMps()

    private fun predictionEnvelopeMps(): Double {
        val age = ((lastAccelNs - lastCredibleNs).coerceAtLeast(0L) / 1e9).coerceAtMost(2.0)
        return 6.0 + 12.0 * age
    }

    private fun suspendFusion(message: String) {
        if (fused) recoveries++
        fused = false
        reason = message
        filter.reset() // Discard old bias, covariance, and delayed IMU history together.
        fusionGravity = null
        handlingStartNs = 0L
        clearQuiet()
    }

    private fun clearQuiet() {
        quietStartNs = 0L
        quietAnchor = null
        quietMean = null
        quietSamples = 0
    }
}
