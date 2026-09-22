package dev.vehiclespeed.gnssprobe.estimator

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

enum class EstimatorMode {
    UNINITIALIZED,
    GPS_CORRECTION,
    PREDICTING,
    GPS_DEGRADED,
    STATIONARY,
    GPS_ONLY,
}

data class VelocityEstimatorState(
    val initialized: Boolean = false,
    val timestampNs: Long = 0L,
    val velocityMps: Double = 0.0,
    val longitudinalAccelerationMps2: Double = 0.0,
    val correctedAccelerationMps2: Double = 0.0,
    val accelerationBiasMps2: Double = 0.0,
    val innovationMps: Double? = null,
    val speedVarianceMps2: Double = Double.POSITIVE_INFINITY,
    val mode: EstimatorMode = EstimatorMode.UNINITIALIZED,
    val gpsMeasurementAccepted: Boolean = false,
)

/**
 * Two-state linear Kalman filter:
 *
 *     x = [vehicle speed, effective longitudinal accelerometer bias]
 *
 * The bias includes sensor offset and slowly changing gravity leakage from mounting pitch or road
 * grade. Android GNSS speed on the development tablet follows physical acceleration by about one
 * second, so moving observations correct a short historical state and stored IMU samples are
 * replayed to the present. The filter never labels its prediction as a GNSS measurement.
 */
