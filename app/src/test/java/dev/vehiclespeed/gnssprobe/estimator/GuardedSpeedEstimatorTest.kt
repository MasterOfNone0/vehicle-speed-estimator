package dev.vehiclespeed.gnssprobe.estimator

import org.junit.Assert.*
import org.junit.Test

class GuardedSpeedEstimatorTest {
    private fun controller() = GuardedSpeedEstimator(VehicleFrameTransform(Axis3(1.0, 0.0, 0.0), "test"))
    private fun ns(seconds: Double) = (seconds * 1e9).toLong()

    private fun feed(c: GuardedSpeedEstimator, start: Int, end: Int, vector: Axis3, speed: Double?) {
        for (tick in start * 100 + 1..end * 100) {
            val time = ns(tick / 100.0)
            if (tick % 100 == 0 && speed != null) c.onGpsSpeed(time, speed, 0.3)
            c.onAcceleration(time, vector.x, vector.y, vector.z)
        }
    }

    @Test fun seatStartupAndRemountWhileDrivingCannotRunAwayOrLockOutGps() {
        val c = controller()
        feed(c, 0, 269, Axis3(-9.2, 0.0, 0.0), null)
        assertFalse(c.state().estimator.initialized)
        feed(c, 269, 289, Axis3(-9.2, 0.0, 0.0), 28.0)
        feed(c, 289, 670, Axis3(1.2, 0.0, 9.7), 28.0)
        assertFalse(c.state().fusionReady)
        assertEquals(EstimatorMode.GPS_ONLY, c.state().estimator.mode)
        assertEquals(28.0, c.state().estimator.velocityMps, 0.0)
        assertEquals(0.0, c.state().estimator.accelerationBiasMps2, 0.0)
        assertTrue(c.onGpsSpeed(ns(671.0), 26.0, 0.3).estimator.gpsMeasurementAccepted)
        feed(c, 671, 680, Axis3(1.2, 0.0, 9.7), 0.0)
        assertTrue(c.state().fusionReady)
        assertEquals(1.2, c.state().estimator.accelerationBiasMps2, 0.1)
    }

    @Test fun quietParkedMountEnablesPredictionBeforeNextGps() {
        val c = controller()
        feed(c, 0, 8, Axis3(1.2, 0.0, 9.7), 0.0)
        assertTrue(c.state().fusionReady)
        assertEquals(1.2, c.state().estimator.accelerationBiasMps2, 0.1)
        feed(c, 8, 9, Axis3(3.2, 0.0, 9.7), null)
        assertTrue(c.state().fusionReady)
        assertTrue(c.state().estimator.velocityMps > 1.5)
    }

    @Test fun movingLargeRotationDiscardsBiasAndRemainsGpsOnlyUntilParked() {
        val c = controller()
        feed(c, 0, 8, Axis3(0.0, 0.0, 9.8), 0.0)
        assertTrue(c.state().fusionReady)
        // Gentle launch avoids asking the filter to accept a discontinuous GPS jump.
        for (second in 9..18) {
            feed(c, second - 1, second, Axis3(1.0, 0.0, 9.8), (second - 8).toDouble())
        }
        assertTrue(c.state().fusionReady)
        feed(c, 18, 20, Axis3(0.0, 9.8, 0.0), 10.0)
        assertFalse(c.state().fusionReady)
        assertEquals(0.0, c.state().estimator.accelerationBiasMps2, 0.0)
        feed(c, 20, 25, Axis3(0.0, 0.0, 9.8), 10.0)
        assertFalse(c.state().fusionReady)
        assertEquals(10.0, c.state().estimator.velocityMps, 0.0)
        feed(c, 25, 35, Axis3(0.0, 0.0, 9.8), 0.0)
        assertTrue(c.state().fusionReady)
    }

    @Test fun rejectedGpsCannotKeepBadPredictionAliveBetweenCallbacks() {
        val c = controller()
        feed(c, 0, 8, Axis3(0.0, 0.0, 9.8), 0.0)
        c.onGpsSpeed(ns(9.0), 30.0, 0.3)
        assertFalse(c.state().fusionReady)
        feed(c, 9, 15, Axis3(9.8, 0.0, 0.0), 30.0)
        assertEquals(30.0, c.state().estimator.velocityMps, 0.0)
        assertEquals(EstimatorMode.GPS_ONLY, c.state().estimator.mode)
    }

    @Test fun staleFixesAndLongSensorGapCannotEnableFusion() {
        val c = controller()
        feed(c, 0, 8, Axis3(0.0, 0.0, 9.8), 0.0)
        assertTrue(c.state().fusionReady)
        c.onAcceleration(ns(9.0), 0.0, 0.0, 9.8)
        assertFalse(c.state().fusionReady)
        c.onGpsSpeed(ns(10.0), 0.0, 0.3, ns(15.0))
        feed(c, 15, 23, Axis3(0.0, 0.0, 9.8), null)
        assertFalse(c.state().fusionReady)
        assertFalse(c.onGpsSpeed(ns(9.0), 100.0, 0.3).estimator.gpsMeasurementAccepted)
    }

    @Test fun quietSeatPoseDoesNotQualifyAsMounted() {
        val c = controller()
        feed(c, 0, 15, Axis3(-9.8, 0.0, 0.0), 0.0)
        assertFalse(c.state().fusionReady)
        assertEquals(0.0, c.state().estimator.velocityMps, 0.0)
    }

    @Test fun sustainedHardLaunchIsNotMistakenForRemounting() {
        val c = controller()
        feed(c, 0, 8, Axis3(0.0, 0.0, 9.8), 0.0)
        for (second in 9..13) {
            feed(c, second - 1, second, Axis3(8.0, 0.0, 9.8), 8.0 * (second - 8))
            assertTrue(c.state().reason, c.state().fusionReady)
        }
        assertEquals(40.0, c.state().estimator.velocityMps, 1.0)
    }

    @Test fun oneZeroSpeedFixCannotAuthorizeStationaryBiasLearning() {
        val c = controller()
        feed(c, 0, 8, Axis3(0.0, 0.0, 9.8), null)
        c.onGpsSpeed(ns(8.0), 0.0, 0.3)
        feed(c, 8, 14, Axis3(0.0, 0.0, 9.8), null)
        assertFalse(c.state().fusionReady)
    }
}
