package dev.vehiclespeed.gnssprobe.estimator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VelocityBiasKalmanFilterTest {
    @Test
    fun remainsUninitializedUntilGpsSpeedArrives() {
        val filter = VelocityBiasKalmanFilter()

        repeat(100) { index ->
            filter.onAcceleration(seconds(1.0 + index / 100.0), 2.0)
        }

        val state = filter.state()
        assertFalse(state.initialized)
        assertEquals(EstimatorMode.UNINITIALIZED, state.mode)
        assertEquals(0.0, state.velocityMps, 0.0)
    }

    @Test
    fun accelerationChangesVelocityBeforeNextGpsObservation() {
        val filter = VelocityBiasKalmanFilter()
        filter.onGpsSpeed(seconds(1.0), 10.0, 0.5)

        repeat(100) { index ->
            filter.onAcceleration(seconds(1.01 + index / 100.0), 1.0)
        }

        val state = filter.state()
        assertTrue(state.initialized)
        assertEquals(11.0, state.velocityMps, 0.02)
        assertEquals(EstimatorMode.PREDICTING, state.mode)
    }

    @Test
    fun gpsCorrectionPullsPredictionAndLearnsPositiveBias() {
        val filter = VelocityBiasKalmanFilter()
        filter.onGpsSpeed(seconds(1.0), 10.0, 0.4)
        repeat(100) { index ->
            filter.onAcceleration(seconds(1.01 + index / 100.0), 1.0)
        }

        val state = filter.onGpsSpeed(seconds(2.0), 10.0, 0.4)

        assertTrue(state.gpsMeasurementAccepted)
        assertTrue(state.velocityMps < 11.0)
        assertTrue(state.accelerationBiasMps2 > 0.0)
        assertEquals(-1.0, state.innovationMps ?: Double.NaN, 0.03)
    }

    @Test
    fun repeatedGpsCorrectionsLearnSustainedEffectiveBias() {
        val filter = VelocityBiasKalmanFilter()
        filter.onGpsSpeed(seconds(1.0), 10.0, 0.25)

        for (second in 1 until 31) {
            repeat(100) { sample ->
                val time = 1.0 + second - 1 + (sample + 1) / 100.0
                filter.onAcceleration(seconds(time), 0.30)
            }
            filter.onGpsSpeed(seconds(1.0 + second), 10.0, 0.25)
        }

        val state = filter.state()
        assertEquals(10.0, state.velocityMps, 0.25)
        assertEquals(0.30, state.accelerationBiasMps2, 0.12)
    }

    @Test
    fun confirmedStopConvergesExactlyToZeroAndLaunchExitsStationaryMode() {
        val filter = VelocityBiasKalmanFilter()
        filter.onGpsSpeed(seconds(1.0), 4.0, 0.3)
        filter.onGpsSpeed(seconds(2.0), 0.1, 0.3)
        filter.onGpsSpeed(seconds(3.0), 0.05, 0.3)
        val stopped = filter.onGpsSpeed(seconds(4.0), 0.0, 0.3)

        assertEquals(EstimatorMode.STATIONARY, stopped.mode)
        assertEquals(0.0, stopped.velocityMps, 0.0)

        val launched = filter.onAcceleration(seconds(4.01), 1.0)
        assertEquals(EstimatorMode.PREDICTING, launched.mode)
        assertTrue(launched.velocityMps > 0.0)
    }

    @Test
    fun rejectsVeryUncertainGpsAndNeverCreatesReverseSpeed() {
        val filter = VelocityBiasKalmanFilter()
        filter.onGpsSpeed(seconds(1.0), 0.2, 0.3)
        val degraded = filter.onGpsSpeed(seconds(2.0), 12.0, 6.0)

        assertFalse(degraded.gpsMeasurementAccepted)
        assertEquals(EstimatorMode.GPS_DEGRADED, degraded.mode)

        repeat(100) { index ->
            filter.onAcceleration(seconds(2.01 + index / 100.0), -5.0)
        }
        assertEquals(0.0, filter.state().velocityMps, 0.0)
    }

    @Test
    fun externalStationaryHoldSuppressesMountMovementAndRelearnsBias() {
        val filter = VelocityBiasKalmanFilter()
        filter.onGpsSpeed(seconds(1.0), 0.0, 0.3)

        repeat(300) { index ->
            filter.onAcceleration(
                eventTimestampNs = seconds(1.01 + index / 100.0),
                longitudinalAccelerationMps2 = 3.0,
                forceStationaryHold = true,
            )
        }

        val state = filter.state()
        assertEquals(EstimatorMode.STATIONARY, state.mode)
        assertEquals(0.0, state.velocityMps, 0.0)
        assertEquals(3.0, state.accelerationBiasMps2, 0.02)
    }

    @Test
    fun delayedGpsCorrectsHistoricalStateThenReplaysAccelerationToNow() {
        val filter = VelocityBiasKalmanFilter(gpsObservationDelaySeconds = 1.0)
        filter.onGpsSpeed(seconds(1.0), 10.0, 0.3)

        repeat(200) { index ->
            filter.onAcceleration(seconds(1.01 + index / 100.0), 1.0)
        }
        val beforeCorrection = filter.state()
        val corrected = filter.onGpsSpeed(
            eventTimestampNs = seconds(3.0),
            speedMps = 11.0,
            speedAccuracyMps = 0.3,
        )

        assertEquals(12.0, beforeCorrection.velocityMps, 0.03)
        assertTrue(corrected.gpsMeasurementAccepted)
        assertEquals(0.0, corrected.innovationMps ?: Double.NaN, 0.03)
        assertEquals(12.0, corrected.velocityMps, 0.05)
        assertEquals(seconds(3.0), corrected.timestampNs)
    }

    @Test
    fun zeroDelayRetainsConventionalCurrentStateCorrectionForComparison() {
        val filter = VelocityBiasKalmanFilter(gpsObservationDelaySeconds = 0.0)
        filter.onGpsSpeed(seconds(1.0), 10.0, 0.3)
        repeat(200) { index ->
            filter.onAcceleration(seconds(1.01 + index / 100.0), 1.0)
        }

        val corrected = filter.onGpsSpeed(seconds(3.0), 11.0, 0.3)

        assertTrue(corrected.gpsMeasurementAccepted)
        assertEquals(-1.0, corrected.innovationMps ?: Double.NaN, 0.03)
        assertTrue(corrected.velocityMps < 12.0)
    }

    @Test fun explicitReanchorDiscardsOldBiasCovarianceAndReplayHistory() {
        val filter = VelocityBiasKalmanFilter()
        filter.onGpsSpeed(seconds(1.0), 30.0, 0.3)
        repeat(400) { filter.onAcceleration(seconds(1.01 + it / 100.0), 9.0) }
        filter.reanchor(seconds(5.0), 20.0, 0.3, 1.2)
        val reset = filter.state()
        assertEquals(20.0, reset.velocityMps, 0.0)
        assertEquals(1.2, reset.accelerationBiasMps2, 0.0)
        repeat(100) { filter.onAcceleration(seconds(5.01 + it / 100.0), 1.2) }
        val corrected = filter.onGpsSpeed(seconds(6.0), 20.0, 0.3)
        assertEquals(20.0, corrected.velocityMps, 0.01)
        assertTrue(corrected.gpsMeasurementAccepted)
    }

    private fun seconds(value: Double): Long = (value * 1_000_000_000L).toLong()
}
