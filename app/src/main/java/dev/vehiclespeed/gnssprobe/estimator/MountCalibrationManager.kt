package dev.vehiclespeed.gnssprobe.estimator

import kotlin.math.exp
import kotlin.math.sqrt

enum class MountCalibrationMode {
    READY,
    MOUNT_MOVED,
    CALIBRATING,
}

data class MountCalibrationState(
    val mode: MountCalibrationMode,
    val activeTransform: VehicleFrameTransform,
    val orientationChangeDegrees: Double,
    val gravityNoiseRmsMps2: Double,
    val stationaryConfirmed: Boolean,
    val holdSpeedAtZero: Boolean,
    val recalibrationCount: Int,
)

/**
 * Detects tablet movement only after GNSS has established that the vehicle is stopped.
 *
 * The gravity vector at the beginning of each stop is the anchor. This prevents road grade that
 * was already present when the vehicle stopped from being mistaken for a mount change. Once a
 * changed orientation becomes stable, the existing forward axis receives the minimum rotation
 * between the old and new gravity vectors. Yaw around gravity remains unobservable.
 */
class MountCalibrationManager(
    private val baseTransform: VehicleFrameTransform = VehicleFrameTransform.DEVELOPMENT_DRIVE,
) {
    private var activeTransform = baseTransform
    private var mode = MountCalibrationMode.READY
    private var gravityEstimate: Axis3? = null
    private var gravityNoiseVariance = 0.0
    private var lastAccelerationTimestampNs = 0L
    private var lastGpsTimestampNs = 0L
    private var stationaryCandidateStartNs: Long? = null
    private var stationaryConfirmed = false
    private var stationaryAnchorGravity: Axis3? = null
    private var mountChangeCandidateStartNs: Long? = null
    private var stableCandidateStartNs: Long? = null
    private var stableTargetReturnedToOriginal: Boolean? = null
    private var calibrationStableStartNs: Long? = null
    private var pendingPreviousGravity: Axis3? = null
    private var orientationChangeDegrees = 0.0
    private var holdSpeedAtZero = false
    private var recalibrationCount = 0

    fun reset() {
        activeTransform = baseTransform
        mode = MountCalibrationMode.READY
        gravityEstimate = null
        gravityNoiseVariance = 0.0
        lastAccelerationTimestampNs = 0L
        lastGpsTimestampNs = 0L
        stationaryCandidateStartNs = null
        stationaryConfirmed = false
        stationaryAnchorGravity = null
        mountChangeCandidateStartNs = null
        stableCandidateStartNs = null
        stableTargetReturnedToOriginal = null
        calibrationStableStartNs = null
        pendingPreviousGravity = null
        orientationChangeDegrees = 0.0
        holdSpeedAtZero = false
        recalibrationCount = 0
    }

    fun onGpsSpeed(
        eventTimestampNs: Long,
        speedMps: Double,
        speedAccuracyMps: Double?,
    ): MountCalibrationState {
        if (eventTimestampNs <= 0L || !speedMps.isFinite() || speedMps < 0.0) return state()
        lastGpsTimestampNs = maxOf(lastGpsTimestampNs, eventTimestampNs)

        val accuracy = speedAccuracyMps?.takeIf { it.isFinite() && it > 0.0 }
        val trustworthyForStationary = accuracy == null || accuracy <= MAX_STATIONARY_GPS_SIGMA_MPS
        if (trustworthyForStationary && speedMps <= STATIONARY_ENTER_SPEED_MPS) {
            val candidateStart = stationaryCandidateStartNs ?: eventTimestampNs.also {
                stationaryCandidateStartNs = it
            }
            if (
                !stationaryConfirmed &&
                eventTimestampNs - candidateStart >= STATIONARY_CONFIRMATION_NS
            ) {
                stationaryConfirmed = true
                if (mode == MountCalibrationMode.READY) {
                    stationaryAnchorGravity = gravityEstimate
                    mountChangeCandidateStartNs = null
                }
            }

            val mountCandidateStart = mountChangeCandidateStartNs
            if (
                stationaryConfirmed &&
                mode == MountCalibrationMode.READY &&
                mountCandidateStart != null &&
                eventTimestampNs > mountCandidateStart &&
                eventTimestampNs - mountCandidateStart >= MOUNT_CHANGE_CONFIRMATION_NS &&
                orientationChangeDegrees >= MOUNT_CHANGE_THRESHOLD_DEGREES
            ) {
                mode = MountCalibrationMode.MOUNT_MOVED
                pendingPreviousGravity = stationaryAnchorGravity
                stableCandidateStartNs = null
                stableTargetReturnedToOriginal = null
                holdSpeedAtZero = true
            }
        } else if (trustworthyForStationary && speedMps >= STATIONARY_EXIT_SPEED_MPS) {
            stationaryCandidateStartNs = null
            stationaryConfirmed = false
            if (mode == MountCalibrationMode.READY) {
                // No post-change stationary fix confirmed this candidate, so it was vehicle
                // motion rather than a stationary mount adjustment.
                mountChangeCandidateStartNs = null
                stationaryAnchorGravity = null
                orientationChangeDegrees = 0.0
            } else if (mode == MountCalibrationMode.CALIBRATING) {
                // The mount was already stable before bias settling began. A launch may end the
                // settling period without invalidating the accepted transform.
                mode = MountCalibrationMode.READY
                pendingPreviousGravity = null
                stableCandidateStartNs = null
                stableTargetReturnedToOriginal = null
                calibrationStableStartNs = null
                orientationChangeDegrees = 0.0
            }
            holdSpeedAtZero = false
        }
        return state()
    }

    fun onAcceleration(
        eventTimestampNs: Long,
        accelX: Double,
        accelY: Double,
        accelZ: Double,
    ): MountCalibrationState {
        if (
            eventTimestampNs <= 0L ||
            !accelX.isFinite() ||
            !accelY.isFinite() ||
            !accelZ.isFinite()
        ) {
            return state()
        }

        val sample = Axis3(accelX, accelY, accelZ)
        updateGravityEstimate(eventTimestampNs, sample)
        val currentGravity = gravityEstimate ?: return state()
        val gpsFresh = lastGpsTimestampNs > 0L &&
            eventTimestampNs - lastGpsTimestampNs in 0..GPS_STATIONARY_FRESHNESS_NS

        if (mode == MountCalibrationMode.CALIBRATING) {
            holdSpeedAtZero = stationaryConfirmed && gpsFresh
            if (!stationaryConfirmed || !gpsFresh) {
                calibrationStableStartNs = null
                return state()
            }

            val anchor = stationaryAnchorGravity ?: currentGravity.also {
                stationaryAnchorGravity = it
            }
            orientationChangeDegrees = angleDegrees(anchor, currentGravity)
            val stable =
                orientationChangeDegrees <= RETURNED_TO_MOUNT_THRESHOLD_DEGREES &&
                    gravityNoiseRmsMps2() <= STABLE_GRAVITY_NOISE_RMS_MPS2
            if (!stable) {
                calibrationStableStartNs = null
                if (orientationChangeDegrees >= MOUNT_CHANGE_THRESHOLD_DEGREES) {
                    mode = MountCalibrationMode.MOUNT_MOVED
                    pendingPreviousGravity = anchor
                    stableCandidateStartNs = null
                    stableTargetReturnedToOriginal = null
                }
                return state()
            }

            val stableStart = calibrationStableStartNs ?: eventTimestampNs.also {
                calibrationStableStartNs = it
            }
            if (eventTimestampNs - stableStart >= BIAS_SETTLE_NS) {
                mode = MountCalibrationMode.READY
                stationaryAnchorGravity = currentGravity
                mountChangeCandidateStartNs = null
                stableCandidateStartNs = null
                stableTargetReturnedToOriginal = null
                calibrationStableStartNs = null
                holdSpeedAtZero = false
            }
            return state()
        }

        if (!stationaryConfirmed || !gpsFresh) {
            holdSpeedAtZero = false
            return state()
        }

        if (mode == MountCalibrationMode.READY) {
            val anchor = stationaryAnchorGravity ?: currentGravity.also {
                stationaryAnchorGravity = it
            }
            orientationChangeDegrees = angleDegrees(anchor, currentGravity)
            if (orientationChangeDegrees >= MOUNT_CHANGE_THRESHOLD_DEGREES) {
                if (mountChangeCandidateStartNs == null) {
                    mountChangeCandidateStartNs = eventTimestampNs
                }
                // A later trustworthy low-speed GNSS fix must confirm that the vehicle remained
                // stopped after this candidate began. Until then this may be a hard launch.
            } else {
                mountChangeCandidateStartNs = null
            }
        }

        if (mode == MountCalibrationMode.MOUNT_MOVED) {
            holdSpeedAtZero = true
            val previousGravity = pendingPreviousGravity ?: stationaryAnchorGravity
            if (previousGravity == null) return state()
            orientationChangeDegrees = angleDegrees(previousGravity, currentGravity)

            val returnedToOriginal =
                orientationChangeDegrees <= RETURNED_TO_MOUNT_THRESHOLD_DEGREES
            val canSettle =
                orientationChangeDegrees <= MAX_AUTO_CALIBRATION_ANGLE_DEGREES &&
                    gravityNoiseRmsMps2() <= STABLE_GRAVITY_NOISE_RMS_MPS2
            if (canSettle) {
                if (stableTargetReturnedToOriginal != returnedToOriginal) {
                    stableCandidateStartNs = null
                    stableTargetReturnedToOriginal = returnedToOriginal
                }
                val stableStart = stableCandidateStartNs ?: eventTimestampNs.also {
                    stableCandidateStartNs = it
                }
                if (eventTimestampNs - stableStart >= STABLE_CONFIRMATION_NS) {
                    if (!returnedToOriginal) {
                        recalibrationCount++
                        activeTransform = activeTransform.reorientedForGravityChange(
                            previousGravity = previousGravity,
                            currentGravity = currentGravity,
                            calibrationName = "Auto-adjusted mount calibration #$recalibrationCount",
                        )
                    }
                    beginBiasSettling(currentGravity)
                }
            } else {
                stableCandidateStartNs = null
                stableTargetReturnedToOriginal = null
            }
        }

        return state()
    }

    fun state(): MountCalibrationState = MountCalibrationState(
        mode = mode,
        activeTransform = activeTransform,
        orientationChangeDegrees = orientationChangeDegrees,
        gravityNoiseRmsMps2 = gravityNoiseRmsMps2(),
        stationaryConfirmed = stationaryConfirmed,
        holdSpeedAtZero = holdSpeedAtZero,
        recalibrationCount = recalibrationCount,
    )

    private fun updateGravityEstimate(eventTimestampNs: Long, sample: Axis3) {
        val previous = gravityEstimate
        if (previous == null || lastAccelerationTimestampNs <= 0L) {
            gravityEstimate = sample
            gravityNoiseVariance = 0.0
            lastAccelerationTimestampNs = eventTimestampNs
            return
        }

        val dtSeconds = ((eventTimestampNs - lastAccelerationTimestampNs) / NS_PER_SECOND)
            .coerceIn(0.0, MAX_FILTER_STEP_SECONDS)
        lastAccelerationTimestampNs = maxOf(lastAccelerationTimestampNs, eventTimestampNs)
        if (dtSeconds <= 0.0) return

        val gravityBlend = 1.0 - exp(-dtSeconds / GRAVITY_TIME_CONSTANT_SECONDS)
        val updated = previous + (sample - previous) * gravityBlend
        gravityEstimate = updated

        val residual = (sample - updated).magnitude()
        val noiseBlend = 1.0 - exp(-dtSeconds / NOISE_TIME_CONSTANT_SECONDS)
        gravityNoiseVariance += noiseBlend * (residual * residual - gravityNoiseVariance)
    }

    private fun beginBiasSettling(currentGravity: Axis3) {
        stationaryAnchorGravity = currentGravity
        pendingPreviousGravity = null
        mountChangeCandidateStartNs = null
        stableCandidateStartNs = null
        stableTargetReturnedToOriginal = null
        calibrationStableStartNs = null
        orientationChangeDegrees = 0.0
        mode = MountCalibrationMode.CALIBRATING
        holdSpeedAtZero = true
    }

    private fun gravityNoiseRmsMps2(): Double = sqrt(gravityNoiseVariance.coerceAtLeast(0.0))

    companion object {
        private const val NS_PER_SECOND = 1_000_000_000.0
        private const val GRAVITY_TIME_CONSTANT_SECONDS = 0.60
        private const val NOISE_TIME_CONSTANT_SECONDS = 0.50
        private const val MAX_FILTER_STEP_SECONDS = 0.25
        private const val STATIONARY_ENTER_SPEED_MPS = 0.50
        private const val STATIONARY_EXIT_SPEED_MPS = 1.50
        private const val MAX_STATIONARY_GPS_SIGMA_MPS = 2.0
        private const val STATIONARY_CONFIRMATION_NS = 1_500_000_000L
        private const val GPS_STATIONARY_FRESHNESS_NS = 2_500_000_000L
        private const val MOUNT_CHANGE_THRESHOLD_DEGREES = 12.0
        private const val RETURNED_TO_MOUNT_THRESHOLD_DEGREES = 4.0
        private const val MAX_AUTO_CALIBRATION_ANGLE_DEGREES = 45.0
        private const val MOUNT_CHANGE_CONFIRMATION_NS = 750_000_000L
        private const val STABLE_GRAVITY_NOISE_RMS_MPS2 = 0.30
        private const val STABLE_CONFIRMATION_NS = 2_000_000_000L
        private const val BIAS_SETTLE_NS = 1_000_000_000L
    }
}