class VelocityBiasKalmanFilter(
    private val accelerationNoiseStdMps2: Double = 0.65,
    private val biasRandomWalkStdMps2PerSqrtSecond: Double = 0.10,
    gpsObservationDelaySeconds: Double = DEFAULT_GPS_OBSERVATION_DELAY_SECONDS,
) {
    private data class InternalState(
        val initialized: Boolean,
        val timestampNs: Long,
        val velocityMps: Double,
        val accelerationBiasMps2: Double,
        val lastLongitudinalAccelerationMps2: Double,
        val innovationMps: Double?,
        val mode: EstimatorMode,
        val gpsMeasurementAccepted: Boolean,
        val stationary: Boolean,
        val stationaryCandidateStartNs: Long?,
        val p00: Double,
        val p01: Double,
        val p10: Double,
        val p11: Double,
    )

    private data class AccelerationHistoryEntry(
        val timestampNs: Long,
        val longitudinalAccelerationMps2: Double,
        val forceStationaryHold: Boolean,
        var stateAfter: InternalState,
    )

    private val gpsObservationDelayNs =
        (gpsObservationDelaySeconds * NS_PER_SECOND).toLong()
    private val accelerationHistory = ArrayDeque<AccelerationHistoryEntry>()

    private var initialized = false
    private var timestampNs = 0L
    private var velocityMps = 0.0
    private var accelerationBiasMps2 = 0.0
    private var lastLongitudinalAccelerationMps2 = 0.0
    private var innovationMps: Double? = null
    private var mode = EstimatorMode.UNINITIALIZED
    private var gpsMeasurementAccepted = false
    private var stationary = false
    private var stationaryCandidateStartNs: Long? = null

    private var p00 = INITIAL_SPEED_VARIANCE_MPS2
    private var p01 = 0.0
    private var p10 = 0.0
    private var p11 = INITIAL_BIAS_VARIANCE_MPS4

    init {
        require(gpsObservationDelaySeconds in 0.0..MAX_GPS_OBSERVATION_DELAY_SECONDS) {
            "GNSS observation delay must fit inside the estimator history"
        }
    }

    fun reset() {
        initialized = false
        timestampNs = 0L
        velocityMps = 0.0
        accelerationBiasMps2 = 0.0
        lastLongitudinalAccelerationMps2 = 0.0
        innovationMps = null
        mode = EstimatorMode.UNINITIALIZED
        gpsMeasurementAccepted = false
        stationary = false
        stationaryCandidateStartNs = null
        p00 = INITIAL_SPEED_VARIANCE_MPS2
        p01 = 0.0
        p10 = 0.0
        p11 = INITIAL_BIAS_VARIANCE_MPS4
        accelerationHistory.clear()
    }

    /** Explicit recovery discards delayed history and the bias tied to the old mounting pose. */
    fun reanchor(eventTimestampNs: Long, speedMps: Double, sigmaMps: Double?, biasMps2: Double = 0.0) {
        require(eventTimestampNs > 0L && SpeedObservation.usable(speedMps, sigmaMps))
        require(biasMps2.isFinite() && abs(biasMps2) <= 6.0)
        reset()
        onGpsSpeed(eventTimestampNs, speedMps, sigmaMps)
        accelerationBiasMps2 = biasMps2
    }

    fun onAcceleration(
        eventTimestampNs: Long,
        longitudinalAccelerationMps2: Double,
        forceStationaryHold: Boolean = false,
    ): VelocityEstimatorState {
        if (!longitudinalAccelerationMps2.isFinite() || eventTimestampNs <= 0L) return state()

        applyAcceleration(
            eventTimestampNs = eventTimestampNs,
            longitudinalAccelerationMps2 = longitudinalAccelerationMps2,
            forceStationaryHold = forceStationaryHold,
        )
        if (
            accelerationHistory.isEmpty() ||
            eventTimestampNs > accelerationHistory.last.timestampNs
        ) {
            accelerationHistory.addLast(
                AccelerationHistoryEntry(
                    timestampNs = eventTimestampNs,
                    longitudinalAccelerationMps2 = longitudinalAccelerationMps2,
                    forceStationaryHold = forceStationaryHold,
                    stateAfter = captureInternalState(),
                ),
            )
            trimAccelerationHistory(eventTimestampNs)
        }
        return state()
    }

    fun onGpsSpeed(
        eventTimestampNs: Long,
        speedMps: Double,
        speedAccuracyMps: Double?,
    ): VelocityEstimatorState {
        gpsMeasurementAccepted = false
        if (eventTimestampNs <= 0L || !speedMps.isFinite() || speedMps < 0.0) {
            mode = if (initialized) EstimatorMode.GPS_DEGRADED else EstimatorMode.UNINITIALIZED
            return state()
        }

        val suppliedAccuracy = speedAccuracyMps?.takeIf { it.isFinite() && it > 0.0 }
        if (suppliedAccuracy != null && suppliedAccuracy > MAX_ACCEPTED_GPS_SIGMA_MPS) {
            innovationMps = if (initialized) {
                speedMps - historicalVelocityFor(eventTimestampNs)
            } else {
                null
            }
            mode = if (initialized) EstimatorMode.GPS_DEGRADED else EstimatorMode.UNINITIALIZED
            return state()
        }
        val measurementSigma = (suppliedAccuracy ?: DEFAULT_GPS_SIGMA_MPS)
            .coerceIn(MIN_GPS_SIGMA_MPS, MAX_ACCEPTED_GPS_SIGMA_MPS)

        if (!initialized) {
            initialized = true
            timestampNs = maxOf(timestampNs, eventTimestampNs)
            velocityMps = speedMps
            accelerationBiasMps2 = 0.0
            p00 = measurementSigma * measurementSigma
            p01 = 0.0
            p10 = 0.0
            p11 = INITIAL_BIAS_VARIANCE_MPS4
            innovationMps = 0.0
            gpsMeasurementAccepted = true
            updateStationaryCandidate(eventTimestampNs, speedMps, measurementSigma)
            mode = if (stationary) EstimatorMode.STATIONARY else EstimatorMode.GPS_CORRECTION
            accelerationHistory.clear()
            return state()
        }

        updateStationaryCandidate(eventTimestampNs, speedMps, measurementSigma)
        if (stationary) {
            applyCurrentSpeedMeasurement(
                eventTimestampNs = eventTimestampNs,
                observationMps = 0.0,
                observationSigmaMps = STATIONARY_SPEED_SIGMA_MPS,
                gateInnovation = false,
            )
            velocityMps = 0.0
            mode = EstimatorMode.STATIONARY
            return state()
        }

        val targetTimestampNs = eventTimestampNs - gpsObservationDelayNs
        val history = accelerationHistory.toList()
        val anchorIndex = history.indexOfLast {
            it.timestampNs <= targetTimestampNs && it.stateAfter.initialized
        }
        val anchor = history.getOrNull(anchorIndex)
        val anchorIsCloseEnough = anchor != null &&
            targetTimestampNs - anchor.timestampNs in 0..MAX_HISTORY_ANCHOR_AGE_NS
        if (anchor != null && anchorIsCloseEnough) {
            return applyHistoricalSpeedMeasurement(
                history = history,
                anchorIndex = anchorIndex,
                observationMps = speedMps,
                observationSigmaMps = measurementSigma,
            )
        }

        applyCurrentSpeedMeasurement(
            eventTimestampNs = eventTimestampNs,
            observationMps = speedMps,
            observationSigmaMps = measurementSigma,
            gateInnovation = true,
        )
        return state()
    }

    fun state(): VelocityEstimatorState = VelocityEstimatorState(
        initialized = initialized,
        timestampNs = timestampNs,
        velocityMps = velocityMps,
        longitudinalAccelerationMps2 = lastLongitudinalAccelerationMps2,
        correctedAccelerationMps2 = lastLongitudinalAccelerationMps2 - accelerationBiasMps2,
        accelerationBiasMps2 = accelerationBiasMps2,
        innovationMps = innovationMps,
        speedVarianceMps2 = if (initialized) p00 else Double.POSITIVE_INFINITY,
        mode = mode,
        gpsMeasurementAccepted = gpsMeasurementAccepted,
    )

    private fun applyAcceleration(
        eventTimestampNs: Long,
        longitudinalAccelerationMps2: Double,
        forceStationaryHold: Boolean,
    ) {
        lastLongitudinalAccelerationMps2 = longitudinalAccelerationMps2
        gpsMeasurementAccepted = false

        if (!initialized) {
            timestampNs = maxOf(timestampNs, eventTimestampNs)
            mode = EstimatorMode.UNINITIALIZED
            return
        }

        val dtSeconds = (eventTimestampNs - timestampNs) / NS_PER_SECOND
        if (dtSeconds <= 0.0) return
        timestampNs = eventTimestampNs

        if (forceStationaryHold) {
            stationary = true
            stationaryCandidateStartNs = eventTimestampNs
            velocityMps = 0.0
            if (dtSeconds <= MAX_PROPAGATION_STEP_SECONDS) {
                val correctedAcceleration = longitudinalAccelerationMps2 - accelerationBiasMps2
                val biasBlend = 1.0 - exp(-dtSeconds / FORCED_HOLD_BIAS_TIME_CONSTANT_SECONDS)
                accelerationBiasMps2 += biasBlend * correctedAcceleration
                predictCovariance(dtSeconds)
            }
            mode = EstimatorMode.STATIONARY
            return
        }

        if (dtSeconds > MAX_PROPAGATION_STEP_SECONDS) {
            mode = if (stationary) EstimatorMode.STATIONARY else EstimatorMode.PREDICTING
            return
        }

        val correctedAcceleration = longitudinalAccelerationMps2 - accelerationBiasMps2
        if (stationary && abs(correctedAcceleration) < STATIONARY_EXIT_ACCEL_MPS2) {
            val biasBlend = 1.0 - exp(-dtSeconds / STATIONARY_BIAS_TIME_CONSTANT_SECONDS)
            accelerationBiasMps2 += biasBlend * correctedAcceleration
            velocityMps = 0.0
            predictCovariance(dtSeconds)
            mode = EstimatorMode.STATIONARY
            return
        }

        if (stationary) {
            stationary = false
            stationaryCandidateStartNs = null
        }

        velocityMps = max(0.0, velocityMps + correctedAcceleration * dtSeconds)
        predictCovariance(dtSeconds)
        mode = EstimatorMode.PREDICTING
    }

    private fun applyHistoricalSpeedMeasurement(
        history: List<AccelerationHistoryEntry>,
        anchorIndex: Int,
        observationMps: Double,
        observationSigmaMps: Double,
    ): VelocityEstimatorState {
        val anchor = history[anchorIndex]
        val observationVariance = observationSigmaMps * observationSigmaMps
        val residual = observationMps - anchor.stateAfter.velocityMps
        val residualVariance = anchor.stateAfter.p00 + observationVariance
        val gate = max(
            MIN_INNOVATION_GATE_MPS,
            INNOVATION_GATE_SIGMAS * sqrt(residualVariance),
        )
        if (abs(residual) > gate) {
            innovationMps = residual
            mode = EstimatorMode.GPS_DEGRADED
            return state()
        }

        val currentStationary = stationary
        val currentStationaryCandidateStartNs = stationaryCandidateStartNs
        restoreInternalState(anchor.stateAfter)
        stationary = currentStationary
        stationaryCandidateStartNs = currentStationaryCandidateStartNs
        applySpeedMeasurement(residual, observationVariance, residualVariance)
        velocityMps = max(0.0, velocityMps)
        innovationMps = residual
        gpsMeasurementAccepted = true
        mode = EstimatorMode.GPS_CORRECTION

        accelerationHistory.clear()
        history.forEachIndexed { index, entry ->
            when {
                index < anchorIndex -> accelerationHistory.addLast(entry)
                index == anchorIndex -> accelerationHistory.addLast(
                    entry.copy(stateAfter = captureInternalState()),
                )
                else -> {
                    applyAcceleration(
                        eventTimestampNs = entry.timestampNs,
                        longitudinalAccelerationMps2 = entry.longitudinalAccelerationMps2,
                        forceStationaryHold = entry.forceStationaryHold,
                    )
                    accelerationHistory.addLast(
                        entry.copy(stateAfter = captureInternalState()),
                    )
                }
            }
        }

        innovationMps = residual
        gpsMeasurementAccepted = true
        mode = EstimatorMode.GPS_CORRECTION
        if (accelerationHistory.isNotEmpty()) {
            accelerationHistory.last.stateAfter = captureInternalState()
        }
        trimAccelerationHistory(timestampNs)
        return state()
    }

    private fun applyCurrentSpeedMeasurement(
        eventTimestampNs: Long,
        observationMps: Double,
        observationSigmaMps: Double,
        gateInnovation: Boolean,
    ) {
        timestampNs = maxOf(timestampNs, eventTimestampNs)
        val observationVariance = observationSigmaMps * observationSigmaMps
        val residual = observationMps - velocityMps
        innovationMps = residual
        val residualVariance = p00 + observationVariance
        val gate = max(
            MIN_INNOVATION_GATE_MPS,
            INNOVATION_GATE_SIGMAS * sqrt(residualVariance),
        )
        if (gateInnovation && abs(residual) > gate) {
            mode = EstimatorMode.GPS_DEGRADED
            return
        }

        applySpeedMeasurement(residual, observationVariance, residualVariance)
        velocityMps = max(0.0, velocityMps)
        gpsMeasurementAccepted = true
        mode = if (stationary) EstimatorMode.STATIONARY else EstimatorMode.GPS_CORRECTION
        // This fallback is used only before enough delayed history exists. Start a fresh history
        // from the corrected present state so a later rewind cannot restore a pre-correction state.
        accelerationHistory.clear()
    }

    private fun historicalVelocityFor(eventTimestampNs: Long): Double {
        val targetTimestampNs = eventTimestampNs - gpsObservationDelayNs
        val anchor = accelerationHistory.lastOrNull {
            it.timestampNs <= targetTimestampNs && it.stateAfter.initialized
        }
        return if (
            anchor != null &&
            targetTimestampNs - anchor.timestampNs in 0..MAX_HISTORY_ANCHOR_AGE_NS
        ) {
            anchor.stateAfter.velocityMps
        } else {
            velocityMps
        }
    }

    private fun trimAccelerationHistory(referenceTimestampNs: Long) {
        val cutoff = referenceTimestampNs - HISTORY_DURATION_NS
        while (
            accelerationHistory.size > 1 &&
            accelerationHistory.first.timestampNs < cutoff
        ) {
            accelerationHistory.removeFirst()
        }
    }

    private fun captureInternalState(): InternalState = InternalState(
        initialized = initialized,
        timestampNs = timestampNs,
        velocityMps = velocityMps,
        accelerationBiasMps2 = accelerationBiasMps2,
        lastLongitudinalAccelerationMps2 = lastLongitudinalAccelerationMps2,
        innovationMps = innovationMps,
        mode = mode,
        gpsMeasurementAccepted = gpsMeasurementAccepted,
        stationary = stationary,
        stationaryCandidateStartNs = stationaryCandidateStartNs,
        p00 = p00,
        p01 = p01,
        p10 = p10,
        p11 = p11,
    )

    private fun restoreInternalState(state: InternalState) {
        initialized = state.initialized
        timestampNs = state.timestampNs
        velocityMps = state.velocityMps
        accelerationBiasMps2 = state.accelerationBiasMps2
        lastLongitudinalAccelerationMps2 = state.lastLongitudinalAccelerationMps2
        innovationMps = state.innovationMps
        mode = state.mode
        gpsMeasurementAccepted = state.gpsMeasurementAccepted
        stationary = state.stationary
        stationaryCandidateStartNs = state.stationaryCandidateStartNs
        p00 = state.p00
        p01 = state.p01
        p10 = state.p10
        p11 = state.p11
    }

    private fun predictCovariance(dtSeconds: Double) {
        val oldP00 = p00
        val oldP01 = p01
        val oldP10 = p10
        val oldP11 = p11
        val accelerationVariance = accelerationNoiseStdMps2 * accelerationNoiseStdMps2
        val biasRandomWalkVariance =
            biasRandomWalkStdMps2PerSqrtSecond * biasRandomWalkStdMps2PerSqrtSecond

        p00 = oldP00 - dtSeconds * (oldP01 + oldP10) +
            dtSeconds * dtSeconds * oldP11 + accelerationVariance * dtSeconds * dtSeconds
        p01 = oldP01 - dtSeconds * oldP11
        p10 = oldP10 - dtSeconds * oldP11
        p11 = oldP11 + biasRandomWalkVariance * dtSeconds
        symmetrizeAndClampCovariance()
    }

    private fun applySpeedMeasurement(
        residual: Double,
        observationVariance: Double,
        residualVariance: Double,
    ) {
        val oldP00 = p00
        val oldP01 = p01
        val oldP10 = p10
        val oldP11 = p11
        val speedGain = oldP00 / residualVariance
        val biasGain = oldP10 / residualVariance

        velocityMps += speedGain * residual
        accelerationBiasMps2 += biasGain * residual

        // Joseph covariance update preserves symmetry and positive semidefiniteness.
        val a00 = 1.0 - speedGain
        val a10 = -biasGain
        val ap00 = a00 * oldP00
        val ap01 = a00 * oldP01
        val ap10 = a10 * oldP00 + oldP10
        val ap11 = a10 * oldP01 + oldP11
        p00 = ap00 * a00 + speedGain * speedGain * observationVariance
        p01 = ap00 * a10 + ap01 + speedGain * biasGain * observationVariance
        p10 = ap10 * a00 + biasGain * speedGain * observationVariance
        p11 = ap10 * a10 + ap11 + biasGain * biasGain * observationVariance
        symmetrizeAndClampCovariance()
    }

    private fun updateStationaryCandidate(
        eventTimestampNs: Long,
        speedMps: Double,
        speedSigmaMps: Double,
    ) {
        val credibleLowSpeed =
            speedMps <= STATIONARY_ENTER_SPEED_MPS && speedSigmaMps <= STATIONARY_MAX_GPS_SIGMA_MPS
        if (credibleLowSpeed) {
            val start = stationaryCandidateStartNs ?: eventTimestampNs.also {
                stationaryCandidateStartNs = it
            }
            if (eventTimestampNs - start >= STATIONARY_CONFIRMATION_NS) stationary = true
        } else if (speedMps >= STATIONARY_EXIT_SPEED_MPS) {
            stationary = false
            stationaryCandidateStartNs = null
        } else if (!stationary) {
            stationaryCandidateStartNs = null
        }
    }

    private fun symmetrizeAndClampCovariance() {
        p00 = max(MIN_COVARIANCE, p00)
        p11 = max(MIN_COVARIANCE, p11)
        val cross = (p01 + p10) / 2.0
        p01 = cross
        p10 = cross
    }

    companion object {
        private const val NS_PER_SECOND = 1_000_000_000.0
        private const val MAX_PROPAGATION_STEP_SECONDS = 0.25
        private const val INITIAL_SPEED_VARIANCE_MPS2 = 4.0
        private const val INITIAL_BIAS_VARIANCE_MPS4 = 1.0
        private const val MIN_COVARIANCE = 1e-9
        private const val DEFAULT_GPS_SIGMA_MPS = 1.5
        private const val MIN_GPS_SIGMA_MPS = 0.20
        private const val MAX_ACCEPTED_GPS_SIGMA_MPS = 5.0
        private const val MIN_INNOVATION_GATE_MPS = 5.0
        private const val INNOVATION_GATE_SIGMAS = 5.0
        private const val STATIONARY_ENTER_SPEED_MPS = 0.30
        private const val STATIONARY_EXIT_SPEED_MPS = 0.75
        private const val STATIONARY_MAX_GPS_SIGMA_MPS = 1.5
        private const val STATIONARY_CONFIRMATION_NS = 1_500_000_000L
        private const val STATIONARY_SPEED_SIGMA_MPS = 0.10
        private const val STATIONARY_EXIT_ACCEL_MPS2 = 0.55
        private const val STATIONARY_BIAS_TIME_CONSTANT_SECONDS = 5.0
        private const val FORCED_HOLD_BIAS_TIME_CONSTANT_SECONDS = 0.50
        private const val DEFAULT_GPS_OBSERVATION_DELAY_SECONDS = 1.0
        private const val MAX_GPS_OBSERVATION_DELAY_SECONDS = 2.5
        private const val HISTORY_DURATION_NS = 3_500_000_000L
        private const val MAX_HISTORY_ANCHOR_AGE_NS = 100_000_000L
    }
}
